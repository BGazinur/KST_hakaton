package com.arengineering.inspection.cv

enum class TrackingState { TRACKING, PAUSED, STOPPED }
data class GateContext(val sessionId: String,val geometryEpoch: Long,val inspectionEpoch: Long,val trackingState: TrackingState)
enum class GateReason { ACCEPTED, WRONG_SESSION, GEOMETRY_CHANGED, INSPECTION_CHANGED, TRACKING_UNAVAILABLE, OUT_OF_ORDER, TOO_OLD, CLOCK_IN_FUTURE }
data class GateDecision(val accepted: Boolean,val reason: GateReason,val ageNs: Long?)

/** Serialized delivery gate. 250 ms is a provisional UI policy, not a measured device latency guarantee. */
class ResultGate(val maxAgeNs: Long = 250_000_000L) {
    init { require(maxAgeNs >= 0) }
    private var activeIdentity: Triple<String,Long,Long>? = null
    private var lastAcceptedTimestamp: Long = -1L

    @Synchronized
    fun evaluate(result: CvResult,current: GateContext,nowElapsedNs: Long): GateDecision =
        evaluate(result.stamp,current,nowElapsedNs)

    @Synchronized
    fun evaluate(stamp: FrameStamp,current: GateContext,nowElapsedNs: Long): GateDecision {
        require(nowElapsedNs >= 0)
        val identity = Triple(current.sessionId,current.geometryEpoch,current.inspectionEpoch)
        if (identity != activeIdentity) { activeIdentity=identity; lastAcceptedTimestamp=-1L }
        fun reject(reason: GateReason,age: Long? = null) = GateDecision(false,reason,age)
        if (stamp.sessionId != current.sessionId) return reject(GateReason.WRONG_SESSION)
        if (stamp.geometryEpoch != current.geometryEpoch) return reject(GateReason.GEOMETRY_CHANGED)
        if (stamp.inspectionEpoch != current.inspectionEpoch) return reject(GateReason.INSPECTION_CHANGED)
        if (current.trackingState != TrackingState.TRACKING) return reject(GateReason.TRACKING_UNAVAILABLE)
        if (stamp.acquiredElapsedNs > nowElapsedNs) return reject(GateReason.CLOCK_IN_FUTURE)
        val age = nowElapsedNs-stamp.acquiredElapsedNs
        if (age > maxAgeNs) return reject(GateReason.TOO_OLD,age)
        if (stamp.frameTimestampNs <= lastAcceptedTimestamp) return reject(GateReason.OUT_OF_ORDER,age)
        lastAcceptedTimestamp=stamp.frameTimestampNs
        return GateDecision(true,GateReason.ACCEPTED,age)
    }

    @Synchronized
    fun reset() { activeIdentity=null; lastAcceptedTimestamp=-1L }
}
