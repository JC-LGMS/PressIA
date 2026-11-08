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
                // Images du corps et favicons : 30 s au total (une originale de 5 Mo met plus de
                // 10 s quand six descendent ensemble), mais plafonnées à la taille des
                // miniatures — au-delà, l'image est coupée et dite « image trop lourde ».
                .okHttpClient {
                    okhttp3.OkHttpClient.Builder()
                        .addInterceptor(
                            com.pressia.data.local.imageCapInterceptor(
                                com.pressia.data.local.ThumbStore.MAX_SOURCE_BYTES.toLong()
                            )
                        )
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                // SVG (logos de flux, illustrations) : sans ce décodeur Coil les ignore.
                .components { add(coil.decode.SvgDecoder.Factory()) }
                .respectCacheHeaders(false)
                .build()
        }
        // Plus de synchronisation planifiée : l'app ne synchronise que lorsqu'elle est
        // ouverte. On annule un éventuel travail hérité d'une version précédente.
        WorkManager.getInstance(this).cancelAllWorkByTag(LEGACY_SYNC_TAG)
    }
}
