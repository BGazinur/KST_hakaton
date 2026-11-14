package com.arengineering.inspection.cv

import org.junit.Assert.*
import org.junit.Test

class CvResultJsonTest {
    private fun result(sessionId: String = "s",mode: CvMode = CvMode.OVERVIEW): CvResult {
        val stamp = FrameStamp(sessionId,12,34,9_007_199_254_740_993L,9_007_199_254_740_991L,640,480)
        val geometry = GeometrySnapshot(stamp,CameraIntrinsics(500.0,500.0,320.0,240.0),CameraPose(0.0,0.0,0.0,0.0,0.0,0.0,1.0),Affine2x3(1.0,0.0,0.0,0.0,1.0,0.0))
        return CvResult(stamp,geometry,status=CvStatus.NO_DEFECT_DETECTED,mode=mode)
    }

    @Test fun stampsAreQuotedDecimalsAndCoordinateContractIsExplicit() {
        val json = CvResultJson.encode(result(mode=CvMode.DETAIL_RUST))
        assertTrue(json.startsWith("{\"schemaVersion\":1,\"modelManifestVersion\":\"pump-cv-1\""))
        assertTrue(json.contains("\"mode\":\"DETAIL_RUST\""))
        assertTrue(json.contains("\"coordinateSpace\":\"raw_image_normalized\""))
        assertTrue(json.contains("\"geometryEpoch\":\"12\",\"inspectionEpoch\":\"34\""))
        assertTrue(json.contains("\"frameTimestampNs\":\"9007199254740993\""))
        assertTrue(json.contains("\"acquiredElapsedNs\":\"9007199254740991\""))
        assertTrue(json.contains("\"imageWidth\":640,\"imageHeight\":480"))
        assertTrue(json.contains("\"status\":\"NO_DEFECT_DETECTED\""))
        assertFalse(json.contains("healthy"))
    }

    @Test fun identifiersAreEscapedIncludingControlsAndSurrogates() {
        val json = CvResultJson.encode(result("quoted\"\\path\n\r\t\b\u000C\u0001\uD800"))
        assertTrue(json.contains("\"sessionId\":\"quoted\\\"\\\\path\\n\\r\\t\\b\\f\\u0001\\ud800\""))
        assertFalse(json.contains('\n')); assertFalse(json.contains('\u0001'))
    }

    @Test fun wireShapesKeepBinaryMaskTopologyAndStableZoneIdentifiers() {
        val mask = BinaryMaskRle(3,3,intArrayOf(0,4,1,4))
        val bbox = Box2(0.1,0.2,0.8,0.9)
        val base = result()
        val json = CvResultJson.encode(base.copy(
            equipment=listOf(EquipmentHypothesis("equipment-2",0.8,bbox,mask)),
            zoneCandidates=listOf(ZoneCandidate(ZoneId.TERMINAL_BOX,Point2(0.3,0.4),0.9,"equipment-2")),
            defects=listOf(DefectHypothesis("rust-3",0.75,bbox,mask)),
            status=CvStatus.DEFECT_CANDIDATE
        ))
        assertTrue(json.contains("\"bbox\":[0.1,0.2,0.8,0.9]"))
        assertTrue(json.contains("\"mask\":{\"encoding\":\"rle_row_major_zero_first\",\"width\":3,\"height\":3,\"counts\":[0,4,1,4]}"))
        assertTrue(json.contains("\"id\":\"terminal_box\",\"point\":[0.3,0.4]"))
        assertTrue(json.contains("\"equipmentId\":\"equipment-2\""))
        assertTrue(json.contains("\"type\":\"corrosion\""))
    }

    @Test fun nonFiniteNumbersFailInsteadOfProducingInvalidJson() {
        val base = result()
        for (bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                CvResultJson.encode(base.copy(timings=CvTimings(inferenceMs=bad)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                CvResultJson.encode(base.copy(zoneCandidates=listOf(ZoneCandidate(ZoneId.FRAME,Point2(bad,0.5),0.9,"equipment-0"))))
            }
        }
    }
}
