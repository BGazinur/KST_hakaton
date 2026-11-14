package com.arengineering.inspection.cv

enum class CodeSource { QR, OCR }
enum class CodeState { SEARCHING, INVALID_CODE, AMBIGUOUS, STABILIZING, CODE_READY, STALE, ALREADY_EMITTED, FAILED }

/** Demo format: eight ASCII digits. Preserve zeros; never cast to a number. */
data class CodeRules(val digits: Int = 8) {
    init { require(digits in 1..64) }
    fun parse(raw: String): String? = raw.trim().takeIf { it.length == digits && it.all { c -> c in '0'..'9' } }
}
data class CodeFrame(val sessionId: String, val inspectionEpoch: Long, val timestampNs: Long) {
    init { require(sessionId.isNotBlank() && inspectionEpoch >= 0 && timestampNs >= 0) }
}
data class CodeObservation(val frame: CodeFrame, val state: CodeState, val code: String? = null, val source: CodeSource? = null)
data class EquipmentLookupRequest(val requestId: String, val equipmentCode: String, val source: CodeSource, val frame: CodeFrame) {
    fun wireFields(): Map<String, Any> = mapOf(
        "schemaVersion" to 1, "event" to "equipment_code_recognized", "requestId" to requestId,
        "equipmentCode" to equipmentCode, "source" to source.name,
        "sessionId" to frame.sessionId, "inspectionEpoch" to frame.inspectionEpoch.toString(),
        "frameTimestampNs" to frame.timestampNs.toString()
    )
}
data class CodeDecision(val state: CodeState, val request: EquipmentLookupRequest? = null, val agreement: Int = 0)

/** Do not extract substrings, substitute O/0, merge lines or execute QR URLs. */
class EquipmentCodeParser(private val rules: CodeRules = CodeRules()) {
    fun parse(frame: CodeFrame, qrValues: List<String>, qrDetected: Boolean, ocrLines: List<String>): CodeObservation {
        val method = if (qrDetected) CodeSource.QR else CodeSource.OCR
        val raw = if (qrDetected) qrValues else ocrLines
        if (qrDetected && (raw.isEmpty() || raw.any { rules.parse(it) == null }))
            return CodeObservation(frame, CodeState.INVALID_CODE)
        val values = raw.mapNotNull(rules::parse).toSet()
        return when (values.size) {
            0 -> CodeObservation(frame, if (qrDetected) CodeState.INVALID_CODE else CodeState.SEARCHING)
            1 -> CodeObservation(frame, CodeState.STABILIZING, values.single(), method)
            else -> CodeObservation(frame, CodeState.AMBIGUOUS)
        }
    }
}

/** Worker-confined. One DB event per scan. Reset when starting/rescanning identification. */
class EquipmentCodeGate(
    private val qrFrames: Int = 3,
    private val ocrFrames: Int = 5,
    private val maxGapNs: Long = 750_000_000L,
    private val newRequestId: () -> String = { java.util.UUID.randomUUID().toString() }
) {
    init { require(qrFrames > 0 && ocrFrames > 0 && maxGapNs > 0) }
    private var scope: Pair<String, Long>? = null
    private var lastTimestamp = -1L
    private var candidate: Pair<String, CodeSource>? = null
    private var count = 0
    private var emitted = false
    fun reset() { scope = null; lastTimestamp = -1; candidate = null; count = 0; emitted = false }
    /** Authoritative host scope. Late results from an old scan may not change it. */
    fun beginScan(sessionId: String, inspectionEpoch: Long) {
        require(sessionId.isNotBlank() && inspectionEpoch >= 0)
        reset(); scope = sessionId to inspectionEpoch
    }
    fun observe(observation: CodeObservation): CodeDecision {
        val f = observation.frame
        val nextScope = f.sessionId to f.inspectionEpoch
        if (scope != nextScope) return CodeDecision(CodeState.STALE)
        if (f.timestampNs <= lastTimestamp) return CodeDecision(CodeState.STALE)
        val gap = lastTimestamp >= 0 && f.timestampNs - lastTimestamp > maxGapNs
        lastTimestamp = f.timestampNs
        if (emitted) return CodeDecision(CodeState.ALREADY_EMITTED)
        if (observation.state != CodeState.STABILIZING || observation.code == null || observation.source == null) {
            candidate = null; count = 0
            return CodeDecision(observation.state)
        }
        val key = observation.code to observation.source
        count = if (!gap && key == candidate) count + 1 else 1
        candidate = key
        val required = if (key.second == CodeSource.QR) qrFrames else ocrFrames
        if (count < required) return CodeDecision(CodeState.STABILIZING, agreement = count)
        emitted = true
        return CodeDecision(CodeState.CODE_READY, EquipmentLookupRequest(newRequestId(), key.first, key.second, f), count)
    }
}
