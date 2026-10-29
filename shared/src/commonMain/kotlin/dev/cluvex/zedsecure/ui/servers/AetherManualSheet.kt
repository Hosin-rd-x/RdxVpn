@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import dev.cluvex.zedsecure.domain.config.AetherManualSettings
import dev.cluvex.zedsecure.domain.config.AetherProfile
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.components.PickerField

/**
 * Manual Aether editor: every Aether setting, like PattNG's editor.
 * Structured fields build the stored argument list through
 * [AetherManualSettings]; editing an existing profile parses its args
 * back, and settings the editor does not model are kept verbatim as
 * extra arguments, never dropped.
 *
 * The automatic one-tap flow is untouched and stays the main path for
 * users who do not want to choose anything. The scan button here only
 * fills in an endpoint (and pins it); connecting never re-scans — the
 * service connects with --quick-reconnect, like PattNG's session.
 *
 * [onScan] runs in the app layer (it starts the real binary): it gets
 * scan-form args and returns the gateway the scan selected, or a
 * failure with a ready-to-show message. Null hides the scan button.
 */
@Composable
fun AetherManualSheet(
    onDismiss: () -> Unit,
    onSave: (name: String, AetherProfile) -> Unit,
    onScan: (suspend (args: List<String>, onProgress: (String) -> Unit) -> Result<String>)? = null,
    initial: AetherProfile? = null,
    initialName: String = "",
) {
    val scope = rememberCoroutineScope()
    val initialSettings = remember(initial) {
        initial?.let { AetherManualSettings.fromArgs(it.args) } ?: AetherManualSettings()
    }
    var name by remember { mutableStateOf(initialName.ifBlank { initial?.displayName ?: "" }) }
    var settings by remember { mutableStateOf(initialSettings) }
    var scannedPeer by remember { mutableStateOf("") }
    var scanRunning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun dismiss() {
        scanJob?.cancel()
        scanJob = null
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            scanJob?.cancel()
            scanJob = null
        }
    }

    val endpointError = stringResource(Res.string.aether_m_error_endpoint)
    val upstreamError = stringResource(Res.string.aether_m_error_upstream)
    val scanFailed = stringResource(Res.string.aether_m_scan_failed)
    val scanFoundTemplate = stringResource(Res.string.aether_m_scan_found)

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.aether_manual_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(Res.string.aether_manual_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (initialSettings.hasUnknownArgs) {
                Text(
                    stringResource(Res.string.aether_m_unknown_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            F(name, Res.string.manual_remark) { name = it }

            PickerField(
                label = stringResource(Res.string.aether_m_protocol),
                options = keepCurrent(
                    listOf(
                        AetherManualSettings.PROTOCOL_MASQUE to stringResource(Res.string.aether_m_protocol_masque),
                        AetherManualSettings.PROTOCOL_WG to stringResource(Res.string.aether_m_protocol_wg),
                        AetherManualSettings.PROTOCOL_GOOL to stringResource(Res.string.aether_m_protocol_gool),
                        AetherManualSettings.PROTOCOL_MIM to stringResource(Res.string.aether_m_protocol_mim),
                        AetherManualSettings.PROTOCOL_WG_OVER_MASQUE to stringResource(Res.string.aether_m_protocol_wg_over_masque),
                    ),
                    settings.protocol,
                ),
                selected = settings.protocol,
                onSelect = { settings = settings.copy(protocol = it) },
            )
            PickerField(
                label = stringResource(Res.string.aether_m_scan),
                options = keepCurrent(
                    listOf(
                        AetherManualSettings.SCAN_TURBO to stringResource(Res.string.aether_m_scan_turbo),
                        AetherManualSettings.SCAN_BALANCED to stringResource(Res.string.aether_m_scan_balanced),
                        AetherManualSettings.SCAN_THOROUGH to stringResource(Res.string.aether_m_scan_thorough),
                        AetherManualSettings.SCAN_VERIFIED to stringResource(Res.string.aether_m_scan_verified),
                        AetherManualSettings.SCAN_IRONCLAD to stringResource(Res.string.aether_m_scan_ironclad),
                    ),
                    settings.scanMode,
                ),
                selected = settings.scanMode,
                onSelect = { settings = settings.copy(scanMode = it) },
            )
            PickerField(
                label = stringResource(Res.string.aether_m_transport),
                options = keepCurrent(
                    listOf(
                        AetherManualSettings.TRANSPORT_H3 to stringResource(Res.string.aether_m_transport_h3),
                        AetherManualSettings.TRANSPORT_H2 to stringResource(Res.string.aether_m_transport_h2),
                    ),
                    settings.transport,
                ),
                selected = settings.transport,
                onSelect = { settings = settings.copy(transport = it) },
            )
            PickerField(
                label = stringResource(Res.string.aether_m_ip),
                options = keepCurrent(
                    listOf(
                        AetherManualSettings.IP_V4 to stringResource(Res.string.aether_m_ip_v4),
                        AetherManualSettings.IP_V6 to stringResource(Res.string.aether_m_ip_v6),
                        AetherManualSettings.IP_BOTH to stringResource(Res.string.aether_m_ip_both),
                    ),
                    settings.ipVersion,
                ),
                selected = settings.ipVersion,
                onSelect = { settings = settings.copy(ipVersion = it) },
            )
            PickerField(
                label = stringResource(Res.string.aether_m_noize),
                options = keepCurrent(
                    listOf(
                        AetherManualSettings.NOIZE_AUTO to stringResource(Res.string.aether_m_noize_auto),
                        AetherManualSettings.NOIZE_OFF to stringResource(Res.string.aether_m_noize_off),
                        AetherManualSettings.NOIZE_LIGHT to stringResource(Res.string.aether_m_noize_light),
                        AetherManualSettings.NOIZE_FIREWALL to stringResource(Res.string.aether_m_noize_firewall),
                        AetherManualSettings.NOIZE_BALANCED to stringResource(Res.string.aether_m_noize_balanced),
                        AetherManualSettings.NOIZE_GFW to stringResource(Res.string.aether_m_noize_gfw),
                        AetherManualSettings.NOIZE_AGGRESSIVE to stringResource(Res.string.aether_m_noize_aggressive),
                    ),
                    settings.noize,
                ),
                selected = settings.noize,
                onSelect = { settings = settings.copy(noize = it) },
            )

            if (settings.isTwoHop) {
                F(settings.outerPeer, Res.string.aether_m_outer, "162.159.198.79:443") {
                    settings = settings.copy(outerPeer = it)
                }
                F(settings.innerPeer, Res.string.aether_m_inner, "162.159.198.106:443") {
                    settings = settings.copy(innerPeer = it)
                }
            } else {
                F(settings.peer, Res.string.aether_m_peer, "162.159.198.54:443") {
                    settings = settings.copy(peer = it)
                }
            }

            if (onScan != null) {
                OutlinedButton(
                    onClick = {
                        if (scanRunning) return@OutlinedButton
                        val snapshot = settings
                        scanMessage = null
                        error = null
                        scanRunning = true
                        scanJob = scope.launch {
                            try {
                                val scanArgs = snapshot.toArgs() + listOf(
                                    "--no-quick-reconnect",
                                    "--bind", "127.0.0.1:1819",
                                )
                                val result = onScan(scanArgs) { }
                                scanRunning = false
                                scanJob = null
                                result.fold(
                                    onSuccess = { gateway ->
                                        if (snapshot.isTwoHop) {
                                            settings = settings.copy(outerPeer = gateway)
                                        } else {
                                            settings = settings.copy(peer = gateway)
                                            scannedPeer = gateway
                                        }
                                        scanMessage = scanFoundTemplate.replace("%1\$s", gateway)
                                    },
                                    onFailure = { e ->
                                        scanMessage = e.message?.takeIf { it.isNotBlank() } ?: scanFailed
                                    },
                                )
                            } catch (e: CancellationException) {
                                scanRunning = false
                                scanJob = null
                            } catch (e: Exception) {
                                scanRunning = false
                                scanJob = null
                                scanMessage = e.message?.takeIf { it.isNotBlank() } ?: scanFailed
                            }
                        }
                    },
                    enabled = !scanRunning,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        if (scanRunning) stringResource(Res.string.aether_m_scanning)
                        else stringResource(Res.string.aether_m_scan_button),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (scanRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (scanMessage != null) {
                    Text(
                        scanMessage ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            F(settings.dns, Res.string.aether_m_dns, "1.1.1.1,1.0.0.1") {
                settings = settings.copy(dns = it)
            }
            F(settings.exitLoc, Res.string.aether_m_exit_loc, "DE") {
                settings = settings.copy(exitLoc = it)
            }

            if (settings.isOverMasque) {
                F(settings.masqueSni, Res.string.aether_m_masque_sni, "www.cloudflare.com") {
                    settings = settings.copy(masqueSni = it)
                }
                if (settings.transport == AetherManualSettings.TRANSPORT_H2) {
                    SwitchRow(
                        stringResource(Res.string.aether_m_fragment),
                        settings.fragment,
                    ) { settings = settings.copy(fragment = it) }
                    if (settings.fragment) {
                        F(settings.fragmentSize, Res.string.aether_m_fragment_size, numeric = true, allowDash = true) {
                            settings = settings.copy(fragmentSize = it)
                        }
                        F(settings.fragmentDelay, Res.string.aether_m_fragment_delay, numeric = true, allowDash = true) {
                            settings = settings.copy(fragmentDelay = it)
                        }
                    }
                }
                SwitchRow(
                    stringResource(Res.string.aether_m_ech),
                    settings.ech,
                ) { settings = settings.copy(ech = it) }
                if (settings.ech) {
                    F(settings.echDns, Res.string.aether_m_ech_dns, "udp://1.1.1.1") {
                        settings = settings.copy(echDns = it)
                    }
                    F(settings.echDomain, Res.string.aether_m_ech_domain, "cloudflare-ech.com") {
                        settings = settings.copy(echDomain = it)
                    }
                }
                PickerField(
                    label = stringResource(Res.string.aether_m_fingerprint),
                    options = keepCurrent(
                        listOf(
                            AetherManualSettings.FINGERPRINT_CHROME to stringResource(Res.string.aether_m_fingerprint_chrome),
                            AetherManualSettings.FINGERPRINT_CHROME_120 to stringResource(Res.string.aether_m_fingerprint_chrome_120),
                            AetherManualSettings.FINGERPRINT_FIREFOX to stringResource(Res.string.aether_m_fingerprint_firefox),
                            AetherManualSettings.FINGERPRINT_SEMI_PYTHON to stringResource(Res.string.aether_m_fingerprint_semi_python),
                            AetherManualSettings.FINGERPRINT_GO to stringResource(Res.string.aether_m_fingerprint_go),
                        ),
                        settings.fingerprint,
                    ),
                    selected = settings.fingerprint,
                    onSelect = { settings = settings.copy(fingerprint = it) },
                )
            }

            PickerField(
                label = stringResource(Res.string.aether_m_psiphon),
                options = keepCurrent(
                    listOf(
                        AetherManualSettings.PSIPHON_OFF to stringResource(Res.string.aether_m_psiphon_off),
                        AetherManualSettings.PSIPHON_CHAIN to stringResource(Res.string.aether_m_psiphon_chain),
                        AetherManualSettings.PSIPHON_REVERSE to stringResource(Res.string.aether_m_psiphon_reverse),
                        AetherManualSettings.PSIPHON_ONLY to stringResource(Res.string.aether_m_psiphon_only),
                    ),
                    settings.psiphon,
                ),
                selected = settings.psiphon,
                onSelect = { settings = settings.copy(psiphon = it) },
            )
            if (settings.psiphon != AetherManualSettings.PSIPHON_OFF) {
                Text(
                    stringResource(Res.string.aether_m_chained_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PickerField(
                    label = stringResource(Res.string.aether_m_psiphon_mode),
                    options = keepCurrent(
                        listOf(
                            AetherManualSettings.PSIPHON_MODE_AUTO to stringResource(Res.string.aether_m_psiphon_mode_auto),
                            AetherManualSettings.PSIPHON_MODE_CDN to stringResource(Res.string.aether_m_psiphon_mode_cdn),
                            AetherManualSettings.PSIPHON_MODE_DIRECT to stringResource(Res.string.aether_m_psiphon_mode_direct),
                        ),
                        settings.psiphonMode,
                    ),
                    selected = settings.psiphonMode,
                    onSelect = { settings = settings.copy(psiphonMode = it) },
                )
                F(settings.psiphonRegion, Res.string.aether_m_psiphon_region, "DE") {
                    settings = settings.copy(psiphonRegion = it)
                }
                if (settings.psiphonMode != AetherManualSettings.PSIPHON_MODE_DIRECT) {
                    F(settings.psiphonCdnIps, Res.string.aether_m_psiphon_cdn_ips) {
                        settings = settings.copy(psiphonCdnIps = it)
                    }
                    F(settings.psiphonCdnSni, Res.string.aether_m_psiphon_cdn_sni) {
                        settings = settings.copy(psiphonCdnSni = it)
                    }
                    F(settings.psiphonCdnSets, Res.string.aether_m_psiphon_cdn_sets, "cloudflare, fastly") {
                        settings = settings.copy(psiphonCdnSets = it)
                    }
                }
                SwitchRow(
                    stringResource(Res.string.aether_m_psiphon_bundled),
                    settings.psiphonBundledList,
                ) { settings = settings.copy(psiphonBundledList = it) }
            } else {
                Text(
                    stringResource(Res.string.aether_m_direct_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            F(settings.reconnectSecs, Res.string.aether_m_reconnect, numeric = true) {
                settings = settings.copy(reconnectSecs = it)
            }
            F(settings.keepaliveSecs, Res.string.aether_m_keepalive, numeric = true) {
                settings = settings.copy(keepaliveSecs = it)
            }
            F(settings.validateSecs, Res.string.aether_m_validate, numeric = true) {
                settings = settings.copy(validateSecs = it)
            }
            F(settings.startupSecs, Res.string.aether_m_startup, numeric = true) {
                settings = settings.copy(startupSecs = it)
            }
            PickerField(
                label = stringResource(Res.string.aether_m_perf),
                options = keepCurrent(
                    listOf(
                        "" to stringResource(Res.string.aether_m_perf_auto),
                        "low" to stringResource(Res.string.aether_m_perf_low),
                        "medium" to stringResource(Res.string.aether_m_perf_medium),
                        "high" to stringResource(Res.string.aether_m_perf_high),
                    ),
                    settings.perf,
                ),
                selected = settings.perf,
                onSelect = { settings = settings.copy(perf = it) },
            )
            SwitchRow(
                stringResource(Res.string.aether_m_no_quic_v2),
                settings.noQuicV2,
            ) { settings = settings.copy(noQuicV2 = it) }
            F(settings.h2Peer, Res.string.aether_m_h2_peer) {
                settings = settings.copy(h2Peer = it)
            }

            F(settings.routeBlock, Res.string.aether_m_route_block) {
                settings = settings.copy(routeBlock = it)
            }
            F(settings.routeDirect, Res.string.aether_m_route_direct) {
                settings = settings.copy(routeDirect = it)
            }
            F(settings.upstream, Res.string.aether_m_upstream, "socks5://127.0.0.1:1080") {
                settings = settings.copy(upstream = it)
            }

            F(settings.extraArgs, Res.string.aether_m_extra_args, minLines = 2) {
                settings = settings.copy(extraArgs = it)
            }
            Text(
                stringResource(Res.string.aether_m_extra_args_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (error != null) {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val snapshot = settings
                    val endpointBad = listOf(
                        snapshot.peer, snapshot.outerPeer,
                        snapshot.innerPeer, snapshot.h2Peer,
                    ).any { it.isNotBlank() && !AetherManualSettings.isValidEndpoint(it) }
                    if (endpointBad) {
                        error = endpointError
                        return@Button
                    }
                    val upstream = snapshot.upstream.trim()
                    if (upstream.isNotEmpty() &&
                        !upstream.startsWith("socks5://") && !upstream.startsWith("http://")
                    ) {
                        error = upstreamError
                        return@Button
                    }
                    error = null
                    // The pinned gateway belongs to the scanned endpoint:
                    // a hand-edited endpoint replaces it, and two-hop
                    // profiles never pin (the service would add a stray
                    // --peer next to the hop flags).
                    val peerChanged = initial == null ||
                        snapshot.peer != initialSettings.peer ||
                        snapshot.outerPeer != initialSettings.outerPeer
                    val pinned = when {
                        scannedPeer.isNotBlank() -> scannedPeer
                        snapshot.isTwoHop -> ""
                        peerChanged -> ""
                        else -> initial?.pinnedPeer.orEmpty()
                    }
                    val endpoint = snapshot.peer.ifBlank { snapshot.outerPeer }
                    onSave(
                        name.trim(),
                        AetherProfile(
                            method = "manual",
                            args = snapshot.toArgs(),
                            displayName = name.trim().ifBlank { "Aether" },
                            address = AetherManualSettings.hostOf(endpoint),
                            ipVersion = if (snapshot.ipVersion == AetherManualSettings.IP_BOTH) {
                                "any"
                            } else {
                                snapshot.ipVersion
                            },
                            fingerprint = initial?.fingerprint ?: "",
                            cipherSuites = initial?.cipherSuites ?: "",
                            finalMask = initial?.finalMask ?: "",
                            echConfigList = initial?.echConfigList ?: "",
                            alpn = initial?.alpn ?: "",
                            pinnedPeer = pinned,
                        ),
                    )
                },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    stringResource(Res.string.action_save),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Keeps a stored value selectable even when it is not a standard option. */
private fun <T> keepCurrent(
    options: List<Pair<T, String>>,
    current: T,
): List<Pair<T, String>> =
    if (options.any { it.first == current }) options
    else options + (current to current.toString())

@Composable
private fun F(
    value: String,
    labelRes: StringResource,
    placeholder: String? = null,
    minLines: Int = 1,
    numeric: Boolean = false,
    allowDash: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            onChange(
                if (numeric) it.filter { c -> c.isDigit() || (allowDash && c == '-') } else it,
            )
        },
        label = { Text(stringResource(labelRes)) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
