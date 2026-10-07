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
     * IDs des articles non-lus côté compte sur les 30 derniers jours ([RECONCILE_WINDOW_MS]),
     * pour réconcilier le lu/non-lu distant à la synchronisation (lus ailleurs → passés en
     * lus ; non lus ailleurs → rouverts). Null = non supporté (flux locaux).
     * `complete` = faux si l'énumération a été plafonnée : dans ce cas on ne passe rien en lu
     * (des non-lus inconnus resteraient), on ne fait que rouvrir.
     */
    suspend fun unreadIds(token: String?, maxIds: Int = 5000): UnreadSet? = null
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

/** Non-lus d'un compte + exhaustivité de l'énumération (voir [FeedProvider.unreadIds]). */
data class UnreadSet(val ids: Set<String>, val complete: Boolean)

/**
 * Fenêtre de réconciliation : 30 derniers jours, comme la purge locale (`trimToMax`) et le
 * marquage lu automatique de Feedly au-delà de 31 jours. Au-delà, le local ne conserve de toute
 * façon que les favoris.
 */
const val RECONCILE_WINDOW_MS = 30L * 24 * 3600 * 1000

/** `published` en ms, qu'il soit exprimé en secondes (Inoreader) ou en ms (Feedly). */
fun publishedMs(published: Long): Long =
    if (published in 1 until 1_000_000_000_000L) published * 1000 else published

/** Résultat de la réconciliation : IDs à passer en lus / à rouvrir localement. */
data class ReadReconciliation(val markRead: Set<String>, val markUnread: Set<String>)

/**
 * Partie pure de la réconciliation lu/non-lu distant (testable en JVM).
 *
 * [localUnread] = non-lus locaux du compte, [remoteUnread] = non-lus du compte,
 * [localReadAmongRemote] = lus locaux parmi les non-lus du compte, [pendingIds] = articles
 * avec une action locale non poussée (jamais écrasés : le serveur est périmé pour eux).
 */
fun computeReadReconciliation(
    localUnread: Set<String>,
    remoteUnread: Set<String>,
    localReadAmongRemote: Set<String>,
    pendingIds: Set<String>,
    remoteComplete: Boolean,
    hasPendingMarkAll: Boolean
): ReadReconciliation {
    val markRead =
        if (remoteComplete && !hasPendingMarkAll) (localUnread - remoteUnread - pendingIds)
        else emptySet()
    val markUnread = localReadAmongRemote - pendingIds
    return ReadReconciliation(markRead, markUnread)
}

/** Normalisation pour déduplication. */
fun dedupHash(title: String, url: String): String {
    val t = title.lowercase().replace(Regex("[^a-z0-9àâäéèêëîïôöùûüÿç ]"), "").replace(Regex("\\s+"), " ").trim()
    return "${t.hashCode()}:${url.substringAfter("://").substringBefore("?").hashCode()}"
}
