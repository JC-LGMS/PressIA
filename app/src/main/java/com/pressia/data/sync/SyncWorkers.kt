package com.pressia.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.pressia.data.PressRepository
import com.pressia.data.isSyncFresh
import com.pressia.data.local.Prefs
import com.pressia.di.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sync périodique façon Press : même contenu que toute synchro (file montante, articles,
 * tableau « Read later »), planifiée, Wi-Fi only en option.
 *
 * Même règle anti-doublon qu'au lancement : si une synchro s'est bien finie il y a moins de
 * X minutes (X = « Fréquence de synchronisation »), la tâche se termine sans rien faire — la
 * flèche ne tourne pas pour rien. Un saut est un succès, jamais un nouvel essai.
 *
 * Fraîcheur revérifiée sous le verrou partagé ([PressRepository.withSyncLock]) : si le
 * lancement synchronise au même moment, un seul des deux télécharge, l'autre constate un
 * cache frais et saute son tour — un seul déclenchement effectif.
 */
class SyncWorker(appCtx: Context, p: WorkerParameters) : CoroutineWorker(appCtx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        return@withContext try {
            val repo: PressRepository = Locator.repo(applicationContext)
            repo.withSyncLock {
                val last = repo.prefs.data.first()[Prefs.LAST_SYNC_AT] ?: 0L
                val windowMin = repo.prefs.data.first()[Prefs.SYNC_INTERVAL_MIN] ?: Prefs.SYNC_INTERVAL_DEFAULT
                if (isSyncFresh(last, System.currentTimeMillis(), windowMin)) {
                    android.util.Log.i("PressIA", "sync planifiée sautée (dernière synchro récente)")
                } else {
                    // Même pipeline que la synchro manuelle : les étapes partent dans
                    // repo.syncStep (bandeau + barre si l'app est ouverte), puis on referme.
                    try {
                        repo.syncPipeline()
                    } finally {
                        repo.syncStep.value = null
                    }
                }
            }
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
