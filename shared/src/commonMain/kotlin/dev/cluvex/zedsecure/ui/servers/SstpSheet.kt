@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.config.SstpProfile
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Manual SSTP entry: server, port and the PAP/MS-CHAPv2 credentials. The
 * servers accept a single credential pair per session, so nothing else is
 * configurable here.
 */
@Composable
fun SstpSheet(
    initial: SstpProfile? = null,
    initialName: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, SstpProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var server by remember { mutableStateOf(initial?.server ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 443).toString()) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }

    val portValue = port.trim().toIntOrNull()
    val valid = server.isNotBlank() &&
        portValue != null && portValue in 1..65535 &&
        username.isNotBlank() &&
        (password.isNotBlank() || initial?.password?.isNotBlank() == true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(Res.string.sstp_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.subs_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(Res.string.sstp_server)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = port,
                onValueChange = { input -> port = input.filter { it.isDigit() } },
                label = { Text(stringResource(Res.string.sstp_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(Res.string.sstp_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(Res.string.sstp_pass)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = valid,
                onClick = {
                    val settings = SstpProfile(
                        server = server.trim(),
                        port = portValue ?: 443,
                        username = username.trim(),
                        password = password.ifBlank { initial?.password ?: "" },
                    )
                    onSave(name.trim().ifBlank { server.trim() }, settings)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.action_save)) }

            Spacer(Modifier.height(24.dp))
        }
    }
}
