package com.arengineering.inspection.cv

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.logging.Logger

/**
 * Synchronous CPU inference. Construct, analyze and close on the same dedicated worker.
 * This class has no camera/GL dependency and does not retain CvRequest or its RGB array.
 * Android loader example: { path -> context.assets.open(path).use { it.readBytes() } }.
 * JVM tests can supply a filesystem loader; production SHA checks remain identical.
 *
 * Dependency pinned to com.microsoft.onnxruntime:onnxruntime-android:1.23.2 (API 24+).
 * These are visual hypotheses, never an automatic diagnosis or a certificate of health.
 */
class OrtPumpEngine(
    private val modelLoader: (String) -> ByteArray,
    private val threads: Int = 2,
) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    // ORT's process-wide environment is shared; sessions and per-call tensors are owned here.
    private val environment = OrtEnvironment.getEnvironment()
    private val decoder = YoloDecoder()
    private val sessions = linkedMapOf<String, OrtSession>()
    private val pilot by lazy { loadPilotSession() }
    private var closed = false

    init { require(threads in 1..16) { "Choose a bounded CPU thread count" } }

    fun analyze(request: CvRequest): CvResult {
        checkOwner()
        check(!closed) { "OrtPumpEngine is closed" }
        val frame = request.frame
        val geometry = frame.geometry
        val stamp = geometry.stamp
        require(stamp.imageWidth > 0 && stamp.imageHeight > 0)
        require(stamp.imageWidth.toLong() * stamp.imageHeight * 3 == frame.rgb.size.toLong()) {
            "RGB8 bytes must describe the full raw CPU image in the captured geometry"
        }
        if (request.mode == CvMode.DETAIL_UNSUPPORTED) {
            return CvResult(
                stamp = stamp, geometry = geometry, equipment = emptyList(), zoneCandidates = emptyList(),
                defects = emptyList(), status = CvStatus.UNSUPPORTED_DETAIL,
                modelManifestVersion = MODEL_MANIFEST_VERSION,
                mode = request.mode,
            )
        }
        if (request.mode == CvMode.VISUAL_HINT && request.hint == VisualHint.GROUND) {
            val start = System.nanoTime()
            locateGroundWireCue(frame)?.let { region ->
                return CvResult(
                    stamp = stamp, geometry = geometry, status = CvStatus.HINT_FOUND,
                    timings = CvTimings(inferenceMs = elapsedMs(start)),
                    modelManifestVersion = "ground-wire-color-1", mode = request.mode, visualHints = listOf(region),
                )
            }
        }

        val preprocessStart = System.nanoTime()
        val prepared = ImagePreprocessor.prepare(
            frame.rgb, stamp.imageWidth, stamp.imageHeight, geometry.rotationCw,
        )
        val preprocessMs = elapsedMs(preprocessStart)
        val inferenceStart = System.nanoTime()
        val raw = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(prepared.chw), longArrayOf(1, 3, 416, 416),
        ).use { input ->
            when (request.mode) {
                CvMode.OVERVIEW -> mapOf(
                    "pump-seg" to runCopied("pump-seg", input),
                    "pump-pose" to runCopied("pump-pose", input),
                )
                CvMode.DETAIL_RUST -> mapOf("corrosion" to runCopied("corrosion", input))
                CvMode.VISUAL_HINT -> pilot?.let { mapOf(PILOT_ID to readOutputs(it, PILOT_OUTPUTS, input)) }
                    ?: mapOf(HINT_SPEC.id to runCopied(HINT_SPEC.id, input))
                CvMode.DETAIL_UNSUPPORTED -> error("Unsupported mode handled before inference")
            }
        }
        val inferenceMs = elapsedMs(inferenceStart)
        val decodeStart = System.nanoTime()
        val equipment: List<EquipmentHypothesis>
        val zones: List<ZoneCandidate>
        val defects: List<DefectHypothesis>
        var visualHints = emptyList<VisualHintRegion>()
        val status: CvStatus
        if (request.mode == CvMode.OVERVIEW) {
            val segmentation = raw.getValue("pump-seg")
            equipment = decoder.decodeEquipment(segmentation[0], segmentation[1], prepared.transform)
                .mapNotNull { candidate ->
                    clipToCrop(candidate.bbox, candidate.mask, frame)?.let { (box, mask) ->
                        candidate.copy(bbox = box, mask = mask)
                    }
                }
                .filter { it.bbox.area >= MIN_EQUIPMENT_AREA }
            val poses = decoder.decodePose(raw.getValue("pump-pose")[0], prepared.transform)
            zones = if (equipment.size == 1) decoder.associateZones(equipment, poses)
                .filter { insideValidCrop(it.point, frame) } else emptyList()
            defects = emptyList()
            status = when (equipment.size) {
                0 -> CvStatus.EQUIPMENT_NOT_FOUND
                1 -> CvStatus.EQUIPMENT_FOUND
                else -> CvStatus.AMBIGUOUS
            }
        } else if (request.mode == CvMode.DETAIL_RUST) {
            equipment = emptyList()
            zones = emptyList()
            val segmentation = raw.getValue("corrosion")
            defects = decoder.decodeRust(segmentation[0], segmentation[1], prepared.transform)
                .mapNotNull { candidate ->
                    clipToCrop(candidate.bbox, candidate.mask, frame)?.let { (box, mask) ->
                        candidate.copy(bbox = box, mask = mask)
                    }
                }
            // Empty output means no candidate at this threshold, NEVER "equipment is healthy".
            status = if (defects.isEmpty()) CvStatus.NO_DEFECT_DETECTED else CvStatus.DEFECT_CANDIDATE
        } else {
            equipment = emptyList()
            zones = emptyList()
            defects = emptyList()
            val hint = requireNotNull(request.hint)
            val outputs = raw.values.single()
            val candidate = if (PILOT_ID in raw) decodePilotHint(outputs.single(), prepared.transform, hint)
                else decoder.decodeVisualHint(outputs[0], outputs[1], prepared.transform, hint)
            visualHints = candidate?.let { region ->
                region.mask?.let { mask ->
                    clipToCrop(region.bbox, mask, frame)?.let { (box, clipped) -> region.copy(bbox = box, mask = clipped) }
                } ?: clipBoxToCrop(region.bbox, frame).takeIf { region.mask == null && it.area > 0.0 }
                    ?.let { box -> region.copy(bbox = box) }
            }?.let(::listOf).orEmpty()
            status = if (visualHints.isEmpty()) CvStatus.HINT_NOT_FOUND else CvStatus.HINT_FOUND
        }
        return CvResult(
            stamp = stamp,
            geometry = geometry,
            equipment = equipment,
            zoneCandidates = zones,
            defects = defects,
            status = status,
            timings = CvTimings(preprocessMs, inferenceMs, elapsedMs(decodeStart)),
            modelManifestVersion = if (PILOT_ID in raw) PILOT_ID else MODEL_MANIFEST_VERSION,
            mode = request.mode,
            visualHints = visualHints,
        )
    }

    /** Returned FloatArrays are copies and remain valid after native Result/tensors close. */
    private fun runCopied(id: String, input: OnnxTensor): List<FloatArray> {
        val spec = if (id == HINT_SPEC.id) HINT_SPEC else MODEL_SPECS.first { it.id == id }
        val session = sessions.getOrPut(id) { openSession(spec) }
        return readOutputs(session, spec.outputs, input)
    }

    private fun readOutputs(session: OrtSession, outputs: List<OutputSpec>, input: OnnxTensor): List<FloatArray> =
        session.run(mapOf("images" to input)).use { result ->
            outputs.mapIndexed { index, expected ->
                val tensor = result[index] as? OnnxTensor ?: error("Output $index is not a tensor")
                validateTensor(tensor.info, expected.shape, "Runtime output $index")
                val buffer = tensor.floatBuffer ?: error("Output $index has no float buffer")
                val size = expected.shape.fold(1L) { a, b -> a * b }.toInt()
                check(buffer.remaining() == size) { "Output $index has an unexpected number of elements" }
                FloatArray(size).also { buffer.get(it) }
            }
        }

    private fun openSession(spec: ModelSpec): OrtSession {
        val bytes = modelLoader(spec.assetPath)
        require(sha256(bytes) == spec.sha256) { "Model SHA-256 mismatch: ${spec.assetPath}" }
        return openSession(spec, bytes)
    }

    private fun openSession(spec: ModelSpec, bytes: ByteArray): OrtSession {
        val opened = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(bytes, options)
        }
        try {
            validateModel(opened, spec)
        } catch (failure: Throwable) {
            try { opened.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
        return opened
    }

    private fun loadPilotSession(): OrtSession? {
        return try {
            openSession(PILOT_SPEC, modelLoader(PILOT_SPEC.assetPath)).also { sessions[PILOT_ID] = it }
        } catch (_: FileNotFoundException) {
            null
        } catch (error: OrtException) {
            logPilotUnavailable(error)
            null
        } catch (error: IllegalArgumentException) {
            logPilotUnavailable(error)
            null
        }
    }

    private fun logPilotUnavailable(error: Exception) =
        Logger.getLogger("InspectionCV").warning("Local inspection model unavailable: ${error.javaClass.simpleName}")

    private fun validateModel(session: OrtSession, spec: ModelSpec) {
        require(session.inputNames == setOf("images")) { "${spec.id}: unexpected model inputs" }
        validateTensor(session.inputInfo.getValue("images").info as? TensorInfo,
            listOf(1L, 3L, 416L, 416L), "${spec.id} input")
        require(session.outputNames.toList() == spec.outputs.map { it.name }) {
            "${spec.id}: unexpected output names or order"
        }
        for (output in spec.outputs) {
            validateTensor(session.outputInfo.getValue(output.name).info as? TensorInfo,
                output.shape, "${spec.id} ${output.name}")
        }
    }

    private fun insideValidCrop(point: Point2, frame: CvFrame): Boolean {
        val x = point.x * frame.geometry.stamp.imageWidth
        val y = point.y * frame.geometry.stamp.imageHeight
        val crop = frame.validCrop
        return x >= crop.left && x < crop.right && y >= crop.top && y < crop.bottom
    }

    private fun clipBoxToCrop(box: Box2, frame: CvFrame): Box2 {
        val width = frame.geometry.stamp.imageWidth
        val height = frame.geometry.stamp.imageHeight
        val crop = frame.validCrop
        return Box2(
            maxOf(box.left, crop.left.toDouble() / width),
            maxOf(box.top, crop.top.toDouble() / height),
            minOf(box.right, crop.right.toDouble() / width),
            minOf(box.bottom, crop.bottom.toDouble() / height),
        )
    }

    /** Never surface detections inferred exclusively from the synthetic padding outside Image.cropRect. */
    private fun clipToCrop(box: Box2, mask: BinaryMaskRle, frame: CvFrame): Pair<Box2, BinaryMaskRle>? {
        val clipped = clipBoxToCrop(box, frame)
        if (clipped.area <= 0.0) return null
        val pixels = mask.decode()
        var nonempty = false
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            val index = y * mask.width + x
            if (pixels[index]) {
                val point = Point2((x + 0.5) / mask.width, (y + 0.5) / mask.height)
                pixels[index] = insideValidCrop(point, frame)
                nonempty = nonempty || pixels[index]
            }
        }
        if (!nonempty) return null
        return clipped to BinaryMaskRle.encode(mask.width, mask.height, pixels)
    }

    private fun validateTensor(info: TensorInfo?, expectedShape: List<Long>, label: String) {
        require(info != null && info.type == OnnxJavaType.FLOAT && info.shape.toList() == expectedShape) {
            "$label: expected FLOAT $expectedShape, received $info"
        }
    }

    override fun close() {
        checkOwner()
        if (closed) return
        closed = true
        var failure: Throwable? = null
        for (session in sessions.values.toList().asReversed()) {
            try { session.close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        if (failure != null) throw failure
    }

    private fun checkOwner() = check(Thread.currentThread() === ownerThread) {
        "Construct, analyze and close OrtPumpEngine on the same worker; never on the GL thread"
    }

    data class OutputSpec(val name: String, val shape: List<Long>)
    data class ModelSpec(val id: String, val assetPath: String, val sha256: String, val outputs: List<OutputSpec>)

    companion object {
        const val ORT_VERSION = "1.23.2"
        const val MODEL_MANIFEST_VERSION = "pump-cv-1"
        const val MIN_EQUIPMENT_AREA = 0.035
        private const val PILOT_ID = "visual-hint-local-pilot"
        private val PILOT_OUTPUTS = listOf(OutputSpec("output0", listOf(1L, 300L, 6L)))
        private val PILOT_SPEC = ModelSpec(PILOT_ID, "cv/visual-hint-local.onnx", "", PILOT_OUTPUTS)
        private val HINT_SPEC = ModelSpec(
            "visual-hint", "cv/visual-hint.onnx",
            "ffd177a642315c50aa9df296f07fa38486e40989e09a3d8ac987f55b0c8386bc",
            listOf(OutputSpec("output0", listOf(1L, 300L, 38L)), OutputSpec("output1", listOf(1L, 32L, 104L, 104L))),
        )
        private val SEG_OUTPUTS = listOf(
            OutputSpec("output0", listOf(1L, 37L, 3549L)),
            OutputSpec("output1", listOf(1L, 32L, 104L, 104L)),
        )
        val MODEL_SPECS: List<ModelSpec> = listOf(
            ModelSpec("pump-seg", "cv/pump-seg.onnx",
                "8ae73cbfad0c2d9d8579d389caf6c40d7ca14bea1dc4988ba37f91f2cca858d9", SEG_OUTPUTS),
            ModelSpec("pump-pose", "cv/pump-pose.onnx",
                "bcce4956d42f5e4b55aba29b7b405c8d79f666ad3d67acccf331dc25455121ef",
                listOf(OutputSpec("output0", listOf(1L, 14L, 3549L)))),
            ModelSpec("corrosion", "cv/corrosion.onnx",
                "fe23ef5e49b29ec4a3adc97d6ea956bbf576aeb350b36714f0a12aafe92aa3f9", SEG_OUTPUTS),
        )

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun elapsedMs(start: Long): Double = (System.nanoTime() - start) / 1_000_000.0
    }
}
