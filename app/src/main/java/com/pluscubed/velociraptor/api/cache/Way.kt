package com.pluscubed.velociraptor.api.cache

import androidx.room.Entity
import com.pluscubed.velociraptor.api.Coord
import com.pluscubed.velociraptor.api.LimitResponse
import java.util.*

@Entity(primaryKeys = ["clat", "clon"])
data class Way(
        val clat: Double,
        val clon: Double,
        val maxspeedNormal: Int,
        val maxspeedConditionalLimit: Int?,
        val maxspeedConditionalDay1: Int?,
        val maxspeedConditionalDay2: Int?,
        val maxspeedConditionalH1: Int?,
        val maxspeedConditionalMin1: Int?,
        val maxspeedConditionalH2: Int?,
        val maxspeedConditionalMin2: Int?,
        val maxspeedVariable: Boolean,
        val timestamp: Long,
        val lat1: Double,
        val lon1: Double,
        val lat2: Double,
        val lon2: Double,
        val road: String,
        val origin: Int
) {

    companion object {
        fun fromResponse(response: LimitResponse): List<Way> {
            val ways = ArrayList<Way>()
            for (i in 0 until response.coords.size - 1) {
                val coord1 = response.coords[i]
                val coord2 = response.coords[i + 1]

                val clat = (coord1.lat + coord2.lat) / 2
                val clon = (coord1.lon + coord2.lon) / 2

                val way = Way(
                        clat, clon,
                        response.speedLimitNormal,
                        response.speedLimitConditional?.speedLimit,
                        response.speedLimitConditional?.day1,
                        response.speedLimitConditional?.day2,
                        response.speedLimitConditional?.h1,
                        response.speedLimitConditional?.min1,
                        response.speedLimitConditional?.h2,
                        response.speedLimitConditional?.min2,
                        response.speedLimitVariable,
                        response.timestamp,
                        coord1.lat, coord1.lon, coord2.lat, coord2.lon,
                        response.roadName,
                        response.origin
                )
                ways.add(way)
            }
            return ways
        }
    }

    fun toResponse(): LimitResponse {
        val maxspeedConditional = if (maxspeedConditionalLimit != null && maxspeedConditionalH1 != null && maxspeedConditionalMin1 != null && maxspeedConditionalH2 != null && maxspeedConditionalMin2 != null) {
            LimitResponse.SpeedLimitConditional(speedLimit = maxspeedConditionalLimit, day1 = maxspeedConditionalDay1, day2 = maxspeedConditionalDay2, h1 = maxspeedConditionalH1, min1 = maxspeedConditionalMin1, h2 = maxspeedConditionalH2, min2 = maxspeedConditionalMin2)
        } else {
            null
        }

        return LimitResponse(
                speedLimitNormal = maxspeedNormal,
                speedLimitConditional = maxspeedConditional,
                speedLimitVariable = maxspeedVariable,
                timestamp = timestamp,
                roadName = road,
                origin = origin,
                coords = Arrays.asList(Coord(lat1, lon1), Coord(lat2, lon2))
        )
    }

}