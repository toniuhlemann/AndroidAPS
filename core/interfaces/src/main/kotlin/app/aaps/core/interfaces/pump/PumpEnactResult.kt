package app.aaps.core.interfaces.pump

interface PumpEnactResult {

    var success: Boolean // request was processed successfully (but possible no change was needed)
    var enacted: Boolean // request was processed successfully and change has been made
    var comment: String

    // Result of basal change
    var duration: Int // duration set [minutes]
    var absolute: Double // absolute rate [U/h] , isPercent = false
    var percent: Int // percent of current basal [%] (100% = current basal), isPercent = true
    var isPercent: Boolean // if true percent is used, otherwise absolute
    var isTempCancel: Boolean // if true we are canceling temp basal

    // Result of treatment delivery
    var bolusDelivered: Double // real value of delivered insulin
    var queued: Boolean

    /**
     * FUSE KI-171: true ONLY when the request was rejected on a path that
     * provably never reached the pump driver (loop or queue rejected it before
     * `deliverTreatment`). Never set after a pump call, never on a timeout or
     * cancel: there the pump may have delivered. Default false = nothing proven.
     */
    var notSentToPump: Boolean

    fun success(success: Boolean): PumpEnactResult
    fun enacted(enacted: Boolean): PumpEnactResult
    fun comment(comment: String): PumpEnactResult
    fun comment(comment: Int): PumpEnactResult
    fun duration(duration: Int): PumpEnactResult
    fun absolute(absolute: Double): PumpEnactResult
    fun percent(percent: Int): PumpEnactResult
    fun isPercent(isPercent: Boolean): PumpEnactResult
    fun isTempCancel(isTempCancel: Boolean): PumpEnactResult
    fun bolusDelivered(bolusDelivered: Double): PumpEnactResult
    fun queued(queued: Boolean): PumpEnactResult
    fun notSentToPump(notSentToPump: Boolean): PumpEnactResult
}