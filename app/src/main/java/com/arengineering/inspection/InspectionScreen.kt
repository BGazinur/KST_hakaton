package com.arengineering.inspection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private val LogoBlue = Color(0xFF125AA6)
internal val SkyBlue = Color(0xFF8BBFFF)
internal val Amber = Color(0xFFFFB56E)
internal val Night = Color(0xFF081524)
internal val Glass = Color(0xED122B48)
internal val Muted = Color(0xFFC3D4E9)
private val BackgroundBlue = Color(0xFF123660)
internal val PanelShape = RoundedCornerShape(18.dp)
private val Red = Color(0xFFFFB4AA)

@Composable
internal fun InspectionScreen(
    state: InspectionUiState,
    preview: ArPreview?,
    issue: StartupIssue?,
    onAction: (InspectionAction) -> Unit,
    onStartupAction: () -> Unit,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SkyBlue, onPrimary = Night, surface = Night, onSurface = Color(0xFFEAF3FF),
            onSurfaceVariant = Muted, outline = Color(0xFF6C8CAD),
            outlineVariant = Color(0xFF3E5C7F), error = Red,
        ),
    ) {
        if (state.account == null) {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BackgroundBlue, Night)))
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(24.dp).hudPanel().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.kst_energy_logo),
                        contentDescription = stringResource(R.string.kst_energy_logo_description),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .widthIn(max = 292.dp).fillMaxWidth().aspectRatio(2f),
                    )
                    AccountContent(state, onAction)
                }
            }
        } else {
            CameraHud(state, preview, issue, onAction, onStartupAction)
        }
    }
}

@Composable
private fun CameraHud(
    state: InspectionUiState,
    preview: ArPreview?,
    issue: StartupIssue?,
    onAction: (InspectionAction) -> Unit,
    onStartupAction: () -> Unit,
) {
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf<InspectionSection?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val hud = preview?.hud?.collectAsState()
    val status by remember(hud) { derivedStateOf { hud?.value?.status } }
    val hasAnchor by remember(hud) { derivedStateOf { hud?.value?.hasAnchor == true } }
    val stage = state.stage
    val manualMode = manual && state.isDemo && stage is InspectionStage.ScanQr && issue == null
    val sensorAlert = state.findings.any { it.source == FindingSource.SENSOR }
    val openSection: (InspectionSection) -> Unit = {
        drawerOpen = false
        section = it
    }

    BackHandler(drawerOpen || manual || stage is InspectionStage.PickEquipment) {
        when {
            drawerOpen -> drawerOpen = false
            manual -> {
                manual = false
                preview?.clearMarker()
            }
            else -> onAction(InspectionAction.ScanAgain)
        }
    }

    Box(Modifier.fillMaxSize().background(Night)) {
        if (preview != null) {
            AndroidView(factory = { preview.view }, modifier = Modifier.fillMaxSize())
        }
        if (issue != null) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BackgroundBlue, Night))))
        }
        InspectionGraphics(
            hud = hud.takeIf { issue == null },
            overlay = state.overlay,
            qrGuide = issue == null && stage is InspectionStage.ScanQr && !manualMode,
            manualPreview = preview.takeIf { manualMode },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Night.copy(alpha = 0.62f), 0.26f to Color.Transparent,
                    0.60f to Color.Transparent, 1f to Night.copy(alpha = 0.88f),
                ),
            ),
        )

        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val compact = maxHeight < 480.dp
            if (state.isDemo) {
                Text(
                    stringResource(R.string.demo_mode),
                    Modifier.align(Alignment.TopStart).padding(16.dp).hudPanel()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = SkyBlue,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (sensorAlert) {
                val alertLabel = stringResource(R.string.sensor_alert)
                Button(
                    onClick = { openSection(InspectionSection.FINDINGS) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp)
                        .semantics { contentDescription = alertLabel },
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9A2525), contentColor = Color.White),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("!", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            if (!drawerOpen) {
                MenuHandle(Modifier.align(Alignment.CenterEnd), onOpen = { drawerOpen = true })
            }
            Column(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 14.dp)
                    .heightIn(max = maxHeight * (if (compact) 0.76f else 0.54f))
                    .hudPanel().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (issue != null) {
                    PanelTitle(stringResource(issue.title))
                    PanelText(stringResource(issue.hint))
                    issue.action?.let { id -> OutlinedButton(onClick = onStartupAction) { Text(stringResource(id)) } }
                }
                if (manualMode) {
                    PanelTitle(stringResource(R.string.manual_marker))
                    PanelText(stringResource(trackingHint(status)))
                    if (hasAnchor) {
                        OutlinedButton(onClick = { preview?.clearMarker() }) { Text(stringResource(R.string.clear_marker)) }
                    }
                    TextButton(onClick = {
                        preview?.clearMarker()
                        manual = false
                    }) { Text(stringResource(R.string.back_to_inspection)) }
                } else {
                    StageContent(state, status, onAction, openSection)
                    if (state.isDemo && stage is InspectionStage.ScanQr && issue == null) {
                        TextButton(onClick = { manual = true }) { Text(stringResource(R.string.manual_marker)) }
                    }
                }
            }

            if (drawerOpen) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).clickable(
                    onClickLabel = stringResource(R.string.close), onClick = { drawerOpen = false },
                ))
                InspectionDrawer(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onOpen = openSection, onClose = { drawerOpen = false },
                )
            }
        }
    }
    section?.let {
        InspectionSectionDialog(it, state, onAction, onClose = { section = null })
    }
}

@Composable
private fun MenuHandle(modifier: Modifier, onOpen: () -> Unit) {
    val label = stringResource(R.string.open_panel)
    Box(
        modifier.size(width = 48.dp, height = 93.dp).systemGestureExclusion()
            .pointerInput(onOpen) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag < -20.dp.toPx()) onOpen() },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                }
            }.clickable(role = Role.Button, onClickLabel = label, onClick = onOpen)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Canvas(Modifier.size(20.dp, 93.dp).hudPanel()) {
            val x = center.x - 2.5.dp.toPx()
            val y = center.y
            val d = 5.dp.toPx()
            drawLine(SkyBlue, Offset(x + d, y - d), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
            drawLine(SkyBlue, Offset(x, y), Offset(x + d, y + d), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

internal fun Modifier.hudPanel() = background(Glass, PanelShape)
    .border(1.dp, SkyBlue.copy(alpha = 0.34f), PanelShape)

@Composable
internal fun PanelTitle(value: String) {
    Text(value, color = SkyBlue, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun PanelText(value: String, color: Color = Muted) {
    Text(value, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun ErrorText(value: String) {
    Text(value, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics {
        liveRegion = LiveRegionMode.Polite
    })
}

@Composable
private fun StageContent(
    state: InspectionUiState,
    status: HudStatus?,
    onAction: (InspectionAction) -> Unit,
    open: (InspectionSection) -> Unit,
) {
    when (val stage = state.stage) {
        is InspectionStage.ScanQr -> {
            PanelTitle(stringResource(R.string.scan_qr))
            stage.error?.let {
                ErrorText(it)
                OutlinedButton(onClick = { onAction(InspectionAction.ScanAgain) }) { Text(stringResource(R.string.retry)) }
            }
            if (state.isDemo) {
                ActionButton(R.string.demo_read_qr) { onAction(InspectionAction.DemoReadQr) }
            }
            TextButton(onClick = { onAction(InspectionAction.OpenEquipmentPicker) }) {
                Text(stringResource(R.string.select_manually))
            }
        }
        is InspectionStage.PickEquipment -> {
            PanelTitle(stringResource(R.string.equipment_picker))
            if (stage.loading) LoadingLabel(R.string.loading)
            stage.error?.let { ErrorText(it) }
            if (!stage.loading && stage.error == null && stage.options.isEmpty()) {
                PanelText(stringResource(R.string.no_equipment_options))
            }
            stage.options.forEach { option ->
                OutlinedButton(onClick = { onAction(InspectionAction.SelectEquipment(option.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.equipment_option, option.name, option.serialNumber))
                }
            }
            TextButton(onClick = { onAction(InspectionAction.ScanAgain) }) { Text(stringResource(R.string.back_to_qr)) }
        }
        InspectionStage.EquipmentPreview -> {
            state.equipment?.equipment?.let {
                PanelTitle(it.name)
                PanelText(stringResource(R.string.serial_number_value, it.serialNumber))
                OutlinedButton(onClick = { open(InspectionSection.EQUIPMENT) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_equipment_card))
                }
                ActionButton(R.string.start_inspection) { onAction(InspectionAction.StartInspection) }
            }
        }
        InspectionStage.Analyzing -> LoadingLabel(R.string.analyzing)
        is InspectionStage.AnalysisReady -> {
            PanelTitle(stringResource(R.string.analysis_done))
            if (stage.missingData) PanelText(stringResource(R.string.missing_telemetry), Amber)
            stage.error?.let { ErrorText(it) }
            ActionButton(R.string.continue_inspection) { onAction(InspectionAction.ContinueInspection) }
        }
        InspectionStage.FindPump -> {
            PanelTitle(stringResource(R.string.find_pump))
            if (state.isDemo) ActionButton(R.string.demo_find_pump) { onAction(InspectionAction.DemoFindPump) }
        }
        is InspectionStage.InspectNode -> NodePanel(stage, status, state.isDemo, onAction)
        is InspectionStage.Summary -> ReportPanel(stage, onAction)
    }
}

@Composable
private fun NodePanel(
    stage: InspectionStage.InspectNode,
    status: HudStatus?,
    demo: Boolean,
    onAction: (InspectionAction) -> Unit,
) {
    Text(
        stringResource(R.string.node_progress, stage.number, stage.total),
        color = SkyBlue, style = MaterialTheme.typography.labelMedium,
    )
    PanelTitle(stage.step.component)
    val result = stage.result
    if (result == null) {
        PanelText(stage.step.instruction)
        if (status != null && !status.canProject) {
            PanelText(stringResource(trackingHint(status)), Amber)
        } else {
            stage.guidance?.let { PanelText(it, Amber) }
        }
        if (demo) ActionButton(R.string.demo_read_node) { onAction(InspectionAction.DemoReadNode(stage.step.id)) }
        OutlinedButton(onClick = { onAction(InspectionAction.SkipNode(stage.step.id)) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.skip_node))
        }
    } else {
        PanelText(stringResource(result.outcome.label), if (result.outcome == StepOutcome.CLEAR) Muted else Amber)
        if (status != null && !status.canProject) PanelText(stringResource(trackingHint(status)), Amber)
        result.findings.forEach {
            PanelText(it.reason)
            PanelText(it.recommendation)
        }
        ActionButton(R.string.next_node) { onAction(InspectionAction.NextNode(stage.step.id)) }
    }
}

@Composable
private fun ReportPanel(stage: InspectionStage.Summary, onAction: (InspectionAction) -> Unit) {
    var note by rememberSaveable(stage.report.id) { mutableStateOf(stage.report.note) }
    PanelTitle(stringResource(R.string.inspection_summary))
    PanelText(
        stringResource(R.string.summary_counts, stage.report.results.size, stage.report.findings.size,
            stage.report.results.count { it.outcome == StepOutcome.SKIPPED }),
    )
    if (stage.saved) {
        Text(stringResource(R.string.report_sent), color = SkyBlue, modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
        })
        ActionButton(R.string.new_inspection) { onAction(InspectionAction.NewInspection) }
    } else {
        OutlinedTextField(
            value = note, onValueChange = { note = it }, enabled = !stage.saving, maxLines = 4,
            label = { Text(stringResource(R.string.report_note)) }, modifier = Modifier.fillMaxWidth(),
        )
        stage.error?.let { ErrorText(it) }
        if (stage.saving) LoadingLabel(R.string.saving_report)
        ActionButton(R.string.send_report, enabled = !stage.saving) {
            onAction(InspectionAction.SaveReport(stage.report.id, note))
        }
    }
}

@Composable
internal fun ActionButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LogoBlue, contentColor = Color.White),
    ) {
        Text(stringResource(label))
    }
}

@Composable
internal fun LoadingLabel(label: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        PanelText(stringResource(label))
    }
}

internal val StepOutcome.label: Int
    get() = when (this) {
        StepOutcome.CLEAR -> R.string.outcome_clear
        StepOutcome.REVIEW -> R.string.outcome_review
        StepOutcome.POSSIBLE_DEFECT -> R.string.outcome_defect
        StepOutcome.SKIPPED -> R.string.outcome_skipped
    }

private fun trackingHint(status: HudStatus?) = when (status) {
    HudStatus.READY -> R.string.tracking_hint_ready
    HudStatus.PLACED -> R.string.tracking_hint_anchored
    HudStatus.MISSED -> R.string.tracking_hint_missed
    HudStatus.PAUSED -> R.string.tracking_hint_paused
    HudStatus.LOST -> R.string.tracking_hint_lost
    HudStatus.TOO_DARK -> R.string.tracking_hint_dark
    HudStatus.TOO_FAST -> R.string.tracking_hint_motion
    HudStatus.LOW_DETAIL -> R.string.tracking_hint_texture
    HudStatus.CAMERA_BUSY -> R.string.camera_busy_hint
    HudStatus.SEARCHING, null -> R.string.tracking_hint_wait
}
