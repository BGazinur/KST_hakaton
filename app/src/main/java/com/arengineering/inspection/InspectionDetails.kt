package com.arengineering.inspection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

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
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(color = Night, shape = PanelShape) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(section.label), style = MaterialTheme.typography.headlineSmall, color = SkyBlue)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (section) {
                        InspectionSection.EQUIPMENT -> EquipmentDetails(state.equipment)
                        InspectionSection.FINDINGS -> FindingList(state.findings)
                        InspectionSection.HISTORY -> HistoryDetails(state.history)
                        InspectionSection.ACCOUNT -> AccountContent(state, onAction)
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
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
    var password by remember { mutableStateOf("") }
    val signIn = {
        if (!state.signingIn && login.isNotBlank() && password.isNotBlank()) {
            onAction(InspectionAction.SignIn(login, password))
        }
    }
    Text(
        stringResource(R.string.sign_in_title), color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineMedium,
    )
    if (state.isDemo) PanelText(stringResource(R.string.demo_sign_in_hint))
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
    PanelText(stringResource(R.string.serial_number_value, card.equipment.serialNumber))
    if (card.specifications.isNotEmpty()) {
        PanelTitle(stringResource(R.string.equipment_specs))
        FieldList(card.specifications)
    }
    if (card.telemetry.isNotEmpty()) {
        PanelTitle(stringResource(R.string.equipment_telemetry))
        FieldList(card.telemetry)
    }
    if (card.components.isNotEmpty()) {
        PanelTitle(stringResource(R.string.equipment_components))
        card.components.forEach { component ->
            ExpandableRow(component.id, component.name) {
                if (component.fields.isEmpty()) PanelText(stringResource(R.string.no_component_details))
                FieldList(component.fields)
            }
        }
    }
    PanelTitle(stringResource(R.string.previous_findings))
    FindingList(card.previousFindings)
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
private fun HistoryDetails(reports: List<InspectionReport>) {
    if (reports.isEmpty()) PanelText(stringResource(R.string.no_inspection_history))
    reports.forEach { report ->
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(report.timestampMillis))
        ExpandableRow(report.id, date) {
            PanelTitle(report.equipment.name)
            if (report.results.isNotEmpty()) {
                report.results.forEach { result ->
                    PanelText(stringResource(R.string.field_value, result.step.component, stringResource(result.outcome.label)))
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
private fun ExpandableRow(id: String, label: String, details: @Composable () -> Unit) {
    key(id) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().hudPanel().padding(horizontal = 8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(if (expanded) R.string.expanded_row else R.string.collapsed_row, label),
                    Modifier.fillMaxWidth(),
                )
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
