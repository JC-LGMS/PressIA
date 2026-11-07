package com.pressia

import android.app.Application
import androidx.work.WorkManager
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache

/** Étiquette du travail planifié hérité d'anciennes versions (à annuler, plus rien ne planifie). */
private const val LEGACY_SYNC_TAG = "pressia-sync"

class PressIAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader {
            ImageLoader.Builder(this)
                // Images du corps et favicons : même plafond que les miniatures (10 s), sinon
                // une image qui traîne retient la synchro bien plus longtemps.
                .okHttpClient {
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                .respectCacheHeaders(false)
                .build()
        }
        // Plus de synchronisation planifiée : l'app ne synchronise que lorsqu'elle est
        // ouverte. On annule un éventuel travail hérité d'une version précédente.
        WorkManager.getInstance(this).cancelAllWorkByTag(LEGACY_SYNC_TAG)
    }
}
