package com.utaloom.android

import android.app.Application
import com.utaloom.data.Paths
import com.utaloom.ui.applySession

class UtaloomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Paths.initialize(filesDir)
        System.setProperty("utaloom.version", BuildConfig.VERSION_NAME)
        applySession()
    }

    companion object {
        lateinit var instance: UtaloomApplication
            private set
    }
}
