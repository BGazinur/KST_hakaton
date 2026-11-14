package com.arengineering.inspection.cv

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Independent geometric landmarks and hand-constructed topology, rather than round-trip-only checks. */
class CoreCvTest {
    private fun near(expected: Double,actual: Double,tolerance: Double = 1e-9) = assertEquals(expected,actual,tolerance)

    @Test fun fourRotationsMapKnownRawLandmarkAndAsymmetricPadding() {
        // 641x479 -> 416x311; 105 padding pixels cannot be split symmetrically.
        val knownModel = listOf(Point2(104.0,285.25),Point2(129.75,104.0),Point2(312.0,129.75),Point2(285.25,312.0))
        for ((index,rotation) in listOf(0,90,180,270).withIndex()) {
            val transform = LetterboxTransform.create(641,479,rotation)
            if (rotation%180 == 0) {
                assertEquals(416,transform.resizedWidth); assertEquals(311,transform.resizedHeight)
                assertEquals(52,transform.padTop); assertEquals(53,transform.padBottom)
            } else {
                assertEquals(311,transform.resizedWidth); assertEquals(416,transform.resizedHeight)
                assertEquals(52,transform.padLeft); assertEquals(53,transform.padRight)
            }
            val mapped = transform.rawToModel(Point2(0.25,0.75))
            near(knownModel[index].x,mapped.x); near(knownModel[index].y,mapped.y)
            val inverse = transform.modelToRaw(knownModel[index])
            near(0.25,inverse.x); near(0.75,inverse.y)
        }
    }

    @Test fun letterboxUsesRoundedAxisScalesAndDoesNotClampPaddingIntoImage() {
        val transform = LetterboxTransform.create(641,479,90)
        assertTrue(kotlin.math.abs(transform.scaleX-transform.gain) > 1e-6)
        val box = transform.modelBoxToRaw(Box2(238.6,41.6,300.8,124.8))
        near(0.1,box.left); near(0.2,box.top); near(0.3,box.right); near(0.4,box.bottom)
        assertTrue(transform.modelToRaw(Point2(0.0,208.0)).y > 1.0)
        // 416 * 1 / 64 = 6.5, ties-to-even matches the Python resize dimensions.
        assertEquals(6,LetterboxTransform.create(64,1,0).resizedHeight)
    }

    @Test fun capturedAffineSelectsClockwiseQuadrantAndRayUsesSourcePose() {
        val affines = listOf(
            Affine2x3(2.0,0.0,-0.5,0.0,1.0,0.0),
            Affine2x3(0.0,-2.0,1.5,1.0,0.0,0.0),
            Affine2x3(-2.0,0.0,1.5,0.0,-1.0,1.0),
            Affine2x3(0.0,2.0,-0.5,-1.0,0.0,1.0)
        )
        affines.forEachIndexed { index,affine -> assertEquals(index*90,affine.clockwiseRotation()) }
        val snapshot = geometry()
        val center = Geometry.sourceWorldRay(snapshot,Point2(0.5,0.5))
        assertEquals(snapshot.stamp,center.sourceStamp)
        assertEquals(Point3(1.0,2.0,3.0),center.origin)
        near(0.0,center.direction.x); near(0.0,center.direction.y); near(-1.0,center.direction.z)
        val downRight = Geometry.sourceWorldRay(snapshot,Point2(0.75,0.75))
        assertTrue(downRight.direction.x > 0); assertTrue(downRight.direction.y < 0); assertTrue(downRight.direction.z < 0)
        val yaw90 = snapshot.copy(cameraPose=CameraPose(1.0,2.0,3.0,0.0,sqrt(0.5),0.0,sqrt(0.5)))
        val ray = Geometry.sourceWorldRay(yaw90,Point2(0.5,0.5))
        near(-1.0,ray.direction.x); near(0.0,ray.direction.y); near(0.0,ray.direction.z)
    }

    @Test fun rowMajorZeroStartRleDecodesKnownRunsAndCopiesMutableArrays() {
        // 3x3 ring plus a separate pixel after a separating empty row.
        val knownRuns = intArrayOf(0,3,2,1,1,1,2,3,11,1)
        val rle = BinaryMaskRle(5,5,knownRuns)
        val expectedRows = listOf("11100","10100","11100","00000","00001")
        val expected = expectedRows.joinToString("").map { it == '1' }.toBooleanArray()
        assertArrayEquals(expected,rle.decode())
        knownRuns[1]=0
        val exposed = rle.counts
        exposed[1]=0
        assertArrayEquals(expected,rle.decode())
        assertArrayEquals(intArrayOf(0,3,2,1,1,1,2,3,11,1),BinaryMaskRle.encode(5,5,expected).counts)
    }

    @Test fun maskMatrixCombinationPreservesRingHoleAndDisconnectedComponent() {
        val decoder = YoloDecoder(maskMaxSide=104)
        val output = segmentation()
        val prototypes = topologyPrototypes()
        val mask = decoder.decodeEquipment(output,prototypes,LetterboxTransform.create(416,416,0)).single().mask
        assertEquals(104,mask.width); assertEquals(104,mask.height)
        val pixels = mask.decode()
        assertEquals(252,pixels.count { it })
        assertTrue(pixels[10*104+10]); assertFalse(pixels[20*104+20])
        assertTrue(pixels[72*104+72]); assertFalse(pixels[50*104+50])
        assertEquals(2,components(pixels,104,104))
        assertEquals(104*104,mask.counts.sum())
        assertTrue(mask.counts.size > 8)
    }

    @Test fun maskRoiAndRawRotationMovePixelsWithoutJoiningRegions() {
        val decoder = YoloDecoder(maskMaxSide=104)
        val output = segmentation()
        val prototypes = topologyPrototypes()
        val rotated = decoder.decodeEquipment(output,prototypes,LetterboxTransform.create(416,416,90)).single().mask.decode()
        assertEquals(252,rotated.count { it })
        assertTrue(rotated[93*104+10]); assertFalse(rotated[83*104+20])
        assertTrue(rotated[30*104+72]); assertEquals(2,components(rotated,104,104))
        // Box surrounds only the disconnected 6x6 square, excluding the ring before threshold.
        setBox(output,0,290f,290f,60f,60f,0.9f)
        val cropped = decoder.decodeRust(output,prototypes,LetterboxTransform.create(416,416,0)).single().mask.decode()
        assertEquals(36,cropped.count { it }); assertEquals(1,components(cropped,104,104))
        assertFalse(cropped[10*104+10])
    }

    @Test fun exactZeroLogitsStayBackgroundAndMalformedShapesFail() {
        val decoder = YoloDecoder()
        val zero = decoder.decodeEquipment(segmentation(),FloatArray(32*104*104),LetterboxTransform.create(641,479,0)).single().mask
        assertEquals(256,zero.width); assertEquals(191,zero.height)
        assertFalse(zero.decode().any { it })
        assertArrayEquals(intArrayOf(256*191),zero.counts)
        assertThrows(IllegalArgumentException::class.java) {
            decoder.decodePose(FloatArray(3549*13),LetterboxTransform.create(641,479,0))
        }
    }

    @Test fun nmsIsOneClassScoreOrderedAndCappedAndIgnoresNan() {
        val output = FloatArray(37*3549)
        setBox(output,0,100f,100f,80f,80f,0.8f)
        setBox(output,1,101f,101f,80f,80f,0.9f) // same object, stronger than index 0
        setBox(output,2,300f,300f,40f,40f,0.7f)
        setBox(output,3,200f,200f,40f,40f,0.2f) // below threshold
        setBox(output,4,Float.NaN,100f,40f,40f,0.99f)
        val decoded = YoloDecoder().decodeEquipment(output,FloatArray(32*104*104),LetterboxTransform.create(416,416,0))
        assertEquals(listOf("equipment-1","equipment-2"),decoded.map { it.id })
        val many = FloatArray(37*3549)
        for (index in 0..11) setBox(many,index,15f+30f*index,50f,10f,10f,0.8f)
        assertEquals((0..9).map { "equipment-$it" },YoloDecoder().decodeEquipment(many,FloatArray(32*104*104),LetterboxTransform.create(416,416,0)).map { it.id })
    }

    @Test fun poseNeedsVisibleConfidenceAndRawFovAndBothModelsMustAgree() {
        val output = FloatArray(14*3549)
        setBox(output,0,208f,208f,416f,416f,0.9f)
        // Rotation90 maps model(104,312) to raw(.75,.75).
        setPoint(output,0,0,104f,312f,0.95f)
        setPoint(output,0,1,208f,208f,0.64f)
        setPoint(output,0,2,-10f,208f,0.99f)
        val decoder = YoloDecoder()
        val pose = decoder.decodePose(output,LetterboxTransform.create(416,416,90)).single()
        assertEquals(1,pose.keypoints.size)
        assertEquals(ZoneId.FRAME,pose.keypoints.single().id)
        near(0.75,pose.keypoints.single().point.x); near(0.75,pose.keypoints.single().point.y)
        val full = BinaryMaskRle(104,104,intArrayOf(0,104*104))
        val equipment = EquipmentHypothesis("equipment-0",0.8,Box2(0.0,0.0,1.0,1.0),full)
        assertEquals(1,decoder.associateZones(listOf(equipment),listOf(pose)).size)
        assertTrue(decoder.associateZones(listOf(equipment.copy(bbox=Box2(0.0,0.0,0.5,0.5))),listOf(pose)).isEmpty())
        val empty = BinaryMaskRle(104,104,intArrayOf(104*104))
        assertTrue(decoder.associateZones(listOf(equipment.copy(mask=empty)),listOf(pose)).isEmpty())
    }

    @Test fun nominalFloat32ConfidenceBoundaryIsInclusive() {
        val poseOutput = FloatArray(14*3549)
        setBox(poseOutput,0,208f,208f,416f,416f,0.30f)
        setPoint(poseOutput,0,0,208f,208f,0.65f)
        val decoder = YoloDecoder()
        val pose = decoder.decodePose(poseOutput,LetterboxTransform.create(416,416,0)).single()
        assertEquals(1,pose.keypoints.size)
        val equipment = EquipmentHypothesis("equipment-0",0.9,Box2(0.0,0.0,1.0,1.0),BinaryMaskRle(8,8,intArrayOf(0,64)))
        assertEquals(1,decoder.associateZones(listOf(equipment),listOf(pose)).size)
        val segOutput = segmentation()
        segOutput[4*3549]=0.35f
        assertEquals(1,decoder.decodeEquipment(segOutput,topologyPrototypes(),LetterboxTransform.create(416,416,0)).size)
    }

    @Test fun gateRejectsIndependentSessionEpochTrackingAgeAndOutOfOrderResults() {
        val gate = ResultGate()
        val current = GateContext("session-a",7,11,TrackingState.TRACKING)
        val source = stamp()
        assertEquals(GateReason.WRONG_SESSION,gate.evaluate(source.copy(sessionId="old"),current,source.acquiredElapsedNs).reason)
        assertEquals(GateReason.GEOMETRY_CHANGED,gate.evaluate(source.copy(geometryEpoch=6),current,source.acquiredElapsedNs).reason)
        assertEquals(GateReason.INSPECTION_CHANGED,gate.evaluate(source.copy(inspectionEpoch=10),current,source.acquiredElapsedNs).reason)
        assertEquals(GateReason.TRACKING_UNAVAILABLE,gate.evaluate(source,current.copy(trackingState=TrackingState.PAUSED),source.acquiredElapsedNs).reason)
        assertEquals(GateReason.CLOCK_IN_FUTURE,gate.evaluate(source,current,source.acquiredElapsedNs-1).reason)
        assertEquals(GateReason.TOO_OLD,gate.evaluate(source,current,source.acquiredElapsedNs+250_000_001L).reason)
        assertTrue(gate.evaluate(source,current,source.acquiredElapsedNs+250_000_000L).accepted)
        assertEquals(GateReason.OUT_OF_ORDER,gate.evaluate(source,current,source.acquiredElapsedNs).reason)
        assertEquals(GateReason.OUT_OF_ORDER,gate.evaluate(source.copy(frameTimestampNs=source.frameTimestampNs-1),current,source.acquiredElapsedNs).reason)
        assertTrue(gate.evaluate(source.copy(frameTimestampNs=source.frameTimestampNs+1),current,source.acquiredElapsedNs).accepted)
        val newSession = current.copy(sessionId="session-b")
        assertTrue(gate.evaluate(source.copy(sessionId="session-b",frameTimestampNs=1L),newSession,source.acquiredElapsedNs).accepted)
    }

    @Test fun rejectionDoesNotPromoteSourceTimestampOrConsumeNewerResultAndLongsAreStrings() {
        val source = stamp()
        val snapshot = geometry()
        val result = CvResult(source,snapshot,status=CvStatus.NO_DEFECT_DETECTED)
        val original = result.stamp
        val gate = ResultGate()
        val context = GateContext("session-a",7,11,TrackingState.TRACKING)
        val expiredNewer = source.copy(frameTimestampNs=source.frameTimestampNs+100,acquiredElapsedNs=1L)
        assertFalse(gate.evaluate(expiredNewer,context,source.acquiredElapsedNs).accepted)
        assertTrue(gate.evaluate(result,context,source.acquiredElapsedNs+10).accepted)
        assertSame(original,result.stamp)
        assertEquals("9007199254740993",result.stamp.wireFields()["frameTimestampNs"])
        assertTrue(result.stamp.wireFields()["acquiredElapsedNs"] is String)
        assertEquals(CvStatus.NO_DEFECT_DETECTED,result.status)
        assertThrows(IllegalArgumentException::class.java) {
            result.copy(stamp=source.copy(frameTimestampNs=123L))
        }
    }

    private fun stamp() = FrameStamp("session-a",7,11,9_007_199_254_740_993L,1_000_000_000L,640,480)
    private fun geometry() = GeometrySnapshot(stamp(),CameraIntrinsics(500.0,500.0,320.0,240.0),CameraPose(1.0,2.0,3.0,0.0,0.0,0.0,1.0),Affine2x3(1.0,0.0,0.0,0.0,1.0,0.0))
    private fun setBox(output: FloatArray,index: Int,x: Float,y: Float,w: Float,h: Float,score: Float) {
        output[index]=x; output[3549+index]=y; output[2*3549+index]=w; output[3*3549+index]=h; output[4*3549+index]=score
    }
    private fun setPoint(output: FloatArray,index: Int,keypoint: Int,x: Float,y: Float,confidence: Float) {
        val base = 5+3*keypoint
        output[base*3549+index]=x; output[(base+1)*3549+index]=y; output[(base+2)*3549+index]=confidence
    }
    private fun segmentation(): FloatArray = FloatArray(37*3549).also {
        setBox(it,0,208f,208f,416f,416f,0.9f)
        it[5*3549]=2f; it[6*3549]=-1f
    }
    private fun topologyPrototypes(): FloatArray {
        val result = FloatArray(32*104*104)
        for (y in 0 until 104) for (x in 0 until 104) {
            val ring = x in 10..30 && y in 10..30 && !(x in 13..27 && y in 13..27)
            val square = x in 70..75 && y in 70..75
            result[y*104+x] = if (ring || square) 1f else -2f
            result[104*104+y*104+x] = 1f
        }
        return result
    }
    private fun components(pixels: BooleanArray,width: Int,height: Int): Int {
        val seen = BooleanArray(pixels.size)
        var count = 0
        for (start in pixels.indices) {
            if (!pixels[start] || seen[start]) continue
            count++
            val queue = java.util.ArrayDeque<Int>()
            queue.add(start); seen[start]=true
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                val x=at%width; val y=at/width
                for ((nx,ny) in listOf(x-1 to y,x+1 to y,x to y-1,x to y+1)) {
                    if (nx in 0 until width && ny in 0 until height) {
                        val next=ny*width+nx
                        if (pixels[next] && !seen[next]) { seen[next]=true; queue.add(next) }
                    }
                }
            }
        }
        return count
    }
}
