package lian.core.engine.watch

import org.junit.jupiter.api.Test
import lian.core.engine.Severity
import lian.core.engine.app.Capability
import lian.core.engine.app.Provenance
import lian.core.engine.device.CheckStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTest {
    private val base = Snapshot(
        ruleSetVersion = "app-1",
        apps = mapOf(
            "fpA" to AppState("sigA", Provenance.STORE_REVIEWED, true, emptySet(), 0),
            "fpB" to AppState("sigB", Provenance.FILE_INSTALL, true, setOf(Capability.SMS), 30),
        ),
        posture = mapOf("lock" to CheckStatus.PASS, "developer" to CheckStatus.PASS),
        userCaCount = 0,
    )

    private fun types(old: Snapshot?, new: Snapshot) = WatchDiff.diff(old, new).map { it.type }

    @Test
    fun `first run records a baseline without alerts`() {
        assertEquals(emptyList(), WatchDiff.diff(null, base))
        assertEquals(emptyList(), WatchDiff.diff(base, base))
    }

    @Test
    fun `signer change and hidden icon are critical`() {
        val changed = base.copy(apps = base.apps + ("fpB" to base.apps.getValue("fpB").copy(signerFp = "other", launcher = false)))
        val events = WatchDiff.diff(base, changed)
        assertEquals(listOf(WatchEvent.Type.SIGNER_CHANGED, WatchEvent.Type.ICON_HIDDEN), events.map { it.type })
        assertTrue(events.all { it.severity == Severity.CRITICAL })
    }

    @Test
    fun `new sideloaded app, new special access, new CA and posture regressions are reported`() {
        val next = base.copy(
            apps = base.apps +
                ("fpA" to base.apps.getValue("fpA").copy(special = setOf(Capability.ACCESSIBILITY))) +
                ("fpC" to AppState("sigC", Provenance.FILE_INSTALL, true, emptySet(), 0)),
            posture = mapOf("lock" to CheckStatus.FAIL, "developer" to CheckStatus.PASS),
            userCaCount = 1,
        )
        val t = types(base, next)
        assertTrue(listOf(WatchEvent.Type.NEW_APP, WatchEvent.Type.SPECIAL_ACCESS_GAINED, WatchEvent.Type.USER_CA_ADDED, WatchEvent.Type.POSTURE_REGRESSED).all { it in t }, t.toString())
    }

    @Test
    fun `a new store app with no risk is not news`() {
        val next = base.copy(apps = base.apps + ("fpD" to AppState("sigD", Provenance.STORE_REVIEWED, true, emptySet(), 5)))
        assertEquals(emptyList(), types(base, next))
    }

    @Test
    fun `risk increases after a rules update are not reported`() {
        val riskier = base.apps + ("fpB" to base.apps.getValue("fpB").copy(score = 80))
        assertTrue(WatchEvent.Type.RISK_ROSE in types(base, base.copy(apps = riskier)))
        assertFalse(WatchEvent.Type.RISK_ROSE in types(base, base.copy(apps = riskier, ruleSetVersion = "app-2")))
    }

    @Test
    fun `snapshots round-trip and garbage is treated as no baseline`() {
        assertEquals(base, Snapshot.decode(base.encode()))
        assertNull(Snapshot.decode("not a snapshot"))
        assertTrue("sigA" in base.encode() && "com." !in base.encode())   // fingerprints only, never package names
    }

    @Test
    fun `at most one notification a day, and only for medium or worse`() {
        val events = listOf(WatchEvent(WatchEvent.Type.NEW_APP, Severity.MEDIUM, "fp", ""))
        assertTrue(WatchDiff.shouldNotify(events, lastNotifiedDay = 10, today = 11))
        assertFalse(WatchDiff.shouldNotify(events, lastNotifiedDay = 11, today = 11))
        assertFalse(WatchDiff.shouldNotify(listOf(WatchEvent(WatchEvent.Type.NEW_APP, Severity.LOW, "fp", "")), 0, 11))
    }
}
