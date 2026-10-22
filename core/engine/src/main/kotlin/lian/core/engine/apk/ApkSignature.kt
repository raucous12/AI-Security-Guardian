package lian.core.engine.apk

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec

/** The result of checking an APK's signatures. Certificates are identified by SHA-256 (and SHA-1 for indicator lookups). */
data class SigningFacts(
    /** 1 = JAR (v1), 2 = APK Signature Scheme v2, 3 = v3, 31 = v3.1. */
    val schemes: Set<Int>,
    /** True if LIAN verified a v2/v3 signature and the whole-file digest; false if verification failed; null if not checked (v1 only). */
    val verified: Boolean?,
    val problem: String?,
    val certificates: List<CertificateId>,
)

data class CertificateId(val sha256: String, val sha1: String, val subject: String) {
    val isDebug: Boolean get() = "CN=Android Debug" in subject
}

/**
 * Reads and verifies APK Signature Scheme v2/v3 (the scheme Android itself requires since 11 for
 * new targetSdk): finds the signing block before the central directory, verifies each signer's
 * signature over its signed data, then recomputes the chunked SHA-256/512 digest of the whole file.
 * v1 (JAR) signatures are identified but not verified here; Android verifies them at install.
 */
object ApkSignatureVerifier {
    private const val BLOCK_MAGIC = "APK Sig Block 42"
    private const val V2_ID = 0x7109871aL
    private const val V3_ID = 0xf05368c0L
    private const val V31_ID = 0x1b93ad61L
    private const val CHUNK = 1024 * 1024
    private const val MAX_BLOCK = 64 * 1024 * 1024

    private class Algorithm(val id: Int, val jca: String, val digest: String, val pss: Boolean = false)

    // Strongest first; verity variants (0x04xx) need the verity tree and are skipped.
    private val ALGORITHMS = listOf(
        Algorithm(0x0104, "SHA512withRSA", "SHA-512"),
        Algorithm(0x0202, "SHA512withECDSA", "SHA-512"),
        Algorithm(0x0102, "SHA512withRSA/PSS", "SHA-512", pss = true),
        Algorithm(0x0103, "SHA256withRSA", "SHA-256"),
        Algorithm(0x0201, "SHA256withECDSA", "SHA-256"),
        Algorithm(0x0101, "SHA256withRSA/PSS", "SHA-256", pss = true),
        Algorithm(0x0301, "SHA256withDSA", "SHA-256"),
    )

    fun verify(source: ByteSource, zip: ZipArchive): SigningFacts {
        val hasV1 = zip.entries.any { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")) }
        val block = signingBlock(source, zip)
        val pairs = block?.let { pairs(it.second) }.orEmpty()
        val schemes = buildSet {
            if (hasV1) add(1)
            if (V2_ID in pairs) add(2)
            if (V3_ID in pairs) add(3)
            if (V31_ID in pairs) add(31)
        }
        val (schemeId, value) = when {
            V3_ID in pairs -> 3 to pairs.getValue(V3_ID)
            V2_ID in pairs -> 2 to pairs.getValue(V2_ID)
            else -> return SigningFacts(schemes, if (hasV1) null else false, if (hasV1) null else "not signed", if (hasV1) v1Certificates(zip) else emptyList())
        }
        val blockStart = block?.first ?: return SigningFacts(schemes, false, "malformed signing block", emptyList())
        return try {
            val (certs, digests) = verifySigners(value, schemeId)
            for ((algorithm, expected) in digests) {
                val actual = contentDigest(source, zip, blockStart, algorithm)
                if (!actual.contentEquals(expected)) return SigningFacts(schemes, false, "file contents don't match the signature (modified after signing)", certs)
            }
            if (digests.isEmpty()) return SigningFacts(schemes, null, "only verity digests present; not checked", certs)
            SigningFacts(schemes, true, null, certs)
        } catch (e: SignatureProblem) {
            SigningFacts(schemes, false, e.message, emptyList())
        } catch (_: RuntimeException) {
            // BufferUnderflow, IllegalArgument, IndexOutOfBounds…: a hostile or corrupt block, never a crash.
            SigningFacts(schemes, false, "malformed signing block", emptyList())
        }
    }

    private class SignatureProblem(message: String) : Exception(message)

    /** Returns (block start offset, pairs region) or null. */
    private fun signingBlock(source: ByteSource, zip: ZipArchive): Pair<Long, ByteBuffer>? {
        val cd = zip.centralDirectoryOffset
        if (cd < 32) return null
        val footer = source.bytes(cd - 24, 24)
        if (String(footer, 8, 16, Charsets.US_ASCII) != BLOCK_MAGIC) return null
        val size = le64(footer, 0)
        if (size < 24 || size > MAX_BLOCK || size + 8 > cd) return null
        val start = cd - size - 8
        val block = source.bytes(start, (size + 8).toInt())
        if (le64(block, 0) != size) return null
        val pairs = ByteBuffer.wrap(block, 8, (size - 24).toInt()).slice().order(ByteOrder.LITTLE_ENDIAN)
        return start to pairs
    }

    private fun pairs(buf: ByteBuffer): Map<Long, ByteBuffer> {
        val out = HashMap<Long, ByteBuffer>()
        while (buf.remaining() >= 12) {
            val len = buf.long
            if (len < 4 || len > buf.remaining()) break
            val id = buf.int.toLong() and 0xFFFFFFFFL
            val value = slice(buf, (len - 4).toInt())
            out.putIfAbsent(id, value)
        }
        return out
    }

    private fun slice(buf: ByteBuffer, len: Int): ByteBuffer {
        require(len >= 0 && len <= buf.remaining()) { "length out of range" }
        val s = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        s.limit(len)
        buf.position(buf.position() + len)
        return s
    }

    private fun prefixed(buf: ByteBuffer): ByteBuffer = slice(buf, buf.int)

    private fun bytesOf(buf: ByteBuffer): ByteArray = ByteArray(buf.remaining()).also { buf.duplicate().get(it) }

    /** Verifies every signer; returns the first signer's certificates and the content digests to check. */
    private fun verifySigners(value: ByteBuffer, scheme: Int): Pair<List<CertificateId>, Map<Algorithm, ByteArray>> {
        val signers = prefixed(value)
        var firstCerts: List<CertificateId>? = null
        val digests = LinkedHashMap<Algorithm, ByteArray>()
        var count = 0
        while (signers.hasRemaining()) {
            if (++count > 10) throw SignatureProblem("too many signers")
            val signer = prefixed(signers)
            val signedData = prefixed(signer)
            val signedBytes = bytesOf(signedData)
            if (scheme == 3) { signer.int; signer.int }   // min/max SDK
            val signatures = prefixed(signer)
            val publicKeyBytes = bytesOf(prefixed(signer))

            val offered = LinkedHashMap<Int, ByteArray>()
            while (signatures.hasRemaining()) {
                val sig = prefixed(signatures)
                val id = sig.int
                offered[id] = bytesOf(prefixed(sig))
            }
            val algorithm = ALGORITHMS.firstOrNull { it.id in offered } ?: throw SignatureProblem("no supported signature algorithm")
            val publicKey = publicKey(publicKeyBytes)
            if (!verifySignature(algorithm, publicKey, signedBytes, offered.getValue(algorithm.id))) throw SignatureProblem("signature doesn't verify")

            val digestList = prefixed(signedData)
            val certList = prefixed(signedData)
            var digest: ByteArray? = null
            while (digestList.hasRemaining()) {
                val d = prefixed(digestList)
                val id = d.int
                val bytes = bytesOf(prefixed(d))
                if (id == algorithm.id) digest = bytes
            }
            val certs = ArrayList<CertificateId>()
            val factory = CertificateFactory.getInstance("X.509")
            while (certList.hasRemaining()) {
                val der = bytesOf(prefixed(certList))
                val cert = try { factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate } catch (_: GeneralSecurityException) { throw SignatureProblem("unreadable certificate") }
                certs += CertificateId(hex(sha("SHA-256", der)), hex(sha(SHA1, der)), cert.subjectX500Principal.name)
                if (certs.size == 1 && !cert.publicKey.encoded.contentEquals(publicKeyBytes)) throw SignatureProblem("certificate doesn't match the signing key")
            }
            if (certs.isEmpty()) throw SignatureProblem("no certificate")
            if (firstCerts == null) firstCerts = certs
            digests[algorithm] = digest ?: throw SignatureProblem("signed data has no digest for its algorithm")
        }
        return (firstCerts ?: throw SignatureProblem("no signers")) to digests
    }

    private fun publicKey(der: ByteArray): PublicKey {
        for (alg in listOf("RSA", "EC", "DSA")) {
            try { return KeyFactory.getInstance(alg).generatePublic(X509EncodedKeySpec(der)) } catch (_: GeneralSecurityException) { }
        }
        throw SignatureProblem("unsupported public key")
    }

    private fun verifySignature(a: Algorithm, key: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        val verifier = if (a.pss) pssVerifier(a) else Signature.getInstance(a.jca)
        verifier.initVerify(key)
        verifier.update(data)
        verifier.verify(sig)
    } catch (_: GeneralSecurityException) {
        false
    }

    /** Android names PSS "SHA256withRSA/PSS"; desktop JDKs use "RSASSA-PSS" with explicit parameters. */
    private fun pssVerifier(a: Algorithm): Signature = try {
        Signature.getInstance(a.jca)
    } catch (_: GeneralSecurityException) {
        val digestLength = if (a.digest == "SHA-512") 64 else 32
        val md = if (a.digest == "SHA-512") MGF1ParameterSpec.SHA512 else MGF1ParameterSpec.SHA256
        Signature.getInstance("RSASSA-PSS").apply { setParameter(PSSParameterSpec(a.digest, "MGF1", md, digestLength, 1)) }
    }

    /**
     * v2/v3 content digest: the file minus the signing block, in 1 MB chunks
     * (`0xa5 | len | chunk`), then `0x5a | count | chunk digests`. The EOCD's central-directory
     * offset is replaced by the signing block's offset, as when it was signed.
     */
    private fun contentDigest(source: ByteSource, zip: ZipArchive, blockStart: Long, a: Algorithm): ByteArray {
        val eocd = source.bytes(zip.eocdOffset, (source.size - zip.eocdOffset).toInt())
        val off = blockStart
        eocd[16] = off.toByte(); eocd[17] = (off shr 8).toByte(); eocd[18] = (off shr 16).toByte(); eocd[19] = (off shr 24).toByte()
        val sections = listOf<Triple<Long, Long, ByteArray?>>(
            Triple(0L, blockStart, null),
            Triple(zip.centralDirectoryOffset, zip.centralDirectorySize, null),
            Triple(0L, eocd.size.toLong(), eocd),
        )
        val chunkDigests = ArrayList<ByteArray>()
        val buffer = ByteArray(CHUNK)
        for ((start, length, inMemory) in sections) {
            var pos = 0L
            while (pos < length) {
                val n = minOf(CHUNK.toLong(), length - pos).toInt()
                if (inMemory != null) System.arraycopy(inMemory, pos.toInt(), buffer, 0, n) else source.read(start + pos, buffer, 0, n)
                val md = MessageDigest.getInstance(a.digest)
                md.update(0xa5.toByte())
                md.update(le32bytes(n))
                md.update(buffer, 0, n)
                chunkDigests += md.digest()
                pos += n
            }
        }
        val top = MessageDigest.getInstance(a.digest)
        top.update(0x5a.toByte())
        top.update(le32bytes(chunkDigests.size))
        chunkDigests.forEach { top.update(it) }
        return top.digest()
    }

    private fun le32bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** v1-only APKs: certificates from the PKCS#7 signature block (identification only). */
    private fun v1Certificates(zip: ZipArchive): List<CertificateId> {
        val entry = zip.entries.firstOrNull { it.name.startsWith("META-INF/") && (it.name.endsWith(".RSA") || it.name.endsWith(".DSA") || it.name.endsWith(".EC")) }
            ?: return emptyList()
        return try {
            val pkcs7 = zip.read(entry, 1024 * 1024)
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pkcs7))
                .filterIsInstance<X509Certificate>()
                .map { CertificateId(hex(sha("SHA-256", it.encoded)), hex(sha(SHA1, it.encoded)), it.subjectX500Principal.name) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Certificate SHA-1 is only a lookup key for published indicator lists, never used for integrity (Phase 3.8.3). */
    private const val SHA1 = "SHA-1" // privacy-gate: allow crypto.weak (lookup key for stalkerware indicators, not integrity)

    private fun sha(alg: String, data: ByteArray): ByteArray = MessageDigest.getInstance(alg).digest(data)

    fun hex(bytes: ByteArray): String {
        val h = "0123456789abcdef"
        return buildString(bytes.size * 2) { for (b in bytes) append(h[(b.toInt() shr 4) and 0xF]).append(h[b.toInt() and 0xF]) }
    }
}
