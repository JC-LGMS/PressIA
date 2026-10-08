package com.pressia.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Miniatures d'articles stockées en local, à la taille affichée — jamais l'original full-size.
 *
 * La plus grande miniature utilisée dans les listes est la bannière de la vue magazine
 * (largeur d'écran × 180 dp) : c'est ce gabarit qui borne ce qui est enregistré. Les vues
 * carte (72 dp) et liste (56 dp) se contentent de réduire ce même fichier à l'affichage.
 *
 * Un fichier par URL (`SHA-1(url) + ".jpg"`) : plusieurs articles partageant la même image
 * ne la téléchargent qu'une fois. La colonne [ArticleEntity.imageCached] dit si le fichier
 * est présent ; elle est remise à zéro quand le cache est vidé, pour que la synchronisation
 * suivante récupère tout.
 */
object ThumbStore {
    const val DIR = "thumbs"
    private const val BANNER_DP_HEIGHT = 180
    private const val JPEG_QUALITY = 85
    /** Plafond d'un original : au-delà, on saute (une vignette pèse ~100 Ko). */
    const val MAX_SOURCE_BYTES = 10 * 1024 * 1024

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Nom de fichier déterministe pour une URL : testable sans Android. */
    fun thumbFileName(url: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        return sha1.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /**
     * Facteur de sous-échantillonnage (puissance de 2) qui fait tenir une image source dans
     * le gabarit. Testable sans Android.
     */
    fun sampleSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return 1
        var s = 1
        while (srcW / (s * 2) >= maxW && srcH / (s * 2) >= maxH) s *= 2
        return s
    }

    /** Dimensions finales (ratio conservé) d'une image source calée dans le gabarit. */
    fun fitSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return 1 to 1
        val scale = minOf(maxW / srcW.toDouble(), maxH / srcH.toDouble(), 1.0)
        return maxOf(1, (srcW * scale).toInt()) to maxOf(1, (srcH * scale).toInt())
    }

    /** Gabarit maximal : largeur d'écran × hauteur de bannière magazine, en pixels. */
    fun maxThumbSize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to (BANNER_DP_HEIGHT * dm.density).toInt().coerceAtLeast(1)
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    fun fileFor(ctx: Context, url: String): File = File(dir(ctx), thumbFileName(url))

    /** Fichier local si la miniature est déjà stockée, `null` sinon. */
    fun cachedFile(ctx: Context, url: String): File? =
        fileFor(ctx, url).takeIf { it.exists() }

    /**
     * Télécharge [url], la réduit au gabarit et l'enregistre. Renvoie `true` si le fichier
     * est désormais présent (y compris s'il l'était déjà).
     */
    suspend fun fetch(ctx: Context, url: String, maxW: Int, maxH: Int): Boolean =
        withContext(Dispatchers.IO) {
            val target = fileFor(ctx, url)
            if (target.exists()) return@withContext true
            try {
                val bytes = http.newCall(Request.Builder().url(url).get().build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext false
                        val body = resp.body ?: return@withContext false
                        // Plafond : charger 10 Mo en RAM pour une vignette de ~100 Ko ne sert à rien.
                        readCapped(body.byteStream(), MAX_SOURCE_BYTES)?.takeIf { it.isNotEmpty() }
                            ?: return@withContext false
                    }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: return@withContext false
                val (w, h) = fitSize(decoded.width, decoded.height, maxW, maxH)
                val scaled = if (decoded.width == w && decoded.height == h) decoded
                else Bitmap.createScaledBitmap(decoded, w, h, true).also {
                    if (it != decoded) decoded.recycle()
                }
                // Le JPEG ne connaît pas la transparence : on aplatit sur fond blanc plutôt
                // que de laisser un fond noir aux logos PNG.
                val opaque = if (scaled.hasAlpha()) {
                    Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.RGB_565).also { out ->
                        val c = Canvas(out)
                        c.drawColor(Color.WHITE)
                        c.drawBitmap(scaled, 0f, 0f, null)
                    }.also { scaled.recycle() }
                } else scaled
                val d = dir(ctx)
                if (!d.exists()) d.mkdirs()
                val tmp = File(d, target.name + ".tmp")
                try {
                    tmp.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    if (!tmp.renameTo(target)) { tmp.delete(); return@withContext false }
                } finally {
                    tmp.delete()
                    opaque.recycle()
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    /** Lit au plus [cap] octets, `null` si le corps dépasse. Pur JVM, testé en unitaire. */
    fun readCapped(stream: java.io.InputStream, cap: Int): ByteArray? {
        if (cap <= 0) return null
        val out = java.io.ByteArrayOutputStream(minOf(cap, 256 * 1024))
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Taille occupée par les miniatures, en octets. */
    suspend fun sizeBytes(ctx: Context): Long = withContext(Dispatchers.IO) {
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Supprime toutes les miniatures (vidage du cache : le suivi base est réinitialisé à part). */
    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Supprime les fichiers orphelins (articles purgés ou compte supprimé). [keepNames] sont
     * les noms attendus, via [thumbFileName] ; les `.tmp` abandonnés partent dans tous les cas.
     */
    suspend fun gc(ctx: Context, keepNames: Set<String>): Int = withContext(Dispatchers.IO) {
        var n = 0
        dir(ctx).listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || (f.name.endsWith(".jpg") && f.name !in keepNames)) {
                if (f.delete()) n++
            }
        }
        n
    }
}
