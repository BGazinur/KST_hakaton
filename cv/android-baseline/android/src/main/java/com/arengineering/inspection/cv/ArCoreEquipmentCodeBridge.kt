package com.arengineering.inspection.cv

import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class CapturedCodeDecision(val frame: CodeFrame, val decision: CodeDecision)

/**
 * Identification-only ingress from the application's existing ARCore Session.
 * GL: beginScan/onFrame/invalidate. UI: takeDecision. close is lifecycle-safe.
 * No AR tracking/pose required for reading a label, and no second camera opened.
 */
class ArCoreEquipmentCodeBridge(private val rules: CodeRules = CodeRules()) : AutoCloseable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"pump-equipment-id") }
    private val busy = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val latest = AtomicReference<CapturedCodeDecision?>(null)
    private var closed = false
    private var scope: Pair<String,Long>? = null
    private var glThread: Long? = null
    private var lastSubmitted = -1L
    private var lookupIssued = false
    // Worker-owned only; SDK resources are created/closed on that same worker.
    private var scanner: EquipmentCodeScanner? = null
    private val gate = EquipmentCodeGate()
    private var workerGeneration = -1L

    private fun requireGl() {
        val id = Thread.currentThread().id
        if (glThread == null) glThread = id
        check(glThread == id) { "Call identification ingress from the same render thread" }
    }
    fun beginScan(sessionId: String, inspectionEpoch: Long) = synchronized(lock) {
        requireGl();require(sessionId.isNotBlank() && inspectionEpoch >= 0);check(!closed)
        scope = sessionId to inspectionEpoch
        generation.incrementAndGet();latest.set(null);lastSubmitted = -1;lookupIssued = false
    }
    fun invalidate() = synchronized(lock) {
        requireGl();generation.incrementAndGet();scope = null;latest.set(null);lastSubmitted = -1;lookupIssued = false
    }
    fun onFrame(frame: Frame, sessionId: String, inspectionEpoch: Long, rotationCw: Int, region: CodeScanRegion): Boolean = synchronized(lock) {
        requireGl();require(rotationCw in listOf(0,90,180,270))
        if (closed || lookupIssued || scope != (sessionId to inspectionEpoch)) return@synchronized false
        val timestamp = frame.timestamp
        if (timestamp <= 0 || timestamp <= lastSubmitted || (lastSubmitted >= 0 && timestamp-lastSubmitted < 200_000_000L)) return@synchronized false
        if (!busy.compareAndSet(false,true)) return@synchronized false
        val token = generation.get()
        val id = CodeFrame(sessionId,inspectionEpoch,timestamp)
        try {
            // All camera-owned memory is closed before queueing background work.
            val pixels = frame.acquireCameraImage().use { Yuv420Copier.copy(it) }
            lastSubmitted = timestamp
            worker.execute {
                try {
                    if (generation.get() == token) {
                        if (workerGeneration != token) {
                            gate.beginScan(sessionId,inspectionEpoch);workerGeneration = token
                        }
                        val running = scanner ?: EquipmentCodeScanner(rules).also { scanner = it }
                        val observation = running.scanRgb(pixels.toRgb(), pixels.width, pixels.height, rotationCw, pixels.validCrop, id, region)
                        synchronized(lock) {
                            if (!closed && generation.get() == token && scope == (sessionId to inspectionEpoch)) {
                                val decision = gate.observe(observation)
                                if (decision.state == CodeState.CODE_READY) lookupIssued = true
                                // Preserve an unread DB event instead of overwriting it with ALREADY_EMITTED.
                                if (decision.state != CodeState.ALREADY_EMITTED) latest.set(CapturedCodeDecision(id,decision))
                            }
                        }
                    }
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (!closed && generation.get() == token) {
                            gate.reset();workerGeneration = -1
                            latest.set(CapturedCodeDecision(id,CodeDecision(CodeState.FAILED)))
                        }
                    }
                } finally { busy.set(false) }
            }
            true
        } catch (_: NotYetAvailableException) {
            busy.set(false);false
        } catch (_: Exception) {
            busy.set(false);latest.set(CapturedCodeDecision(id,CodeDecision(CodeState.FAILED)));false
        }
    }
    /** Poll on UI; apply only for the host's still-current scope. Consumes a DB event once. */
    fun takeDecision(sessionId: String, inspectionEpoch: Long): CapturedCodeDecision? = synchronized(lock) {
        if (closed || scope != (sessionId to inspectionEpoch)) return@synchronized null
        latest.getAndSet(null)?.takeIf { it.frame.sessionId == sessionId && it.frame.inspectionEpoch == inspectionEpoch }
    }
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true;generation.incrementAndGet();scope = null;latest.set(null)
            worker.execute { scanner?.close();scanner = null;gate.reset() }
            worker.shutdown()
        }
    }
}
