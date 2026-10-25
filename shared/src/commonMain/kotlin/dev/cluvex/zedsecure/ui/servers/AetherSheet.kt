@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.domain.config.AetherProfile

/**
 * One-tap Aether sheet: no manual fields, no advanced section in the main
 * flow. A single button runs the automatic probe (supplied by the app layer
 * through [onAutoConnect], which runs AetherAutoConfig), and on success the
 * verified profile is handed to [onSave] — the caller saves it, makes it
 * active and starts it, exactly like the other sheets' onSave handlers.
 *
 * Closing the sheet cancels the running auto-config job; the auto-config
 * itself always kills its probe processes before returning, so no probe
 * process can outlive a cancelled run.
 */
@Composable
fun AetherSheet(
    onDismiss: () -> Unit,
    onAutoConnect: (suspend (onProgress: (String) -> Unit) -> Result<AetherProfile>)?,
    onSave: (name: String, AetherProfile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var autoJob by remember { mutableStateOf<Job?>(null) }

    fun dismiss() {
        autoJob?.cancel()
        autoJob = null
        onDismiss()
    }

    // Belt and braces next to dismiss(): if the sheet leaves composition any
    // other way (e.g. the host screen goes away), the probe job still dies.
    DisposableEffect(Unit) {
        onDispose {
            autoJob?.cancel()
            autoJob = null
        }
    }

    val fallbackError = stringResource(Res.string.aether_error_fallback)
    val idleHint = stringResource(Res.string.aether_sheet_hint)
    val unavailable = stringResource(Res.string.aether_unavailable)

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
                stringResource(Res.string.aether_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(
                if (onAutoConnect == null) unavailable else idleHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (error != null) {
                Text(
                    error ?: fallbackError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    val auto = onAutoConnect ?: return@Button
                    if (running) return@Button
                    error = null
                    progress = ""
                    running = true
                    autoJob = scope.launch {
                        try {
                            val result = auto { text -> progress = text }
                            running = false
                            autoJob = null
                            result.fold(
                                onSuccess = { profile -> onSave(profile.displayName, profile) },
                                onFailure = { e ->
                                    error = e.message?.takeIf { it.isNotBlank() } ?: fallbackError
                                },
                            )
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // The job itself died (e.g. the sheet was
                            // dismissed mid-run): nothing to show, the
                            // sheet is already going away.
                            running = false
                            autoJob = null
                        } catch (e: Exception) {
                            running = false
                            autoJob = null
                            error = e.message?.takeIf { it.isNotBlank() } ?: fallbackError
                        }
                    }
                },
                enabled = !running && onAutoConnect != null,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text(
                    if (running && progress.isNotBlank()) progress
                    else stringResource(Res.string.aether_connect_button),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
