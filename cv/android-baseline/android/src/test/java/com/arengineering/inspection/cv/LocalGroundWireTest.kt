package com.arengineering.inspection.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class LocalGroundWireTest {
    @Test fun groundCueOnLocalHoldout() {
        val directory = File(System.getProperty("cv.localHoldout") ?: "")
        assumeTrue("Private annotations are optional", File(directory, "images/holdout").isDirectory)
        val photos = File(directory, "images/holdout").listFiles().orEmpty().filter { it.extension == "jpg" }
        var positives = 0
        var hits = 0
        var falseAlarms = 0
        for (photo in photos) {
            val ground = File(directory, "labels/holdout/${photo.nameWithoutExtension}.txt").readLines()
                .filter(String::isNotBlank).map { it.trim().split(Regex("\\s+")).map(String::toDouble) }
                .singleOrNull { it[0] == VisualHint.GROUND.pilotClass.toDouble() }
            val image = ImageIO.read(photo)
            val w = image.width
            val h = image.height
            val rgb = ByteArray(w * h * 3)
            image.getRGB(0, 0, w, h, null, 0, w).forEachIndexed { i, pixel ->
                rgb[i * 3] = (pixel shr 16).toByte()
                rgb[i * 3 + 1] = (pixel shr 8).toByte()
                rgb[i * 3 + 2] = pixel.toByte()
            }
            val geometry = GeometrySnapshot(FrameStamp("test", 0, 0, 1, 1, w, h),
                CameraIntrinsics(100.0, 100.0, w / 2.0, h / 2.0),
                CameraPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), Affine2x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
            val detection = locateGroundWireCue(CvFrame(geometry, rgb))?.bbox
            if (ground == null) {
                if (detection != null) falseAlarms++
            } else {
                positives++
                val (_, x, y, bw, bh) = ground
                val expected = Box2(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)
                if (detection != null && expected.contains(Point2(
                        (detection.left + detection.right) / 2, (detection.top + detection.bottom) / 2))) hits++
            }
        }
        println("Local ground-wire cue: ${photos.size} images, $hits/$positives localized points, $falseAlarms false alarms")
        assertTrue(positives > 0)
        assertEquals(positives, hits)
        assertEquals(0, falseAlarms)
    }
}
