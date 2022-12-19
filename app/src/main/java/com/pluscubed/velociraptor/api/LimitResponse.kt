package com.pluscubed.velociraptor.api

import java.util.*

data class LimitResponse(
        val fromCache: Boolean = false,
        val debugInfo: String = "",
        val origin: Int = ORIGIN_INVALID,
        val error: Throwable? = null,
        /**
         * In km/h, -1 if limit does not exist
         */
        val speedLimitNormal: Int = -1,
        val speedLimitConditional: SpeedLimitConditional? = null,
        val speedLimitVariable: Boolean = false,
        val roadName: String = "",
        val coords: List<Coord> = ArrayList(),
        val timestamp: Long = 0
) {

    fun speedLimit(): Int {
        return if (speedLimitConditional?.check() == true) {
            speedLimitConditional.speedLimit
        } else {
            speedLimitNormal
        }
    }

    data class SpeedLimitConditional(
            val speedLimit: Int = -1,
            val day1: Int? = null,
            val day2: Int? = null,
            val h1: Int = 0,
            val min1: Int = 0,
            val h2: Int = 0,
            val min2: Int = 0
    ) {
        fun check(): Boolean {
            val calendar = Calendar.getInstance()
            var current = calendar.get(Calendar.DAY_OF_WEEK) - 2
            val currentH = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMin = calendar.get(Calendar.MINUTE)
            if (current == -1)
                current = 6

            if (day1 != null && day2 != null) {
                if (day1 < day2 && (current < day1 || current > day2)) {
                    return false
                } else if (day2 < day1 && (current > day1 || current < day2)) {
                    return false
                }
            }

            if (h2 > h1) {
                if (currentH < h1) {
                    return false
                } else if (currentH == h1 && currentMin < min1) {
                    return false
                }

                if (currentH > h2) {
                    return false
                } else if (currentH == h2 && currentMin > min2) {
                    return false
                }
            } else if (h1 > h2) {
                if ((currentH < h1 || (currentH == h1 && currentMin < min1)) && (currentH > h2 || (currentH == h2 && currentMin > min2))) {
                    return false
                }
            }

            return true
        }
    }

    val isEmpty: Boolean
        get() = coords.isEmpty()

    fun initDebugInfo(debuggingEnabled: Boolean): LimitResponse {
        if (debuggingEnabled) {
            val origin = getLimitProviderString(origin)

            var text = "\nOrigin: " + origin +
                    "\n--From cache: " + fromCache
            if (error == null) {
                text += "\n--Road name: " + roadName +
                        "\n--Coords: " + coords +
                        "\n--Limit: " + speedLimit()
            } else {
                text += "\n--Error: " + error.toString()
            }

            return copy(debugInfo = debugInfo + text)
        } else {
            return this
        }
    }

    companion object {
        const val ORIGIN_INVALID = -1
        const val ORIGIN_HERE = 2
        const val ORIGIN_TOMTOM = 1
        const val ORIGIN_OSM = 0

        internal fun getLimitProviderString(origin: Int): String {
            var provider = ""
            when (origin) {
                LimitResponse.ORIGIN_HERE -> provider = "HERE"
                LimitResponse.ORIGIN_TOMTOM -> provider = "TomTom"
                LimitResponse.ORIGIN_OSM -> provider = "OSM"
                -1 -> provider = "?"
                else -> provider = origin.toString()
            }
            return provider
        }
    }
}
