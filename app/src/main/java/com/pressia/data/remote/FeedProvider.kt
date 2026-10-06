package com.pressia.data.remote

import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.CategoryEntity
import com.pressia.data.local.SubscriptionEntity

/** Contrat commun — permet Feedly, Inoreader (API compatibles) et RSS local. */
interface FeedProvider {
    val id: String // "feedly" | "inoreader" | "local"
    suspend fun categories(token: String?): List<CategoryEntity>
    suspend fun subscriptions(token: String?): List<SubscriptionEntity>
    /** Récupère les derniers articles d'un flux / catégorie / global. */
    suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String? = null): StreamPage
    suspend fun markRead(token: String?, articleIds: List<String>)
    suspend fun markUnread(token: String?, articleIds: List<String>)
    suspend fun markAllRead(token: String?, feedIds: List<String>)
    /** Recherche serveur (si supportée), sinon null → fallback local. */
    suspend fun search(token: String?, query: String, feedId: String?): List<ArticleEntity>? = null
    /** Infos du compte connecté (nom / e-mail) — null si l'API ne l'expose pas. */
    suspend fun profile(token: String?): String? = null
    /**
     * Tableau « à lire plus tard » du compte, à distinguer des étoiles PressIA. Vide par défaut :
     * les flux RSS saisis à la main n'ont pas de compte, donc rien à y rattacher.
     */
    suspend fun readLater(token: String?, max: Int = 500): List<ArticleEntity> = emptyList()
}

data class StreamPage(
    val articles: List<ArticleEntity>,
    val continuation: String? = null
)

/**
 * Titres avec entités HTML (ex. «&nbsp;» sur Les Numériques) : en parseur XML, `&nbsp;`
 * n'est pas une entité XML valide donc Jsoup la laisse brute. On déséchappe, on retire
 * les éventuelles balises, on normalise les espaces insécables et doublons.
 */
fun cleanTitle(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return try {
        var t = org.jsoup.parser.Parser.unescapeEntities(raw, false)
        if ('<' in t && '>' in t) t = org.jsoup.Jsoup.parse(t).text()
        t.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    } catch (_: Exception) { raw.trim() }
}

/** Normalisation pour déduplication. */
fun dedupHash(title: String, url: String): String {
    val t = title.lowercase().replace(Regex("[^a-z0-9àâäéèêëîïôöùûüÿç ]"), "").replace(Regex("\\s+"), " ").trim()
    return "${t.hashCode()}:${url.substringAfter("://").substringBefore("?").hashCode()}"
}
