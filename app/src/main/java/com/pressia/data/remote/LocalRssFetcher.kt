package com.pressia.data.remote

import com.pressia.data.local.ArticleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Résultat d'une récupération : les articles trouvés, le titre du flux, et la raison d'un
 * échec éventuel.
 *
 * L'échec était muet : un flux qui répond 404 ou une URL malformée renvoyait une liste vide,
 * indiscernable d'un flux réellement vide. L'application annonçait alors « flux ajouté et
 * synchronisé » sans un seul article.
 *
 * [title] est le nom du flux tel que le serveur l'annonce : nom de la chaîne pour YouTube,
 * nom du site pour un flux classique. C'est lui qui doit être affiché à la place de l'URL.
 */
data class LocalFeedFetch(
    val feedId: String = "",
    val articles: List<ArticleEntity> = emptyList(),
    val title: String? = null,
    val error: String? = null
)

/**
 * Nettoie une URL de flux saisie à la main.
 *
 * Deux aléas de saisie sont corrigés :
 *  - les **caractères de contrôle** (NUL, retour chariot…) : invisibles à l'écran, ils sont
 *    encodés en `%00` par le client HTTP et le serveur répond alors 404 — le flux « se
 *    synchronise » sans un seul article, sans le moindre message d'erreur. Un NUL collé par le
 *    clavier suffit à casser le flux ;
 *  - le **schéma absent** : `www.exemple.fr/flux.xml` devient `https://www.exemple.fr/flux.xml`.
 *
 * Le schéma n'est ajouté que s'il manque vraiment (pas de `://` du tout) : une URL d'un autre
 * protocole (`ftp://…`) est laissée telle quelle, pour être refusée par [isSupportedFeedUrl].
 */
fun sanitizeFeedUrl(raw: String): String {
    val cleaned = raw.filter { it.code >= 0x20 && it.code != 0x7F }.trim()
    if (cleaned.isEmpty()) return ""
    if (cleaned.contains("://")) return cleaned
    if (cleaned.startsWith("//")) return "https:$cleaned"
    return "https://$cleaned"
}

/** Une URL de flux exploitable : `http` ou `https`, rien d'autre. */
fun isSupportedFeedUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** Récupère directement un flux RSS/Atom — indépendant de tout cloud (ni Feedly Pro, ni quota). */
object LocalRssFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14; PressIA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    suspend fun fetch(feedUrl: String, feedId: String, max: Int): LocalFeedFetch = withContext(Dispatchers.IO) {
        // L'URL est nettoyée avant la requête : une URL enregistrée avec un caractère parasite
        // (NUL collé au clavier) reste ainsi exploitable, sans obliger l'utilisateur à la ressaisir.
        val url = sanitizeFeedUrl(feedUrl)
        if (url.isEmpty()) return@withContext LocalFeedFetch(feedId, error = "URL vide")
        if (!isSupportedFeedUrl(url)) return@withContext LocalFeedFetch(feedId, error = "protocole non supporté : $url")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        var error: String? = null
        // Un serveur qui répond 500 ou 429 (YouTube le fait régulièrement) n'a pas de réponse
        // définitive : on retente une fois. Un 404, lui, veut dire que l'URL est fausse —
        // insister ne servirait à rien.
        for (attempt in 1..2) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val xml = resp.body?.string() ?: return@withContext LocalFeedFetch(feedId, error = "réponse vide")
                        val parsed = parseFeed(xml, feedId)
                        return@withContext LocalFeedFetch(feedId, parsed.articles.take(max), parsed.title)
                    }
                    error = "HTTP ${resp.code}"
                    if (resp.code != 429 && resp.code !in 500..599) return@withContext LocalFeedFetch(feedId, error = error)
                }
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName} : ${e.message ?: "sans détail"}"
            }
            if (attempt < 2) delay(1500)
        }
        LocalFeedFetch(feedId, error = error)
    }

    /** Parse via Jsoup (mode XML) : gère CDATA, RSS 2.0 et Atom, testable en JVM pure. */
    /** Un flux analysé : ses articles et le nom qu'il annonce (chaîne YouTube, site…). */
    data class ParsedFeed(val articles: List<ArticleEntity>, val title: String?)

    /**
     * Analyse complète : les articles **et** le titre du flux.
     *
     * Le titre est celui de la racine (`<feed>` en Atom, `<channel>` en RSS) — pas celui d'une
     * entrée : c'est le nom de la chaîne (YouTube) ou du site, ce qui doit être affiché à la
     * place de l'URL dans les listes.
     */
    fun parseFeed(xml: String, feedId: String): ParsedFeed {
        val articles = parse(xml, feedId)
        val title = try {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val root = doc.allElements.firstOrNull {
                it.tagName().equals("feed", ignoreCase = true) || it.tagName().equals("rss", ignoreCase = true)
            }
            val holder = root?.children()?.firstOrNull { it.tagName().equals("channel", ignoreCase = true) } ?: root
            holder?.children()
                ?.firstOrNull { it.tagName().equals("title", ignoreCase = true) }
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        return ParsedFeed(articles, title?.let { cleanTitle(it) })
    }

    fun parse(xml: String, feedId: String): List<ArticleEntity> {
        return try {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val items = doc.allElements.filter {
                it.tagName().equals("item", ignoreCase = true) || it.tagName().equals("entry", ignoreCase = true)
            }
            items.mapNotNull { el ->
                var title = ""; var link = ""; var desc = ""; var author = ""
                var pub = 0L; var pubKnown = false; var img: String? = null
                el.children().forEach { c ->
                    when (c.tagName().lowercase()) {
                        "title" -> title = c.text().trim()
                        "link" -> {
                            val href = c.attr("href").trim()
                            link = href.ifBlank { c.text().trim() }
                        }
                        "description", "summary", "content", "content:encoded" -> desc += c.text()
                        "author" -> author = c.selectFirst("name")?.text()?.trim() ?: c.text().trim()
                        "dc:creator" -> author = c.text().trim()
                        // La première date trouvée l'emporte : `published` (publication) passe
                        // avant `updated` (modification), qui n'est qu'un repli.
                        "pubdate", "published", "updated", "dc:date" ->
                            if (!pubKnown) parseDate(c.text().trim())?.let { pub = it; pubKnown = true }
                        "enclosure" ->
                            if (c.attr("type").startsWith("image") && c.hasAttr("url")) img = c.attr("url")
                    }
                }
                // Médias de l'entrée, où qu'ils soient : YouTube les niche dans `media:group`,
                // hors du parcours des enfants directs — sans ce passage, la miniature et la
                // description de chaque vidéo restaient vides.
                el.getAllElements().forEach { c ->
                    when (c.tagName().lowercase()) {
                        "media:thumbnail" ->
                            if (img == null) c.attr("url").trim().takeIf { it.isNotBlank() }?.let { img = it }
                        "media:content" -> if (img == null) {
                            val u = c.attr("url").trim()
                            // `media:content` porte aussi la vidéo elle-même (YouTube y met un
                            // lecteur Flash) : seule une vraie image est retenue.
                            if (u.isNotBlank() && looksLikeImage(c.attr("type"), u)) img = u
                        }
                        "media:description" -> if (desc.isBlank()) desc = c.text().trim()
                    }
                }
                if (!pubKnown) pub = System.currentTimeMillis()
                if (title.isBlank() && link.isBlank()) return@mapNotNull null
                val image = img
                    ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(desc)?.groupValues?.getOrNull(1)
                    ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(el.html())?.groupValues?.getOrNull(1)
                // Les titres RSS arrivent avec des entités HTML (« &nbsp; », « &amp; », …).
                val clean = cleanTitle(title).ifBlank { "(sans titre)" }
                ArticleEntity(
                    id = "$feedId:${(title + link).hashCode()}",
                    feedId = feedId,
                    title = clean,
                    author = author.ifBlank { null },
                    url = link,
                    summary = desc.ifBlank { null },
                    content = desc.ifBlank { null },
                    imageUrl = image,
                    published = pub,
                    titleHash = dedupHash(clean, link)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Une URL d'image ? Type MIME image (jpeg, png…), ou — à défaut — extension connue. */
    private fun looksLikeImage(type: String, url: String): Boolean {
        if (type.startsWith("image", ignoreCase = true)) return true
        if (type.isNotBlank()) return false
        return Regex("\\.(jpe?g|png|gif|webp|bmp|avif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
    }

    private fun parseDate(s: String): Long? {
        if (s.isBlank()) return null
        try { return java.time.Instant.parse(s).toEpochMilli() } catch (_: Exception) {}
        // RFC-822 / RSS pubDate, avec ou sans jour de semaine
        val core = s.substringAfter(", ", s).trim()
        for (fmt in listOf("dd MMM yyyy HH:mm:ss Z", "dd MMM yyyy HH:mm:ss z", "yyyy-MM-dd")) {
            try {
                return SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                    .parse(if (fmt.startsWith("yyyy")) s else core)?.time
            } catch (_: Exception) { }
        }
        return null
    }
}
