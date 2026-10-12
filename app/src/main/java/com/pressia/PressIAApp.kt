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
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                .respectCacheHeaders(false)
                .build()
        }
        // Plus de synchronisation planifiée : l'app ne synchronise que lorsqu'elle est
        // ouverte. On annule un éventuel travail hérité d'une version précédente.
        WorkManager.getInstance(this).cancelAllWorkByTag(LEGACY_SYNC_TAG)
    }
}
