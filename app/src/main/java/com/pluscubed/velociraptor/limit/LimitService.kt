package com.pluscubed.velociraptor.limit

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.google.android.gms.location.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.pluscubed.velociraptor.BuildConfig
import com.pluscubed.velociraptor.R
import com.pluscubed.velociraptor.api.LimitFetcher
import com.pluscubed.velociraptor.api.LimitResponse
import com.pluscubed.velociraptor.api.raptor.RaptorLimitProvider
import com.pluscubed.velociraptor.billing.BillingConstants
import com.pluscubed.velociraptor.billing.BillingManager
import com.pluscubed.velociraptor.settings.SettingsActivity
import com.pluscubed.velociraptor.utils.NotificationUtils
import com.pluscubed.velociraptor.utils.PrefUtils
import com.pluscubed.velociraptor.utils.Utils
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.*

class LimitService : LifecycleService() {
    private var speedLimitViewType = -1
    private var speedLimitView: LimitView? = null

    private var debuggingRequestInfo: String? = null

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var speedLimitJob: Job? = null

    private var currentLimitResponse: LimitResponse? = null
    private var lastLocationWithSpeed: Location? = null
    private var lastLocationWithFetchAttempt: Location? = null

    private var beepLimit: Int = 0
    private var speedingStartTimestamp: Long = -1
    private var limitFetcher: LimitFetcher? = null

    private var isRunning: Boolean = false
    private var isStartedFromNotification: Boolean = false
    private var isLimitHidden: Boolean = false

    private var billingManager: BillingManager? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startNotification()
    }

    @SuppressLint("InflateParams")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (!isStartedFromNotification && intent.getBooleanExtra(
                            EXTRA_CLOSE,
                            false
                    ) || intent.getBooleanExtra(EXTRA_NOTIF_CLOSE, false)
            ) {
                onStop()
                stopSelf()
                return super.onStartCommand(intent, flags, startId)
            }

            val viewType = intent.getIntExtra(EXTRA_VIEW, VIEW_FLOATING)
            if (viewType != speedLimitViewType) {
                speedLimitViewType = viewType

                when (speedLimitViewType) {
                    VIEW_FLOATING -> speedLimitView = FloatingView(this)
                }
            }

            if (intent.extras != null && intent.extras!!.containsKey(EXTRA_HIDE_LIMIT)) {
                isLimitHidden = intent.getBooleanExtra(EXTRA_HIDE_LIMIT, false)
                speedLimitView?.hideLimit(isLimitHidden)
                if (isLimitHidden) {
                    currentLimitResponse = null;
                }
            }

            if (intent.getBooleanExtra(EXTRA_NOTIF_START, false)) {
                isStartedFromNotification = true
            } else if (intent.getBooleanExtra(EXTRA_PREF_CHANGE, false)) {
                speedLimitView?.updatePrefs()

                forceRefetch()
                updateLimitView(false)
                updateSpeedometer(lastLocationWithSpeed)
            }
        }

        if (isRunning || !prequisitesMet() || speedLimitView == null)
            return super.onStartCommand(intent, flags, startId)

        isRunning = true

        debuggingRequestInfo = ""

        limitFetcher = LimitFetcher(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationRequest = LocationRequest()
        locationRequest.interval = 1000
        locationRequest.fastestInterval = 0
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                super.onLocationResult(locationResult)
                onLocationChanged(locationResult!!.lastLocation)
            }
        }

        try {
            fusedLocationClient!!.requestLocationUpdates(
                    locationRequest,
                    locationCallback!!,
                    Looper.myLooper()
            )
        } catch (unlikely: SecurityException) {
        }

        billingManager = BillingManager(this, object : BillingManager.BillingUpdatesListener {
            override fun onBillingClientSetupFinished() {
                if (RaptorLimitProvider.USE_DEBUG_ID) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        verifyPurchase(
                                Purchase(
                                        """{"productId": "debug", "purchaseToken": "debug"}""",
                                        ""
                                )
                        )
                    }
                }
            }

            override fun onConsumeFinished(token: String, result: Int) {

            }

            override fun onPurchasesUpdated(purchases: List<Purchase>) {
                val purchased = HashSet<Purchase>()
                val onetime = BillingConstants.getSkuList(BillingClient.SkuType.INAPP)
                for (purchase in purchases) {
                    if (purchase.sku in onetime) {
                        try {
                            billingManager?.consumeAsync(purchase.purchaseToken);
                        } catch (e: Exception) {
                            FirebaseCrashlytics.getInstance().recordException(e)
                        }
                    } else if (purchase.sku == BillingConstants.SKU_HERE || purchase.sku == BillingConstants.SKU_TOMTOM) {
                        purchased.add(purchase)
                    }
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    verifyPurchase(*purchased.toTypedArray())
                }
            }
        })

        val remoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig.fetch().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                remoteConfig.activate()
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun verifyPurchase(vararg purchases: Purchase) {
        try {
            var updated = false
            purchases.forEach { purchase ->
                try {
                    val res = limitFetcher?.verifyRaptorService(purchase) ?: false
                    if (res)
                        updated = true
                } catch (e: Exception) {
                    FirebaseCrashlytics.getInstance().recordException(e)
                }
            }

            if (updated) {
                forceRefetch()
            }
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    private fun forceRefetch() {
        // Force refetch
        speedLimitJob?.cancel()
        lastLocationWithFetchAttempt = null
    }

    private fun startNotification() {
        val notificationIntent = Intent(this, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
                this,
                PENDING_SETTINGS,
                notificationIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
        )

        NotificationUtils.initChannels(this)
        val notification = NotificationCompat.Builder(this, NotificationUtils.CHANNEL_RUNNING)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_content))
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_speedometer_notif)
                .setContentIntent(pendingIntent)
                .build()
        startForeground(NOTIFICATION_FOREGROUND, notification)
    }

    private fun prequisitesMet(): Boolean {
        if (!PrefUtils.isTermsAccepted(this)) {
            if (BuildConfig.VERSION_CODE > PrefUtils.getVersionCode(this)) {
                showWarningNotification(R.string.terms_warning)
            }
            stopSelf()
            return false
        } else {
            dismissWarningNotification(R.string.terms_warning)
        }

        if (!Utils.isLocationPermissionGranted(this@LimitService) ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)
        ) {
            showWarningNotification(R.string.permissions_warning)
            stopSelf()
            return false
        } else {
            dismissWarningNotification(R.string.permissions_warning)
        }

        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showWarningNotification(R.string.location_settings_warning)
        } else {
            dismissWarningNotification(R.string.location_settings_warning)
        }

        val isConnected = Utils.isNetworkConnected(this)
        if (!isConnected) {
            showWarningNotification(R.string.network_warning)
        } else {
            dismissWarningNotification(R.string.network_warning)
        }
        return true
    }

    @Synchronized
    private fun onLocationChanged(location: Location) {
        updateSpeedometer(location)
        updateDebuggingText(location, null, null)

        val speedLimitInactive = speedLimitJob == null || !speedLimitJob!!.isActive
        val showLimits = PrefUtils.getShowLimits(this@LimitService)
        val farFromLastLocation = lastLocationWithFetchAttempt == null ||
                location.distanceTo(lastLocationWithFetchAttempt) > 10

        if (speedLimitInactive && !isLimitHidden && showLimits && farFromLastLocation) {
            speedLimitJob = lifecycleScope.launch {
                try {
                    val limitResponse =
                            withContext(Dispatchers.IO) { limitFetcher!!.getSpeedLimit(location) }

                    if (!limitResponse.isEmpty) {
                        currentLimitResponse = limitResponse
                        updateLimitView(true)
                    } else {
                        updateLimitView(false)
                    }

                    updateDebuggingText(location, limitResponse, null)

                    lastLocationWithFetchAttempt = location
                } catch (ignore: CancellationException) {
                } catch (e: Exception) {
                    Timber.d(e)

                    updateLimitView(false)
                    updateDebuggingText(location, null, e)

                    lastLocationWithFetchAttempt = location
                }

            }
        }
    }

    private fun updateDebuggingText(
            location: Location,
            limitResponse: LimitResponse?,
            error: Throwable?
    ) {
        if (!PrefUtils.isDebuggingEnabled(this)) {
            debuggingRequestInfo = ""
            return
        }

        var text = "Location: $location\n"

        if (lastLocationWithFetchAttempt != null) {
            text += "Time since: " + (System.currentTimeMillis() - lastLocationWithFetchAttempt!!.time) + "\n"
        }

        if (error == null && limitResponse != null) {
            debuggingRequestInfo = limitResponse.debugInfo
        } else if (error != null) {
            debuggingRequestInfo = "Catastrophic error: $error"
        }


        text += debuggingRequestInfo
        text += "\n\nYou can turn off this window in the Velociraptor app"
        speedLimitView?.setDebuggingText(text)
    }

    private fun updateLimitView(success: Boolean) {
        var text = "--"
        val speedLimit = getCurrentSpeedLimit()
        if (speedLimit != -1) {
            text = convertToUiSpeed(speedLimit).toString()
            if (!success) {
                text = "($text)"
            }

            val provider = currentLimitResponse?.origin ?: LimitResponse.ORIGIN_INVALID
            val providerString = LimitResponse.getLimitProviderString(provider)
            speedLimitView?.setLimit(text, providerString)
        } else {
            speedLimitView?.setLimit(text, "")
        }
    }

    private fun updateSpeedometer(location: Location?) {
        if (location == null || !location.hasSpeed()) {
            return
        }

        val metersPerSeconds = location.speed

        val kmhSpeed = Math.round(metersPerSeconds.toDouble() * 60.0 * 60.0 / 1000).toInt()
        val speedometerPercentage = Math.round(kmhSpeed.toFloat() / 240 * 100)

        val percentToleranceFactor = 1 + PrefUtils.getSpeedingPercent(this).toFloat() / 100
        val constantTolerance = PrefUtils.getSpeedingConstant(this)

        val currentSpeedLimit = getCurrentSpeedLimit()
        val percentToleratedLimit = (currentSpeedLimit * percentToleranceFactor).toInt()
        val warningLimit: Int
        if (PrefUtils.getToleranceMode(this)) {
            warningLimit = percentToleratedLimit + constantTolerance
        } else {
            warningLimit = Math.min(percentToleratedLimit, currentSpeedLimit + constantTolerance)
        }

        if (currentSpeedLimit != -1 && kmhSpeed > warningLimit) {
            speedLimitView?.setSpeeding(true)

            val currentTimeMillis = System.currentTimeMillis()
            val beepEnabled = PrefUtils.isBeepAlertEnabled(this)

            if (speedingStartTimestamp == -1L) {
                speedingStartTimestamp = currentTimeMillis
            }

            if (beepEnabled) {
                if (currentTimeMillis > speedingStartTimestamp + 1000L && beepEnabled && beepLimit == 0) {
                    Utils.playBeeps()
                    beepLimit += 1
                    speedingStartTimestamp = currentTimeMillis
                } else if (currentTimeMillis > speedingStartTimestamp + 3000L && beepEnabled && beepLimit < 5) {
                    Utils.playBeeps()
                    beepLimit += 1
                    speedingStartTimestamp = currentTimeMillis
                } else if (currentTimeMillis > speedingStartTimestamp + 5000L && beepEnabled && beepLimit < 10) {
                    Utils.playBeeps()
                    beepLimit += 1
                    speedingStartTimestamp = currentTimeMillis
                } else if (currentTimeMillis > speedingStartTimestamp + 30000L && beepEnabled) {
                    Utils.playBeeps()
                    beepLimit += 1
                    speedingStartTimestamp = currentTimeMillis
                }
            }
        } else {
            speedLimitView?.setSpeeding(false)
            speedingStartTimestamp = -1

            if (beepLimit > 0) {
                beepLimit = 0
                Utils.playGoodBeeps()
            }
        }

        speedLimitView?.setSpeed(convertToUiSpeed(kmhSpeed), speedometerPercentage)

        lastLocationWithSpeed = location
    }

    private fun getCurrentSpeedLimit() = currentLimitResponse?.speedLimit ?: -1

    private fun convertToUiSpeed(kmhSpeed: Int): Int {
        var speed = kmhSpeed
        if (!PrefUtils.getUseMetric(this)) {
            speed = Utils.convertKmhToMph(speed)
        }
        return speed
    }

    internal fun showWarningNotification(stringRes: Int) {
        val notificationIntent = Intent(this, SettingsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
                this,
                PENDING_SETTINGS,
                notificationIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
        )

        NotificationUtils.initChannels(this)
        val notificationText = getString(stringRes)
        val notification = NotificationCompat.Builder(this, NotificationUtils.CHANNEL_WARNINGS)
                .setContentTitle(getString(R.string.warning_notif_title))
                .setContentText(notificationText)
                .setPriority(Notification.PRIORITY_LOW)
                .setSmallIcon(R.drawable.ic_speedometer_notif)
                .setContentIntent(pendingIntent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
                .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(stringRes, notification)
    }

    internal fun dismissWarningNotification(stringRes: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(stringRes)
    }

    override fun onDestroy() {
        onStop()
        super.onDestroy()
    }

    private fun onStop() {
        if (fusedLocationClient != null) {
            try {
                fusedLocationClient!!.removeLocationUpdates(locationCallback!!)
            } catch (ignore: SecurityException) {
            }
        }

        if (speedLimitView != null)
            speedLimitView!!.stop()


        if (billingManager != null)
            billingManager!!.destroy()

        isRunning = false;
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (speedLimitView != null)
            speedLimitView!!.changeConfig()
    }

    companion object {
        const val PENDING_SETTINGS = 5

        const val EXTRA_NOTIF_START = "com.pluscubed.velociraptor.EXTRA_NOTIF_START"
        const val EXTRA_NOTIF_CLOSE = "com.pluscubed.velociraptor.EXTRA_NOTIF_CLOSE"
        const val EXTRA_CLOSE = "com.pluscubed.velociraptor.EXTRA_CLOSE"
        const val EXTRA_PREF_CHANGE = "com.pluscubed.velociraptor.EXTRA_PREF_CHANGE"

        const val EXTRA_VIEW = "com.pluscubed.velociraptor.EXTRA_VIEW"
        const val VIEW_FLOATING = 0

        const val EXTRA_HIDE_LIMIT = "com.pluscubed.velociraptor.HIDE_LIMIT"
        private const val NOTIFICATION_FOREGROUND = 303
    }

}
