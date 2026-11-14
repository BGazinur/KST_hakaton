package com.arengineering.inspection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

internal enum class InspectionSection(val label: Int) {
    EQUIPMENT(R.string.equipment_card),
    FINDINGS(R.string.current_findings),
    HISTORY(R.string.inspection_history),
    ACCOUNT(R.string.account),
}

@Composable
internal fun InspectionDrawer(
    modifier: Modifier,
    onOpen: (InspectionSection) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier.widthIn(max = 320.dp).fillMaxWidth(0.84f).fillMaxHeight(),
        color = Night, shape = PanelShape,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.close))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (section in listOf(InspectionSection.EQUIPMENT, InspectionSection.FINDINGS, InspectionSection.HISTORY)) {
                    DrawerItem(section) { onOpen(section) }
                }
            }
            HorizontalDivider()
            DrawerItem(InspectionSection.ACCOUNT) { onOpen(InspectionSection.ACCOUNT) }
        }
    }
}

@Composable
private fun DrawerItem(section: InspectionSection, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(section.label), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun InspectionSectionDialog(
    section: InspectionSection,
    state: InspectionUiState,
    onAction: (InspectionAction) -> Unit,
    onExportPdf: (String) -> Unit,
    onClose: () -> Unit,
) {
    InspectionDialog(title = stringResource(section.label), onClose = onClose) {
        when (section) {
            InspectionSection.EQUIPMENT -> EquipmentDetails(state.equipment)
            InspectionSection.FINDINGS -> FindingList(
                if (state.isDemo) state.findings else state.equipment?.alerts.orEmpty() +
                    state.findings.filter { it.source == FindingSource.VISUAL },
            )
            InspectionSection.HISTORY -> HistoryDetails(state.history, onExportPdf.takeUnless { state.isDemo })
            InspectionSection.ACCOUNT -> AccountContent(state, onAction)
        }
    }
}

@Composable
internal fun InspectionDialog(
    title: String,
    onClose: () -> Unit,
    closeLabel: Int = R.string.close,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            color = Night,
            shape = PanelShape,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = SkyBlue)
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text(stringResource(closeLabel))
                }
            }
        }
    }
}

@Composable
internal fun AccountContent(state: InspectionUiState, onAction: (InspectionAction) -> Unit) {
    val account = state.account
    if (account != null) {
        FieldList(
            listOf(
                InspectionField(stringResource(R.string.full_name), account.fullName),
                InspectionField(stringResource(R.string.position), account.position),
                InspectionField(stringResource(R.string.login), account.login),
            ),
        )
        ActionButton(R.string.sign_out) { onAction(InspectionAction.SignOut) }
        return
    }
    var login by rememberSaveable { mutableStateOf("") }
    var serverAddress by rememberSaveable { mutableStateOf(state.serverAddress) }
    var password by remember { mutableStateOf("") }
    val signIn = {
        if (!state.signingIn && login.isNotBlank() && password.isNotBlank()) {
            onAction(InspectionAction.SignIn(login, password, serverAddress))
            password = ""
        }
    }
    Text(
        stringResource(R.string.sign_in_title), color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineMedium,
    )
    if (state.isDemo) PanelText(stringResource(R.string.demo_sign_in_hint))
    if (!state.isDemo) {
        OutlinedTextField(
            value = serverAddress, onValueChange = { serverAddress = it }, enabled = !state.signingIn,
            label = { Text(stringResource(R.string.inspection_server_address)) },
            placeholder = { Text("http://192.168.1.10:8000") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
    OutlinedTextField(
        value = login, onValueChange = { login = it }, enabled = !state.signingIn,
        label = { Text(stringResource(R.string.login)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = password, onValueChange = { password = it }, enabled = !state.signingIn,
        label = { Text(stringResource(R.string.password)) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { signIn() }),
    )
    state.signInError?.let { ErrorText(it) }
    if (state.signingIn) LoadingLabel(R.string.loading)
    ActionButton(R.string.sign_in, enabled = !state.signingIn && login.isNotBlank() && password.isNotBlank(), onClick = signIn)
}

@Composable
private fun EquipmentDetails(card: EquipmentCard?) {
    if (card == null) {
        PanelText(stringResource(R.string.no_selected_equipment))
        return
    }
    PanelTitle(card.equipment.name)
    EquipmentIdentifier(card.equipment)
    if (card.refreshFailed) {
        PanelText(stringResource(R.string.equipment_refresh_failed), Amber)
    }
    if (card.readings.isNotEmpty() || card.telemetry.isNotEmpty()) {
        PanelTitle(stringResource(R.string.equipment_live_readings))
        card.sampleUpdatedAt?.let {
            val time = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.forLanguageTag("ru-RU")).format(Date(it))
            PanelText(stringResource(if (card.replay) R.string.equipment_updated_replay else R.string.equipment_updated, time))
        }
        card.readings.groupBy { it.group }.forEach { (group, readings) ->
            Text(stringResource(group), color = SkyBlue, style = MaterialTheme.typography.titleSmall)
            Column(Modifier.fillMaxWidth().hudPanel()) {
                readings.forEachIndexed { index, reading ->
                    ReadingRow(reading)
                    if (index < readings.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                }
            }
        }
        FieldList(card.telemetry)
    } else {
        PanelText(stringResource(R.string.equipment_no_telemetry))
    }
    if (card.alerts.isNotEmpty()) {
        PanelTitle(stringResource(R.string.equipment_alerts))
        FindingList(card.alerts)
    }
    if (card.operation.isNotEmpty()) {
        ExpandableRow("operation", stringResource(R.string.equipment_operation)) { FieldList(card.operation) }
    }
    if (card.specifications.isNotEmpty()) {
        ExpandableRow("specifications", stringResource(R.string.equipment_specs)) { FieldList(card.specifications) }
    }
    if (card.components.isNotEmpty()) {
        ExpandableRow("components", stringResource(R.string.equipment_components)) {
            card.components.forEach { component ->
                ExpandableRow(component.id, component.name) {
                    if (component.fields.isEmpty()) PanelText(stringResource(R.string.no_component_details))
                    FieldList(component.fields)
                }
            }
        }
    }
    if (card.checks.isNotEmpty()) {
        ExpandableRow("inspection-checks", stringResource(R.string.equipment_checklist, card.checks.size)) {
            card.checks.forEach { step ->
                PanelText(stringResource(R.string.field_value, step.component, stringResource(step.inspectionMethod)))
            }
        }
    }
    if (card.previousFindings.isNotEmpty()) {
        PanelTitle(stringResource(R.string.previous_findings))
        FindingList(card.previousFindings)
    }
}

private val PassportReading.group: Int
    get() = when (code) {
        "overall_rms_mm_s", "amplitude_1x_mm_s", "amplitude_2x_mm_s", "hf_noise_g" -> R.string.equipment_vibration
        "pump_bearing_c", "motor_winding_c" -> R.string.equipment_temperature
        else -> R.string.equipment_other_readings
    }

@Composable
private fun ReadingRow(reading: PassportReading) {
    key(reading.code) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val hasLimits = reading.limits.isNotEmpty()
        val title = if (hasLimits) {
            stringResource(if (expanded) R.string.expanded_row else R.string.collapsed_row, reading.label)
        } else reading.label
        val readingValue = reading.value ?: stringResource(R.string.equipment_reading_missing)
        val status = readingStatus(reading.status)
        val contentColor = if (reading.status.hasAlert) Amber else Muted
        val gesture = if (hasLimits) Modifier.clickable(role = Role.Button) { expanded = !expanded } else Modifier
        Row(
            Modifier.fillMaxWidth().then(gesture).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(0.64f), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(0.36f), horizontalAlignment = Alignment.End) {
                Text(
                    readingValue, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = contentColor, textAlign = TextAlign.End)
                }
            }
        }
        if (hasLimits && expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { FieldList(reading.limits) }
        }
    }
}

@Composable
internal fun EquipmentIdentifier(equipment: EquipmentRef) {
    val label = if (equipment.hasSerialNumber) R.string.serial_number_value else R.string.inventory_number_value
    PanelText(stringResource(label, equipment.serialNumber))
}

internal val InspectionStep.inspectionMethod: Int
    get() = if (visualModel == "corrosion") R.string.inspection_with_agent else R.string.inspection_manual

private val ReadingStatus.hasAlert: Boolean
    get() = this == ReadingStatus.WARNING || this == ReadingStatus.ALARM

@Composable
private fun readingStatus(status: ReadingStatus): String? = when (status) {
    ReadingStatus.NORMAL -> stringResource(R.string.equipment_reading_normal)
    ReadingStatus.WARNING -> stringResource(R.string.equipment_reading_warning)
    ReadingStatus.ALARM -> stringResource(R.string.equipment_reading_alarm)
    ReadingStatus.UNCLASSIFIED -> stringResource(R.string.equipment_reading_no_limits)
    ReadingStatus.MISSING -> null
}

@Composable
private fun FindingList(findings: List<InspectionFinding>) {
    if (findings.isEmpty()) PanelText(stringResource(R.string.no_findings))
    findings.forEach { finding ->
        ExpandableRow(finding.id, finding.title) {
            val sourceLabel = when (finding.source) {
                FindingSource.SENSOR -> R.string.source_sensor
                FindingSource.REGULATION -> R.string.source_regulation
                FindingSource.VISUAL -> R.string.source_visual
            }
            PanelText(stringResource(sourceLabel), Amber)
            PanelText(finding.reason)
            FieldList(finding.readings)
            finding.visualObservation?.let { PanelText(it) }
            finding.confidence?.let {
                PanelText(stringResource(R.string.confidence_value, NumberFormat.getPercentInstance().format(it.toDouble())))
            }
            PanelTitle(stringResource(R.string.recommendation))
            PanelText(finding.recommendation)
        }
    }
}

@Composable
private fun HistoryDetails(reports: List<InspectionReport>, onExportPdf: ((String) -> Unit)?) {
    if (reports.isEmpty()) PanelText(stringResource(R.string.no_inspection_history))
    reports.forEach { report ->
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.timestampMillis))
        ExpandableRow(report.id, date) {
            PanelTitle(report.equipment.name)
            if (onExportPdf != null) {
                TextButton(onClick = { onExportPdf(report.id) }) { Text(stringResource(R.string.report_export_pdf)) }
            }
            if (report.results.isNotEmpty()) {
                report.results.forEach { result ->
                    PanelText(stringResource(R.string.field_value, result.step.component, stringResource(result.outcome.label)))
                    result.observation?.let { PanelText(it) }
                    if (result.evidence.isNotEmpty()) {
                        PanelText(stringResource(R.string.evidence_count, result.evidence.size))
                    }
                }
            }
            if (report.telemetry.isNotEmpty()) FieldList(report.telemetry)
            if (report.note.isNotBlank()) {
                PanelTitle(stringResource(R.string.report_note))
                PanelText(report.note)
            }
            PanelTitle(stringResource(R.string.current_findings))
            FindingList(report.findings)
        }
    }
}

@Composable
private fun ExpandableRow(
    id: String,
    label: String,
    details: @Composable () -> Unit,
) {
    key(id) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().hudPanel().padding(horizontal = 8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(if (expanded) R.string.expanded_row else R.string.collapsed_row, label), Modifier.fillMaxWidth())
            }
            if (expanded) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    details()
                }
            }
        }
    }
}

@Composable
private fun FieldList(fields: List<InspectionField>) {
    fields.forEach {
        PanelText(stringResource(R.string.field_value, it.name, it.value))
    }
}
