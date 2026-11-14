package com.arengineering.inspection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

class MainActivity : ComponentActivity() {
    private var preview by mutableStateOf<ArPreview?>(null)
    private lateinit var demoController: InspectionUiController
    private lateinit var serverController: BackendInspectionController
    private var demoMode by mutableStateOf(false)
    private val controller get() = if (demoMode) demoController else serverController
    private var issue by mutableStateOf<StartupIssue?>(null)
    private var session: Session? = null
    private var foreground = false
    private var previewRunning = false
    private var installRequested = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startAr()
        } else if (controller.state.value.account != null) {
            issue = if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                StartupIssue.PERMISSION
            } else {
                StartupIssue.PERMISSION_SETTINGS
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        installRequested = savedInstanceState?.getBoolean("arInstallRequested") ?: false
        demoController = DemoInspectionController(this, lifecycleScope)
        serverController = BackendInspectionController(resources, lifecycleScope)
        demoMode = savedInstanceState?.getBoolean("demoMode") ?: false
        setContent {
            val active = controller
            val state by active.state.collectAsState()
            val terminalServer = remember { TerminalServer() }
            var terminalOpen by rememberSaveable { mutableStateOf(false) }
            InspectionScreen(
                state, preview, issue, onAction = active::dispatch,
                onStartupAction = ::handleStartupAction,
                onOpenTerminal = { terminalOpen = true },
                onSwitchMode = { demoMode = !demoMode },
            )
            if (terminalOpen) {
                val database = (application as InspectionApplication).telemetryDatabase
                TelemetryTerminal(database.telemetry(), terminalServer, onDismiss = { terminalOpen = false })
            }
            LaunchedEffect(active) {
                active.state.collect { value ->
                    if (value.account == null) {
                        closeSession()
                        preview?.closeAnalysis()
                        preview = null
                        issue = null
                    } else {
                        startArIfNeeded()
                        if (foreground) preview?.analyze(value.cameraAnalysisTarget())
                    }
                }
            }
            val activePreview = preview
            LaunchedEffect(activePreview, active) {
                activePreview?.cameraAnalysis?.collect { update ->
                    if (active === serverController && foreground && update.target == active.state.value.cameraAnalysisTarget()) {
                        serverController.onCorrosionCandidate(update.confidence)
                    }
                }
            }
        }
    }

    private fun startArIfNeeded() {
        if (!foreground || controller.state.value.account == null || preview != null) return
        preview = ArPreview(this, onCode = { code ->
            runOnUiThread {
                if (!isDestroyed && foreground && !demoMode && previewRunning && preview?.isCurrentCode(code) == true) {
                    serverController.onEquipmentCode(code)
                }
            }
        }) {
            runOnUiThread {
                if (!isDestroyed && controller.state.value.account != null) {
                    closeSession()
                    issue = StartupIssue.CAMERA_BUSY
                }
            }
        }
        startAr()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (preview == null) startArIfNeeded() else startAr()
    }

    override fun onPause() {
        foreground = false
        pauseSession()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("arInstallRequested", installRequested)
        outState.putBoolean("demoMode", demoMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closeSession()
        preview?.closeAnalysis()
        super.onDestroy()
    }

    private fun startAr() {
        if (!foreground || controller.state.value.account == null || previewRunning) return
        val arPreview = preview ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (issue != StartupIssue.PERMISSION_SETTINGS) issue = StartupIssue.PERMISSION
            return
        }

        try {
            if (session == null) {
                val install = ArCoreApk.getInstance().requestInstall(this, !installRequested)
                if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    issue = StartupIssue.INSTALLING
                    return
                }
                session = createSession()
            }
            val current = checkNotNull(session)
            current.resume()
            arPreview.analyze(controller.state.value.cameraAnalysisTarget())
            arPreview.start(current)
            previewRunning = true
            issue = null
        } catch (error: Exception) {
            closeSession()
            Log.w("ArEngineering", "Could not start the AR session", error)
            issue = when (error) {
                is CameraNotAvailableException -> StartupIssue.CAMERA_BUSY
                is UnavailableDeviceNotCompatibleException -> StartupIssue.UNSUPPORTED
                is UnavailableSdkTooOldException -> StartupIssue.UPDATE_APP
                is UnavailableArcoreNotInstalledException,
                is UnavailableApkTooOldException,
                is UnavailableUserDeclinedInstallationException -> StartupIssue.INSTALL_NEEDED
                else -> StartupIssue.FAILED
            }
        }
    }

    private fun createSession(): Session {
        val current = Session(this)
        try {
            val config = current.config
            config.focusMode = Config.FocusMode.AUTO
            if (current.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
            current.configure(config)
            return current
        } catch (error: Exception) {
            current.close()
            throw error
        }
    }

    private fun pauseSession() {
        if (!previewRunning) return
        preview?.pause()
        session?.pause()
        previewRunning = false
    }

    private fun closeSession() {
        pauseSession()
        preview?.releaseSession()
        session?.close()
        session = null
    }

    private fun handleStartupAction() {
        when (issue) {
            StartupIssue.PERMISSION -> cameraPermission.launch(Manifest.permission.CAMERA)
            StartupIssue.PERMISSION_SETTINGS -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            StartupIssue.INSTALL_NEEDED -> {
                installRequested = false
                startAr()
            }
            StartupIssue.CAMERA_BUSY, StartupIssue.FAILED -> startAr()
            else -> Unit
        }
    }
}

internal enum class StartupIssue(val title: Int, val hint: Int, val action: Int? = null) {
    PERMISSION(R.string.camera_permission, R.string.camera_permission_hint, R.string.camera_permission_action),
    PERMISSION_SETTINGS(R.string.camera_permission, R.string.camera_permission_hint, R.string.camera_settings_action),
    INSTALLING(R.string.ar_installing, R.string.ar_installing_hint),
    INSTALL_NEEDED(R.string.ar_install_needed, R.string.ar_install_needed_hint, R.string.ar_install_action),
    UNSUPPORTED(R.string.ar_unsupported, R.string.ar_unsupported_hint),
    UPDATE_APP(R.string.ar_update_app, R.string.ar_update_app_hint),
    CAMERA_BUSY(R.string.camera_busy, R.string.camera_busy_hint, R.string.retry),
    FAILED(R.string.ar_error, R.string.ar_error_hint, R.string.retry),
}
