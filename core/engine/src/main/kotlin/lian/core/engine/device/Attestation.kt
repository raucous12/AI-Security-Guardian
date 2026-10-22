package lian.core.engine.device

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** What a hardware key attestation proved about the boot chain (Phase 3.6). */
data class AttestationFacts(
    val verdict: Verdict,
    /** 0 = software, 1 = TEE, 2 = StrongBox. */
    val securityLevel: Int?,
    val deviceLocked: Boolean?,
    /** 0 = Verified (green), 1 = SelfSigned (yellow), 2 = Unverified (orange), 3 = Failed (red). */
    val verifiedBootState: Int?,
    /** YYYYMM, from secure hardware. */
    val osPatchLevel: Int?,
    val detail: String,
) {
    enum class Verdict { VERIFIED_HARDWARE, REVOKED, UNVERIFIED, UNAVAILABLE }

    companion object {
        fun unavailable(detail: String) = AttestationFacts(Verdict.UNAVAILABLE, null, null, null, null, detail)
    }
}

/** Trust inputs from the bundled data: Google's root public keys and the revocation list. */
interface AttestationTrust {
    /** SubjectPublicKeyInfo DER of each trusted root. */
    val rootPublicKeys: List<ByteArray>

    /** [serialHex] is the certificate serial in lowercase hex without leading zeros. */
    fun isRevoked(serialHex: String): Boolean
}

/**
 * Verifies an Android key-attestation certificate chain and reads the boot state from it.
 * Pure JVM (java.security); the Keystore call that produces the chain lives in :core:platform.
 */
object AttestationVerifier {
    const val OID = "1.3.6.1.4.1.11129.2.1.17"
    private const val MAX_CHAIN = 10

    fun verify(chainDer: List<ByteArray>, challenge: ByteArray, trust: AttestationTrust): AttestationFacts {
        if (chainDer.isEmpty() || chainDer.size > MAX_CHAIN) return AttestationFacts.unavailable("no certificate chain")
        val chain = try {
            val factory = CertificateFactory.getInstance("X.509")
            chainDer.map { factory.generateCertificate(ByteArrayInputStream(it)) as X509Certificate }
        } catch (_: GeneralSecurityException) {
            return AttestationFacts.unavailable("unreadable certificate chain")
        }

        val description = try {
            chain.first().getExtensionValue(OID)?.let(::parseExtension)
        } catch (_: DerException) {
            null
        } ?: return AttestationFacts(AttestationFacts.Verdict.UNVERIFIED, null, null, null, null, "no attestation extension")

        val signaturesOk = chain.indices.all { i ->
            val issuer = if (i + 1 < chain.size) chain[i + 1] else chain[i]
            try { chain[i].verify(issuer.publicKey); true } catch (_: Exception) { false }
        }
        val rootKey = chain.last().publicKey.encoded
        val rootTrusted = trust.rootPublicKeys.any { it.contentEquals(rootKey) }
        val revoked = chain.any { trust.isRevoked(it.serialNumber.toString(16)) }
        val challengeOk = description.challenge.contentEquals(challenge)
        val hardware = description.securityLevel >= 1

        val verdict = when {
            !signaturesOk || !rootTrusted || !challengeOk -> AttestationFacts.Verdict.UNVERIFIED
            revoked -> AttestationFacts.Verdict.REVOKED
            !hardware -> AttestationFacts.Verdict.UNVERIFIED
            else -> AttestationFacts.Verdict.VERIFIED_HARDWARE
        }
        val detail = when {
            !signaturesOk -> "certificate signatures don't verify"
            !rootTrusted -> "chain doesn't end at a Google root (software or emulator keystore)"
            !challengeOk -> "challenge mismatch"
            revoked -> "a certificate in the chain is revoked"
            !hardware -> "key is software-backed"
            else -> if (description.securityLevel == 2) "StrongBox" else "TEE"
        }
        return AttestationFacts(verdict, description.securityLevel, description.deviceLocked, description.verifiedBootState, description.osPatchLevel, detail)
    }

    internal class KeyDescription(
        val securityLevel: Int,
        val challenge: ByteArray,
        val deviceLocked: Boolean?,
        val verifiedBootState: Int?,
        val osPatchLevel: Int?,
    )

    /** The X.509 extension value is an OCTET STRING wrapping the KeyDescription SEQUENCE. */
    internal fun parseExtension(extensionValue: ByteArray): KeyDescription {
        val outer = Der.read(extensionValue)
        val seq = Der.read(if (outer.tag == Der.OCTET_STRING) outer.content else extensionValue)
        val fields = Der.children(seq)
        if (fields.size < 8) throw DerException("KeyDescription too short")
        val securityLevel = Der.integer(fields[1]).toInt()
        val challenge = fields[4].content
        // Prefer the hardware-enforced list (index 7); fall back to software-enforced (index 6).
        val hw = Der.children(fields[7])
        val sw = Der.children(fields[6])
        fun tagged(list: List<Der.Node>, tag: Int) = list.firstOrNull { it.contextTag == tag }
        val rootOfTrust = (tagged(hw, 704) ?: tagged(sw, 704))?.let { Der.children(Der.read(it.content)) }
        val patch = (tagged(hw, 706) ?: tagged(sw, 706))?.let { Der.integer(Der.read(it.content)).toInt() }
        return KeyDescription(
            securityLevel = securityLevel,
            challenge = challenge,
            deviceLocked = rootOfTrust?.getOrNull(1)?.let { it.content.isNotEmpty() && it.content[0] != 0.toByte() },
            verifiedBootState = rootOfTrust?.getOrNull(2)?.let { Der.integer(it).toInt() },
            osPatchLevel = patch,
        )
    }
}

class DerException(message: String) : Exception(message)

/**
 * A minimal DER reader for the attestation extension: definite lengths only, at most 64 KB and
 * 8 levels deep, so malformed input fails fast instead of exhausting memory or the stack.
 */
object Der {
    const val OCTET_STRING = 0x04
    private const val MAX_BYTES = 65_536

    /** [tag] is the full tag number; [contextTag] is set for context-specific tags ([704] etc.). */
    class Node(val tag: Int, val constructed: Boolean, val tagClass: Int, val content: ByteArray) {
        val contextTag: Int? get() = if (tagClass == 2) tag else null
    }

    fun read(bytes: ByteArray): Node {
        if (bytes.size > MAX_BYTES) throw DerException("too large")
        val (node, end) = readAt(bytes, 0)
        if (end != bytes.size) throw DerException("trailing bytes")
        return node
    }

    fun children(node: Node, depth: Int = 0): List<Node> {
        if (depth > 8) throw DerException("too deep")
        if (!node.constructed) throw DerException("not constructed")
        val out = ArrayList<Node>()
        var pos = 0
        while (pos < node.content.size) {
            val (child, end) = readAt(node.content, pos)
            out += child
            pos = end
        }
        return out
    }

    fun integer(node: Node): BigInteger {
        if (node.content.isEmpty() || node.content.size > 16) throw DerException("bad integer")
        return BigInteger(node.content)
    }

    private fun readAt(b: ByteArray, start: Int): Pair<Node, Int> {
        var p = start
        fun next(): Int = if (p < b.size) b[p++].toInt() and 0xFF else throw DerException("truncated")
        val first = next()
        val tagClass = first ushr 6
        val constructed = first and 0x20 != 0
        var tag = first and 0x1F
        if (tag == 0x1F) {
            tag = 0
            var count = 0
            do {
                val x = next()
                tag = (tag shl 7) or (x and 0x7F)
                if (++count > 4) throw DerException("tag too long")
            } while (x and 0x80 != 0)
        }
        var length = next()
        if (length and 0x80 != 0) {
            val n = length and 0x7F
            if (n == 0 || n > 3) throw DerException("unsupported length")
            length = 0
            repeat(n) { length = (length shl 8) or next() }
        }
        if (length < 0 || p + length > b.size) throw DerException("length out of range")
        return Node(tag, constructed, tagClass, b.copyOfRange(p, p + length)) to (p + length)
    }
}
