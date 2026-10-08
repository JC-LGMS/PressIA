package com.pressia.data.remote

import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.CategoryEntity
import com.pressia.data.local.SubscriptionEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Provider Feedly Cloud (OAuth2, endpoints gratuits v3).
 *
 * Choix structurant : **le tableau « Read later » du compte n'est jamais importé comme étoile
 * PressIA**. Feedly ne connaît qu'un seul état « enregistré » (`global.saved`), qui est son
 * tableau à lire plus tard ; le confondre avec les favoris de l'application faisait apparaître
 * les favoris de PressIA dans le filtre « Read later », et inversement.
 */
class FeedlyProvider(private val api: FeedlyService = FeedlyService.create()) : FeedProvider {
    override val id = "feedly"
    private fun auth(t: String?) = "OAuth ${t.orEmpty()}"

    /**
     * Feedly n'accepte l'alias `user/-/...` que sur certains endpoints : le flux global renvoie
     * 404. On récupère donc l'identifiant réel du compte via le profil et on le met en cache.
     */
    @Volatile private var userIdCache: String? = null

    private suspend fun userId(token: String?): String {
        userIdCache?.let { return it }
        val id = runCatching { api.profile(auth(token)).id?.takeIf { it.isNotBlank() } }.getOrNull()
        return id.orEmpty()
    }

    override suspend fun categories(token: String?) =
        api.categories(auth(token)).map { CategoryEntity(id = it.id, label = it.label, provider = id) }

    override suspend fun subscriptions(token: String?) =
        api.subscriptions(auth(token)).map {
            SubscriptionEntity(id = it.id, title = it.title ?: it.id,
                siteUrl = it.website.orEmpty(), categoryId = it.categories.firstOrNull()?.id,
                iconUrl = it.iconUrl, provider = id, url = it.id)
        }

    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?): StreamPage {
        val scope = userId(token)
        val streamId = feedId ?: categoryId
            ?: scope.ifBlank { return StreamPage(emptyList(), null) }.let { "user/$it/category/global.all" }
        val s = api.stream(auth(token), streamId, count = max.coerceIn(1, 500), cont = continuation)
        return StreamPage(articles = s.items.map { it.toEntity() }, continuation = s.continuation)
    }

    override suspend fun markRead(token: String?, articleIds: List<String>) {
        if (articleIds.isNotEmpty()) api.markers(auth(token), FeedlyMarkers("markAsRead", "entries", entryIds = articleIds))
    }
    override suspend fun markUnread(token: String?, articleIds: List<String>) {
        if (articleIds.isNotEmpty()) api.markers(auth(token), FeedlyMarkers("keepUnread", "entries", entryIds = articleIds))
    }
    override suspend fun markAllRead(token: String?, feedIds: List<String>) {
        // Feedly : markAsRead sur category/feed — on boucle simplement.
        feedIds.forEach { f -> api.markers(auth(token), FeedlyMarkers("markAsRead", "feeds", feedIds = listOf(f))) }
    }
    override suspend fun search(token: String?, query: String, feedId: String?): List<ArticleEntity> {
        val scope = userId(token)
        if (scope.isBlank()) return emptyList()
        val s = api.search(auth(token), "user/$scope/category/global.all", query)
        return s.items.map { it.toEntity() }
    }
    /** Tableau « Read later » du compte : le périmètre additionnel demandé pour les favoris. */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val scope = userId(token)
        if (scope.isBlank()) return emptyList()
        val s = api.stream(auth(token), "user/$scope/tag/global.saved", count = max.coerceIn(1, 500))
        return s.items.map { it.toEntity().copy(laterSaved = true) }
    }
    /**
     * Non-lus du compte sur 30 jours (flux global, `unreadOnly`, du plus récent au plus ancien) :
     * sert à propager les lus / non lus d'ailleurs, y compris hors de la fenêtre récente
     * redescendue par la synchronisation. Voie légère d'abord (IDs seuls via `streams/ids`,
     * ~20x moins de données), repli sur le contenu si l'endpoint est refusé.
     */
    override suspend fun unreadIds(token: String?, maxIds: Int): UnreadSet? {
        val scope = userId(token)
        if (scope.isBlank()) return UnreadSet(emptySet(), true)
        runCatching { unreadIdsViaIds(token, scope, maxIds) }
            .onSuccess { android.util.Log.d("PressIA", "feedly unreadIds via ids (${it.ids.size})") }
            .onFailure { android.util.Log.d("PressIA", "feedly streams/ids refusé, repli contents") }
            .getOrNull()?.let { return it }
        return unreadIdsViaContents(token, scope, maxIds)
    }

    /** Énumération par IDs seuls : pas de dates, donc jusqu'à épuisement ou plafond. */
    private suspend fun unreadIdsViaIds(token: String?, scope: String, maxIds: Int): UnreadSet {
        val ids = HashSet<String>()
        var cont: String? = null
        var complete = false
        var stagnant = 0
        while (ids.size < maxIds) {
            val before = ids.size
            val page = api.streamIds(auth(token), "user/$scope/category/global.all", count = 1000, unreadOnly = true, cont = cont)
            if (page.ids.isEmpty()) { complete = true; break }
            ids += page.ids
            // Pages qui n'apportent rien de neuf : on sort au lieu de boucler.
            stagnant = if (ids.size == before) stagnant + 1 else 0
            val next = page.continuation
            if (next.isNullOrBlank() || next == cont || stagnant >= 2) { complete = stagnant < 2; break }
            cont = next
        }
        return UnreadSet(ids, complete)
    }

    /**
     * Repli : on ne retient que les articles que le serveur dit non-lus, au cas où le filtre
     * serait ignoré ; on s'arrête dès qu'une page est entièrement plus vieille que la fenêtre.
     */
    private suspend fun unreadIdsViaContents(token: String?, scope: String, maxIds: Int): UnreadSet? {
        val cutoff = System.currentTimeMillis() - RECONCILE_WINDOW_MS
        val ids = HashSet<String>()
        var cont: String? = null
        var complete = false
        while (ids.size < maxIds) {
            val s = api.stream(auth(token), "user/$scope/category/global.all", count = 500, unreadOnly = true, cont = cont)
            if (s.items.isEmpty()) { complete = true; break }
            s.items.filter { it.unread && publishedMs(it.published) >= cutoff }.forEach { ids += it.id }
            val oldest = s.items.minOf { publishedMs(it.published) }
            val next = s.continuation
            // Page entièrement hors fenêtre (dates renseignées) : les suivantes sont plus vieilles.
            if ((oldest in 1 until cutoff) || next.isNullOrBlank() || next == cont) { complete = true; break }
            cont = next
        }
        return UnreadSet(ids, complete)
    }

    /** Total des non-lus en un appel (`markers/counts`), pour le détecteur de changement. */
    override suspend fun unreadTotal(token: String?): Int? = runCatching {
        api.unreadCounts(auth(token)).unreadcounts.sumOf { it.count }
    }.getOrNull()
    override suspend fun profile(token: String?): String? {
        val p = api.profile(auth(token))
        return listOfNotNull(p.userName?.takeIf { it.isNotBlank() }, p.email?.takeIf { it.isNotBlank() })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun FeedlyEntry.toEntity(): ArticleEntity {
        val link = alternate.firstOrNull()?.href ?: origin?.htmlUrl.orEmpty()
        val html = content?.content ?: summary?.content
        val img = visual?.url ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(html.orEmpty())?.groupValues?.getOrNull(1)
        val t = cleanTitle(title).ifBlank { "(sans titre)" }
        return ArticleEntity(id = id, feedId = origin?.streamId.orEmpty(), title = t,
            author = author, url = link, summary = summary?.content, content = html, imageUrl = img,
            published = published, unread = unread, starred = false,
            titleHash = dedupHash(t, link))
    }
}

/**
 * Provider Inoreader (OAuth2 + API Google-Reader-like).
 *
 * Comme pour Feedly, les « starred items » du compte restent son tableau à part : les favoris
 * PressIA sont un état local de l'application et ne sont jamais déduits des étiquettes du compte.
 */
class InoreaderProvider(private val api: InoreaderService = InoreaderService.create()) : FeedProvider {
    override val id = "inoreader"
    private fun auth(t: String?) = "GoogleLogin auth=${t.orEmpty()}"

    override suspend fun categories(token: String?): List<CategoryEntity> {
        val tags = api.tags(auth(token))
        return tags.tags.filter { it.id.contains("label/") || it.id.contains("folder/") }
            .map { CategoryEntity(id = it.id, label = it.label ?: it.id.substringAfterLast("/"), provider = id) }
    }
    override suspend fun subscriptions(token: String?): List<SubscriptionEntity> {
        val l = api.subs(auth(token))
        return l.subscriptions.map {
            SubscriptionEntity(id = it.id, title = it.title, url = it.url.orEmpty(), siteUrl = it.htmlUrl.orEmpty(),
                categoryId = it.categories.firstOrNull()?.id, iconUrl = it.iconUrl, provider = id)
        }
    }
    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?): StreamPage {
        val stream = feedId ?: categoryId ?: "user/-/state/com.google/reading-list"
        val s = api.stream(auth(token), stream, n = max.coerceIn(1, 200), cont = continuation)
        return StreamPage(s.items.map { it.toEntity() }, s.continuation)
    }
    private suspend fun tag(token: String?, itemId: String, add: String?, remove: String?) {
        val t = try { api.token(auth(token)) } catch (_: Exception) { "" }
        api.editTag(auth(token), itemId, add, remove, t)
    }
    override suspend fun markRead(t: String?, ids: List<String>) = ids.forEach { tag(t, it, "user/-/state/com.google/read", null) }
    override suspend fun markUnread(t: String?, ids: List<String>) = ids.forEach { tag(t, it, null, "user/-/state/com.google/read") }
    override suspend fun markAllRead(t: String?, feedIds: List<String>) { /* via mark-all-as-read endpoint côté repo */ }
    override suspend fun profile(token: String?): String? {
        val u = api.userInfo(auth(token))
        return listOfNotNull(u.userName?.takeIf { it.isNotBlank() }, u.userEmail?.takeIf { it.isNotBlank() })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    }
    /** Total des non-lus en un appel (`unread-count`), pour le détecteur de changement. */
    override suspend fun unreadTotal(token: String?): Int? = runCatching {
        api.unreadCount(auth(token)).unreadcounts.sumOf { it.count }
    }.getOrNull()
    /** Étoiles du compte (« Starred items » chez Inoreader). */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val s = api.stream(auth(token), "user/-/state/com.google/starred", n = max.coerceIn(1, 200))
        return s.items.map { it.toEntity().copy(laterSaved = true) }
    }
    /**
     * Non-lus du compte sur 30 jours (`reading-list` sans l'état « lu », du plus récent au plus
     * ancien) : même usage que côté Feedly. Le filtre se fait aussi sur les catégories de chaque
     * article, au cas où `xt` serait ignoré ; arrêt dès qu'une page est entièrement hors fenêtre.
     */
    override suspend fun unreadIds(token: String?, maxIds: Int): UnreadSet? {
        val cutoff = System.currentTimeMillis() - RECONCILE_WINDOW_MS
        val ids = HashSet<String>()
        var cont: String? = null
        var complete = false
        while (ids.size < maxIds) {
            val s = api.stream(auth(token), "user/-/state/com.google/reading-list", n = 1000, cont = cont, xt = READ_STATE)
            if (s.items.isEmpty()) { complete = true; break }
            s.items
                .filter { !it.categories.contains(READ_STATE) && publishedMs(it.published) >= cutoff }
                .forEach { ids += it.id }
            val oldest = s.items.minOf { publishedMs(it.published) }
            val next = s.continuation
            if ((oldest in 1 until cutoff) || next.isNullOrBlank() || next == cont) { complete = true; break }
            cont = next
        }
        return UnreadSet(ids, complete)
    }

    companion object {
        const val READ_STATE = "user/-/state/com.google/read"
    }

    private fun InoreaderItem.toEntity(): ArticleEntity {
        val link = (canonical + alternate).firstOrNull()?.href.orEmpty()
        val html = content?.content ?: summary?.content
        val img = Regex("<img[^>]+src=[\"']([^\"']+)").find(html.orEmpty())?.groupValues?.getOrNull(1)
        val tt = cleanTitle(title).ifBlank { "(sans titre)" }
        return ArticleEntity(id = id, feedId = origin?.streamId.orEmpty(), title = tt,
            author = author, url = link, summary = summary?.content, content = html, imageUrl = img,
            published = published, unread = !categories.contains(READ_STATE),
            starred = false,
            titleHash = dedupHash(tt, link))
    }
}

/** Provider 100 % local : N flux RSS/Atom saisis par l'utilisateur, sans cloud. */
class LocalProvider : FeedProvider {
    override val id = "local"
    var feeds: List<SubscriptionEntity> = emptyList() // injecté depuis Room
    override suspend fun categories(token: String?) = emptyList<CategoryEntity>()
    override suspend fun subscriptions(token: String?) = feeds
    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?): StreamPage {
        val targets = if (feedId != null) feeds.filter { it.id == feedId } else feeds
        // En parallèle (8 flux à la fois) : en séquentiel, un seul flux lent (timeout 15 s)
        // retardait tous les autres. Le tri final rend l'ordre d'arrivée indifférent.
        val permits = Semaphore(8)
        val all = coroutineScope {
            targets.take(30).map { f ->
                async {
                    permits.withPermit {
                        runCatching { LocalRssFetcher.fetch(f.url.ifBlank { f.id }, f.id, max) }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
        return StreamPage(all.sortedByDescending { it.published }.take(max))
    }
    override suspend fun markRead(token: String?, articleIds: List<String>) {}
    override suspend fun markUnread(token: String?, articleIds: List<String>) {}
    override suspend fun markAllRead(token: String?, feedIds: List<String>) {}
}
