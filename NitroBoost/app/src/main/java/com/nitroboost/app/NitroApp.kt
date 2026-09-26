package com.nitroboost.app

import android.app.Application

class NitroApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
    }
}
