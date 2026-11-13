package com.arengineering.inspection

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.arengineering.inspection.data.EquipmentReading
import com.arengineering.inspection.data.TelemetryDao
import com.arengineering.inspection.data.TelemetryDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException
import java.text.DateFormat
import java.util.Date

@Composable
internal fun TelemetryTerminal(telemetry: TelemetryDao, server: TerminalServer, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loginEmail by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var showLocal by remember { mutableStateOf(false) }
    var watchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun respond(request: suspend () -> TerminalResponse): Boolean {
        return try {
            val response = request()
            reply = listOf("HTTP ${response.status}", response.formattedBody())
                .filter(String::isNotBlank).joinToString("\n")
            message = if (response.status == 401) R.string.telemetry_server_unauthorized else null
            response.successful
        } catch (_: IOException) {
            reply = ""
            message = R.string.telemetry_server_unreachable
            false
        } catch (_: JSONException) {
            reply = ""
            message = R.string.telemetry_server_invalid_response
            false
        }
    }

    fun runRequest(request: suspend () -> TerminalResponse) {
        waiting = true
        scope.launch {
            try {
                respond(request)
            } finally {
                waiting = false
            }
        }
    }

    fun submitCommand() {
        watchJob?.cancel()
        watchJob = null
        showLocal = false
        reply = ""
        message = null
        val email = loginEmail
        if (email != null) {
            val enteredPassword = password
            password = ""
            loginEmail = null
            runRequest { server.login(email, enteredPassword) }
            return
        }

        val parts = input.trim().split(Regex("\\s+"))
        input = ""
        val name = parts.first().lowercase()
        val args = parts.drop(1)
        command = if (name == "login") name else parts.joinToString(" ")
        val id = args.firstOrNull()?.toLongOrNull()?.takeIf { it > 0 }
        when {
            name == "help" -> message = R.string.telemetry_terminal_hint
            name == "local" -> {
                server.disconnect()
                showLocal = true
            }
            name == "pasport" && args.isEmpty() && server.address == null -> showLocal = true
            name == "server" -> {
                if (args.size == 1 && server.connect(args.single())) {
                    runRequest { server.health() }
                } else {
                    message = R.string.telemetry_server_address_hint
                }
            }
            name !in listOf("health", "login", "logout", "pasport", "watch", "checks", "savecheck", "report") ->
                message = R.string.telemetry_unknown_command
            server.address == null -> message = R.string.telemetry_server_address_hint
            name == "health" -> runRequest { server.health() }
            name == "login" -> {
                if (args.size == 1 && '@' in args.single()) {
                    loginEmail = args.single()
                } else {
                    message = R.string.telemetry_server_login_hint
                }
            }
            name == "logout" -> runRequest { server.logout() }
            name == "pasport" && (args.isEmpty() || args.size == 1 && id != null) ->
                runRequest { server.passport(id ?: 1L, checksOnly = false) }
            name == "watch" && (args.isEmpty() || args.size == 1 && id != null) -> {
                watchJob = scope.launch {
                    while (respond { server.passport(id ?: 1L, checksOnly = false) }) {
                        delay(1_000)
                    }
                }
            }
            name == "checks" && args.size == 1 && id != null ->
                runRequest { server.passport(id, checksOnly = true) }
            name == "report" && args.size == 1 && id != null -> runRequest { server.report(id) }
            name == "savecheck" -> {
                val checkId = args.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
                if (args.size == 3 && id != null && checkId != null) {
                    runRequest { server.saveCheck(id, checkId, args[2]) }
                } else {
                    message = R.string.telemetry_server_check_hint
                }
            }
            else -> message = R.string.telemetry_unknown_command
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val submit: () -> Unit = {
            submitCommand()
            focusManager.clearFocus()
            keyboard?.hide()
        }
        val enteringPassword = loginEmail != null
        val canSubmit = !waiting && if (enteringPassword) password.isNotEmpty() else input.isNotBlank()
        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF65F3EB))) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.telemetry_terminal_title),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.telemetry_terminal_close)) }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (command.isNotEmpty()) {
                            Text("> $command", fontFamily = FontFamily.Monospace)
                        }
                        if (command.isEmpty()) Text(stringResource(R.string.telemetry_terminal_hint))
                        if (showLocal) LivePassport(telemetry)
                        if (waiting || watchJob != null && reply.isEmpty() && message == null) {
                            Text(stringResource(R.string.telemetry_server_waiting))
                        }
                        message?.let { Text(stringResource(it)) }
                        if (reply.isNotEmpty()) Text(reply, fontFamily = FontFamily.Monospace)
                    }
                    OutlinedTextField(
                        value = if (enteringPassword) password else input,
                        onValueChange = { if (enteringPassword) password = it else input = it },
                        label = {
                            Text(stringResource(
                                if (enteringPassword) R.string.telemetry_server_password else R.string.telemetry_command,
                            ))
                        },
                        visualTransformation = if (enteringPassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        enabled = !waiting,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (enteringPassword) KeyboardType.Password else KeyboardType.Text,
                            imeAction = ImeAction.Done,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                    )
                    Button(onClick = submit, enabled = canSubmit, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.telemetry_run_command))
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePassport(telemetry: TelemetryDao) {
    val equipmentId = TelemetryDatabase.DEMO_EQUIPMENT_ID
    val reading by remember(telemetry) { telemetry.observeCurrent(equipmentId) }.collectAsState(initial = null)
    Text(
        stringResource(R.string.telemetry_passport, equipmentId),
        fontFamily = FontFamily.Monospace,
    )
    val sample = reading
    if (sample == null) {
        Text(stringResource(R.string.telemetry_waiting))
    } else {
        PassportReadings(sample)
    }
}

@Composable
private fun PassportReadings(sample: EquipmentReading) {
    val vibration = sample.vibration
    val replayTime = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(sample.updatedAtMillis))
    Text(
        stringResource(R.string.telemetry_sample_time, vibration.measuredAt.replace('T', ' '), replayTime),
        fontFamily = FontFamily.Monospace,
    )
    Text(
        stringResource(
            R.string.telemetry_vibration,
            vibration.speedRpm, vibration.overallRmsMmPerSecond, vibration.amplitude1xMmPerSecond,
            vibration.amplitude2xMmPerSecond, vibration.highFrequencyNoiseG, vibration.sourceDiagnosis,
        ),
        fontFamily = FontFamily.Monospace,
    )
    Text(
        stringResource(
            R.string.telemetry_temperature,
            sample.pumpBearingCelsius, sample.motorWindingCelsius, sample.temperatureDiagnosis,
        ),
        fontFamily = FontFamily.Monospace,
    )
}
