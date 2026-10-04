package com.pressia.data.remote

import com.pressia.data.local.ArticleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Récupère directement un flux RSS/Atom — indépendant de tout cloud (ni Feedly Pro, ni quota). */
object LocalRssFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14; PressIA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    suspend fun fetch(feedUrl: String, feedId: String, max: Int): List<ArticleEntity> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(feedUrl).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val xml = resp.body?.string() ?: return@withContext emptyList()
            parse(xml, feedId).take(max)
        }
    }

    /** Parse via Jsoup (mode XML) : gère CDATA, RSS 2.0 et Atom, testable en JVM pure. */
    fun parse(xml: String, feedId: String): List<ArticleEntity> {
        return try {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val items = doc.allElements.filter {
                it.tagName().equals("item", ignoreCase = true) || it.tagName().equals("entry", ignoreCase = true)
            }
            items.mapNotNull { el ->
                var title = ""; var link = ""; var desc = ""; var author = ""
                var pub = System.currentTimeMillis(); var img: String? = null
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
                        "pubdate", "published", "updated", "dc:date" ->
                            parseDate(c.text().trim())?.let { pub = it }
                        "enclosure" ->
                            if (c.attr("type").startsWith("image") && c.hasAttr("url")) img = c.attr("url")
                        "media:thumbnail", "media:content" ->
                            c.attr("url").trim().takeIf { it.isNotBlank() }?.let { img = it }
                    }
                }
                if (title.isBlank() && link.isBlank()) return@mapNotNull null
                val image = img
                    ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(desc)?.groupValues?.getOrNull(1)
                    ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(el.html())?.groupValues?.getOrNull(1)
                ArticleEntity(
                    id = "$feedId:${(title + link).hashCode()}",
                    feedId = feedId,
                    title = title.ifBlank { "(sans titre)" },
                    author = author.ifBlank { null },
                    url = link,
                    summary = desc.ifBlank { null },
                    content = desc.ifBlank { null },
                    imageUrl = image,
                    published = pub,
                    titleHash = dedupHash(title, link)
                )
            }
        } catch (_: Exception) { emptyList() }
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
