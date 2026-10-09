package com.pressia

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import com.pressia.data.local.Prefs
import com.pressia.data.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PressIAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader {
            ImageLoader.Builder(this)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                .respectCacheHeaders(false)
                .build()
        }
        CoroutineScope(Dispatchers.IO).launch {
            val p = Prefs(this@PressIAApp)
            val interval = p.data.first()[Prefs.SYNC_INTERVAL_MIN] ?: Prefs.SYNC_INTERVAL_DEFAULT
            val wifi = p.data.first()[Prefs.WIFI_ONLY] ?: true
            SyncWorker.schedule(this@PressIAApp, interval, wifi)
        }
    }
}
