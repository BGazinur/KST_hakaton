package com.arengineering.inspection

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal class DemoInspectionController(
    context: Context,
    private val scope: CoroutineScope,
) : InspectionUiController {
    private val preferences = context.getSharedPreferences("inspection_ui_demo", Context.MODE_PRIVATE)
    private val strings = context.applicationContext.resources
    private fun text(id: Int) = strings.getString(id)

    private val profile = InspectorAccount(
        text(R.string.demo_name), text(R.string.demo_position), "engineer@example.test",
    )
    private val pump = EquipmentRef("demo-pump", text(R.string.demo_pump), "KST-042")
    private val temperature = InspectionField(text(R.string.demo_temperature), text(R.string.demo_temperature_value))
    private val sensorFinding = InspectionFinding(
        "demo-sensor", text(R.string.demo_sensor_title), text(R.string.demo_sensor_reason),
        text(R.string.demo_sensor_advice), FindingSource.SENSOR, listOf(temperature),
    )
    private val visualFinding = InspectionFinding(
        "demo-visual", text(R.string.demo_visual_title), text(R.string.demo_visual_reason),
        text(R.string.demo_visual_advice), FindingSource.VISUAL, visualObservation = text(R.string.demo_visual_observation),
    )
    private val equipment = EquipmentCard(
        pump,
        specifications = listOf(InspectionField(text(R.string.equipment_model), "КСТ 65–40")),
        telemetry = listOf(temperature),
        components = listOf(
            ComponentCard(
                "seal", text(R.string.demo_seal),
                listOf(
                    InspectionField(text(R.string.demo_norm), text(R.string.demo_norm_value)),
                    InspectionField(text(R.string.demo_remaining), text(R.string.demo_remaining_value)),
                ),
            ),
            ComponentCard("frame", text(R.string.demo_frame), emptyList()),
        ),
        previousFindings = listOf(sensorFinding),
    )
    private val steps = listOf(
        InspectionStep("seal", text(R.string.demo_seal), text(R.string.demo_seal_instruction)),
        InspectionStep("frame", text(R.string.demo_frame), text(R.string.demo_frame_instruction)),
    )
    private val reports = mutableListOf(
        InspectionReport("demo-previous", pump, 1_763_035_200_000L, emptyList(), listOf(sensorFinding), listOf(temperature)),
    )
    private val results = mutableListOf<StepResult>()
    private var analysis: Job? = null
    private val mutableState = MutableStateFlow(
        InspectionUiState(
            account = if (preferences.getBoolean("signed_in", false)) {
                profile.copy(login = preferences.getString("login", profile.login) ?: profile.login)
            } else null,
            isDemo = true,
        ),
    )
    override val state = mutableState.asStateFlow()

    override fun dispatch(action: InspectionAction) {
        val current = state.value
        val stage = current.stage
        if (current.account == null && action !is InspectionAction.SignIn) return

        when (action) {
            is InspectionAction.SignIn -> if (current.account == null) {
                if (action.login.isBlank() || action.password.isBlank()) {
                    mutableState.value = current.copy(signInError = text(R.string.sign_in_missing))
                    return
                }
                val login = action.login.trim()
                preferences.edit {
                    putBoolean("signed_in", true)
                    putString("login", login)
                }
                mutableState.value = current.copy(account = profile.copy(login = login), signInError = null)
            }
            InspectionAction.SignOut -> {
                analysis?.cancel()
                results.clear()
                preferences.edit { clear() }
                mutableState.value = InspectionUiState(isDemo = true)
            }
            InspectionAction.ScanAgain -> if (stage is InspectionStage.PickEquipment || stage is InspectionStage.ScanQr ||
                stage is InspectionStage.EquipmentPreview
            ) {
                mutableState.value = current.copy(stage = InspectionStage.ScanQr(), equipment = null, history = emptyList())
            }
            InspectionAction.OpenEquipmentPicker -> if (stage is InspectionStage.ScanQr) {
                mutableState.value = current.copy(stage = InspectionStage.PickEquipment(listOf(pump)))
            }
            is InspectionAction.SelectEquipment -> if (stage is InspectionStage.PickEquipment && action.id == pump.id) {
                showEquipment(current)
            }
            InspectionAction.DemoReadQr -> if (stage is InspectionStage.ScanQr) showEquipment(current)
            InspectionAction.StartInspection -> if (stage is InspectionStage.EquipmentPreview) {
                results.clear()
                mutableState.value = current.copy(stage = InspectionStage.Analyzing)
                analysis = scope.launch {
                    delay(650)
                    mutableState.value = state.value.copy(
                        stage = InspectionStage.AnalysisReady(), findings = listOf(sensorFinding),
                    )
                }
            }
            InspectionAction.ContinueInspection -> if (stage is InspectionStage.AnalysisReady) {
                mutableState.value = current.copy(stage = InspectionStage.FindPump)
            }
            InspectionAction.DemoFindPump -> if (stage is InspectionStage.FindPump) {
                mutableState.value = current.copy(stage = InspectionStage.InspectNode(steps.first(), 1, steps.size))
            }
            is InspectionAction.DemoReadNode -> if (stage is InspectionStage.InspectNode &&
                stage.result == null && stage.step.id == action.stepId
            ) {
                val findings = if (stage.number == 1) listOf(visualFinding) else emptyList()
                val outcome = if (findings.isEmpty()) StepOutcome.CLEAR else StepOutcome.POSSIBLE_DEFECT
                mutableState.value = current.copy(
                    stage = stage.copy(result = StepResult(stage.step, outcome, findings)),
                    findings = current.findings + findings,
                )
            }
            is InspectionAction.RecordNode -> Unit
            is InspectionAction.SkipNode -> if (stage is InspectionStage.InspectNode &&
                stage.result == null && stage.step.id == action.stepId
            ) {
                advance(current, stage, StepResult(stage.step, StepOutcome.SKIPPED))
            }
            is InspectionAction.NextNode -> if (stage is InspectionStage.InspectNode && stage.step.id == action.stepId) {
                stage.result?.let { advance(current, stage, it) }
            }
            is InspectionAction.SaveReport -> if (stage is InspectionStage.Summary && !stage.saved &&
                !stage.saving && stage.report.id == action.reportId
            ) {
                val report = stage.report.copy(note = action.note.trim())
                reports.add(0, report)
                mutableState.value = current.copy(stage = stage.copy(report = report, saved = true), history = reports.toList())
            }
            InspectionAction.NewInspection -> if (stage is InspectionStage.Summary && stage.saved) {
                mutableState.value = InspectionUiState(account = current.account, isDemo = true)
            }
        }
    }

    private fun showEquipment(current: InspectionUiState) {
        mutableState.value = current.copy(
            stage = InspectionStage.EquipmentPreview, equipment = equipment, history = reports.toList(),
        )
    }

    private fun advance(current: InspectionUiState, stage: InspectionStage.InspectNode, result: StepResult) {
        results.add(result)
        val next = steps.getOrNull(stage.number)
        mutableState.value = current.copy(
            stage = if (next != null) {
                InspectionStage.InspectNode(next, stage.number + 1, steps.size)
            } else {
                InspectionStage.Summary(
                    InspectionReport(
                        UUID.randomUUID().toString(), pump, System.currentTimeMillis(),
                        results.toList(), current.findings, equipment.telemetry,
                    ),
                )
            },
            overlay = InspectionOverlay(),
        )
    }
}
