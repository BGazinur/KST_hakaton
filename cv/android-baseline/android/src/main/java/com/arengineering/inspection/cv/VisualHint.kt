package com.arengineering.inspection.cv

/** Optional inspection cues. Presence or absence is never an inspection outcome. */
enum class VisualHint(val wireValue: String, internal val classIndices: Set<Int>, internal val pilotClass: Int) {
    LEAK("shaft_leak", setOf(0, 1), 0),
    GUARD("coupling_guard", setOf(2), 1),
    CABLE("motor_cable", setOf(3, 4), 2),
    FINS("motor_fins", setOf(5), 3),
    GROUND("motor_ground", setOf(6), 4),
    DISCOLORATION("motor_discoloration", setOf(7), 5);

    companion object {
        fun fromWire(value: String?): VisualHint? = entries.firstOrNull { it.wireValue == value }
    }
}

data class VisualHintRegion(val target: VisualHint, val bbox: Box2, val mask: BinaryMaskRle? = null)

internal fun decodePilotHint(
    detections: FloatArray,
    transform: LetterboxTransform,
    hint: VisualHint,
): VisualHintRegion? {
    require(detections.size == 300 * 6)
    var bestScore = 0.35f
    var bestBox: Box2? = null
    for (offset in detections.indices step 6) {
        val score = detections[offset + 4]
        if (score !in bestScore..1f || detections[offset + 5] != hint.pilotClass.toFloat()) continue
        val edges = (0..3).map { detections[offset + it].toDouble() }
        if (!edges.all { it.isFinite() }) continue
        val box = Box2(edges[0], edges[1], edges[2], edges[3])
        if (box.area <= 0.0) continue
        val raw = transform.modelBoxToRaw(box)
        if (raw.area <= 0.0) continue
        bestScore = score
        bestBox = raw
    }
    return bestBox?.let { VisualHintRegion(hint, it) }
}

internal fun YoloDecoder.decodeVisualHint(
    detections: FloatArray,
    prototypes: FloatArray,
    transform: LetterboxTransform,
    hint: VisualHint,
): VisualHintRegion? {
    val channels = 6 + YoloDecoder.MASK_CHANNELS
    require(detections.size == 300 * channels)
    require(prototypes.size == YoloDecoder.MASK_CHANNELS * YoloDecoder.PROTO_SIZE * YoloDecoder.PROTO_SIZE)
    val candidates = (0 until 300).map { it * channels }.filter { offset ->
        val score = detections[offset + 4]
        val label = detections[offset + 5]
        score in 0.35f..1f && label == label.toInt().toFloat() && label.toInt() in hint.classIndices
    }.sortedByDescending { detections[it + 4] }
    for (offset in candidates) {
        val coordinates = (0 until 4).map { detections[offset + it].toDouble() }
        if (!coordinates.all { it.isFinite() }) continue
        val box = Box2(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
        val raw = transform.modelBoxToRaw(box)
        if (box.area <= 0 || raw.area <= 0) continue
        val mask = decodeMask(prototypes, box, transform) { channel -> detections[offset + 6 + channel] }
        if (mask.decode().any { it }) return VisualHintRegion(hint, raw, mask)
    }
    return null
}
