package com.pressia.data.remote

import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.CategoryEntity
import com.pressia.data.local.SubscriptionEntity

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
    override suspend fun markStarred(token: String?, articleIds: List<String>) {
        if (articleIds.isNotEmpty()) api.markers(auth(token), FeedlyMarkers("markAsSaved", "entries", entryIds = articleIds))
    }
    override suspend fun markUnstarred(token: String?, articleIds: List<String>) {
        if (articleIds.isNotEmpty()) api.markers(auth(token), FeedlyMarkers("markAsUnsaved", "entries", entryIds = articleIds))
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
    override suspend fun profile(token: String?): String? {
        val p = api.profile(auth(token))
        return listOfNotNull(p.userName?.takeIf { it.isNotBlank() }, p.email?.takeIf { it.isNotBlank() })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun FeedlyEntry.toEntity(): ArticleEntity {
        val link = alternate.firstOrNull()?.href ?: origin?.htmlUrl.orEmpty()
        val html = content?.content ?: summary?.content
        val img = visual?.url ?: Regex("<img[^>]+src=[\"']([^\"']+)").find(html.orEmpty())?.groupValues?.getOrNull(1)
        val t = title.orEmpty()
        return ArticleEntity(id = id, feedId = origin?.streamId.orEmpty(), title = t.ifBlank { "(sans titre)" },
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
    override suspend fun markStarred(t: String?, ids: List<String>) = ids.forEach { tag(t, it, "user/-/state/com.google/starred", null) }
    override suspend fun markUnstarred(t: String?, ids: List<String>) = ids.forEach { tag(t, it, null, "user/-/state/com.google/starred") }
    override suspend fun markAllRead(t: String?, feedIds: List<String>) { /* via mark-all-as-read endpoint côté repo */ }
    override suspend fun profile(token: String?): String? {
        val u = api.userInfo(auth(token))
        return listOfNotNull(u.userName?.takeIf { it.isNotBlank() }, u.userEmail?.takeIf { it.isNotBlank() })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    }
    /** Étoiles du compte (« Starred items » chez Inoreader). */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val s = api.stream(auth(token), "user/-/state/com.google/starred", n = max.coerceIn(1, 200))
        return s.items.map { it.toEntity().copy(laterSaved = true) }
    }

    private fun InoreaderItem.toEntity(): ArticleEntity {
        val link = (canonical + alternate).firstOrNull()?.href.orEmpty()
        val html = content?.content ?: summary?.content
        val img = Regex("<img[^>]+src=[\"']([^\"']+)").find(html.orEmpty())?.groupValues?.getOrNull(1)
        val tt = title.orEmpty()
        return ArticleEntity(id = id, feedId = origin?.streamId.orEmpty(), title = tt.ifBlank { "(sans titre)" },
            author = author, url = link, summary = summary?.content, content = html, imageUrl = img,
            published = published, unread = !categories.contains("user/-/state/com.google/read"),
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
        val all = mutableListOf<ArticleEntity>()
        targets.take(30).forEach { f ->
            try { all += LocalRssFetcher.fetch(f.url.ifBlank { f.id }, f.id, max) } catch (_: Exception) {}
        }
        return StreamPage(all.sortedByDescending { it.published }.take(max))
    }
    override suspend fun markRead(token: String?, articleIds: List<String>) {}
    override suspend fun markUnread(token: String?, articleIds: List<String>) {}
    override suspend fun markStarred(token: String?, articleIds: List<String>) {}
    override suspend fun markUnstarred(token: String?, articleIds: List<String>) {}
    override suspend fun markAllRead(token: String?, feedIds: List<String>) {}
}
