package com.pressia

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache

class PressIAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader {
            ImageLoader.Builder(this)
                // Images du corps et favicons : mêmes délais que `ThumbStore` — 5 s pour
                // joindre l'hôte ou pour le moindre octet, 20 s en tout pour une image qui
                // descend lentement — et plafonnées à la taille des miniatures : au-delà,
                // l'image est coupée et dite « image trop lourde ».
                .okHttpClient {
                    okhttp3.OkHttpClient.Builder()
                        .addInterceptor(
                            com.pressia.data.local.imageCapInterceptor(
                                com.pressia.data.local.ThumbStore.MAX_SOURCE_BYTES.toLong()
                            )
                        )
                        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                // SVG (logos de flux, illustrations) : sans ce décodeur Coil les ignore.
                .components { add(coil.decode.SvgDecoder.Factory()) }
                .respectCacheHeaders(false)
                .build()
        }
    }
}
