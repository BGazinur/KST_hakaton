package com.arengineering.inspection.cv

/** Explicit version-1 wire contract. No JSON dependency, Android dependency, NaN or lossy JS Longs. */
object CvResultJson {
    fun encode(result: CvResult): String = obj(
        "schemaVersion" to result.schemaVersion.toString(),
        "modelManifestVersion" to quote(result.modelManifestVersion),
        "mode" to quote(result.mode.name),
        "coordinateSpace" to quote("raw_image_normalized"),
        "stamp" to stamp(result.stamp),
        "geometry" to geometry(result.geometry),
        "equipment" to array(result.equipment.map { equipment(it) }),
        "zoneCandidates" to array(result.zoneCandidates.map { zone(it) }),
        "defects" to array(result.defects.map { defect(it) }),
        "status" to quote(result.status.name),
        "timings" to obj(
            "preprocessMs" to number(result.timings.preprocessMs),
            "inferenceMs" to number(result.timings.inferenceMs),
            "decodeMs" to number(result.timings.decodeMs)
        )
    )

    private fun stamp(value: FrameStamp): String = obj(
        "sessionId" to quote(value.sessionId),
        "geometryEpoch" to quote(value.geometryEpoch.toString()),
        "inspectionEpoch" to quote(value.inspectionEpoch.toString()),
        "frameTimestampNs" to quote(value.frameTimestampNs.toString()),
        "acquiredElapsedNs" to quote(value.acquiredElapsedNs.toString()),
        "imageWidth" to value.imageWidth.toString(),
        "imageHeight" to value.imageHeight.toString()
    )

    private fun geometry(value: GeometrySnapshot): String {
        val k = value.intrinsics
        val p = value.cameraPose
        val a = value.imageToView
        return obj(
            "intrinsics" to obj("fx" to number(k.fx),"fy" to number(k.fy),"cx" to number(k.cx),"cy" to number(k.cy)),
            "cameraPose" to obj(
                "tx" to number(p.tx),"ty" to number(p.ty),"tz" to number(p.tz),
                "qx" to number(p.qx),"qy" to number(p.qy),"qz" to number(p.qz),"qw" to number(p.qw)
            ),
            "imageToView" to array(listOf(a.a,a.b,a.c,a.d,a.e,a.f).map { number(it) }),
            "rotationCw" to value.rotationCw.toString()
        )
    }

    private fun equipment(value: EquipmentHypothesis): String = obj(
        "id" to quote(value.id),"confidence" to number(value.confidence),
        "bbox" to box(value.bbox),"mask" to mask(value.mask)
    )

    private fun zone(value: ZoneCandidate): String = obj(
        "id" to quote(value.id.wireValue),"point" to point(value.point),
        "confidence" to number(value.confidence),"equipmentId" to quote(value.equipmentId)
    )

    private fun defect(value: DefectHypothesis): String = obj(
        "id" to quote(value.id),"type" to quote(value.type.wireValue),
        "confidence" to number(value.confidence),"bbox" to box(value.bbox),"mask" to mask(value.mask)
    )

    private fun box(value: Box2): String = array(listOf(value.left,value.top,value.right,value.bottom).map { number(it) })
    private fun point(value: Point2): String = array(listOf(number(value.x),number(value.y)))
    private fun mask(value: BinaryMaskRle): String = obj(
        "encoding" to quote("rle_row_major_zero_first"),"width" to value.width.toString(),
        "height" to value.height.toString(),"counts" to array(value.counts.map { it.toString() })
    )
    private fun number(value: Double): String {
        require(value.isFinite()) { "JSON cannot encode a non-finite CV number" }
        return value.toString()
    }
    private fun obj(vararg values: Pair<String,String>): String = values.joinToString(",","{","}") { quote(it.first)+":"+it.second }
    private fun array(values: List<String>): String = values.joinToString(",","[","]")
    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20 || ch.isSurrogate()) {
                    append("\\u"); append(ch.code.toString(16).padStart(4,'0'))
                } else append(ch)
            }
        }
        append('"')
    }
}
