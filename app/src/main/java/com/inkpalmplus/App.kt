package com.inkpalmplus

import android.app.Application

/** Gives [Eink] the files dir the KompaktOS bridge watches, before any service, tile or receiver needs it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Eink.init(this)
    }
}
