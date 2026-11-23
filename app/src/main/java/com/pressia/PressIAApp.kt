package com.pressia

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
import com.pressia.data.local.Prefs
import com.pressia.data.sync.BackgroundSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PressIAApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Synchro de fond : replanifiée à chaque démarrage (une sauvegarde restaurée réinstalle
        // le réglage sans le travail, et l'échéance doit survivre aux mises à jour).
        CoroutineScope(Dispatchers.IO).launch {
            // Un échec (DataStore illisible) resterait sinon invisible : personne n'attend ce
            // scope, ni bandeau ni log — on ne saurait pas que la synchro de fond n'est plus
            // planifiée.
            runCatching {
                val p = Prefs(this@PressIAApp)
                BackgroundSyncWorker.schedule(
                    this@PressIAApp,
                    p.data.first()[Prefs.BACKGROUND_SYNC_HOURS] ?: 0,
                    p.data.first()[Prefs.WIFI_ONLY] ?: true
                )
            }.onFailure {
                android.util.Log.w("PressIA", "synchro de fond non replanifiée : ${it.message}")
            }
        }
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
                        // Le vrai plafond d'une passe d'images est ici, pas dans le `Semaphore`
                        // de `syncImages` : sans répartiteur propre, OkHttp n'exécute que
                        // cinq requêtes par hôte et met les autres en file — une image lente
                        // d'un même CDN retardait toutes les suivantes.
                        .dispatcher(
                            okhttp3.Dispatcher().apply {
                                maxRequests = 32
                                maxRequestsPerHost = 8
                            }
                        )
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
