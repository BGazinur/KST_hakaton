package com.arengineering.inspection.cv

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Decoder for fixed 416 px, batch-one Ultralytics YOLO11 raw exports, one class only. No Android/ORT. */
class YoloDecoder(
    val segmentationThreshold: Double = 0.35,
    val poseThreshold: Double = 0.30,
    val keypointThreshold: Double = 0.65,
    val nmsIou: Double = 0.70,
    val maxDetections: Int = 10,
    val maskMaxSide: Int = 256,
    val associationIou: Double = 0.55
) {
    init {
        require(listOf(segmentationThreshold,poseThreshold,keypointThreshold,nmsIou,associationIou).all { it in 0.0..1.0 })
        require(maxDetections > 0 && maskMaxSide in 1..416)
    }
    private data class Detection(val sourceIndex: Int,val confidence: Double,val modelBox: Box2)

    fun decodeEquipment(output0: FloatArray,output1: FloatArray,transform: LetterboxTransform): List<EquipmentHypothesis> {
        requireSegmentationShape(output0,output1,transform)
        return detections(output0,37,segmentationThreshold).mapNotNull { detection ->
            val bbox = transform.modelBoxToRaw(detection.modelBox)
            if (bbox.area <= 0.0) null else EquipmentHypothesis(
                "equipment-${detection.sourceIndex}",detection.confidence,bbox,
                decodeMask(output0,output1,detection,transform)
            )
        }
    }

    fun decodeRust(output0: FloatArray,output1: FloatArray,transform: LetterboxTransform): List<DefectHypothesis> {
        requireSegmentationShape(output0,output1,transform)
        return detections(output0,37,segmentationThreshold).mapNotNull { detection ->
            val bbox = transform.modelBoxToRaw(detection.modelBox)
            if (bbox.area <= 0.0) null else DefectHypothesis(
                "corrosion-${detection.sourceIndex}",detection.confidence,bbox,
                decodeMask(output0,output1,detection,transform)
            )
        }
    }

    fun decodePose(output0: FloatArray,transform: LetterboxTransform): List<PoseHypothesis> {
        require(transform.inputSize == INPUT_SIZE)
        require(output0.size == 14*ANCHORS) { "Pose output0 must be flat CHW [1,14,3549]" }
        return detections(output0,14,poseThreshold).mapNotNull { detection ->
            val bbox = transform.modelBoxToRaw(detection.modelBox)
            if (bbox.area <= 0.0) return@mapNotNull null
            val keypoints = ZoneId.values().mapIndexedNotNull { index,id ->
                val base = 5+index*3
                val x = output0[base*ANCHORS+detection.sourceIndex].toDouble()
                val y = output0[(base+1)*ANCHORS+detection.sourceIndex].toDouble()
                val confidence = output0[(base+2)*ANCHORS+detection.sourceIndex].toDouble()
                if (!x.isFinite() || !y.isFinite() || !confidence.isFinite() || confidence < floatThreshold(keypointThreshold) || confidence > 1.0) return@mapIndexedNotNull null
                val point = transform.modelToRaw(Point2(x,y))
                if (point.x !in 0.0..1.0 || point.y !in 0.0..1.0) null else KeypointHypothesis(id,point,confidence)
            }
            PoseHypothesis(detection.confidence,bbox,keypoints)
        }
    }

    /** Require agreement of independently trained segmentation and pose models before showing a zone. */
    fun associateZones(equipment: List<EquipmentHypothesis>,poses: List<PoseHypothesis>): List<ZoneCandidate> {
        val candidates = ArrayList<ZoneCandidate>()
        for (pump in equipment) {
            val pose = poses.filter { Geometry.intersectionOverUnion(pump.bbox,it.bbox) >= associationIou }
                .maxByOrNull { Geometry.intersectionOverUnion(pump.bbox,it.bbox) }
                ?: continue
            val tolerance = 0.012*max(pump.mask.width,pump.mask.height)
            pose.keypoints.filter { it.confidence >= floatThreshold(keypointThreshold) && pump.bbox.contains(it.point) && pump.mask.contains(it.point,tolerance) }
                .forEach { candidates.add(ZoneCandidate(it.id,it.point,it.confidence,pump.id)) }
        }
        return candidates
    }

    private fun requireSegmentationShape(output0: FloatArray,output1: FloatArray,transform: LetterboxTransform) {
        require(transform.inputSize == INPUT_SIZE)
        require(output0.size == 37*ANCHORS) { "Segmentation output0 must be flat CHW [1,37,3549]" }
        require(output1.size == MASK_CHANNELS*PROTO_SIZE*PROTO_SIZE) { "Segmentation output1 must be flat CHW [1,32,104,104]" }
    }

    private fun detections(output: FloatArray,channels: Int,threshold: Double): List<Detection> {
        require(output.size == channels*ANCHORS)
        val candidates = ArrayList<Detection>()
        for (index in 0 until ANCHORS) {
            val score = output[4*ANCHORS+index].toDouble()
            if (!score.isFinite() || score < floatThreshold(threshold) || score > 1.0) continue
            val x = output[index].toDouble()
            val y = output[ANCHORS+index].toDouble()
            val w = output[2*ANCHORS+index].toDouble()
            val h = output[3*ANCHORS+index].toDouble()
            if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0.0 || h <= 0.0) continue
            candidates.add(Detection(index,score,Box2(x-w/2,y-h/2,x+w/2,y+h/2)))
        }
        val kept = ArrayList<Detection>()
        // Stable source-index tie-breaking makes outputs reproducible across Android/desktop runtimes.
        for (candidate in candidates.sortedWith(compareByDescending<Detection> { it.confidence }.thenBy { it.sourceIndex })) {
            if (kept.all { Geometry.intersectionOverUnion(it.modelBox,candidate.modelBox) <= nmsIou }) {
                kept.add(candidate)
                if (kept.size == maxDetections) break
            }
        }
        return kept
    }

    /**
     * coeff @ proto logits, bilinear align_corners=false, model-ROI crop, then logit > 0.
     * Sample centers of the complete RAW FOV directly through the inverse preprocess geometry.
     * No contours/polygon stitching or morphological filling: split components and holes stay in RLE.
     */
    private fun decodeMask(output: FloatArray,prototypes: FloatArray,detection: Detection,transform: LetterboxTransform): BinaryMaskRle {
        val side = PROTO_SIZE
        val plane = side*side
        val logits = FloatArray(plane)
        for (channel in 0 until MASK_CHANNELS) {
            val coefficient = output[(5+channel)*ANCHORS+detection.sourceIndex]
            require(coefficient.isFinite()) { "Non-finite mask coefficient" }
            if (coefficient == 0f) continue
            val offset = channel*plane
            for (i in 0 until plane) logits[i] += coefficient*prototypes[offset+i]
        }
        require(logits.all { it.isFinite() }) { "Non-finite mask logits" }
        val scale = min(1.0,maskMaxSide.toDouble()/max(transform.rawWidth,transform.rawHeight))
        val width = round(transform.rawWidth*scale).toInt().coerceAtLeast(1)
        val height = round(transform.rawHeight*scale).toInt().coerceAtLeast(1)
        val binary = BooleanArray(width*height)
        val box = detection.modelBox
        for (y in 0 until height) {
            for (x in 0 until width) {
                val model = transform.rawToModel(Point2((x+0.5)/width,(y+0.5)/height))
                if (model.x < box.left || model.x >= box.right || model.y < box.top || model.y >= box.bottom) continue
                val px = (model.x*side/INPUT_SIZE-0.5).coerceIn(0.0,(side-1).toDouble())
                val py = (model.y*side/INPUT_SIZE-0.5).coerceIn(0.0,(side-1).toDouble())
                val x0 = floor(px).toInt()
                val y0 = floor(py).toInt()
                val x1 = min(side-1,x0+1)
                val y1 = min(side-1,y0+1)
                val dx = px-x0
                val dy = py-y0
                val top = logits[y0*side+x0]*(1.0-dx)+logits[y0*side+x1]*dx
                val bottom = logits[y1*side+x0]*(1.0-dx)+logits[y1*side+x1]*dx
                binary[y*width+x] = top*(1.0-dy)+bottom*dy > 0.0
            }
        }
        return BinaryMaskRle.encode(width,height,binary)
    }

    companion object {
        const val INPUT_SIZE = 416
        const val ANCHORS = 3549
        const val MASK_CHANNELS = 32
        const val PROTO_SIZE = 104
    }

    // Export tensors are float32. A nominal 0.65f is 0.649999976 as Double and must meet >=0.65.
    private fun floatThreshold(value: Double): Double = value.toFloat().toDouble()
}
