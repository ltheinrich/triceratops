package com.pluscubed.velociraptor.api.osm

import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.pluscubed.velociraptor.BuildConfig
import com.pluscubed.velociraptor.api.LimitFetcher
import com.pluscubed.velociraptor.api.LimitInterceptor
import com.pluscubed.velociraptor.api.LimitProvider
import com.pluscubed.velociraptor.api.LimitResponse
import com.pluscubed.velociraptor.api.cache.CacheLimitProvider
import com.pluscubed.velociraptor.api.osm.data.Element
import com.pluscubed.velociraptor.api.osm.data.OsmResponse
import com.pluscubed.velociraptor.api.osm.data.Tags
import com.pluscubed.velociraptor.utils.PrefUtils
import com.pluscubed.velociraptor.utils.Utils
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.util.*
import kotlin.system.exitProcess

class OsmLimitProvider(
        private val context: Context,
        private val client: OkHttpClient,
        private val cacheLimitProvider: CacheLimitProvider
) : LimitProvider {

    private val osmOverpassApis: MutableList<OsmApiEndpoint>

    private fun initializeOsmService(endpoint: OsmApiEndpoint) {
        val osmInterceptor = LimitInterceptor(object : LimitInterceptor.Callback() {
            override fun updateTimeTaken(timeTaken: Int) {
                updateEndpointTimeTaken(timeTaken, endpoint)
            }
        })

        val osmClient = client.newBuilder()
                .addInterceptor(osmInterceptor)
                .build()
        val osmRest = LimitFetcher.buildRetrofit(osmClient, endpoint.baseUrl)

        val osmService = osmRest.create(OsmService::class.java)
        endpoint.service = osmService
    }

    private fun updateEndpointTimeTaken(timeTaken: Int, endpoint: OsmApiEndpoint) {
        endpoint.timeTaken = timeTaken
        osmOverpassApis.sort()
        Timber.d("Endpoints: %s", osmOverpassApis)
    }

    private fun refreshApiEndpoints() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()

        val apisString = remoteConfig.getString(FB_CONFIG_OSM_APIS)

        if (apisString.isEmpty()) {
            return
        }

        val stringArray = apisString.replace("[", "").replace("]", "").split(",".toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()

        val existingUrls = HashSet<String>()
        for (existing in osmOverpassApis) {
            existingUrls.add(existing.baseUrl)
        }

        for (i in stringArray.indices) {
            val apiHost = stringArray[i].replace("\"", "")
            val enabled = remoteConfig.getBoolean(FB_CONFIG_OSM_API_ENABLED_PREFIX + i)
            if (enabled && !existingUrls.contains(apiHost)) {
                val endpoint = OsmApiEndpoint(apiHost)
                initializeOsmService(endpoint)
                osmOverpassApis.add(endpoint)
            }
        }
    }

    private fun buildQueryBody(location: Location): String {
        val latitude = String.format(Locale.ROOT, "%.5f", location.latitude)
        val longitude = String.format(Locale.ROOT, "%.5f", location.longitude)
        return ("[out:json];" +
                "way(around:" + OSM_RADIUS + ","
                + latitude + ","
                + longitude +
                ")" +
                "[\"highway\"];out body geom;")
    }

    private fun getOsmResponse(location: Location): OsmResponse {
        val selectedEndpoint: OsmApiEndpoint
        if (Math.random() < 0.7) {
            //Select the fastest endpoint most of the time
            selectedEndpoint = osmOverpassApis[0]
        } else {
            //Select a random endpoint 30% of the time to "correct" for anomalies
            selectedEndpoint = osmOverpassApis[(Math.random() * osmOverpassApis.size).toInt()]
        }
        try {
            val osmNetworkResponse =
                    selectedEndpoint.service!!.getOsm(buildQueryBody(location)).execute()
            logOsmRequest(selectedEndpoint)
            return Utils.getResponseBody(osmNetworkResponse)
        } catch (e: Exception) {
            //catch any errors, rethrow
            updateEndpointTimeTaken(Integer.MAX_VALUE, selectedEndpoint)
            logOsmError(selectedEndpoint, e)
            throw e
        }
    }

    override fun getSpeedLimit(
            location: Location,
            lastResponse: LimitResponse?,
            origin: Int
    ): List<LimitResponse> {
        val debuggingEnabled = PrefUtils.isDebuggingEnabled(context)
        var limitResponse = LimitResponse(
                timestamp = System.currentTimeMillis(),
                origin = LimitResponse.ORIGIN_OSM,
                debugInfo = (
                        if (debuggingEnabled)
                            "\nOSM Info:\n--" + TextUtils.join("\n--", osmOverpassApis)
                        else
                            ""
                        )
        )
        try {
            val osmResponse = getOsmResponse(location)

            val emptyObservableResponse = {
                listOf(limitResponse.initDebugInfo(debuggingEnabled))
            }

            val elements = osmResponse.elements

            if (elements.isEmpty()) {
                return emptyObservableResponse()
            }

            val bestMatch = getBestElement(elements, lastResponse)
            var bestResponse: LimitResponse? = null

            for (element in elements) {

                //Get coords
                if (element.geometry != null && !element.geometry.isEmpty()) {
                    limitResponse = limitResponse.copy(coords = element.geometry)
                } else if (element !== bestMatch) {
                    /* If coords are empty and element is not the best one,
                            no need to continue parsing info for cacheLimitProvider. Skip to next element. */
                    continue
                }

                //Get road names
                val tags = element.tags
                limitResponse = limitResponse.copy(roadName = parseOsmRoadName(tags))

                //Get speed limit
                val maxspeed = tags.maxspeed
                val maxspeedConditional = tags.maxspeedConditional
                val maxspeedVariable = tags.maxspeedVariable

                if (maxspeedVariable != null && !maxspeedVariable.equals("no")) {
                    limitResponse = limitResponse.copy(speedLimitVariable = true)
                }
                if (maxspeed != null) {
                    limitResponse = limitResponse.copy(speedLimitNormal = parseOsmSpeedLimit(maxspeed))
                }
                if (maxspeedConditional != null) {
                    limitResponse = limitResponse.copy(speedLimitConditional = parseOsmSpeedLimitConditional(maxspeedConditional))
                }

                val response = limitResponse.initDebugInfo(debuggingEnabled)

                //Cache
                cacheLimitProvider.put(response)

                if (element === bestMatch) {
                    bestResponse = response
                }
            }

            if (bestResponse != null) {
                return listOf(bestResponse)
            }

            return emptyObservableResponse()

        } catch (e: Exception) {
            return listOf(
                    limitResponse.copy(error = e).initDebugInfo(debuggingEnabled)
            )
        }
    }

    private fun parseOsmRoadName(tags: Tags): String {
        val ref = tags.ref
        val name = tags.name
        return if (ref == null && name == null) {
            "null"
        } else if (name != null && ref != null) {
            "$ref$ROADNAME_DELIM$name"
        } else name ?: ref
    }

    private fun parseOsmSpeedLimit(maxspeed: String): Int {
        var speedLimit = -1
        if (maxspeed.matches("^-?\\d+$".toRegex())) {
            //If it is an integer, it is in km/h
            speedLimit = Integer.valueOf(maxspeed)
        } else if (maxspeed.contains("mph")) {
            val split = maxspeed.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            speedLimit = Integer.valueOf(split[0])
            speedLimit = Utils.convertMphToKmh(speedLimit)
        }

        return speedLimit
    }

    private fun parseOsmSpeedLimitConditional(maxspeedConditional: String): LimitResponse.SpeedLimitConditional? {
        val split = maxspeedConditional.split("@")
        val maxspeed = split.first().trim()
        val speedLimitConditional = parseOsmSpeedLimit(maxspeed)
        var resp = LimitResponse.SpeedLimitConditional(speedLimit = speedLimitConditional)

        var condition = split.last().toString().replace("(", "").toString().replace(")", "").toString().trim().toString()
        if (!condition.contains("-")) {
            return null
        }

        if (!condition[0].isDigit()) {
            val day1 = condition.substring(0, 2).toLowerCase().replace("tu", "di").replace("we", "mi").replace("th", "do").replace("su", "so")
            var day2 = condition.substring(3, 5).toLowerCase().replace("tu", "di").replace("we", "mi").replace("th", "do").replace("su", "so")
            var cut = 5
            if (condition[2] == ' ') {
                day2 = condition.substring(5, 7)
                cut = 7
            }

            val days = arrayOf("mo", "di", "mi", "do", "fr", "sa", "so")
            resp = resp.copy(day1 = findPos(days, 0, day1))
            resp = resp.copy(day2 = findPos(days, 0, day2))

            condition = condition.substring(cut, condition.length).trim()
        }

        val time = condition.split(",").first().trim().split("-")

        val time1 = time.first().trim().split(":")
        val time2 = time.last().trim().split(":")
        try {
            resp = resp.copy(h1 = Integer.valueOf(time1.first().trim()))
            resp = resp.copy(min1 = Integer.valueOf(time1.last().trim()))
            resp = resp.copy(h2 = Integer.valueOf(time2.first().trim()))
            resp = resp.copy(min2 = Integer.valueOf(time2.last().trim()))
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        return resp
    }

    private fun findPos(values: Array<String>, start: Int, v: String): Int {
        if (start < 0)
            return -1

        for (i in start..values.size) {
            if (values[i].equals(v, ignoreCase = true)) {
                return i
            }
        }

        return -1
    }

    private fun getBestElement(elements: List<Element>, lastResponse: LimitResponse?): Element? {
        var bestElement: Element? = null
        var fallback: Element? = null

        if (lastResponse != null) {
            for (newElement in elements) {
                val newTags = newElement.tags
                if (fallback == null && newTags.maxspeed != null) {
                    fallback = newElement
                }
                if (lastResponse.roadName == parseOsmRoadName(newTags)) {
                    bestElement = newElement
                    break
                }
            }
        }

        if (bestElement == null) {
            bestElement = fallback ?: elements[0]
        }
        return bestElement
    }

    private fun logOsmRequest(endpoint: OsmApiEndpoint) {
        if (!BuildConfig.DEBUG) {
            val endpointString = Uri.parse(endpoint.baseUrl).authority!!
                    .replace(".", "_")
                    .replace("-", "_")
            val key = "osm_request_$endpointString"
            FirebaseAnalytics.getInstance(context).logEvent(key, Bundle())
        }
    }

    private fun logOsmError(endpoint: OsmApiEndpoint, throwable: Throwable) {
        if (!BuildConfig.DEBUG) {
            if (throwable is IOException) {
                val endpointString = Uri.parse(endpoint.baseUrl).authority!!
                        .replace(".", "_")
                        .replace("-", "_")
                val key = "osm_error_$endpointString"
                FirebaseAnalytics.getInstance(context).logEvent(key, Bundle())
            }

            FirebaseCrashlytics.getInstance().recordException(throwable)
        }
    }

    companion object {
        const val FB_CONFIG_OSM_APIS = "osm_apis"
        const val FB_CONFIG_OSM_API_ENABLED_PREFIX = "osm_api"

        const val OSM_RADIUS = 15
        const val ROADNAME_DELIM = "`"
    }

    init {
        osmOverpassApis = ArrayList()
        var endpointUrl = "https://overpass.kumi.systems/api/"
        val resId = context.resources.getIdentifier("overpass_api", "string", context.packageName)
        if (resId != 0) {
            endpointUrl = context.getString(resId)
        } else {
            Timber.d("Private overpass_api not set")
        }
        val endpoint = OsmApiEndpoint(endpointUrl)
        initializeOsmService(endpoint)
        osmOverpassApis.add(endpoint)
        refreshApiEndpoints()
    }

}
