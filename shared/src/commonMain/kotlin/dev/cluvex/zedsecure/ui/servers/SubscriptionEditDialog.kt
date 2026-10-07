package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.data.config.ConfigRepository
import dev.cluvex.zedsecure.domain.config.Subscription
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
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
        AlertDialog(
            onDismissRequest = { onDismiss() },
            title = { Text(stringResource(Res.string.subs_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.editSubscription(target.id, newName, newUa, newUrl)
                    onDismiss()
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

