package lian.app.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lian.app.AppGraph
import lian.app.R
import lian.app.ui.LianTopBar
import lian.app.ui.Loading
import lian.app.ui.MonoText
import lian.app.ui.design.GlassCard
import lian.app.ui.design.IconTile
import lian.app.ui.design.appear
import lian.app.ui.theme.Accent
import lian.app.ui.theme.LocalLianColors
import lian.core.platform.PrivacyProbe
import lian.core.platform.untrusted.UntrustedClient

class PrivacyProofViewModel(graph: AppGraph) : ViewModel() {
    data class State(val report: PrivacyProbe.Report? = null, val sandbox: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { graph.privacyProbe.run() }
            _state.value = State(report)
            val sandbox = withContext(Dispatchers.IO) {
                when (val o = graph.untrusted.selfTest()) {
                    is UntrustedClient.Outcome.Ok -> o.text
                    is UntrustedClient.Outcome.Failed -> "unavailable: ${o.reason}"
                }
            }
            _state.value = State(report, sandbox)
        }
    }
}

@Composable
fun PrivacyProofRoute(graph: AppGraph, onBack: () -> Unit) {
    val vm: PrivacyProofViewModel = viewModel { PrivacyProofViewModel(graph) }
    val state by vm.state.collectAsStateWithLifecycle()
    PrivacyProofScreen(state.report, state.sandbox, onBack)
}

@Composable
fun PrivacyProofScreen(report: PrivacyProbe.Report?, sandbox: String?, onBack: () -> Unit) {
    val c = LocalLianColors.current
    Scaffold(containerColor = Color.Transparent, topBar = { LianTopBar(stringResource(R.string.privacy_title), onBack) }) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The promise first, then the evidence for it.
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.brandBrush).padding(18.dp)) {
                Column {
                    Text(stringResource(R.string.usp_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.privacy_intro), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
                }
            }
            if (report == null) {
                Loading(stringResource(R.string.privacy_loading))
                return@Column
            }
            var index = 0

            ProofCard(index++, !report.internetRequested, R.drawable.ic_wifi_off, Accent.MINT, stringResource(R.string.privacy_internet_title)) {
                Text(stringResource(if (report.internetRequested) R.string.privacy_internet_present else R.string.privacy_internet_absent), style = MaterialTheme.typography.bodyMedium)
            }

            val blocked = report.socket is PrivacyProbe.SocketTest.BlockedByAndroid
            ProofCard(index++, blocked, R.drawable.ic_wifi_off, Accent.SKY, stringResource(R.string.privacy_socket_title)) {
                when (val s = report.socket) {
                    is PrivacyProbe.SocketTest.BlockedByAndroid -> Text(stringResource(R.string.privacy_socket_blocked, s.errno), style = MaterialTheme.typography.bodyMedium)
                    is PrivacyProbe.SocketTest.Allowed -> Text(stringResource(R.string.privacy_socket_allowed, s.detail), style = MaterialTheme.typography.bodyMedium)
                }
            }

            ProofCard(index++, true, R.drawable.ic_eye, Accent.VIOLET, stringResource(R.string.privacy_trackers_title)) {
                Text(stringResource(R.string.privacy_trackers_body), style = MaterialTheme.typography.bodyMedium)
            }

            ProofCard(index++, true, R.drawable.ic_battery, Accent.AMBER, stringResource(R.string.privacy_background_title)) {
                Text(stringResource(R.string.privacy_background_body), style = MaterialTheme.typography.bodyMedium)
            }

            sandbox?.let { s ->
                val isolated = "isolated=true" in s && "canReadAppFiles=false" in s
                ProofCard(index++, isolated, R.drawable.ic_shield, Accent.PINK, stringResource(R.string.privacy_sandbox_title)) {
                    val uid = s.substringAfter("uid=", "?").substringBefore(' ')
                    Text(if (isolated) stringResource(R.string.privacy_sandbox_ok, uid) else stringResource(R.string.privacy_sandbox_bad, s), style = MaterialTheme.typography.bodyMedium)
                    MonoText(s)
                }
            }

            val visible = report.requestedPermissions.filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
            ProofCard(index++, true, R.drawable.ic_lock, Accent.VIOLET, stringResource(R.string.privacy_permissions_title)) {
                if (visible.isEmpty()) Text(stringResource(R.string.privacy_permissions_none), style = MaterialTheme.typography.bodyMedium) else visible.forEach { MonoText(it) }
            }

            ProofCard(index++, true, R.drawable.ic_apps, Accent.SKY, stringResource(R.string.privacy_installer_title)) {
                report.installer?.let { MonoText(it) } ?: Text(stringResource(R.string.privacy_installer_unknown), style = MaterialTheme.typography.bodyMedium)
            }

            val genuine = report.genuineness
            ProofCard(index, genuine != PrivacyProbe.Genuineness.NOT_GENUINE, R.drawable.ic_lock, Accent.MINT, stringResource(R.string.privacy_signature_title)) {
                report.signerSha256.forEach { MonoText(it.chunked(16).joinToString(" ")) }
                Text(
                    stringResource(
                        when (genuine) {
                            PrivacyProbe.Genuineness.GENUINE -> R.string.privacy_genuine
                            PrivacyProbe.Genuineness.NOT_GENUINE -> R.string.privacy_not_genuine
                            PrivacyProbe.Genuineness.NOT_CONFIGURED -> R.string.privacy_not_configured
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One piece of evidence: a coloured tile, the title, a pass/fail badge and the detail read from Android. */
@Composable
private fun ProofCard(index: Int, ok: Boolean, icon: Int, accent: Accent, title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalLianColors.current
    val status = if (ok) c.safe else c.danger
    GlassCard(Modifier.fillMaxWidth().appear(index)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, accent, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(status.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error), null, tint = status, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}
