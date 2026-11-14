package com.arengineering.inspection

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ArPreview(context: Context, onCameraUnavailable: () -> Unit) {
    val view = GLSurfaceView(context)
    private val renderer = ArRenderer(
        rotation = { view.display?.rotation ?: Surface.ROTATION_0 },
        onCameraUnavailable = onCameraUnavailable,
    )
    val hud = renderer.hud

    init {
        view.setEGLContextClientVersion(2)
        view.preserveEGLContextOnPause = true
        view.setRenderer(renderer)
    }

    fun start(session: Session) {
        renderer.activeSession = session
        view.onResume()
    }

    fun pause() {
        view.onPause()
        renderer.hideMarker()
    }

    fun releaseSession() {
        renderer.activeSession = null
    }

    fun placeMarker(point: ScreenPoint) = renderer.request(MarkerCommand.Place(point))

    fun clearMarker() = renderer.request(MarkerCommand.Clear)
}

internal data class ScreenPoint(val x: Float, val y: Float)

internal enum class HudStatus {
    SEARCHING, READY, PLACED, MISSED, PAUSED, LOST, TOO_DARK, TOO_FAST, LOW_DETAIL, CAMERA_BUSY,
}

internal data class HudState(
    val status: HudStatus = HudStatus.SEARCHING,
    val point: ScreenPoint? = null,
    val hasAnchor: Boolean = false,
)

private sealed interface MarkerCommand {
    data class Place(val point: ScreenPoint) : MarkerCommand
    data object Clear : MarkerCommand
}

private class ArRenderer(
    private val rotation: () -> Int,
    private val onCameraUnavailable: () -> Unit,
) : GLSurfaceView.Renderer {
    @Volatile var activeSession: Session? = null

    private val commands = AtomicReference<MarkerCommand?>(null)
    private val mutableHud = MutableStateFlow(HudState())
    val hud = mutableHud.asStateFlow()

    private val background = CameraBackground()
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val world = floatArrayOf(0f, 0f, 0f, 1f)
    private val clip = FloatArray(4)
    private var renderSession: Session? = null
    private var anchor: Anchor? = null
    private var placementStatus: HudStatus? = null
    private var width = 0
    private var height = 0
    private var lastRotation = -1
    private var textureRegistered = false
    private var cameraFailed = false

    fun request(command: MarkerCommand) {
        commands.set(command)
    }

    fun hideMarker() {
        commands.set(null)
        textureRegistered = false
        mutableHud.value = HudState(status = HudStatus.PAUSED, hasAnchor = mutableHud.value.hasAnchor)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(Night.red, Night.green, Night.blue, 1f)
        background.create()
        textureRegistered = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        lastRotation = -1
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val session = activeSession ?: return
        if (width <= 0 || height <= 0) return

        if (renderSession !== session) {
            renderSession = session
            anchor = null
            placementStatus = null
            textureRegistered = false
            cameraFailed = false
            lastRotation = -1
        }
        if (cameraFailed) return
        if (!textureRegistered) {
            session.setCameraTextureName(background.textureId)
            textureRegistered = true
        }
        val displayRotation = rotation()
        if (lastRotation != displayRotation) {
            session.setDisplayGeometry(displayRotation, width, height)
            lastRotation = displayRotation
        }

        val frame = try {
            session.update()
        } catch (_: CameraNotAvailableException) {
            cameraFailed = true
            mutableHud.value = HudState(status = HudStatus.CAMERA_BUSY)
            onCameraUnavailable()
            return
        }
        background.draw(frame)
        updateMarker(frame)
    }

    private fun updateMarker(frame: Frame) {
        val camera = frame.camera
        val command = commands.getAndSet(null)
        if (command == MarkerCommand.Clear) {
            anchor?.detach()
            anchor = null
            placementStatus = null
        }
        if (camera.trackingState != TrackingState.TRACKING) {
            mutableHud.value = HudState(
                status = camera.trackingFailureReason.toHudStatus(anchor != null),
                hasAnchor = anchor != null,
            )
            return
        }

        if (command is MarkerCommand.Place && frame.timestamp != 0L) {
            placeOnSurface(frame, command.point)
        }
        val current = anchor
        when (current?.trackingState) {
            TrackingState.TRACKING -> {
                mutableHud.value = HudState(
                    status = placementStatus ?: HudStatus.PLACED,
                    point = project(camera, current),
                    hasAnchor = true,
                )
            }
            TrackingState.PAUSED -> {
                mutableHud.value = HudState(status = HudStatus.PAUSED, hasAnchor = true)
            }
            TrackingState.STOPPED -> {
                current.detach()
                anchor = null
                placementStatus = HudStatus.LOST
                mutableHud.value = HudState(status = HudStatus.LOST)
            }
            null -> mutableHud.value = HudState(status = placementStatus ?: HudStatus.READY)
        }
    }

    private fun placeOnSurface(frame: Frame, point: ScreenPoint) {
        val hit = frame.hitTest(point.x * width, point.y * height)
            .firstOrNull { hit -> hit.isSurface() }
        if (hit == null) {
            placementStatus = HudStatus.MISSED
            return
        }

        val newAnchor = hit.createAnchor()
        anchor?.detach()
        anchor = newAnchor
        placementStatus = null
    }

    private fun project(camera: Camera, anchor: Anchor): ScreenPoint? {
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        val pose = anchor.pose
        world[0] = pose.tx()
        world[1] = pose.ty()
        world[2] = pose.tz()
        Matrix.multiplyMV(clip, 0, viewProjection, 0, world, 0)

        val w = clip[3]
        if (w <= 0f || clip[2] < -w || clip[2] > w) return null
        val x = (clip[0] / w + 1f) * 0.5f
        val y = (1f - clip[1] / w) * 0.5f
        if (x !in 0f..1f || y !in 0f..1f) return null
        return ScreenPoint(x, y)
    }
}

private fun HitResult.isSurface(): Boolean = when (val surface = trackable) {
    is DepthPoint -> true
    is Plane -> surface.isPoseInPolygon(hitPose)
    is Point -> surface.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
    else -> false
}

private fun TrackingFailureReason.toHudStatus(hasAnchor: Boolean): HudStatus = when (this) {
    TrackingFailureReason.INSUFFICIENT_LIGHT -> HudStatus.TOO_DARK
    TrackingFailureReason.EXCESSIVE_MOTION -> HudStatus.TOO_FAST
    TrackingFailureReason.INSUFFICIENT_FEATURES -> HudStatus.LOW_DETAIL
    TrackingFailureReason.CAMERA_UNAVAILABLE -> HudStatus.CAMERA_BUSY
    else -> if (hasAnchor) HudStatus.PAUSED else HudStatus.SEARCHING
}
