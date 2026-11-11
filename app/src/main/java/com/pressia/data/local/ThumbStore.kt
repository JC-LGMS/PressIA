package com.pressia.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.pressia.data.exceedsCap
import com.pressia.data.readCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Corps d'image au-delà du plafond ([ThumbStore.MAX_SOURCE_BYTES]) : le vrai motif dans le
 *  détail de synchro. Les CDN servent des originales de 30 Mo, qui n'ont pas à descendre sur un
 *  téléphone — sans ce plafond la requête allait jusqu'au délai et s'annonçait « délai dépassé ». */
class ImageTooLargeException : IOException("image trop lourde")

/**
 * Intercepteur OkHttp (client de Coil) qui coupe le téléchargement dès que le plafond est
 * franchi. Le `Content-Length` manque souvent (réponses « chunked ») : le compteur compte les
 * octets lus, et l'annonce est vérifiée d'abord quand elle existe.
 */
fun imageCapInterceptor(cap: Long): okhttp3.Interceptor = okhttp3.Interceptor { chain ->
    val resp = chain.proceed(chain.request())
    val body = resp.body
    if (body == null) return@Interceptor resp
    if (body.contentLength() > cap) throw ImageTooLargeException()
    resp.newBuilder().body(object : okhttp3.ResponseBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source(): okio.BufferedSource =
            object : okio.ForwardingSource(body.source()) {
                private var read = 0L
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) {
                        read += n
                        if (read > cap) throw ImageTooLargeException()
                    }
                    return n
                }
            }.buffer()
    }).build()
}

/** Cause courte d'un échec de téléchargement d'image, telle qu'affichée dans le détail de fin de
 * synchro (« délai dépassé », « HTTP 404 »…). Pure.
 *
 * `InterruptedIOException` couvre le délai **total** d'un appel OkHttp (son message est
 * « timeout ») : une image lourde qui dépasse le plafond de temps était dite « connexion
 * impossible », alors que la connexion marchait. */
fun imageFailureCause(e: Throwable?): String = when {
    e is ImageTooLargeException -> "image trop lourde"
    e is InterruptedIOException -> "délai dépassé"
    e is UnknownHostException -> "hôte introuvable"
    e is SocketException -> "connexion coupée"
    e is IOException -> "connexion impossible"
    else -> "échec"
}

/** Le corps téléchargé est-il un SVG ? BitmapFactory ne sait pas le décoder, AndroidSVG si ;
 * un raster binaire ne contient pas « <svg » dans ses premiers octets. Pure, testée. */
fun isSvgImage(bytes: ByteArray): Boolean =
    bytes.take(512).toByteArray().toString(Charsets.ISO_8859_1).contains("<svg", ignoreCase = true)

/**
 * Miniatures d'articles stockées en local, à la taille affichée — jamais l'original full-size.
 *
 * Le gabarit est un **carré de la largeur d'écran** : c'est la plus grande taille à laquelle
 * ce fichier est affiché — image de tête de l'article, pleine largeur, et bannière de la vue
 * magazine (largeur d'écran × 180 dp, recadrée). Toute image plus large que haute est donc
 * enregistrée à la largeur exacte de l'écran, sans agrandissement à l'affichage ; une image
 * plus haute que large (rare dans les flux) est réduite au carré. Les vues carte (72 dp) et
 * liste (56 dp) se contentent de réduire ce même fichier à l'affichage.
 *
 * Un fichier par URL (`SHA-1(url) + ".jpg"`) : plusieurs articles partageant la même image
 * ne la téléchargent qu'une fois. La colonne [ArticleEntity.imageCached] dit si les images de
 * l'article sont présentes ; elle est remise à zéro quand le cache est vidé, pour que la
 * synchronisation suivante récupère tout.
 */
object ThumbStore {
    const val DIR = "thumbs"
    private const val JPEG_QUALITY = 85
    /** Plafond d'un original : au-delà, on saute (une vignette pèse ~100 Ko). */
    const val MAX_SOURCE_BYTES = 10 * 1024 * 1024

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Une image qui ne répond pas est coupée vite (5 s pour joindre l'hôte, 5 s sans
            // le moindre octet) ; une image qui répond mais descend lentement a 20 s en tout
            // (une originale de 5 Mo peut dépasser 5 s à six de front). L'image est comptée
            // dans le détail de fin de synchro (voir `imageFailures`), la passe n'attend pas.
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Nom de fichier déterministe pour une URL : testable sans Android. L'extension suit le
     * format écrit : `.jpg` par défaut, `.png` quand l'image a de la transparence (le JPEG la
     * perdrait).
     */
    fun thumbFileName(url: String, ext: String = "jpg"): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        return sha1.joinToString("") { "%02x".format(it) } + "." + ext
    }

    /** Les deux noms possibles d'une miniature (JPEG, ou PNG si l'image a de l'alpha). */
    fun thumbFileNames(url: String): List<String> = listOf(thumbFileName(url), thumbFileName(url, "png"))

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

    /**
     * Gabarit maximal : le plus grand affichage de ce fichier, en pixels — un carré de la
     * largeur d'écran (image de tête d'un article, pleine largeur ; la bannière magazine de
     * 180 dp est un recadrage de ce même fichier).
     */
    fun maxThumbSize(ctx: Context): Pair<Int, Int> {
        val w = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        return w to w
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR)

    fun fileFor(ctx: Context, url: String): File = File(dir(ctx), thumbFileName(url))

    /** Fichier local si la miniature est déjà stockée (JPEG, ou PNG transparent), `null` sinon. */
    fun cachedFile(ctx: Context, url: String): File? =
        thumbFileNames(url).firstNotNullOfOrNull { File(dir(ctx), it).takeIf { f -> f.exists() } }

    /**
     * Télécharge [url], la réduit au gabarit et l'enregistre. Renvoie `null` si le fichier
     * est désormais présent (y compris s'il l'était déjà), sinon la **cause** courte de
     * l'échec — le détail de fin de synchro la montre image par image.
     */
    suspend fun fetch(ctx: Context, url: String, maxW: Int, maxH: Int): String? =
        withContext(Dispatchers.IO) {
            if (cachedFile(ctx, url) != null) return@withContext null
            try {
                val bytes = http.newCall(Request.Builder().url(url).get().build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                        val body = resp.body ?: return@withContext "réponse vide"
                        // Taille annoncée au-delà du plafond : inutile de la télécharger.
                        if (exceedsCap(body.contentLength(), MAX_SOURCE_BYTES)) return@withContext "image trop lourde"
                        // Plafond : charger 10 Mo en RAM pour une vignette de ~100 Ko ne sert à rien.
                        // `readCapped` rend `null` au-delà du plafond (taille non annoncée) :
                        // l'appeler « image vide » masquait le vrai motif.
                        val bytes = readCapped(body.byteStream(), MAX_SOURCE_BYTES)
                            ?: return@withContext "image trop lourde"
                        if (bytes.isEmpty()) return@withContext "image vide"
                        bytes
                    }
                // SVG (logos, illustrations vectorielles de certains flux) : BitmapFactory
                // rendrait `null` et l'image resterait éternellement en échec.
                val decoded = (if (isSvgImage(bytes)) decodeSvg(bytes, maxW, maxH)
                else decodeRaster(bytes, maxW, maxH)) ?: return@withContext "image illisible"
                val (w, h) = fitSize(decoded.width, decoded.height, maxW, maxH)
                val scaled = if (decoded.width == w && decoded.height == h) decoded
                else Bitmap.createScaledBitmap(decoded, w, h, true).also {
                    if (it != decoded) decoded.recycle()
                }
                // La transparence décide du format : PNG gardé tel quel quand l'image a des
                // pixels transparents (logos, illustrations — le JPEG les aplatirait), JPEG
                // sinon. Un canal alpha ne suffit pas à décider : beaucoup de photos sont
                // livrées en PNG RGBA parfaitement opaque, et le PNG y pèserait dix fois plus.
                val alpha = scaled.hasAlpha() && hasTransparency(scaled)
                val d = dir(ctx)
                if (!d.exists()) d.mkdirs()
                val target = File(d, thumbFileName(url, if (alpha) "png" else "jpg"))
                val tmp = File(d, target.name + ".tmp")
                try {
                    tmp.outputStream().use {
                        scaled.compress(
                            if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                            if (alpha) 100 else JPEG_QUALITY,
                            it
                        )
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return@withContext "écriture impossible"
                    }
                } finally {
                    tmp.delete()
                    scaled.recycle()
                }
                null
            } catch (e: Exception) {
                imageFailureCause(e)
            }
        }

    /**
     * L'image a-t-elle vraiment des pixels transparents ? Un canal alpha ne suffit pas : nombre
     * de photos sont livrées en PNG RGBA entièrement opaque, et les garder en PNG coûterait dix
     * fois leur taille. Lecture par tranches pour ne pas tenir l'image en plus en mémoire.
     */
    private fun hasTransparency(bmp: Bitmap): Boolean {
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) if ((p ushr 24) != 0xFF) return true
        }
        return false
    }

    /** Décode un raster (JPEG, PNG, WebP…) sous-échantillonné au plus près du gabarit. */    private fun decodeRaster(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Rasterise un SVG directement à la taille du gabarit (AndroidSVG, apporté par coil-svg).
     *  Sans `width`/`height` (viewBox seul, non exposé par AndroidSVG) : gabarit carré, le
     *  `preserveAspectRatio` évite au moins la déformation. */
    private fun decodeSvg(bytes: ByteArray, maxW: Int, maxH: Int): Bitmap? = runCatching {
        val svg = com.caverock.androidsvg.SVG.getFromInputStream(bytes.inputStream())
        val srcW = svg.documentWidth.toInt().takeIf { it > 0 } ?: maxW
        val srcH = svg.documentHeight.toInt().takeIf { it > 0 } ?: maxH
        val (w, h) = fitSize(srcW, srcH, maxW, maxH)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            svg.renderToCanvas(Canvas(bmp), android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()))
        }
    }.getOrNull()

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
     *
     * Coopératif (`yield` régulier) : des milliers de suppressions bloquantes ignoreraient
     * sinon l'annulation (déconnexion pendant une synchronisation).
     */
    suspend fun gc(ctx: Context, keepNames: Set<String>): Int = withContext(Dispatchers.IO) {
        var n = 0
        dir(ctx).listFiles()?.forEachIndexed { i, f ->
            if (i % 128 == 0) kotlinx.coroutines.yield()
            if (f.name.endsWith(".tmp") ||
                ((f.name.endsWith(".jpg") || f.name.endsWith(".png")) && f.name !in keepNames)
            ) {
                if (f.delete()) n++
            }
        }
        n
    }
}
