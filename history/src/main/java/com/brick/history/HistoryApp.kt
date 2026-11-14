package com.brick.history

import android.app.Application
import com.brick.history.data.CleanupJob
import com.brick.history.data.Palettes
import com.brick.history.data.Store

class HistoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Palettes.init(this)
        CleanupJob.schedule(this)
        // The rules may have come due while the app was closed.
        Thread { Store.cleanup() }.apply { isDaemon = true }.start()
    }
}
