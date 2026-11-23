package com.pressia.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pressia.data.PressRepository
import com.pressia.data.isSyncFresh
import com.pressia.data.local.Prefs
import com.pressia.di.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Synchronisation en arrière-plan : Android la réveille toutes les X heures (réglage
 * « Synchronisation en arrière-plan »), application fermée comprise.
 *
 * Même pipeline que le bouton et le lancement ([PressRepository.syncPipeline]) : file montante,
 * nouveaux articles, images et tableau « Read later ». Rien ne s'affiche — personne n'est à
 * l'écran — et la passe saute si une synchronisation s'est bien finie il y a moins de X heures :
 * l'application ouverte a déjà fait le travail.
 */
class BackgroundSyncWorker(appCtx: Context, p: WorkerParameters) : CoroutineWorker(appCtx, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repo = Locator.repo(applicationContext)
            // Un processus démarré uniquement par le worker ne crée pas `PressVM` : sans cet
            // appel, les identifiants Inoreader saisis par l'utilisateur seraient ignorés et
            // l'API répondrait 403 (compte déclaré expiré à tort).
            repo.refreshInoreaderAppAuth()
            val hours = repo.prefs.flow(Prefs.BACKGROUND_SYNC_HOURS, 0).first()
            val last = repo.prefs.flow(Prefs.LAST_SYNC_AT, 0L).first()
            if (hours in 1..24 && repo.hasSources() && repo.isOnline() &&
                !isSyncFresh(last, System.currentTimeMillis(), hours * 60)
            ) {
                // Verrou partagé sans attendre : une synchro manuelle en cours a déjà la
                // réponse, celle-ci n'a rien à y ajouter.
                repo.tryWithSyncLock { repo.syncPipeline() }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Réseau, jeton expiré, service en vrac : la prochaine échéance reprendra.
            android.util.Log.i("PressIA", "synchro de fond en échec : ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val TAG = "pressia-background-sync"

        /**
         * (Re)planifie la synchro de fond : [hours] hors de 1..24 = aucune (travail annulé),
         * sinon toutes ces heures. Appelé au démarrage et à chaque changement de réglage.
         * « Wi-Fi uniquement » devient une contrainte du travail : hors Wi-Fi, il attend.
         */
        fun schedule(ctx: Context, hours: Int, wifiOnly: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (hours !in 1..24) {
                wm.cancelUniqueWork(TAG)
                return
            }
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .addTag(TAG)
                .build()
            wm.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
