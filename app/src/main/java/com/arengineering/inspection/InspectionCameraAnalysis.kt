package com.arengineering.inspection

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.arengineering.inspection.cv.ArCoreCvBridge
import com.arengineering.inspection.cv.ArCoreEquipmentCodeBridge
import com.arengineering.inspection.cv.Box2
import com.arengineering.inspection.cv.CapturedCvResult
import com.arengineering.inspection.cv.CodeScanRegion
import com.arengineering.inspection.cv.CvMode
import com.arengineering.inspection.cv.CvStatus
import com.arengineering.inspection.cv.EquipmentLookupRequest
import com.arengineering.inspection.cv.OrtPumpEngine
import com.arengineering.inspection.cv.OverlayPolicy
import com.arengineering.inspection.cv.Point2
import com.arengineering.inspection.cv.ZoneId
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.roundToInt

internal sealed interface CameraAnalysisTarget {
    data object ScanCode : CameraAnalysisTarget
    data class FindPump(val equipmentId: String) : CameraAnalysisTarget
    data class Corrosion(val stepId: String) : CameraAnalysisTarget
}

internal fun InspectionUiState.cameraAnalysisTarget(): CameraAnalysisTarget? {
    if (isDemo || account == null) return null
    return when (val current = stage) {
        is InspectionStage.ScanQr -> CameraAnalysisTarget.ScanCode.takeIf { current.error == null && !current.lookingUp }
        InspectionStage.FindPump -> equipment?.equipment?.id?.let(CameraAnalysisTarget::FindPump)
        is InspectionStage.InspectNode -> {
            CameraAnalysisTarget.Corrosion(current.step.id).takeIf { current.scanCorrosion && current.result == null }
        }
        else -> null
    }
}

internal data class CameraAnalysisUpdate(
    val target: CameraAnalysisTarget? = null,
    val status: CvStatus? = null,
    val confidence: Float? = null,
    val overlay: InspectionOverlay = InspectionOverlay(),
)

/** One owner per GLSurfaceView. The camera image is acquired only by the active bridge. */
internal class InspectionCameraAnalysis(
    assets: AssetManager,
    private val onCode: (EquipmentLookupRequest) -> Unit,
    private val frameLabel: String,
) : AutoCloseable {
    private val scanner = ArCoreEquipmentCodeBridge()
    private var engine: OrtPumpEngine? = null // Owned by the CV worker.
    private val cv = ArCoreCvBridge(
        analyze = { request ->
            val running = engine ?: OrtPumpEngine(modelLoader = { asset -> assets.open(asset).use { it.readBytes() } })
                .also { engine = it }
            running.analyze(request)
        },
        closeAnalyzer = { engine?.close(); engine = null },
        // A detection may still inform the panel on slower phones. Spatial display has its own 250 ms limit below.
        maxResultAgeNs = 1_500_000_000L,
    )
    private data class Selection(val target: CameraAnalysisTarget?, val epoch: Long)
    @Volatile private var selection = Selection(null, 0)
    private var activeEpoch = -1L
    private var session: Session? = null
    @Volatile private var sessionId = ""
    private var reference: Anchor? = null
    private var lastGeometry = -1L
    private var qrRotation: Int? = null
    private val fullImage = CodeScanRegion(0.0, 0.0, 1.0, 1.0)
    private val update = MutableStateFlow(CameraAnalysisUpdate())
    val state = update.asStateFlow()

    fun isCurrentCode(code: EquipmentLookupRequest): Boolean {
        val current = selection
        return current.target == CameraAnalysisTarget.ScanCode && code.frame.sessionId == sessionId &&
            code.frame.inspectionEpoch == current.epoch
    }

    // Called on the main thread, including pause after GLSurfaceView has stopped.
    fun select(target: CameraAnalysisTarget?, restart: Boolean = false) {
        if (target == selection.target && !restart) return
        selection = Selection(target, selection.epoch + 1)
        update.value = CameraAnalysisUpdate(target)
    }

    fun onFrame(frame: Frame, currentSession: Session, geometry: Long) {
        val selected = selection
        val newSession = session !== currentSession
        if (newSession) {
            session = currentSession
            sessionId = UUID.randomUUID().toString()
            reference = null
        }
        if (newSession || activeEpoch != selected.epoch || lastGeometry != geometry) {
            activeEpoch = selected.epoch
            lastGeometry = geometry
            reference?.detach()
            reference = null
            cv.invalidate()
            scanner.invalidate()
            qrRotation = null
            if (selected.target == CameraAnalysisTarget.ScanCode) scanner.beginScan(sessionId, activeEpoch)
        }
        when (val target = selected.target) {
            null -> Unit
            CameraAnalysisTarget.ScanCode -> {
                val orientation = qrRotation ?: imageRotation(frame)?.also { qrRotation = it } ?: return
                scanner.onFrame(frame, sessionId, activeEpoch, orientation, fullImage)
                if (selection.epoch == activeEpoch) scanner.takeDecision(sessionId, activeEpoch)?.decision?.request?.let(onCode)
            }
            is CameraAnalysisTarget.FindPump -> detect(frame, currentSession, geometry, target, CvMode.OVERVIEW)
            is CameraAnalysisTarget.Corrosion -> detect(frame, currentSession, geometry, target, CvMode.DETAIL_RUST)
        }
    }

    private fun detect(frame: Frame, session: Session, geometry: Long, target: CameraAnalysisTarget, mode: CvMode) {
        if (reference == null && frame.camera.trackingState == TrackingState.TRACKING) {
            reference = runCatching { session.createAnchor(frame.camera.pose) }.getOrNull()
        }
        cv.onFrame(frame, session, geometry, activeEpoch, mode, reference)
        cv.takeFailure()?.let { Log.d("InspectionCV", it) }
        val metadata = cv.latestResult(frame)?.result
        val spatial = cv.latestOverlayResult(frame, OverlayPolicy.APPROXIMATE_STATIONARY_REFERENCE)
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.result.stamp.acquiredElapsedNs <= 250_000_000L }
        update.compareAndSet(
            update.value.takeIf { selection.epoch == activeEpoch } ?: return,
            CameraAnalysisUpdate(
                target, metadata?.status,
                metadata?.defects?.maxOfOrNull { it.confidence }?.toFloat(),
                spatial?.let(::project) ?: InspectionOverlay(),
            ),
        )
    }

    private fun project(capture: CapturedCvResult): InspectionOverlay {
        val result = capture.result
        fun point(raw: Point2): ViewportPoint? {
            val screen = result.geometry.imageToView.map(raw)
            return if (screen.x in 0.0..1.0 && screen.y in 0.0..1.0) {
                ViewportPoint(screen.x.toFloat(), screen.y.toFloat())
            } else null
        }
        fun bounds(box: Box2): List<ViewportPoint> {
            val corners = listOf(
                Point2(box.left, box.top), Point2(box.right, box.top),
                Point2(box.right, box.bottom), Point2(box.left, box.bottom),
            ).map(result.geometry.imageToView::map)
            val left = corners.minOf { it.x }.coerceIn(0.0, 1.0).toFloat()
            val right = corners.maxOf { it.x }.coerceIn(0.0, 1.0).toFloat()
            val top = corners.minOf { it.y }.coerceIn(0.0, 1.0).toFloat()
            val bottom = corners.maxOf { it.y }.coerceIn(0.0, 1.0).toFloat()
            if (left >= right || top >= bottom) return emptyList()
            return listOf(
                ViewportPoint(left, top), ViewportPoint(right, top),
                ViewportPoint(right, bottom), ViewportPoint(left, bottom),
            )
        }
        return InspectionOverlay(
            pumpContour = result.equipment.singleOrNull()?.let { bounds(it.bbox) }.orEmpty(),
            marks = result.zoneCandidates.filter { it.id == ZoneId.FRAME }.mapNotNull {
                point(it.point)?.let { screen -> ProjectedMark(screen, frameLabel) }
            },
            defectRegions = result.defects.map { bounds(it.bbox) }.filter { it.isNotEmpty() },
        )
    }

    private fun imageRotation(frame: Frame): Int? {
        val transformed = FloatArray(4)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS, floatArrayOf(0f, 0f, 10f, 0f),
            Coordinates2d.VIEW, transformed,
        )
        val dx = transformed[2] - transformed[0]
        val dy = transformed[3] - transformed[1]
        if (!dx.isFinite() || !dy.isFinite() || dx == 0f && dy == 0f) return null
        return ((atan2(dy, dx) * 2 / Math.PI).roundToInt() + 4) % 4 * 90
    }

    override fun close() {
        scanner.close()
        cv.close()
    }
}
