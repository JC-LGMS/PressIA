package com.pressia.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.pressia.data.PressRepository
import com.pressia.di.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Sync périodique façon Press : planifiée, Wi-Fi only en option, pousse la file puis tire le flux. */
class SyncWorker(appCtx: Context, p: WorkerParameters) : CoroutineWorker(appCtx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val repo: PressRepository = Locator.repo(applicationContext)
            repo.pushQueue()
            repo.syncDown()
            // Le tableau « Read later » fait partie des favoris affichés : il suit la même rotation.
            // syncDown et syncReadLater téléchargent déjà les miniatures manquantes (syncThumbs).
            repo.syncReadLater()
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }

    companion object {
        const val TAG = "pressia-sync"
        fun schedule(ctx: Context, intervalMin: Int, wifiOnly: Boolean) {
            WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG)
            if (intervalMin <= 0) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMin.toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints).addTag(TAG).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

fun isWifiOnlyBlocked(ctx: Context, wifiOnly: Boolean): Boolean {
    if (!wifiOnly) return false
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
    return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
