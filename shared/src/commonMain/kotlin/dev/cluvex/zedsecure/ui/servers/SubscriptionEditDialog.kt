package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.data.config.ConfigRepository
import dev.cluvex.zedsecure.domain.config.Subscription
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.platform.appVerticalScroll
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The subscription edit dialog (name / URL / user agent). Shared by the
 * subscription sheet and by long-pressing a group tab in the server list.
 */
@Composable
internal fun SubscriptionEditDialog(
    target: Subscription,
    repository: ConfigRepository,
    onDismiss: () -> Unit,
) {
        var newName by remember(target.id) { mutableStateOf(target.name) }
        var newUrl by remember(target.id) { mutableStateOf(target.url) }
        var newUa by remember(target.id) { mutableStateOf(target.userAgent.orEmpty()) }
        var preset by remember(target.id) { mutableStateOf(target.preset) }
        var presetOpen by remember(target.id) { mutableStateOf(!target.preset.isEmpty) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = { Text(stringResource(Res.string.subs_edit)) },
            text = {
                Column(
                    Modifier.heightIn(max = 560.dp).appVerticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(Res.string.subs_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text(stringResource(Res.string.subs_url)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    UserAgentPicker(newUa) { newUa = it }
                    TextButton(onClick = { presetOpen = !presetOpen }) {
                        Text(stringResource(Res.string.subs_preset_title))
                    }
                    if (presetOpen) {
                        Text(
                            stringResource(Res.string.subs_preset_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PresetField(Res.string.subs_preset_address, preset.address) { preset = preset.copy(address = it) }
                        PresetField(Res.string.subs_preset_fingerprint, preset.fingerprint) { preset = preset.copy(fingerprint = it) }
                        PresetField(Res.string.subs_preset_alpn, preset.alpn) { preset = preset.copy(alpn = it) }
                        PresetField(Res.string.subs_preset_finalmask, preset.finalMask, singleLine = false) {
                            preset = preset.copy(finalMask = it)
                        }
                        PresetField(Res.string.subs_preset_ciphers, preset.cipherSuites) { preset = preset.copy(cipherSuites = it) }
                        PresetField(Res.string.subs_preset_ech, preset.echConfigList) { preset = preset.copy(echConfigList = it) }
                        dev.cluvex.zedsecure.ui.components.PickerField(
                            label = stringResource(Res.string.subs_preset_core),
                            options = listOf(
                                "" to stringResource(Res.string.subs_preset_core_link),
                                dev.cluvex.zedsecure.domain.config.SubscriptionPreset.CORE_XRAY to "Xray",
                                dev.cluvex.zedsecure.domain.config.SubscriptionPreset.CORE_SINGBOX to stringResource(Res.string.subs_preset_core_singbox),
                            ),
                            selected = preset.core,
                            onSelect = { preset = preset.copy(core = it) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val changed = preset != target.preset
                    repository.editSubscription(target.id, newName, newUa, newUrl, preset)
                    onDismiss()
                    if (changed) {
                        scope.launch { repository.updateSubscription(target.id) }
                    }
                }) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { onDismiss() }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
}

@Composable
private fun PresetField(label: StringResource, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UserAgentPicker(value: String, onChange: (String) -> Unit) {
    val default = ConfigRepository.DEFAULT_UA
    val alt = ConfigRepository.ALT_UA
    val isDefault = value.isBlank() || value == default
    val isAlt = value == alt
    var custom by remember(value) { mutableStateOf(!isDefault && !isAlt) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.subs_ua_preset),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = isDefault && !custom,
                onClick = { custom = false; onChange("") },
                label = { Text(stringResource(Res.string.subs_ua_v2rayng), maxLines = 1) },
            )
            FilterChip(
                selected = isAlt && !custom,
                onClick = { custom = false; onChange(alt) },
                label = { Text(stringResource(Res.string.subs_ua_zedsecure), maxLines = 1) },
            )
            FilterChip(
                selected = custom,
                onClick = { custom = true },
                label = { Text(stringResource(Res.string.subs_ua_custom), maxLines = 1) },
            )
        }
        if (custom) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(stringResource(Res.string.subs_user_agent)) },
                placeholder = { Text(default) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(Res.string.subs_ua_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

