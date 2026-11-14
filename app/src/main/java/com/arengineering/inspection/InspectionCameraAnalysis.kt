package com.arengineering.inspection

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.arengineering.inspection.cv.ArCoreCvBridge
import com.arengineering.inspection.cv.ArCoreEquipmentCodeBridge
import com.arengineering.inspection.cv.BinaryMaskRle
import com.arengineering.inspection.cv.CapturedCvResult
import com.arengineering.inspection.cv.CodeScanRegion
import com.arengineering.inspection.cv.CvMode
import com.arengineering.inspection.cv.CvResult
import com.arengineering.inspection.cv.CvStatus
import com.arengineering.inspection.cv.EquipmentLookupRequest
import com.arengineering.inspection.cv.OrtPumpEngine
import com.arengineering.inspection.cv.OverlayPolicy
import com.arengineering.inspection.cv.Point2
import com.arengineering.inspection.cv.ZoneId
import com.arengineering.inspection.cv.VisualHint
import com.arengineering.inspection.cv.contours
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.roundToInt

internal sealed interface CameraAnalysisTarget {
    data object ScanCode : CameraAnalysisTarget
    data class FindPump(val equipmentId: String) : CameraAnalysisTarget
    data class Corrosion(val stepId: String) : CameraAnalysisTarget
    data class Hint(val stepId: String, val target: VisualHint) : CameraAnalysisTarget
    data class ManualStep(val stepId: String) : CameraAnalysisTarget
}

internal fun InspectionUiState.cameraAnalysisTarget(): CameraAnalysisTarget? {
    if (isDemo || account == null) return null
    return when (val current = stage) {
        is InspectionStage.ScanQr -> CameraAnalysisTarget.ScanCode.takeIf { current.error == null && !current.lookingUp }
        InspectionStage.EquipmentPreview, InspectionStage.Analyzing, is InspectionStage.AnalysisReady,
        InspectionStage.FindPump -> equipment?.equipment?.takeIf { it.kind == "centrifugal_pump" }
            ?.id?.let(CameraAnalysisTarget::FindPump)
        is InspectionStage.InspectNode -> {
            when {
                current.result != null -> null
                current.scanCorrosion -> CameraAnalysisTarget.Corrosion(current.step.id)
                current.agentHintEnabled -> VisualHint.fromWire(current.agentHint)?.let { CameraAnalysisTarget.Hint(current.step.id, it) }
                else -> CameraAnalysisTarget.ManualStep(current.step.id)
            }
        }
        else -> null
    }
}

internal data class CameraAnalysisUpdate(
    val target: CameraAnalysisTarget? = null,
    val status: CvStatus? = null,
    val confidence: Float? = null,
    val overlay: InspectionOverlay = InspectionOverlay(),
    val tracking: Boolean = false,
    val started: Boolean = false,
    val inferenceMs: Int? = null,
    val failed: Boolean = false,
    val alignmentPending: Boolean = false,
)

/** One owner per GLSurfaceView. The camera image is acquired only by the active bridge. */
internal class InspectionCameraAnalysis(
    assets: AssetManager,
    private val onCode: (EquipmentLookupRequest) -> Unit,
    private val frameLabel: String,
    private val corrosionLabel: String,
) : AutoCloseable {
    private val scanner = ArCoreEquipmentCodeBridge()
    private var engine: OrtPumpEngine? = null // Owned by the CV worker.
    private data class PreparedOverlay(val result: CvResult, val overlay: InspectionOverlay)
    private val projected = AtomicReference<PreparedOverlay?>(null)
    private data class ReviewRequest(val epoch: Long, val requestedAt: Long, val outcome: CompletableDeferred<ReviewPhoto?>)
    private val review = AtomicReference<ReviewRequest?>(null)
    private val cv = ArCoreCvBridge(
        analyze = { request ->
            val running = engine ?: OrtPumpEngine(modelLoader = { asset -> assets.open(asset).use { it.readBytes() } })
                .also { engine = it }
            running.analyze(request)
        },
        closeAnalyzer = { engine?.close(); engine = null },
        maxResultAgeNs = 1_500_000_000L,
        maxCaptureAgeNs = 5_000_000_000L,
        prepareResult = { result -> projected.set(PreparedOverlay(result, project(result))) },
        onAnalyzedFrame = { source, result ->
            review.get()?.takeIf { it.epoch == result.stamp.inspectionEpoch &&
                result.stamp.acquiredElapsedNs >= it.requestedAt &&
                !it.outcome.isCompleted
            }?.let { pending ->
                pending.outcome.complete(runCatching { encodeReviewPhoto(source, result) }.getOrNull())
            }
        },
    )
    private data class Selection(val target: CameraAnalysisTarget?, val epoch: Long)
    @Volatile private var selection = Selection(null, 0)
    private var activeEpoch = -1L
    private var session: Session? = null
    @Volatile private var sessionId = ""
    private var reference: Anchor? = null
    private var lastGeometry = -1L
    private var qrRotation: Int? = null
    private var started = false
    private var failed = false
    private val loggedStatuses = mutableSetOf<CvStatus>()
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
        review.getAndSet(null)?.outcome?.complete(null)
        update.value = CameraAnalysisUpdate(target)
    }

    suspend fun captureReview(expected: CameraAnalysisTarget): ReviewPhoto? {
        val current = selection
        if (current.target != expected) return null
        val pending = ReviewRequest(current.epoch, SystemClock.elapsedRealtimeNanos(), CompletableDeferred())
        if (!review.compareAndSet(null, pending)) return null
        return try {
            withTimeoutOrNull(5_000) { pending.outcome.await() }
        } finally {
            review.compareAndSet(pending, null)
        }
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
            projected.set(null)
            started = false
            failed = false
            loggedStatuses.clear()
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
            is CameraAnalysisTarget.Hint -> detect(frame, currentSession, geometry, target, CvMode.VISUAL_HINT, target.target)
            is CameraAnalysisTarget.ManualStep -> if (review.get()?.outcome?.isCompleted == false) {
                cv.onFrame(frame, currentSession, geometry, activeEpoch, CvMode.DETAIL_UNSUPPORTED)
            }
        }
    }

    private fun detect(
        frame: Frame, session: Session, geometry: Long, target: CameraAnalysisTarget, mode: CvMode, hint: VisualHint? = null,
    ) {
        if (reference == null && frame.camera.trackingState == TrackingState.TRACKING) {
            reference = runCatching { session.createAnchor(frame.camera.pose) }.getOrNull()
        }
        val tracking = frame.camera.trackingState == TrackingState.TRACKING
        started = cv.onFrame(frame, session, geometry, activeEpoch, mode, reference, hint) || started
        cv.takeFailure()?.let {
            if (!failed) Log.w("InspectionCV", it)
            failed = true
        }
        val metadata = cv.latestResult(frame)?.result
        if (metadata != null) {
            if (loggedStatuses.add(metadata.status)) {
                Log.i("InspectionCV", "${metadata.mode}: ${metadata.status}, inference ${metadata.timings.inferenceMs.roundToInt()} ms (${metadata.modelManifestVersion})")
            }
            failed = false
        }
        val spatial = cv.latestOverlayResult(frame, OverlayPolicy.APPROXIMATE_STATIONARY_REFERENCE)
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.completedElapsedNs <= 650_000_000L }
        val overlay = projected.get()?.takeIf { it.result === spatial?.result }?.overlay
        update.compareAndSet(
            update.value.takeIf { selection.epoch == activeEpoch } ?: return,
            CameraAnalysisUpdate(
                target, metadata?.status,
                metadata?.defects?.maxOfOrNull { it.confidence }?.toFloat(),
                overlay ?: InspectionOverlay(),
                tracking = tracking,
                started = started,
                inferenceMs = metadata?.timings?.inferenceMs?.roundToInt(),
                failed = failed,
                alignmentPending = overlay == null && metadata != null &&
                    (metadata.equipment.isNotEmpty() || metadata.defects.isNotEmpty() || metadata.visualHints.isNotEmpty()),
            ),
        )
    }

    private fun project(result: CvResult): InspectionOverlay {
        fun point(raw: Point2): ViewportPoint? {
            val screen = result.geometry.imageToView.map(raw)
            return if (screen.x in 0.0..1.0 && screen.y in 0.0..1.0) {
                ViewportPoint(screen.x.toFloat(), screen.y.toFloat())
            } else null
        }
        fun projectMask(mask: BinaryMaskRle): ProjectedMask? {
            val contours = mask.contours()
            if (contours.isEmpty()) return null
            return ProjectedMask(contours.map { ring ->
                ring.map { raw ->
                    val screen = result.geometry.imageToView.map(raw)
                    ViewportPoint(screen.x.toFloat(), screen.y.toFloat())
                }
            })
        }
        val frameMarks = result.zoneCandidates.filter { it.id == ZoneId.FRAME }.mapNotNull {
            point(it.point)?.let { screen -> ProjectedMark(screen, frameLabel) }
        }
        val corrosionMark = result.defects.maxByOrNull { it.confidence }?.bbox?.let {
            point(Point2((it.left + it.right) / 2, (it.top + it.bottom) / 2))
        }?.let { ProjectedMark(it, corrosionLabel, warning = true) }
        return InspectionOverlay(
            pumpMasks = result.equipment.mapNotNull { projectMask(it.mask) } + result.visualHints.map { region ->
                region.mask?.let(::projectMask) ?: region.bbox.let { box ->
                    val corners = listOf(Point2(box.left, box.top), Point2(box.right, box.top),
                        Point2(box.right, box.bottom), Point2(box.left, box.bottom))
                    ProjectedMask(listOf(corners.map { raw ->
                        val screen = result.geometry.imageToView.map(raw)
                        ViewportPoint(screen.x.toFloat(), screen.y.toFloat())
                    }))
                }
            },
            marks = frameMarks + listOfNotNull(corrosionMark),
            defectMasks = result.defects.mapNotNull { projectMask(it.mask) },
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
        review.getAndSet(null)?.outcome?.complete(null)
        scanner.close()
        cv.close()
    }
}
