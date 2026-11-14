package com.arengineering.inspection.cv

import org.junit.Assert.*
import org.junit.Test

class EquipmentCodeTest {
    private val parser = EquipmentCodeParser()
    private fun frame(t: Long, epoch: Long=1) = CodeFrame("session",epoch,t)
    private fun qr(t: Long, code: String="00123456") = parser.parse(frame(t),listOf(code),true,emptyList())
    private fun gate() = EquipmentCodeGate(newRequestId={"lookup-1"}).apply { beginScan("session",1) }
    @Test fun leadingZerosPreserved() { assertEquals("00123456",qr(1).code) }
    @Test fun lengthsStrict() { assertNull(CodeRules().parse("123456"));assertNull(CodeRules().parse("000123456")) }
    @Test fun configurableLength() { assertEquals("0001",CodeRules(4).parse("0001")) }
    @Test fun noLookalikeRepair() { assertNull(CodeRules().parse("OO123456"));assertNull(CodeRules().parse("００１２３４５６")) }
    @Test fun noSubstringOrMerge() { assertNull(CodeRules().parse("id:00123456"));assertNull(CodeRules().parse("0012 3456")) }
    @Test fun qrUrlNotExecutedOrExtracted() { assertEquals(CodeState.INVALID_CODE,qr(1,"https://example.org/00123456").state) }
    @Test fun badQrDoesNotFallbackToText() { assertEquals(CodeState.INVALID_CODE,parser.parse(frame(1),listOf("bad"),true,listOf("00123456")).state) }
    @Test fun undecodedQrDoesNotFallback() { assertEquals(CodeState.INVALID_CODE,parser.parse(frame(1),emptyList(),true,listOf("00123456")).state) }
    @Test fun differentCodesAmbiguous() { assertEquals(CodeState.AMBIGUOUS,parser.parse(frame(1),listOf("00123456","87654321"),true,emptyList()).state) }
    @Test fun multipleTextLinesAmbiguous() { assertEquals(CodeState.AMBIGUOUS,parser.parse(frame(1),emptyList(),false,listOf("00123456","87654321")).state) }
    @Test fun onlyNumericLineAccepted() { assertEquals("00123456",parser.parse(frame(1),emptyList(),false,listOf("PUMP","00123456","1.5 bar")).code) }
    @Test fun qrNeedsThreeDistinctFrames() { val g=gate();assertNull(g.observe(qr(1)).request);assertNull(g.observe(qr(2)).request);assertEquals("00123456",g.observe(qr(3)).request!!.equipmentCode) }
    @Test fun repeatedFrameNotCounted() { val g=gate();g.observe(qr(1));assertEquals(CodeState.STALE,g.observe(qr(1)).state);assertNull(g.observe(qr(2)).request) }
    @Test fun ocrNeedsFiveFrames() { val g=gate();for(t in 1L..4) assertNull(g.observe(parser.parse(frame(t),emptyList(),false,listOf("00123456"))).request);assertNotNull(g.observe(parser.parse(frame(5),emptyList(),false,listOf("00123456"))).request) }
    @Test fun emptyFrameResetsAgreement() { val g=gate();g.observe(qr(1));g.observe(CodeObservation(frame(2),CodeState.SEARCHING));assertEquals(1,g.observe(qr(3)).agreement) }
    @Test fun codeChangeResetsAgreement() { val g=gate();g.observe(qr(1));assertEquals(1,g.observe(qr(2,"87654321")).agreement) }
    @Test fun timeGapResetsAgreement() { val g=gate();g.observe(qr(1));assertEquals(1,g.observe(qr(900_000_001)).agreement) }
    @Test fun exactlyOneEventPerScan() { val g=gate();for(t in 1L..3)g.observe(qr(t));assertEquals(CodeState.ALREADY_EMITTED,g.observe(qr(4)).state) }
    @Test fun oldSessionCannotRebind() { val g=gate();g.beginScan("session",2);assertEquals(CodeState.STALE,g.observe(qr(1)).state);assertNull(g.observe(qr(2)).request) }
    @Test fun resetRequiresNewScope() { val g=gate();g.reset();assertEquals(CodeState.STALE,g.observe(qr(1)).state) }
    @Test fun wireContractUsesString() { val g=gate();for(t in 1L..2)g.observe(qr(t));val m=g.observe(qr(3)).request!!.wireFields();assertEquals("00123456",m["equipmentCode"]);assertEquals("3",m["frameTimestampNs"]);assertEquals("lookup-1",m["requestId"]) }
}
