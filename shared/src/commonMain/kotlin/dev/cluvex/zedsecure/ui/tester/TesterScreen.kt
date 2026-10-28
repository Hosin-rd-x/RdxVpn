package dev.cluvex.zedsecure.ui.tester

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.zedsecure.core.VpnManager
import dev.cluvex.zedsecure.data.config.ConfigRepository
import dev.cluvex.zedsecure.data.config.PresetSubscriptions
import dev.cluvex.zedsecure.domain.config.VpnProfile
import dev.cluvex.zedsecure.data.net.NetworkInfoRepository
import dev.cluvex.zedsecure.data.net.PingService
import dev.cluvex.zedsecure.domain.model.ConnectionState
import dev.cluvex.zedsecure.platform.currentTimeMillis
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.copied
import dev.cluvex.zedsecure.shared.resources.preset_sub_name_1
import dev.cluvex.zedsecure.shared.resources.subs_failed
import dev.cluvex.zedsecure.shared.resources.tester_cancel
import dev.cluvex.zedsecure.shared.resources.tester_code_hint
import dev.cluvex.zedsecure.shared.resources.tester_copy_code
import dev.cluvex.zedsecure.shared.resources.tester_count
import dev.cluvex.zedsecure.shared.resources.tester_desc
import dev.cluvex.zedsecure.shared.resources.tester_disconnect_first
import dev.cluvex.zedsecure.shared.resources.tester_done
import dev.cluvex.zedsecure.shared.resources.tester_empty
import dev.cluvex.zedsecure.shared.resources.tester_importing
import dev.cluvex.zedsecure.shared.resources.tester_live_counts
import dev.cluvex.zedsecure.shared.resources.tester_refresh
import dev.cluvex.zedsecure.shared.resources.tester_retry_upload
import dev.cluvex.zedsecure.shared.resources.tester_run_irancell
import dev.cluvex.zedsecure.shared.resources.tester_run_mci
import dev.cluvex.zedsecure.shared.resources.tester_running
import dev.cluvex.zedsecure.shared.resources.tester_title
import dev.cluvex.zedsecure.shared.resources.tester_upload_failed
import dev.cluvex.zedsecure.shared.resources.tester_uploading
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Carrier labels written into the uploaded report (plain ASCII, easy to parse). */
private const val CARRIER_MCI = "MCI"
private const val CARRIER_IRANCELL = "Irancell"

private data class CompletedRun(
    val carrier: String,
    val connected: Int,
    val total: Int,
    val code: String,
)

private data class PendingUpload(
    val report: String,
    val carrier: String,
    val connected: Int,
    val total: Int,
)

@Composable
fun TesterScreen(
    repository: ConfigRepository,
    contentPadding: PaddingValues,
    delayTestUrl: String,
    realPingConcurrency: Int,
    appVersion: String,
) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val profiles by repository.profiles.collectAsStateWithLifecycle()
    val subscriptions by repository.subscriptions.collectAsStateWithLifecycle()
    val vpnStatus by VpnManager.status.collectAsStateWithLifecycle()

    val presetName = stringResource(Res.string.preset_sub_name_1)
    val copiedText = stringResource(Res.string.copied)
    val importFailedText = stringResource(Res.string.subs_failed)

    val presetSub = remember(subscriptions) {
        subscriptions.firstOrNull { sub ->
            PresetSubscriptions.URLS.any { it.equals(sub.url, ignoreCase = true) }
        }
    }
    val presetProfiles = remember(profiles, presetSub) {
        val id = presetSub?.id ?: return@remember emptyList()
        profiles.filter { it.subscriptionId == id }
    }

    var importing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    // Results arrive from up to `realPingConcurrency` probe threads at once,
    // so they live in a StateFlow updated with the atomic `update` (a plain
    // map would race). Live counts below derive from this state.
    val resultsFlow = remember { MutableStateFlow<Map<String, Int>>(emptyMap()) }
    val resultsState by resultsFlow.collectAsStateWithLifecycle()
    val okCount = resultsState.count { it.value > 0 }
    val failCount = resultsState.count { it.value <= 0 }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var runs by remember { mutableStateOf<List<CompletedRun>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var pendingUpload by remember { mutableStateOf<PendingUpload?>(null) }

    val vpnBusy = vpnStatus.state == ConnectionState.Connecting ||
        vpnStatus.state == ConnectionState.Connected ||
        vpnStatus.state == ConnectionState.Reconnecting ||
        vpnStatus.state == ConnectionState.Disconnecting
    val canRun = !running && !importing && presetProfiles.isNotEmpty() && !vpnBusy

    fun refreshPreset() {
        if (importing || running) return
        importing = true
        scope.launch {
            repository.importPresetSubscriptions(listOf(presetName))
                .onFailure { platform.toast(importFailedText) }
            importing = false
        }
    }

    fun uploadReport(pending: PendingUpload) {
        if (uploading) return
        uploading = true
        scope.launch {
            val code = if (platform.supportsLogUpload) platform.uploadLog(pending.report) else null
            uploading = false
            if (code != null) {
                runs = listOf(
                    CompletedRun(pending.carrier, pending.connected, pending.total, code),
                ) + runs
                pendingUpload = null
            } else {
                pendingUpload = pending
            }
        }
    }

    fun startRun(carrier: String) {
        if (!canRun) return
        val snapshot = presetProfiles
        if (snapshot.isEmpty()) return
        running = true
        done = 0
        total = snapshot.size
        resultsFlow.value = emptyMap()
        pendingUpload = null
        runJob = scope.launch {
            try {
                PingService.measureAll(
                    snapshot,
                    useRealDelay = true,
                    concurrency = realPingConcurrency,
                    delayUrl = delayTestUrl,
                    chainConfig = repository::chainProbeConfig,
                    onProgress = { d, t ->
                        done = d
                        total = t
                    },
                ) { id, ms, _ ->
                    resultsFlow.update { it + (id to (ms ?: PingService.FAILED_PING)) }
                }
                // Stage 2: every stage-1 survivor must also survive several
                // spaced re-probes, or it is marked failed. A single quick
                // probe passes on carriers that drop sustained connections
                // after a few packets, while real use dies.
                val survivors = snapshot.filter {
                    (resultsFlow.value[it.id] ?: PingService.FAILED_PING) > 0
                }
                if (survivors.isNotEmpty()) {
                    done = 0
                    total = survivors.size
                    PingService.verifySustained(
                        survivors,
                        delayUrl = delayTestUrl,
                        chainConfig = repository::chainProbeConfig,
                        onProgress = { d, t ->
                            done = d
                            total = t
                        },
                    ) { id, ms ->
                        resultsFlow.update { it + (id to (ms ?: PingService.FAILED_PING)) }
                    }
                }
            } catch (e: CancellationException) {
                running = false
                runJob = null
                throw e
            } catch (_: Exception) {
                running = false
                runJob = null
                return@launch
            }
            val results = resultsFlow.value
            val connected = results.count { it.value > 0 }
            val report = buildReport(
                carrier = carrier,
                snapshot = snapshot,
                results = results,
                connected = connected,
                delayUrl = delayTestUrl,
                appVersion = appVersion,
            )
            running = false
            runJob = null
            uploadReport(PendingUpload(report, carrier, connected, snapshot.size))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.tester_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.tester_desc), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.tester_count, presetProfiles.size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (importing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(Res.string.tester_importing))
            } else {
                Button(onClick = ::refreshPreset, enabled = !running) {
                    Text(stringResource(Res.string.tester_refresh))
                }
            }
        }
        if (presetProfiles.isEmpty() && !importing) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.tester_empty), style = MaterialTheme.typography.bodyMedium)
        }
        if (vpnBusy) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.tester_disconnect_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { startRun(CARRIER_MCI) },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.tester_run_mci)) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { startRun(CARRIER_IRANCELL) },
            enabled = canRun,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(Res.string.tester_run_irancell)) }

        if (running) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.tester_running, done, total))
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = if (total > 0) done.toFloat() / total else 0f,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.tester_live_counts, okCount, failCount))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { runJob?.cancel() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.tester_cancel)) }
        }

        if (uploading) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.tester_uploading))
            }
        }

        pendingUpload?.let { pending ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            Res.string.tester_done,
                            pending.connected,
                            pending.total,
                            carrierLabel(pending.carrier),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(Res.string.tester_upload_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { uploadReport(pending) },
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(Res.string.tester_retry_upload)) }
                }
            }
        }

        runs.forEach { run ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            Res.string.tester_done,
                            run.connected,
                            run.total,
                            carrierLabel(run.carrier),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.tester_code_hint))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            run.code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                platform.copyToClipboard(run.code)
                                platform.toast(copiedText)
                            },
                        ) { Text(stringResource(Res.string.tester_copy_code)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun carrierLabel(carrier: String): String = stringResource(
    if (carrier == CARRIER_MCI) Res.string.tester_run_mci else Res.string.tester_run_irancell,
)

/**
 * One line per config: `name | protocol | status | ms`.
 * `status` is ok (connected, ms > 0), fail (probe ran, no connection) or
 * skipped (the prober does not cover this profile kind, e.g. managed tunnels).
 */
private fun buildReport(
    carrier: String,
    snapshot: List<VpnProfile>,
    results: Map<String, Int>,
    connected: Int,
    delayUrl: String,
    appVersion: String,
): String {
    val probe = delayUrl.ifBlank { NetworkInfoRepository.DELAY_TEST_URL }
    val sb = StringBuilder()
    sb.appendLine("RDX CONFIG TEST")
    sb.appendLine("carrier: $carrier")
    sb.appendLine("date: ${currentTimeMillis()}")
    sb.appendLine("app: $appVersion")
    sb.appendLine("probe: $probe")
    sb.appendLine("total: ${snapshot.size}")
    sb.appendLine("connected: $connected")
    sb.appendLine("---")
    sb.appendLine("name | protocol | status | ms")
    for (profile in snapshot) {
        val ms = results[profile.id]
        val (status, msText) = when {
            ms == null -> "skipped" to "-"
            ms > 0 -> "ok" to ms.toString()
            else -> "fail" to "-1"
        }
        sb.appendLine("${sanitize(profile.name)} | ${profile.protocol} | $status | $msText")
    }
    return sb.toString()
}

private fun sanitize(name: String): String = name
    .replace('|', ' ')
    .replace('\n', ' ')
    .replace('\r', ' ')
    .trim()
    .take(120)
