package com.arengineering.inspection

import kotlinx.coroutines.flow.StateFlow

interface InspectionUiController {
    val state: StateFlow<InspectionUiState>
    fun dispatch(action: InspectionAction)
}

data class InspectionUiState(
    val account: InspectorAccount? = null,
    val signingIn: Boolean = false,
    val signInError: String? = null,
    val isDemo: Boolean = false,
    val stage: InspectionStage = InspectionStage.ScanQr(),
    val equipment: EquipmentCard? = null,
    val findings: List<InspectionFinding> = emptyList(),
    val history: List<InspectionReport> = emptyList(),
    val overlay: InspectionOverlay = InspectionOverlay(),
    val serverAddress: String = "",
)

data class InspectorAccount(val fullName: String, val position: String, val login: String)

data class EquipmentRef(
    val id: String,
    val name: String,
    val serialNumber: String,
    val kind: String = "",
    val hasSerialNumber: Boolean = true,
)

data class InspectionField(val name: String, val value: String)

data class ComponentCard(val id: String, val name: String, val fields: List<InspectionField>)

enum class ReadingStatus { NORMAL, WARNING, ALARM, UNCLASSIFIED, MISSING }

data class PassportReading(
    val code: String,
    val label: String,
    val value: String?,
    val status: ReadingStatus,
    val limits: List<InspectionField>,
)

data class EquipmentCard(
    val equipment: EquipmentRef,
    val specifications: List<InspectionField> = emptyList(),
    val telemetry: List<InspectionField> = emptyList(),
    val components: List<ComponentCard> = emptyList(),
    val previousFindings: List<InspectionFinding> = emptyList(),
    val readings: List<PassportReading> = emptyList(),
    val sampleUpdatedAt: Long? = null,
    val replay: Boolean = false,
    val refreshFailed: Boolean = false,
    val alerts: List<InspectionFinding> = emptyList(),
    val operation: List<InspectionField> = emptyList(),
    val checks: List<InspectionStep> = emptyList(),
)

enum class FindingSource { SENSOR, REGULATION, VISUAL }

data class InspectionFinding(
    val id: String,
    val title: String,
    val reason: String,
    val recommendation: String,
    val source: FindingSource,
    val readings: List<InspectionField> = emptyList(),
    val visualObservation: String? = null,
    val confidence: Float? = null,
)

data class InspectionStep(
    val id: String,
    val component: String,
    val instruction: String,
    val visualModel: String? = null,
    val agentHint: String? = null,
)

data class Evidence(val id: String, val frameTimestampNanos: Long, val localUri: String)

enum class StepOutcome { CLEAR, REVIEW, POSSIBLE_DEFECT, CONFIRMED_DEFECT, INCONCLUSIVE, SKIPPED }

data class StepResult(
    val step: InspectionStep,
    val outcome: StepOutcome,
    val findings: List<InspectionFinding> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val observation: String? = null,
)

data class InspectionReport(
    val id: String,
    val equipment: EquipmentRef,
    val timestampMillis: Long,
    val results: List<StepResult>,
    val findings: List<InspectionFinding>,
    val telemetry: List<InspectionField> = emptyList(),
    val note: String = "",
)

sealed interface InspectionStage {
    data class ScanQr(val error: String? = null, val lookingUp: Boolean = false) : InspectionStage
    data class PickEquipment(
        val options: List<EquipmentRef> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    ) : InspectionStage
    data object EquipmentPreview : InspectionStage
    data object Analyzing : InspectionStage
    data class AnalysisReady(
        val missingData: Boolean = false,
        val error: String? = null,
        val checks: List<InspectionStep> = emptyList(),
        val selectedCheckIds: Set<String> = emptySet(),
    ) : InspectionStage
    data object FindPump : InspectionStage
    data class InspectNode(
        val step: InspectionStep,
        val number: Int,
        val total: Int,
        val guidance: String? = null,
        val result: StepResult? = null,
        val corrosionConfidence: Float? = null,
        val agentHintEnabled: Boolean = false,
        val visionObservation: String? = null,
    ) : InspectionStage {
        val scanCorrosion: Boolean get() = step.visualModel == "corrosion"
        val agentHint: String? get() = step.agentHint
    }
    data class Summary(
        val report: InspectionReport,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    ) : InspectionStage
}

data class ViewportPoint(val x: Float, val y: Float)

data class ProjectedMark(
    val point: ViewportPoint,
    val label: String,
    val warning: Boolean = false,
)

data class ProjectedMask(val rings: List<List<ViewportPoint>>)

data class InspectionOverlay(
    val pumpMasks: List<ProjectedMask> = emptyList(),
    val marks: List<ProjectedMark> = emptyList(),
    val defectMasks: List<ProjectedMask> = emptyList(),
)

sealed interface InspectionAction {
    data class SignIn(val login: String, val password: String, val serverAddress: String = "") : InspectionAction
    data object SignOut : InspectionAction
    data object ScanAgain : InspectionAction
    data object OpenEquipmentPicker : InspectionAction
    data class SelectEquipment(val id: String) : InspectionAction
    data object StartInspection : InspectionAction
    data class SelectChecks(val ids: Set<String>) : InspectionAction
    data class SetAgentHint(val enabled: Boolean) : InspectionAction
    data class AcceptVisionObservation(val stepId: String, val observation: String) : InspectionAction
    data object ContinueInspection : InspectionAction
    data object ConfirmPump : InspectionAction
    data class SkipNode(val stepId: String) : InspectionAction
    data class NextNode(val stepId: String) : InspectionAction
    data class RecordNode(val stepId: String, val outcome: StepOutcome) : InspectionAction
    data class SaveReport(val reportId: String, val note: String) : InspectionAction
    data object NewInspection : InspectionAction

    // Standalone UI demo actions.
    data object DemoReadQr : InspectionAction
    data object DemoFindPump : InspectionAction
    data class DemoReadNode(val stepId: String) : InspectionAction
}
