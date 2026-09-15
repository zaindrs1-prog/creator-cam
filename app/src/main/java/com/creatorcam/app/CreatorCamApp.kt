package com.creatorcam.app

import android.app.Application
import android.os.StrictMode
import com.creatorcam.app.util.Logger

/**
 * Application entry point.
 *
 * Offline-first by design: no backend, no analytics SDK, no account system.
 * Everything the camera needs runs on-device.
 */
class CreatorCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
        Logger.i("App", "CreatorCam v${BuildConfig.VERSION_NAME} starting (debug=${BuildConfig.DEBUG})")
    }
}
