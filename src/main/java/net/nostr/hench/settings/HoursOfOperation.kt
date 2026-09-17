package net.nostr.hench.settings

import java.time.LocalTime

sealed class HoursOfOperation {
    abstract fun isOpenAt(time: LocalTime): Boolean

    data object Always : HoursOfOperation() {
        override fun isOpenAt(time: LocalTime): Boolean = true
    }

    data class Daily(
        val start: LocalTime,
        val end: LocalTime,
    ) : HoursOfOperation() {
        override fun isOpenAt(time: LocalTime): Boolean {
            if (start == end) return true
            return if (start.isBefore(end)) {
                !time.isBefore(start) && time.isBefore(end)
            } else {
                !time.isBefore(start) || time.isBefore(end)
            }
        }
    }

    companion object {
        fun always(): HoursOfOperation = Always
        fun daily(start: LocalTime, end: LocalTime): HoursOfOperation = Daily(start, end)
    }
}
