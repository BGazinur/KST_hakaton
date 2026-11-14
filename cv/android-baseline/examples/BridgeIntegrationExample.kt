package com.arengineering.inspection.cv

import android.os.SystemClock
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Session
import java.util.concurrent.atomic.AtomicReference

/**
 * Source-only integration example. The host still owns its AR session, preview and markers.
 * Construct one instance per render-thread lifetime. loadAsset uses AssetManager.open(path).
 */
class BridgeIntegrationExample(
    loadAsset: (String) -> ByteArray,
    private val publishOverlay: (CapturedCvResult?) -> Unit,
    private val publishStatusAndCandidateList: (CapturedCvResult?) -> Unit,
    private val acceptUserConfirmedHitOnGl: (HitResult) -> Unit,
    private val showRejection: (AnchorRejection) -> Unit,
    private val publishFailure: (String) -> Unit = {},
    private val overlayPolicy: OverlayPolicy = OverlayPolicy.SOURCE_FRAME_ONLY,
) : AutoCloseable {
    // Accessed only inside the bridge worker callbacks.
    private var engine: OrtPumpEngine? = null
    private val bridge = ArCoreCvBridge(
        analyze = { request ->
            val running = engine ?: OrtPumpEngine(loadAsset).also { engine = it }
            running.analyze(request)
        },
        closeAnalyzer = { engine?.close(); engine = null },
    )
    private data class SelectionCommand(val capture: CapturedCvResult, val selection: ConfirmedSelection)
    private val pendingConfirmation = AtomicReference<SelectionCommand?>(null)

    /** A UI action copies values from the currently displayed result, never a Frame. */
    fun confirmCandidate(capture: CapturedCvResult, candidate: ZoneCandidate) {
        require(candidate in capture.result.zoneCandidates)
        val selection = ConfirmedSelection(
            capture.result.stamp, candidate.equipmentId, candidate.id, candidate.point,
            SystemClock.elapsedRealtimeNanos(),
        )
        pendingConfirmation.set(SelectionCommand(capture, selection))
    }

    /**
     * Run on GL immediately after session.update. Epochs change before rendering changed context.
     * existingReference is a separate, stable application-owned anchor selected by the host.
     * Moving/removing a user marker must not detach or replace this reference.
     */
    fun afterSessionUpdate(
        frame: Frame,
        session: Session,
        geometryEpoch: Long,
        inspectionEpoch: Long,
        mode: CvMode,
        existingReference: Anchor? = null,
    ) {
        bridge.onFrame(frame, session, geometryEpoch, inspectionEpoch, mode, existingReference)
        val selection = pendingConfirmation.getAndSet(null)
        if (selection != null) {
            when (val proposal = bridge.proposeSelectedHit(frame, selection.capture, selection.selection)) {
                is AnchorProposal.Ready -> acceptUserConfirmedHitOnGl(proposal.hit)
                is AnchorProposal.Rejected -> showRejection(proposal.reason)
            }
        }
        // The callback replaces the UI state including null. The host dispatches to its UI thread.
        // These are 2D source-image contours with captured affine and a short TTL. They are not
        // a surface reconstruction. Persistent marker projection remains in the AR owner.
        publishStatusAndCandidateList(bridge.latestResult(frame))
        publishOverlay(bridge.latestOverlayResult(frame, overlayPolicy))
        bridge.takeFailure()?.let(publishFailure)
    }

    fun pauseOrChangeInspection() {
        pendingConfirmation.set(null)
        bridge.invalidate()
        publishOverlay(null)
        publishStatusAndCandidateList(null)
    }

    override fun close() {
        pendingConfirmation.set(null)
        bridge.close()
        publishOverlay(null)
        publishStatusAndCandidateList(null)
    }
}
