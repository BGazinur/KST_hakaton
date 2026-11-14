package com.arengineering.inspection.cv

/** All public image coordinates refer to the complete RAW CPU image, before rotation or padding. */
data class Point2(val x: Double, val y: Double)
data class Point3(val x: Double, val y: Double, val z: Double)
data class Box2(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = (right - left).coerceAtLeast(0.0)
    val height: Double get() = (bottom - top).coerceAtLeast(0.0)
    val area: Double get() = width * height
    fun contains(point: Point2): Boolean = point.x in left..right && point.y in top..bottom
}

/** Android Long timestamps MUST be serialized as decimal strings at a JS/JSON boundary. */
data class FrameStamp(
    val sessionId: String,
    val geometryEpoch: Long,
    val inspectionEpoch: Long,
    val frameTimestampNs: Long,
    val acquiredElapsedNs: Long,
    val imageWidth: Int,
    val imageHeight: Int
) {
    init {
        require(sessionId.isNotBlank())
        require(geometryEpoch >= 0 && inspectionEpoch >= 0)
        require(frameTimestampNs >= 0 && acquiredElapsedNs >= 0)
        require(imageWidth > 0 && imageHeight > 0)
    }
    fun wireFields(): Map<String, Any> = mapOf(
        "sessionId" to sessionId, "geometryEpoch" to geometryEpoch.toString(),
        "inspectionEpoch" to inspectionEpoch.toString(),
        "frameTimestampNs" to frameTimestampNs.toString(),
        "acquiredElapsedNs" to acquiredElapsedNs.toString(),
        "imageWidth" to imageWidth, "imageHeight" to imageHeight
    )
}

data class CameraIntrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double) {
    init { require(fx.isFinite() && fy.isFinite() && fx > 0 && fy > 0 && cx.isFinite() && cy.isFinite()) }
}

/** ARCore/OpenGL camera-to-world pose captured with the CPU image; never reuse for a later frame. */
data class CameraPose(
    val tx: Double, val ty: Double, val tz: Double,
    val qx: Double, val qy: Double, val qz: Double, val qw: Double
) {
    init {
        require(listOf(tx,ty,tz,qx,qy,qz,qw).all { it.isFinite() })
        val norm = qx*qx + qy*qy + qz*qz + qw*qw
        require(kotlin.math.abs(norm - 1.0) < 1e-3) { "Camera quaternion must be normalized" }
    }
}

data class Affine2x3(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double
) {
    init {
        require(listOf(a,b,c,d,e,f).all { it.isFinite() })
        require(kotlin.math.abs(a*e-b*d) > 1e-12) { "Degenerate image-to-view transform" }
    }
    fun map(point: Point2): Point2 = Point2(a*point.x+b*point.y+c, d*point.x+e*point.y+f)
    /** Screen axes are x-right/y-down, so positive atan2 means clockwise. */
    fun clockwiseRotation(): Int = ((kotlin.math.round(kotlin.math.atan2(d,a)*2.0/Math.PI).toInt()%4+4)%4)*90
}

data class GeometrySnapshot(
    val stamp: FrameStamp,
    val intrinsics: CameraIntrinsics,
    val cameraPose: CameraPose,
    val imageToView: Affine2x3,
    val rotationCw: Int = imageToView.clockwiseRotation()
) {
    init { require(rotationCw == imageToView.clockwiseRotation()) { "Rotation must match captured affine x-axis" } }
}

data class RawCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(left >= 0 && top >= 0 && right > left && bottom > top) }
}

/** rgb is exclusively owned by this job; no Android Image, Camera, Frame, or YUV buffer is retained. */
data class CvFrame(
    val geometry: GeometrySnapshot,
    val rgb: ByteArray,
    val validCrop: RawCrop = RawCrop(0,0,geometry.stamp.imageWidth,geometry.stamp.imageHeight)
) {
    init {
        require(rgb.size.toLong() == geometry.stamp.imageWidth.toLong()*geometry.stamp.imageHeight*3)
        require(validCrop.right <= geometry.stamp.imageWidth && validCrop.bottom <= geometry.stamp.imageHeight)
    }
}

enum class CvMode { OVERVIEW, DETAIL_RUST, DETAIL_UNSUPPORTED, VISUAL_HINT }
data class CvRequest(val frame: CvFrame, val mode: CvMode, val hint: VisualHint? = null)
enum class ZoneId(val wireValue: String) { FRAME("frame"), CASING("casing"), TERMINAL_BOX("terminal_box") }
enum class DefectType(val wireValue: String) { CORROSION("corrosion") }
enum class CvStatus {
    EQUIPMENT_FOUND, EQUIPMENT_NOT_FOUND, DEFECT_CANDIDATE,
    /** Absence of a model hypothesis is not a healthy classification. */
    NO_DEFECT_DETECTED, UNSUPPORTED_DETAIL, AMBIGUOUS, HINT_FOUND, HINT_NOT_FOUND
}

/** Binary RLE, row-major, zero-start counts. Includes the whole raw-image FOV, including zeros. */
class BinaryMaskRle(val width: Int, val height: Int, counts: IntArray) {
    private val runs = counts.copyOf()
    val counts: IntArray get() = runs.copyOf()
    init {
        require(width > 0 && height > 0 && width.toLong()*height <= Int.MAX_VALUE)
        require(runs.isNotEmpty() && runs.all { it >= 0 })
        require(runs.sumOf { it.toLong() } == width.toLong()*height)
    }
    fun decode(): BooleanArray {
        val pixels = BooleanArray(width*height)
        var start = 0
        runs.forEachIndexed { index, count ->
            if (index%2 == 1) pixels.fill(true,start,start+count)
            start += count
        }
        return pixels
    }
    fun contains(point: Point2, radiusPixels: Double = 0.0): Boolean {
        if (!point.x.isFinite() || !point.y.isFinite() || point.x !in 0.0..1.0 || point.y !in 0.0..1.0) return false
        val pixels = decode()
        val x = (point.x*width).toInt().coerceIn(0,width-1)
        val y = (point.y*height).toInt().coerceIn(0,height-1)
        val radius = kotlin.math.ceil(radiusPixels).toInt().coerceAtLeast(0)
        for (yy in (y-radius).coerceAtLeast(0)..(y+radius).coerceAtMost(height-1)) {
            for (xx in (x-radius).coerceAtLeast(0)..(x+radius).coerceAtMost(width-1)) {
                if ((xx-x)*(xx-x)+(yy-y)*(yy-y) <= radiusPixels*radiusPixels && pixels[yy*width+xx]) return true
            }
        }
        return false
    }
    companion object {
        fun encode(width: Int, height: Int, pixels: BooleanArray): BinaryMaskRle {
            require(pixels.size.toLong() == width.toLong()*height)
            val counts = ArrayList<Int>()
            var value = false
            var count = 0
            pixels.forEach { pixel ->
                if (pixel == value) count++ else { counts.add(count); count=1; value=pixel }
            }
            counts.add(count)
            return BinaryMaskRle(width,height,counts.toIntArray())
        }
    }
}

data class EquipmentHypothesis(val id: String, val confidence: Double, val bbox: Box2, val mask: BinaryMaskRle)
data class KeypointHypothesis(val id: ZoneId, val point: Point2, val confidence: Double)
data class PoseHypothesis(val confidence: Double, val bbox: Box2, val keypoints: List<KeypointHypothesis>)
data class ZoneCandidate(val id: ZoneId, val point: Point2, val confidence: Double, val equipmentId: String)
data class DefectHypothesis(
    val id: String, val confidence: Double, val bbox: Box2, val mask: BinaryMaskRle,
    val type: DefectType = DefectType.CORROSION
)
data class CvTimings(val preprocessMs: Double = 0.0, val inferenceMs: Double = 0.0, val decodeMs: Double = 0.0)
data class CvResult(
    val stamp: FrameStamp,
    val geometry: GeometrySnapshot,
    val equipment: List<EquipmentHypothesis> = emptyList(),
    val zoneCandidates: List<ZoneCandidate> = emptyList(),
    val defects: List<DefectHypothesis> = emptyList(),
    val status: CvStatus,
    val schemaVersion: Int = 1,
    val timings: CvTimings = CvTimings(),
    val modelManifestVersion: String = "pump-cv-1",
    val mode: CvMode = CvMode.OVERVIEW,
    val visualHints: List<VisualHintRegion> = emptyList(),
) {
    init { require(stamp == geometry.stamp) { "Result geometry and source stamp differ" } }
}

/** Explicit human action, not a second name for a model candidate or a health verdict. */
data class ConfirmedSelection(
    val sourceStamp: FrameStamp,
    val equipmentId: String,
    val zoneId: ZoneId,
    val sourcePoint: Point2,
    val confirmedElapsedNs: Long
) {
    init { require(confirmedElapsedNs >= sourceStamp.acquiredElapsedNs) }
}

/** Ray is valid only in sourceStamp.sessionId and at its capture pose. Anchor transforms belong to AR bridge. */
data class SourceWorldRay(val sourceStamp: FrameStamp, val origin: Point3, val direction: Point3)
