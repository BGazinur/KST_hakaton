package com.arengineering.inspection.cv

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Full CPU-model parity against independent NumPy + Python ORT 1.23.2, not a device speed test. */
class ModelParityTest {
    @Test fun pumpRaw90OddDimensions() = verifyFixture("pump90")
    @Test fun pumpRaw270OddDimensions() = verifyFixture("pump270")
    @Test fun rustRaw0OddDimensions() = verifyFixture("rust0")

    @Test fun experimentalHintsUseBundledOnnxAndKeepDefectsSeparate() {
        val fixture = readFixture("pump90")
        val directory = modelDirectory()
        OrtPumpEngine({ path -> File(directory, path.substringAfterLast('/')).readBytes() }).use { engine ->
            val result = engine.analyze(CvRequest(frame90(fixture), CvMode.VISUAL_HINT, VisualHint.GUARD))
            assertEquals(CvStatus.HINT_NOT_FOUND, result.status)
            assertTrue(result.defects.isEmpty())
            assertTrue(result.visualHints.isEmpty())
        }
    }

    @Test fun unusableLocalPilotFallsBackToBundledModel() {
        val fixture = readFixture("pump90")
        val directory = modelDirectory()
        var pilotLoads = 0
        OrtPumpEngine({ path ->
            if (path.endsWith("visual-hint-local.onnx")) {
                pilotLoads++
                byteArrayOf(1, 2, 3)
            } else File(directory, path.substringAfterLast('/')).readBytes()
        }).use { engine ->
            val request = CvRequest(frame90(fixture), CvMode.VISUAL_HINT, VisualHint.GUARD)
            repeat(2) { assertEquals("pump-cv-1", engine.analyze(request).modelManifestVersion) }
            assertEquals(1, pilotLoads)
        }
    }

    @Test fun localBuildLoadsThePilotOnnx() {
        val configuredPath = System.getProperty("cv.localPilot")
        org.junit.Assume.assumeNotNull(configuredPath)
        val local = File(checkNotNull(configuredPath))
        val modelPath = File(local, "cv/visual-hint-local.onnx")
        assertTrue(modelPath.isFile)
        val directory = modelDirectory()
        OrtPumpEngine({ path ->
            if (path == "cv/visual-hint-local.onnx") modelPath.readBytes()
            else File(directory, path.substringAfterLast('/')).readBytes()
        }).use { engine ->
            val result = engine.analyze(CvRequest(frame90(readFixture("pump90")), CvMode.VISUAL_HINT, VisualHint.GUARD))
            assertEquals("visual-hint-local-pilot", result.modelManifestVersion)
            assertTrue(result.defects.isEmpty())
        }
    }

    private fun frame90(fixture: Fixture): CvFrame {
        val width = fixture.int("width")
        val height = fixture.int("height")
        val geometry = GeometrySnapshot(FrameStamp("test", 0, 0, 1, 1, width, height),
            CameraIntrinsics(250.0, 250.0, width / 2.0, height / 2.0),
            CameraPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), Affine2x3(0.0, -1.0, 1.0, 1.0, 0.0, 0.0))
        return CvFrame(geometry, fixture.files.getValue("raw.rgb"))
    }

    private data class Fixture(val properties: Properties, val files: Map<String, ByteArray>) {
        fun text(key: String): String = properties.getProperty(key) ?: error("Missing fixture key $key")
        fun int(key: String): Int = text(key).toInt()
        fun number(key: String): Double = text(key).toDouble()
        fun vector(key: String): List<Double> = text(key).split(',').map { it.toDouble() }
        fun floats(path: String): FloatArray {
            val bytes = files.getValue(path)
            require(bytes.size % 4 == 0)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }

    private fun readFixture(name: String): Fixture {
        val resource = javaClass.classLoader!!.getResourceAsStream("parity/$name.zip")
            ?: error("Missing parity/$name.zip on test resources classpath")
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(resource).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && !entries.containsKey(entry.name))
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val properties = Properties().apply {
            entries.getValue("fixture.properties").inputStream().reader(Charsets.UTF_8).use { load(it) }
        }
        return Fixture(properties, entries)
    }

    private fun modelDirectory(): File {
        System.getProperty("cv.modelDir")?.let { return File(it).also { directory -> require(directory.isDirectory) } }
        return listOf("src/main/assets/cv", "android/src/main/assets/cv", "work/android_handoff/android/src/main/assets/cv")
            .map { File(it) }.firstOrNull { File(it, "pump-seg.onnx").isFile }
            ?: error("Set -Dcv.modelDir=<directory containing the pinned ONNX assets>")
    }

    private fun compareArrays(label: String, expected: FloatArray, actual: FloatArray, atol: Double, rtol: Double) {
        assertEquals("$label tensor element count", expected.size, actual.size)
        var maximum = 0.0
        var sumSquared = 0.0
        for (i in expected.indices) {
            assertTrue("$label non-finite at $i", expected[i].isFinite() && actual[i].isFinite())
            val error = abs(expected[i].toDouble() - actual[i].toDouble())
            maximum = max(maximum, error)
            sumSquared += error * error
            assertTrue("$label at $i expected=${expected[i]} actual=${actual[i]} delta=$error",
                error <= atol + rtol * abs(expected[i].toDouble()))
        }
        println("PARITY $label maxAbs=$maximum rms=${sqrt(sumSquared / expected.size)} n=${expected.size}")
    }

    private fun compareBox(expected: List<Double>, actual: Box2) {
        listOf(actual.left, actual.top, actual.right, actual.bottom).forEachIndexed { index, value ->
            assertEquals("normalized bbox coordinate $index", expected[index], value, 1e-4)
        }
    }

    private fun compareMask(name: String, fixture: Fixture, prefix: String, mask: BinaryMaskRle) {
        assertEquals(fixture.int("$prefix.maskWidth"), mask.width)
        assertEquals(fixture.int("$prefix.maskHeight"), mask.height)
        val expected = fixture.files.getValue("$prefix.mask")
        val actual = mask.decode()
        assertEquals(expected.size, actual.size)
        var intersection = 0
        var union = 0
        var mismatches = 0
        for (i in actual.indices) {
            val reference = expected[i].toInt() != 0
            if (reference && actual[i]) intersection++
            if (reference || actual[i]) union++
            if (reference != actual[i]) mismatches++
        }
        val iou = if (union == 0) 1.0 else intersection.toDouble() / union
        println("PARITY $name/$prefix maskIoU=$iou mismatchedPixels=$mismatches")
        assertTrue("$name/$prefix mask IoU $iou < 0.995", iou >= .995)
    }

    private fun verifyFixture(name: String) {
        val fixture = readFixture(name)
        assertEquals("1.23.2", fixture.text("ortVersion"))
        val width = fixture.int("width")
        val height = fixture.int("height")
        val rotation = fixture.int("rotation")
        val raw = fixture.files.getValue("raw.rgb")
        val prepared = ImagePreprocessor.prepare(raw, width, height, rotation)
        compareArrays("$name/preprocess", fixture.floats("preprocessed.f32"), prepared.chw, 1e-6, 0.0)
        val decoder = YoloDecoder()
        val directory = modelDirectory()
        val environment = OrtEnvironment.getEnvironment()
        val actualDecodedEquipment = mutableListOf<EquipmentHypothesis>()
        val actualDecodedDefects = mutableListOf<DefectHypothesis>()
        for (model in fixture.text("models").split(',')) {
            val modelBytes = File(directory, "$model.onnx").readBytes()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(modelBytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("Pinned model SHA", fixture.text("$model.sha256"), sha)
            val outputs = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(modelBytes, options).use { session ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(prepared.chw), longArrayOf(1, 3, 416, 416)).use { input ->
                        session.run(mapOf("images" to input)).use { result ->
                            (0 until fixture.int("$model.outputs")).map { index ->
                                val buffer = (result[index] as OnnxTensor).floatBuffer
                                FloatArray(buffer.remaining()).also { buffer.get(it) }
                            }
                        }
                    }
                }
            }
            outputs.forEachIndexed { index, actual ->
                compareArrays("$name/$model/output$index", fixture.floats("$model.output$index.f32"), actual, .002, 2e-5)
            }
            if (model == "pump-pose") {
                val hypotheses = decoder.decodePose(outputs[0], prepared.transform)
                assertEquals(fixture.int("$model.count"), hypotheses.size)
                hypotheses.forEachIndexed { i, hypothesis ->
                    val prefix = "$model.$i"
                    assertEquals(fixture.number("$prefix.confidence"), hypothesis.confidence, 1e-4)
                    compareBox(fixture.vector("$prefix.bbox"), hypothesis.bbox)
                    assertEquals(fixture.int("$prefix.points"), hypothesis.keypoints.size)
                    hypothesis.keypoints.forEachIndexed { j, point ->
                        assertEquals(fixture.text("$prefix.point$j.id"), point.id.name)
                        val expected = fixture.vector("$prefix.point$j.xy")
                        assertEquals(expected[0], point.point.x, 1e-4)
                        assertEquals(expected[1], point.point.y, 1e-4)
                        assertEquals(fixture.number("$prefix.point$j.confidence"), point.confidence, 1e-4)
                    }
                }
            } else if (model == "pump-seg") {
                val hypotheses = decoder.decodeEquipment(outputs[0], outputs[1], prepared.transform)
                actualDecodedEquipment.addAll(hypotheses)
                assertEquals(fixture.int("$model.count"), hypotheses.size)
                hypotheses.forEachIndexed { i, hypothesis ->
                    val prefix = "$model.$i"
                    assertEquals("equipment-${fixture.int("$prefix.index")}", hypothesis.id)
                    assertEquals(fixture.number("$prefix.confidence"), hypothesis.confidence, 1e-4)
                    compareBox(fixture.vector("$prefix.bbox"), hypothesis.bbox)
                    compareMask(name, fixture, prefix, hypothesis.mask)
                }
            } else {
                val hypotheses = decoder.decodeRust(outputs[0], outputs[1], prepared.transform)
                actualDecodedDefects.addAll(hypotheses)
                assertEquals(fixture.int("$model.count"), hypotheses.size)
                hypotheses.forEachIndexed { i, hypothesis ->
                    val prefix = "$model.$i"
                    assertEquals("corrosion-${fixture.int("$prefix.index")}", hypothesis.id)
                    assertEquals(fixture.number("$prefix.confidence"), hypothesis.confidence, 1e-4)
                    compareBox(fixture.vector("$prefix.bbox"), hypothesis.bbox)
                    compareMask(name, fixture, prefix, hypothesis.mask)
                }
            }
        }

        // Exercise the production engine and metadata envelope, rather than only the pure decoder.
        val stamp = FrameStamp("parity-$name", 2L, 3L, 9_007_199_254_740_993L, 4_000_000L, width, height)
        val affine = when (rotation) {
            0 -> Affine2x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            90 -> Affine2x3(0.0, -1.0, 1.0, 1.0, 0.0, 0.0)
            180 -> Affine2x3(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0)
            else -> Affine2x3(0.0, 1.0, 0.0, -1.0, 0.0, 1.0)
        }
        val geometry = GeometrySnapshot(stamp, CameraIntrinsics(width.toDouble(), height.toDouble(), width / 2.0, height / 2.0),
            CameraPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), affine, rotation)
        val request = CvRequest(CvFrame(geometry, raw), CvMode.valueOf(fixture.text("mode")))
        OrtPumpEngine({ path -> File(directory, path.substringAfterLast('/')).readBytes() }, 2).use { engine ->
            val result = engine.analyze(request)
            assertEquals(stamp, result.stamp)
            assertEquals(geometry, result.geometry)
            assertEquals(request.mode, result.mode)
            assertEquals(1, result.schemaVersion)
            assertEquals("pump-cv-1", result.modelManifestVersion)
            assertEquals(fixture.text("engine.status"), result.status.name)
            assertEquals(fixture.int("engine.equipment"), result.equipment.size)
            assertEquals(fixture.int("engine.defects"), result.defects.size)
            assertEquals(fixture.int("engine.zones"), result.zoneCandidates.size)
            result.zoneCandidates.forEachIndexed { i, zone ->
                assertEquals(fixture.text("zone$i.id"), zone.id.name)
                val point = fixture.vector("zone$i.xy")
                assertEquals(point[0], zone.point.x, 1e-4)
                assertEquals(point[1], zone.point.y, 1e-4)
            }
            // Cross-check engine masks against independently verified decoder outputs.
            result.equipment.forEach { e ->
                val rawDetection = actualDecodedEquipment.first { it.id == e.id }
                assertArrayEquals(rawDetection.mask.counts, e.mask.counts)
                assertTrue("Pump mask should produce a silhouette", e.mask.contours().any { it.size > 4 })
            }
            result.defects.forEach { d ->
                val rawDetection = actualDecodedDefects.first { it.id == d.id }
                assertArrayEquals(rawDetection.mask.counts, d.mask.counts)
                assertTrue("Corrosion mask should produce drawable regions", d.mask.contours().isNotEmpty())
            }
            assertTrue(result.timings.preprocessMs >= 0 && result.timings.inferenceMs >= 0 && result.timings.decodeMs >= 0)
            System.getProperty("cv.resultDir")?.let { output ->
                val target = File(output).apply { mkdirs() }
                File(target, "$name.json").writeText(CvResultJson.encode(result), Charsets.UTF_8)
            }
            println("PARITY $name/engine ${result.status} equipment=${result.equipment.size} zones=${result.zoneCandidates.size} defects=${result.defects.size}")
        }
    }
}
