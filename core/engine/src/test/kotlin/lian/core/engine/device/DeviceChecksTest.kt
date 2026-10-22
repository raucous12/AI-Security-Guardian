package lian.core.engine.device

import org.junit.jupiter.api.Test
import lian.core.engine.EvalContext
import lian.core.engine.app.AppTestIntel
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceChecksTest {
    private val now = DeviceChecks.epochDay("2026-10-01")!! * 86_400_000L + 12 * 3_600_000L

    private fun facts(
        sdk: Int = 35,
        patch: String? = "2026-09-05",
        lock: Boolean? = true,
        adb: Boolean? = false,
        dev: Boolean? = false,
        cas: Int? = 0,
        root: List<String> = emptyList(),
        attestation: AttestationFacts = AttestationFacts.unavailable("emulator"),
    ) = DeviceFacts(
        sdkInt = sdk, release = "15", manufacturer = "Google", model = "Pixel", securityPatch = patch, screenLockSecure = lock,
        encryption = Encryption.PER_USER, adbEnabled = adb, wirelessAdbEnabled = false, developerOptions = dev, userCaCount = cas,
        rootIndicators = root, attestation = attestation, privateDns = PrivateDns.OPPORTUNISTIC, vpnActive = false, advancedProtection = null,
    )

    private fun run(f: DeviceFacts) = DeviceChecks.evaluate(f, EvalContext(nowEpochMs = now), AppTestIntel::osSupport)
    private fun status(r: PostureReport, id: String) = r.checks.firstOrNull { it.id.value == id }?.status

    @Test
    fun `epoch days match the calendar`() {
        assertEquals(0L, DeviceChecks.epochDay("1970-01-01".replace("1970", "2000"))?.minus(10_957))
        assertEquals(20_727L, DeviceChecks.epochDay("2026-10-01"))
        assertEquals(DeviceChecks.epochDay("2024-03-01")!! - 1, DeviceChecks.epochDay("2024-02-29"))
        assertNull(DeviceChecks.epochDay("2026-13-01"))
        assertNull(DeviceChecks.epochDay("not a date"))
    }

    @Test
    fun `a healthy phone scores 100 and unknowns never cost points`() {
        val r = run(facts())
        assertEquals(100, r.score)
        assertEquals(PostureBand.STRONG, r.band)
        assertEquals(CheckStatus.UNKNOWN, status(r, "device.boot.unknown"))
    }

    @Test
    fun `patch age bands`() {
        assertEquals(CheckStatus.PASS, status(run(facts(patch = "2026-08-01")), "device.patch.recent"))
        assertEquals(CheckStatus.WARN, status(run(facts(patch = "2026-05-01")), "device.patch.aging"))
        assertEquals(CheckStatus.FAIL, status(run(facts(patch = "2025-12-01")), "device.patch.old"))
        assertEquals(CheckStatus.UNKNOWN, status(run(facts(patch = null)), "device.patch.unknown"))
    }

    @Test
    fun `penalties count once per group, warnings cost half`() {
        // USB debugging (WARN 10 → 5) and developer options (WARN 5 → 3) share the "developer" group: only 5 is taken.
        assertEquals(95, run(facts(adb = true, dev = true)).score)
        // No lock (20) + old patch (20) + unsupported OS (15) + root (25) = 80 off.
        assertEquals(20, run(facts(lock = false, patch = "2025-01-01", sdk = 33, root = listOf("su at /system/xbin/su"))).score)
    }

    @Test
    fun `hardware attestation reveals an unlocked bootloader`() {
        val unlocked = AttestationFacts(AttestationFacts.Verdict.VERIFIED_HARDWARE, 1, deviceLocked = false, verifiedBootState = 2, osPatchLevel = 202609, detail = "TEE")
        assertEquals(CheckStatus.FAIL, status(run(facts(attestation = unlocked)), "device.boot.unlocked"))
        val locked = unlocked.copy(deviceLocked = true, verifiedBootState = 0)
        assertEquals(CheckStatus.PASS, status(run(facts(attestation = locked)), "device.boot.verified"))
        // Reported patch newer than what secure hardware recorded → warning.
        val stale = locked.copy(osPatchLevel = 202601)
        assertEquals(CheckStatus.WARN, status(run(facts(attestation = stale)), "device.patch.mismatch"))
    }

    @Test
    fun `attestation extension is parsed from DER`() {
        val challenge = byteArrayOf(1, 2, 3, 4)
        val rootOfTrust = seq(octet(ByteArray(32)), bool(false), enumerated(2), octet(ByteArray(32)))
        val hw = seq(tagged(704, rootOfTrust), tagged(706, integer(202609)))
        val description = seq(integer(4), enumerated(1), integer(4), enumerated(1), octet(challenge), octet(ByteArray(0)), seq(), hw)
        val parsed = AttestationVerifier.parseExtension(octet(description))
        assertEquals(1, parsed.securityLevel)
        assertTrue(parsed.challenge.contentEquals(challenge))
        assertEquals(false, parsed.deviceLocked)
        assertEquals(2, parsed.verifiedBootState)
        assertEquals(202609, parsed.osPatchLevel)
    }

    @Test
    fun `malformed DER fails fast`() {
        assertFailsWith<DerException> { Der.read(byteArrayOf(0x30, 0x05, 0x02)) }
        assertFailsWith<DerException> { Der.read(byteArrayOf(0x30, 0x84.toByte(), 0x7F, 0x7F, 0x7F, 0x7F)) }
        assertFailsWith<DerException> { Der.read(byteArrayOf(0x02, 0x01, 0x05, 0x00)) }
    }

    // --- tiny DER builder for the test ---
    private fun tlv(tag: ByteArray, content: ByteArray): ByteArray {
        val len = content.size
        val lenBytes = if (len < 128) byteArrayOf(len.toByte()) else byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        return tag + lenBytes + content
    }
    private fun seq(vararg items: ByteArray) = tlv(byteArrayOf(0x30), items.fold(ByteArray(0)) { a, b -> a + b })
    private fun octet(b: ByteArray) = tlv(byteArrayOf(0x04), b)
    private fun integer(v: Int) = tlv(byteArrayOf(0x02), java.math.BigInteger.valueOf(v.toLong()).toByteArray())
    private fun enumerated(v: Int) = tlv(byteArrayOf(0x0A), byteArrayOf(v.toByte()))
    private fun bool(v: Boolean) = tlv(byteArrayOf(0x01), byteArrayOf(if (v) 0xFF.toByte() else 0))
    /** Context-specific constructed tag with a multi-byte tag number, e.g. [704] = BF 85 40. */
    private fun tagged(n: Int, content: ByteArray) = tlv(byteArrayOf(0xBF.toByte(), (0x80 or (n shr 7)).toByte(), (n and 0x7F).toByte()), content)
}
