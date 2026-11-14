package com.arengineering.inspection.cv

import android.opengl.Matrix
import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState as ArTrackingState
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.ResourceExhaustedException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Call [onFrame] on the render thread directly after Session.update and display geometry setup.
 * It copies YUV planes and immutable metadata, closes Image, and performs RGB conversion/inference
 * on a single worker. The analyzer factory/lazy initialization and close callback run there too.
 *
 * The host owns Session, display geometry, inspection state and every Anchor. This class neither
 * opens a camera nor creates/detaches an anchor. Frame, Camera and live intrinsics never leave GL.
 */
class ArCoreCvBridge(
    private val analyze: (CvRequest) -> CvResult,
    private val closeAnalyzer: () -> Unit = {},
    private val maxResultAgeNs: Long = 250_000_000L,
    private val maxCaptureAgeNs: Long = maxResultAgeNs,
    private val prepareResult: (CvResult) -> Unit = {},
    private val onAnalyzedFrame: (CvFrame, CvResult) -> Unit = { _, _ -> },
) : AutoCloseable {
    init { require(maxResultAgeNs > 0 && maxCaptureAgeNs >= maxResultAgeNs) }

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "pump-cv") }
    private val submitLock = Any()
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val ownerToken = Any()
    private val latest = AtomicReference<CapturedCvResult?>(null)
    private val failure = AtomicReference<String?>(null)
    private var glThreadId: Long? = null
    private var session: Session? = null
    private var sessionId = ""
    private var geometryEpoch = Long.MIN_VALUE
    private var inspectionEpoch = Long.MIN_VALUE
    private var mode: CvMode? = null
    private var hint: VisualHint? = null
    private var reference: Anchor? = null
    private var referenceEpoch = 0L
    private var wasTracking = false
    private var lastGeometryFrame = 0L
    private var lastSubmittedFrame = 0L
    private var currentFrameTimestamp = 0L

    /**
     * Returns true only when analysis was scheduled. Keep calling on every rendered AR frame,
     * including frames on which inference is busy, so tracking/lifecycle invalidation still runs.
     * Upright rotation is derived from the captured image-to-view transform.
     */
    fun onFrame(
        frame: Frame,
        session: Session,
        geometryEpoch: Long,
        inspectionEpoch: Long,
        mode: CvMode,
        referenceAnchor: Anchor? = null,
        hint: VisualHint? = null,
    ): Boolean {
        requireGlThread()
        if (closed.get()) return false
        currentFrameTimestamp = frame.timestamp
        var changed = false
        if (this.session !== session) {
            this.session = session
            sessionId = UUID.randomUUID().toString()
            lastSubmittedFrame = 0L
            changed = true
        }
        if (this.geometryEpoch != geometryEpoch || this.inspectionEpoch != inspectionEpoch ||
            this.mode != mode || this.hint != hint) changed = true
        this.geometryEpoch = geometryEpoch
        this.inspectionEpoch = inspectionEpoch
        this.mode = mode
        this.hint = hint
        if (reference !== referenceAnchor) {
            reference = referenceAnchor
            referenceEpoch++
            changed = true
        }
        if (frame.hasDisplayGeometryChanged() && lastGeometryFrame != frame.timestamp) {
            lastGeometryFrame = frame.timestamp
            changed = true
        }
        val tracking = frame.camera.trackingState == ArTrackingState.TRACKING
        if (tracking != wasTracking) changed = true
        wasTracking = tracking
        if (changed) invalidate()
        if (!tracking || frame.timestamp == 0L || frame.timestamp <= lastSubmittedFrame || !busy.compareAndSet(false, true)) {
            return false
        }
        val token = generation.get()
        try {
            val acquiredAt = SystemClock.elapsedRealtimeNanos()
            val image = frame.acquireCameraImage()
            val pixels: OwnedYuv420
            val snapshot: GeometrySnapshot
            val imageTimestamp: Long
            val cameraInReference: Pose?
            try {
                imageTimestamp = image.timestamp
                // acquireCameraImage() returns the image for this Frame. Keep the image
                // timestamp as metadata; equality across the device's timestamp sources
                // is not required for correlation within this call.
                val camera = frame.camera
                val intrinsics = camera.imageIntrinsics
                val dimensions = intrinsics.imageDimensions
                require(dimensions[0] == image.width && dimensions[1] == image.height) {
                    "CPU intrinsics do not describe the acquired image"
                }
                val focal = intrinsics.focalLength
                val principal = intrinsics.principalPoint
                val pose = camera.pose
                val translation = pose.translation
                val quaternion = pose.rotationQuaternion
                val viewCorners = FloatArray(6)
                frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
                    Coordinates2d.VIEW_NORMALIZED, viewCorners,
                )
                require(viewCorners.all { it.isFinite() })
                snapshot = GeometrySnapshot(
                    FrameStamp(sessionId, geometryEpoch, inspectionEpoch, frame.timestamp, acquiredAt, image.width, image.height),
                    CameraIntrinsics(focal[0].toDouble(), focal[1].toDouble(), principal[0].toDouble(), principal[1].toDouble()),
                    CameraPose(
                        translation[0].toDouble(), translation[1].toDouble(), translation[2].toDouble(),
                        quaternion[0].toDouble(), quaternion[1].toDouble(), quaternion[2].toDouble(), quaternion[3].toDouble(),
                    ),
                    Affine2x3(
                        (viewCorners[2] - viewCorners[0]).toDouble(), (viewCorners[4] - viewCorners[0]).toDouble(), viewCorners[0].toDouble(),
                        (viewCorners[3] - viewCorners[1]).toDouble(), (viewCorners[5] - viewCorners[1]).toDouble(), viewCorners[1].toDouble(),
                    ),
                )
                // A nearby, already approved application anchor is the only cross-frame reference.
                cameraInReference = reference?.takeIf { it.trackingState == ArTrackingState.TRACKING }
                    ?.pose?.inverse()?.compose(pose)
                pixels = Yuv420Copier.copy(image)
            } finally {
                image.close()
            }
            val capturedReferenceEpoch = referenceEpoch
            synchronized(submitLock) {
                if (closed.get() || token != generation.get()) return releaseUnscheduled()
                worker.execute {
                    try {
                        if (token != generation.get() || closed.get()) return@execute
                        val source = CvFrame(snapshot, pixels.toRgb(), pixels.validCrop)
                        val result = analyze(CvRequest(source, mode, hint))
                        require(result.stamp == snapshot.stamp && result.geometry == snapshot && result.mode == mode) {
                            "Analyzer changed source geometry or inspection mode"
                        }
                        prepareResult(result)
                        if (token == generation.get() && !closed.get()) onAnalyzedFrame(source, result)
                        val capture = CapturedCvResult(
                            result, imageTimestamp, pixels.validCrop, ownerToken, token, capturedReferenceEpoch,
                            cameraInReference, SystemClock.elapsedRealtimeNanos(),
                        )
                        if (!closed.get() && isFresh(capture)) latest.set(capture)
                    } catch (error: Exception) {
                        if (token == generation.get()) {
                            latest.set(null)
                            failure.set(error.javaClass.simpleName + ": " + (error.message ?: "CV analysis failed"))
                        }
                    } finally {
                        busy.set(false)
                    }
                }
                lastSubmittedFrame = frame.timestamp
            }
            return true
        } catch (_: NotYetAvailableException) {
            return releaseUnscheduled()
        } catch (_: ResourceExhaustedException) {
            return releaseUnscheduled()
        } catch (_: DeadlineExceededException) {
            return releaseUnscheduled()
        } catch (error: Exception) {
            failure.set(error.javaClass.simpleName + ": " + (error.message ?: "Camera copy failed"))
            return releaseUnscheduled()
        }
    }

    /**
     * Fresh inference metadata for status/candidate lists and source-ray selection.
     * This does not authorize painting its coordinates on the current camera image.
     */
    fun latestResult(frame: Frame): CapturedCvResult? {
        requireGlThread()
        val value = latest.get() ?: return null
        if (closed.get() || !isFresh(value) || frame.timestamp != currentFrameTimestamp ||
            frame.camera.trackingState != ArTrackingState.TRACKING) {
            latest.compareAndSet(value, null)
            return null
        }
        return value
    }

    /** Null clears the spatial 2D contour and image-positioned hotspots. */
    fun latestOverlayResult(frame: Frame, policy: OverlayPolicy = OverlayPolicy.SOURCE_FRAME_ONLY): CapturedCvResult? {
        val value = latestResult(frame) ?: return null
        return value.takeIf { overlayEligibility(frame, it, policy).eligible }
    }

    /**
     * A source affine cannot follow camera motion. The default requires the exact source frame.
     * The opt-in approximation allows at most 1 cm translation and 1 degree rotation relative
     * to the same existing reference. It remains an approximate 2D overlay, never 3D registration.
     */
    fun overlayEligibility(
        frame: Frame,
        capture: CapturedCvResult,
        policy: OverlayPolicy = OverlayPolicy.SOURCE_FRAME_ONLY,
    ): OverlayEligibility {
        requireGlThread()
        if (closed.get() || !isFresh(capture) || frame.timestamp != currentFrameTimestamp) {
            return OverlayEligibility(false, OverlayReason.STALE_RESULT)
        }
        if (frame.camera.trackingState != ArTrackingState.TRACKING) return OverlayEligibility(false, OverlayReason.NOT_TRACKING)
        if (frame.timestamp == capture.result.stamp.frameTimestampNs) return OverlayEligibility(true, OverlayReason.SOURCE_FRAME)
        if (policy == OverlayPolicy.SOURCE_FRAME_ONLY) return OverlayEligibility(false, OverlayReason.DIFFERENT_FRAME)
        val reference = reference
        val sourceInReference = capture.cameraInReference
        if (reference == null || sourceInReference == null || capture.referenceEpoch != referenceEpoch) {
            return OverlayEligibility(false, OverlayReason.REFERENCE_REQUIRED)
        }
        if (reference.trackingState != ArTrackingState.TRACKING) return OverlayEligibility(false, OverlayReason.NOT_TRACKING)
        val currentInReference = reference.pose.inverse().compose(frame.camera.pose)
        val delta = sourceInReference.inverse().compose(currentInReference)
        val motion = ReferenceMotion.fromDelta(delta.translation, delta.rotationQuaternion)
        return OverlayEligibility(
            motion.withinApproximateLimit,
            if (motion.withinApproximateLimit) OverlayReason.APPROXIMATE_STATIONARY_REFERENCE else OverlayReason.CAMERA_MOVED,
            motion,
        )
    }

    /** Safe from any thread, including Activity.onPause. Does not release the in-flight slot early. */
    fun invalidate() {
        generation.incrementAndGet()
        latest.set(null)
        failure.set(null)
    }

    fun takeFailure(): String? = failure.getAndSet(null)

    /**
     * Call on GL only following an explicit user selection/confirmation. Create any resulting
     * anchor immediately on this frame in the application's AR marker owner.
     * Async inference needs an existing tracking reference anchor, because ARCore world
     * coordinates can change between frames. A bare captured cameraPose is never reused.
     */
    fun proposeSelectedHit(
        frame: Frame,
        capture: CapturedCvResult,
        selection: ConfirmedSelection? = null,
    ): AnchorProposal {
        requireGlThread()
        if (selection == null) return AnchorProposal.Rejected(AnchorRejection.USER_CONFIRMATION_REQUIRED)
        if (closed.get() || !isFresh(capture) || frame.timestamp != currentFrameTimestamp) {
            return AnchorProposal.Rejected(AnchorRejection.STALE_RESULT)
        }
        if (frame.camera.trackingState != ArTrackingState.TRACKING) return AnchorProposal.Rejected(AnchorRejection.NOT_TRACKING)
        val stamp = capture.result.stamp
        val candidate = capture.result.zoneCandidates.any {
            it.equipmentId == selection.equipmentId && it.id == selection.zoneId && it.point == selection.sourcePoint
        }
        if (selection.sourceStamp != stamp || selection.confirmedElapsedNs > SystemClock.elapsedRealtimeNanos() || !candidate) {
            return AnchorProposal.Rejected(AnchorRejection.INVALID_SELECTION)
        }
        val crop = capture.validCrop
        val px = selection.sourcePoint.x * stamp.imageWidth
        val py = selection.sourcePoint.y * stamp.imageHeight
        if (!px.isFinite() || !py.isFinite() || px < crop.left || py < crop.top || px >= crop.right || py >= crop.bottom) {
            return AnchorProposal.Rejected(AnchorRejection.OUTSIDE_VALID_IMAGE)
        }
        val reference = reference
        val relative = capture.cameraInReference
        if (reference == null || relative == null || capture.referenceEpoch != referenceEpoch) {
            return AnchorProposal.Rejected(AnchorRejection.REFERENCE_REQUIRED)
        }
        if (reference.trackingState != ArTrackingState.TRACKING) return AnchorProposal.Rejected(AnchorRejection.NOT_TRACKING)
        val sourceCameraInCurrentWorld = reference.pose.compose(relative)
        val intrinsics = capture.result.geometry.intrinsics
        val directionCamera = floatArrayOf(
            ((px - intrinsics.cx) / intrinsics.fx).toFloat(),
            (-(py - intrinsics.cy) / intrinsics.fy).toFloat(),
            -1f,
        )
        val directionWorld = sourceCameraInCurrentWorld.rotateVector(directionCamera)
        val originWorld = sourceCameraInCurrentWorld.translation
        val hits = try {
            frame.hitTest(originWorld, 0, directionWorld, 0).filter(::isUsableSurface)
        } catch (_: DeadlineExceededException) {
            return AnchorProposal.Rejected(AnchorRejection.STALE_RESULT)
        } catch (_: NotTrackingException) {
            return AnchorProposal.Rejected(AnchorRejection.NOT_TRACKING)
        }
        if (hits.isEmpty()) return AnchorProposal.Rejected(AnchorRejection.NO_SURFACE)
        val nearest = hits.first()
        val position = nearest.hitPose.translation
        // Depth and plane hits at the same place may coexist. Distinct layers remain ambiguous.
        if (hits.drop(1).any { hit ->
                val other = hit.hitPose.translation
                sqrt((0..2).sumOf { index -> ((other[index] - position[index]).toDouble()).let { it * it } }) > 0.03
            }) return AnchorProposal.Rejected(AnchorRejection.AMBIGUOUS_SURFACE)
        if (!isOnScreen(frame, position)) return AnchorProposal.Rejected(AnchorRejection.OFF_SCREEN)
        return AnchorProposal.Ready(nearest)
    }

    override fun close() {
        synchronized(submitLock) {
            if (!closed.compareAndSet(false, true)) return
            invalidate()
            // FIFO cleanup follows a possible running analysis. Its owned buffers never race close.
            worker.execute {
                try { closeAnalyzer() } finally { busy.set(false) }
            }
            worker.shutdown()
        }
    }

    private fun isFresh(capture: CapturedCvResult): Boolean {
        val now = SystemClock.elapsedRealtimeNanos()
        return capture.ownerToken === ownerToken && capture.generation == generation.get() &&
            now - capture.completedElapsedNs in 0..maxResultAgeNs &&
            now - capture.result.stamp.acquiredElapsedNs in 0..maxCaptureAgeNs
    }

    private fun releaseUnscheduled(): Boolean {
        busy.set(false)
        return false
    }

    private fun requireGlThread() {
        val id = Thread.currentThread().id
        if (glThreadId == null) glThreadId = id
        check(glThreadId == id) { "AR Frame operations must run on the single render thread" }
    }

    private fun isUsableSurface(hit: HitResult): Boolean {
        val trackable = hit.trackable
        if (trackable.trackingState != ArTrackingState.TRACKING || !hit.distance.isFinite() || hit.distance <= 0f) return false
        return when (trackable) {
            is DepthPoint -> true
            is Plane -> trackable.subsumedBy == null && trackable.isPoseInPolygon(hit.hitPose)
            is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            else -> false
        }
    }

    private fun isOnScreen(frame: Frame, position: FloatArray): Boolean {
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val inView = FloatArray(4)
        val clip = FloatArray(4)
        frame.camera.getViewMatrix(view, 0)
        frame.camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        Matrix.multiplyMV(inView, 0, view, 0, floatArrayOf(position[0], position[1], position[2], 1f), 0)
        Matrix.multiplyMV(clip, 0, projection, 0, inView, 0)
        val w = clip[3]
        return w > 0 && clip.all { it.isFinite() } && (0..2).all { clip[it] in -w..w }
    }
}

class CapturedCvResult internal constructor(
    val result: CvResult,
    val sourceImageTimestampNs: Long,
    val validCrop: RawCrop,
    internal val ownerToken: Any,
    internal val generation: Long,
    internal val referenceEpoch: Long,
    internal val cameraInReference: Pose?,
    val completedElapsedNs: Long,
)

sealed interface AnchorProposal {
    data class Ready(val hit: HitResult) : AnchorProposal
    data class Rejected(val reason: AnchorRejection) : AnchorProposal
}

enum class AnchorRejection {
    USER_CONFIRMATION_REQUIRED, INVALID_SELECTION, STALE_RESULT, NOT_TRACKING, OUTSIDE_VALID_IMAGE,
    REFERENCE_REQUIRED, NO_SURFACE, AMBIGUOUS_SURFACE, OFF_SCREEN,
}

enum class OverlayPolicy { SOURCE_FRAME_ONLY, APPROXIMATE_STATIONARY_REFERENCE }
enum class OverlayReason {
    SOURCE_FRAME, APPROXIMATE_STATIONARY_REFERENCE, DIFFERENT_FRAME,
    STALE_RESULT, NOT_TRACKING, REFERENCE_REQUIRED, CAMERA_MOVED,
}
data class OverlayEligibility(val eligible: Boolean, val reason: OverlayReason, val motion: ReferenceMotion? = null)
data class ReferenceMotion(val translationMeters: Double, val rotationDegrees: Double) {
    val withinApproximateLimit: Boolean get() = translationMeters <= 0.01 && rotationDegrees <= 1.0

    companion object {
        /** A relative transform in one anchor coordinate space; q and -q represent the same motion. */
        internal fun fromDelta(translation: FloatArray, quaternion: FloatArray): ReferenceMotion {
            require(translation.size == 3 && quaternion.size == 4)
            require(translation.all { it.isFinite() } && quaternion.all { it.isFinite() })
            val norm = sqrt(quaternion.sumOf { it.toDouble() * it })
            require(norm > 0)
            val distance = sqrt(translation.sumOf { it.toDouble() * it })
            val angle = Math.toDegrees(2 * acos((abs(quaternion[3].toDouble()) / norm).coerceIn(0.0, 1.0)))
            return ReferenceMotion(distance, angle)
        }
    }
}
