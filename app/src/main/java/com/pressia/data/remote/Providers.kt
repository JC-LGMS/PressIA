package com.pressia.data.remote

import com.pressia.data.isAuthFailure
import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.CategoryEntity
import com.pressia.data.local.SubscriptionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Identifiants d'articles par appel `markers` : ~7,5 Ko de corps, déjà éprouvé par la file
 * montante (une page de 100 actions = un appel).
 */
private const val MARKER_CHUNK = 100

/**
 * Plafond des non-lus énumérés pour un « tout marquer lu » de masse (voir
 * `FeedlyProvider.markStreamRead`) : borne le travail par action, sans jamais marquer localement
 * au-delà.
 */
private const val MARK_ALL_ENUM_CAP = 5000

/**
 * Charge utile d'un « tout marquer lu » Feedly : des marqueurs **par identifiant d'article**,
 * groupés par paquets de [MARKER_CHUNK]. Feedly accepte `type":"feeds"` — et `"categories"` —
 * puis les ignore : seul `type":"entries"` marque réellement les articles (voir
 * `FeedlyProvider.markAllRead`).
 */
fun feedlyMarkAllMarkers(unreadIds: Collection<String>): List<FeedlyMarkers> =
    unreadIds.chunked(MARKER_CHUNK).map { FeedlyMarkers("markAsRead", "entries", entryIds = it) }

/** Flux global d'un compte Feedly (vue « Tous les articles ») : null si l'identifiant est inconnu. */
fun feedlyGlobalStream(userId: String): String? =
    userId.takeIf { it.isNotBlank() }?.let { "user/$it/category/global.all" }

/**
 * Provider Feedly Cloud (OAuth2, endpoints gratuits v3).
 *
 * Choix structurant : **le tableau « Read later » du compte n'est jamais importé comme étoile
 * PressIA**. Feedly ne connaît qu'un seul état « enregistré » (`global.saved`), qui est son
 * tableau à lire plus tard ; le confondre avec les favoris de l'application faisait apparaître
 * les favoris de PressIA dans le filtre « Read later », et inversement. En revanche l'étoile
 * posée sur un article Feedly part vers ce tableau (`markSaved`) au lieu d'un favori local.
 */
class FeedlyProvider(private val api: FeedlyService = FeedlyService.create()) : FeedProvider {
    override val id = "feedly"
    private fun auth(t: String?) = "OAuth ${t.orEmpty()}"

    /**
     * Feedly n'accepte l'alias `user/-/...` que sur certains endpoints : le flux global renvoie
     * 404. On récupère donc l'identifiant réel du compte via le profil et on le met en cache.
     */
    @Volatile private var userIdCache: String? = null

    private suspend fun cachedUserId(token: String?): String {
        userIdCache?.let { return it }
        val id = runCatching { api.profile(auth(token)).id?.takeIf { it.isNotBlank() } }.getOrNull()
        return id.orEmpty()
    }

    /** Identifiant distant stable, sans le cache partagé (voir `cachedUserId`) : à la connexion,
     * le cache peut encore porter l'utilisateur précédent — un doublon manqué à cause d'un
     * cache serait exactement le bug visé. Un appel profil par connexion, négligeable. */
    override suspend fun userId(token: String?): String =
        runCatching { api.profile(auth(token)).id?.takeIf { it.isNotBlank() } }.getOrNull().orEmpty()

    override suspend fun categories(token: String?) =
        api.categories(auth(token)).map { CategoryEntity(id = it.id, label = it.label, provider = id) }

    override suspend fun subscriptions(token: String?) =
        api.subscriptions(auth(token)).map {
            SubscriptionEntity(id = it.id, title = it.title ?: it.id,
                siteUrl = it.website.orEmpty(), categoryId = it.categories.firstOrNull()?.id,
                iconUrl = it.iconUrl, provider = id, url = it.id)
        }

    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?, newerThanMs: Long?): StreamPage {
        val streamId = feedId ?: categoryId ?: feedlyGlobalStream(cachedUserId(token))
            ?: return StreamPage(emptyList(), null)
        val s = api.stream(auth(token), streamId, count = max.coerceIn(1, 500), cont = continuation, newerThan = newerThanMs)
        return StreamPage(articles = s.items.map { it.toEntity() }, continuation = s.continuation)
    }

    override suspend fun markRead(token: String?, articleIds: List<String>) {
        if (articleIds.isNotEmpty()) api.markers(auth(token), FeedlyMarkers("markAsRead", "entries", entryIds = articleIds))
    }
    override suspend fun markUnread(token: String?, articleIds: List<String>) {
        if (articleIds.isNotEmpty()) api.markers(auth(token), FeedlyMarkers("keepUnread", "entries", entryIds = articleIds))
    }
    /**
     * Marquage de masse d'un flux : Feedly n'honore **pas** ses propres marqueurs de masse.
     * `{"action":"markAsRead","type":"feeds","feedIds":[…]}` — avec ou sans `asOf` — comme
     * `type":"categories"` répondent HTTP 200 avec un corps vide **sans rien marquer**
     * (vérifié en direct sur un flux à 3 non-lus : `markers/counts` inchangé après l'appel).
     * Seul `type":"entries"` est réellement appliqué : on énumère donc les non-lus du flux
     * côté serveur et on les marque par paquets, comme un marquage à l'unité.
     *
     * Bénéfice au passage : les articles que le serveur croit encore non lus alors que
     * l'application les a marqués lus (marquage de masse d'une version précédente, tombé dans
     * le vide) sont rattrapés. Sans cette énumération, la synchro suivante les rouvrirait
     * localement, le serveur faisant foi.
     */
    override suspend fun markAllRead(token: String?, feedIds: List<String>) {
        feedIds.distinct().forEach { markStreamRead(token, it) }
    }
    /**
     * Feedly n'a pas de type « global » : tout le compte, c'est sa catégorie `global.all`,
     * marquée à l'unité comme un flux (voir [markAllRead]).
     */
    override val bulkMarkAllRead = true
    override suspend fun markAllReadGlobal(token: String?) {
        val streamId = feedlyGlobalStream(cachedUserId(token))
            ?: throw IllegalStateException("feedly : profil injoignable")
        markStreamRead(token, streamId)
    }

    /**
     * Marque lus les non-lus que le serveur connaît sur ce flux, par paquets d'identifiants
     * (`type":"entries"`, seule forme appliquée par Feedly). Rien à énumérer = rien à faire :
     * le marquage local, lui, est déjà à jour.
     */
    private suspend fun markStreamRead(token: String?, streamId: String) {
        val ids = unreadIdsOfStream(token, streamId, MARK_ALL_ENUM_CAP)
        if (ids.isEmpty()) return
        val payloads = feedlyMarkAllMarkers(ids)
        android.util.Log.d("PressIA", "feedly tout lu : ${ids.size} non-lus sur $streamId (${payloads.size} appels)")
        payloads.forEach { api.markers(auth(token), it) }
    }
    /**
     * Étoile d'un article Feedly = son « Read later » (`global.saved`, « starring » côté Google
     * Reader) : `markAsSaved` / `markAsUnsaved`, sans identifiant de compte à résoudre.
     */
    override suspend fun markSaved(token: String?, articleIds: List<String>) {
        if (articleIds.isEmpty()) return
        android.util.Log.d("PressIA", "feedly save (${articleIds.size})")
        api.markers(auth(token), FeedlyMarkers("markAsSaved", "entries", entryIds = articleIds))
    }
    override suspend fun markUnsaved(token: String?, articleIds: List<String>) {
        if (articleIds.isEmpty()) return
        android.util.Log.d("PressIA", "feedly unsave (${articleIds.size})")
        api.markers(auth(token), FeedlyMarkers("markAsUnsaved", "entries", entryIds = articleIds))
    }
    override suspend fun search(token: String?, query: String, feedId: String?): List<ArticleEntity> {
        val scope = cachedUserId(token)
        if (scope.isBlank()) return emptyList()
        val s = api.search(auth(token), "user/$scope/category/global.all", query)
        return s.items.map { it.toEntity() }
    }
    /** Tableau « Read later » du compte : le périmètre additionnel demandé pour les favoris. */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val scope = cachedUserId(token)
        if (scope.isBlank()) return emptyList()
        // Paginé jusqu'à épuisement : un grand tableau ne doit pas être tronqué à la page.
        val out = ArrayList<ArticleEntity>()
        var cont: String? = null
        while (true) {
            val s = api.stream(auth(token), "user/$scope/tag/global.saved", count = max.coerceIn(1, 500), cont = cont)
            if (s.items.isEmpty()) break
            out += s.items.map { it.toEntity().copy(laterSaved = true) }
            val next = s.continuation
            if (next.isNullOrBlank() || next == cont) break
            cont = next
        }
        return out
    }
    /**
     * IDs seuls du tableau (même endpoint léger que les non-lus) : une page au même plafond
     * que les contenus (500, les plus récents d'abord des deux côtés) — au-delà
     * (continuation), `complete` est faux et on redescend tout, sans sauter.
     */
    override suspend fun readLaterIds(token: String?, max: Int): IdSet? {
        val scope = cachedUserId(token)
        if (scope.isBlank()) return null
        return runCatching {
            val ids = HashSet<String>()
            var cont: String? = null
            while (true) {
                val page = api.streamIds(auth(token), "user/$scope/tag/global.saved", count = max.coerceIn(1, 500), cont = cont)
                if (page.ids.isEmpty()) break
                ids += page.ids
                val next = page.continuation
                if (next.isNullOrBlank() || next == cont) break
                cont = next
            }
            IdSet(ids, true)
        }.getOrNull()
    }
    /**
     * Non-lus du compte sur 30 jours (flux global, `unreadOnly`, du plus récent au plus ancien) :
     * sert à propager les lus / non lus d'ailleurs, y compris hors de la fenêtre récente
     * redescendue par la synchronisation. Voie légère d'abord (IDs seuls via `streams/ids`,
     * ~20x moins de données), repli sur le contenu si l'endpoint est refusé.
     */
    override suspend fun unreadIds(token: String?, maxIds: Int): UnreadSet? {
        // Profil inaccessible (401 transitoire…) : on ne sait RIEN des non-lus distants.
        // `complete = false` impérativement : avec `true` et un ensemble vide, la
        // réconciliation passerait tous les non-lus locaux en lus — perte visible des états
        // de lecture sur une simple erreur réseau. `false` ne fait que rouvrir, sans danger.
        val streamId = feedlyGlobalStream(cachedUserId(token)) ?: return UnreadSet(emptySet(), false)
        return unreadSetOfStream(token, streamId, maxIds)
    }

    /**
     * Non-lus d'un flux quelconque (global du compte, flux précis…), avec l'exhaustivité de
     * l'énumération : voie légère `streams/ids` d'abord, repli `streams/contents` si elle est
     * refusée.
     */
    private suspend fun unreadSetOfStream(token: String?, streamId: String, maxIds: Int): UnreadSet =
        runCatching { unreadIdsViaIds(token, streamId, maxIds) }
            .onSuccess { android.util.Log.d("PressIA", "feedly unreadIds via ids (${it.ids.size})") }
            .onFailure { android.util.Log.d("PressIA", "feedly streams/ids refusé, repli contents") }
            .getOrNull() ?: unreadIdsViaContents(token, streamId, maxIds)

    /** IDs seuls, sans l'exhaustivité : marquage de masse (voir [markStreamRead]). */
    private suspend fun unreadIdsOfStream(token: String?, streamId: String, maxIds: Int): Set<String> =
        unreadSetOfStream(token, streamId, maxIds).ids

    /** Énumération par IDs seuls : pas de dates, donc jusqu'à épuisement ou plafond. */
    private suspend fun unreadIdsViaIds(token: String?, streamId: String, maxIds: Int): UnreadSet {
        val ids = HashSet<String>()
        var cont: String? = null
        var complete = false
        var stagnant = 0
        while (ids.size < maxIds) {
            val before = ids.size
            val page = api.streamIds(auth(token), streamId, count = 1000, unreadOnly = true, cont = cont)
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
    private suspend fun unreadIdsViaContents(token: String?, streamId: String, maxIds: Int): UnreadSet {
        val cutoff = System.currentTimeMillis() - RECONCILE_WINDOW_MS
        val ids = HashSet<String>()
        var cont: String? = null
        var complete = false
        while (ids.size < maxIds) {
            val s = api.stream(auth(token), streamId, count = 500, unreadOnly = true, cont = cont)
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
    /** Non-lus par flux (`markers/counts` donne aussi le détail : catégories et flux). */
    override suspend fun unreadCountsByFeed(token: String?): Map<String, Int>? = runCatching {
        api.unreadCounts(auth(token)).unreadcounts.associate { it.id to it.count }
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

    /**
     * Renouvellement du jeton d'accès expiré.
     *
     * Appelé quand le serveur refuse le jeton (401) : renvoie un jeton neuf, ou null si le
     * renouvellement est impossible (pas de jeton de renouvellement enregistré, ou
     * renouvellement refusé). Câblé par le dépôt — c'est lui qui sait où lire et réécrire le
     * jeton de renouvellement, dans le registre des comptes.
     */
    @Volatile
    var renewToken: (suspend (String) -> String?)? = null

    /**
     * Appel API avec renouvellement si le jeton est refusé.
     *
     * Le jeton d'accès OAuth expire vite (de l'ordre de l'heure chez Inoreader) : sans
     * ce repli, chaque lecture de compte se heurterait à un 401 et le compte serait
     * déclaré « session expirée » alors qu'un simple renouvellement le ranime. Un seul
     * essai de renouvellement : au-delà, c'est que le compte est vraiment mort.
     */
    private suspend fun <T> call(token: String?, block: suspend (String) -> T): T {
        val header = auth(token)
        return try {
            block(header)
        } catch (e: CancellationException) {
            throw e   // une annulation ne doit jamais passer pour un jeton à renouveler
        } catch (e: Exception) {
            if (!isAuthFailure(e)) throw e
            val fresh = renewToken?.invoke(token.orEmpty()) ?: throw e
            block(auth(fresh))
        }
    }

    override suspend fun categories(token: String?): List<CategoryEntity> {
        val tags = call(token) { h -> api.tags(h) }
        return tags.tags.filter { it.id.contains("label/") || it.id.contains("folder/") }
            .map { CategoryEntity(id = it.id, label = it.label ?: it.id.substringAfterLast("/"), provider = id) }
    }
    override suspend fun subscriptions(token: String?): List<SubscriptionEntity> {
        val l = call(token) { h -> api.subs(h) }
        return l.subscriptions.map {
            SubscriptionEntity(id = it.id, title = it.title, url = it.url.orEmpty(), siteUrl = it.htmlUrl.orEmpty(),
                categoryId = it.categories.firstOrNull()?.id, iconUrl = it.iconUrl, provider = id)
        }
    }
    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?, newerThanMs: Long?): StreamPage {
        val stream = feedId ?: categoryId ?: "user/-/state/com.google/reading-list"
        // `newerThanMs` ignoré : l'équivalent Reader API (`nt`) n'est pas vérifié en live
        // (unité/sémantique incertaines) — le filtre par date reste client, comme avant.
        val s = call(token) { h -> api.stream(h, stream, n = max.coerceIn(1, 200), cont = continuation) }
        return StreamPage(s.items.map { it.toEntity() }, s.continuation)
    }
    private suspend fun tag(token: String?, itemId: String, add: String?, remove: String?) {
        call(token) { h ->
            // Le jeton CSRF de l'endpoint est cherché avec le même en-tête : il va de pair
            // avec le jeton d'accès, pas avec lui.
            val t = try { api.token(h) } catch (_: Exception) { "" }
            api.editTag(h, itemId, add, remove, t)
        }
    }
    override suspend fun markRead(t: String?, ids: List<String>) = ids.forEach { tag(t, it, "user/-/state/com.google/read", null) }
    override suspend fun markUnread(t: String?, ids: List<String>) = ids.forEach { tag(t, it, null, "user/-/state/com.google/read") }
    override suspend fun markAllRead(t: String?, feedIds: List<String>) { /* via mark-all-as-read endpoint côté repo */ }
    override suspend fun profile(token: String?): String? {
        val u = call(token) { h -> api.userInfo(h) }
        return listOfNotNull(u.userName?.takeIf { it.isNotBlank() }, u.userEmail?.takeIf { it.isNotBlank() })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    }
    /**
     * Une session web capturée (cookie de session d'une WebView) est-elle acceptée ?
     *
     * Le cookie porte l'identifiant de session d'Inoreader — le `SID` historique du
     * protocole `ClientLogin` — que l'API Reader attend dans l'en-tête
     * `Authorization: GoogleLogin auth=…`, exactement comme le jeton OAuth déjà
     * utilisé par l'application : même schéma pour les deux sources, donc rien à
     * distinguer côté appels.
     *
     * Chaque valeur candidate est essayée à son tour ; la **vérification est un vrai
     * appel API** (`user-info`), jamais une simple ressemblance : c'est elle qui dit
     * si la session est exploitable. On s'arrête au premier profil qui répond — Inoreader
     * compte les requêtes au quota, inutile d'insister.
     */
    override suspend fun verifyWebSession(tokens: List<String>): WebSession? {
        for (t in tokens) {
            if (t.isBlank()) continue
            val label = try {
                api.userInfo(inoreaderAuthHeader(t)).let { u ->
                    listOfNotNull(
                        u.userName?.takeIf { it.isNotBlank() },
                        u.userEmail?.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                }
            } catch (e: CancellationException) {
                throw e   // une annulation ne doit jamais passer pour un refus de session
            } catch (e: Exception) {
                // On journalise : sans ça, un 401 (jeton invalide), un 403 (identifiants
                // applicatifs manquants) ou une panne réseau se ressemblent tous et le
                // diagnostic pour l'utilisateur devient « session non valide ».
                android.util.Log.w("PressIA", "Inoreader verifyWebSession ${t.takeIf { it.isNotBlank() }?.take(8)}… : ${e.javaClass.simpleName} ${e.message}")
                ""       // essai suivant sur le candidat suivant
            }
            if (label.isNotBlank()) return WebSession(token = t, label = label)
        }
        return null
    }

    /**
     * Identifiant distant stable (voir l'interface) : `userId` du profil Inoreader. */
    override suspend fun userId(token: String?): String =
        runCatching { call(token) { h -> api.userInfo(h) }.userId?.takeIf { it.isNotBlank() } }.getOrNull().orEmpty()
    /** Total des non-lus en un appel (`unread-count`), pour le détecteur de changement. */
    override suspend fun unreadTotal(token: String?): Int? = runCatching {
        call(token) { h -> api.unreadCount(h) }.unreadcounts.sumOf { it.count }
    }.getOrNull()
    /** Non-lus par flux (`unread-count` donne aussi le détail par flux). */
    override suspend fun unreadCountsByFeed(token: String?): Map<String, Int>? = runCatching {
        call(token) { h -> api.unreadCount(h) }.unreadcounts.associate { it.id to it.count }
    }.getOrNull()
    /** Étoiles du compte (« Starred items » chez Inoreader), paginées jusqu'à épuisement. */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val out = ArrayList<ArticleEntity>()
        var cont: String? = null
        while (true) {
            val s = call(token) { h -> api.stream(h, "user/-/state/com.google/starred", n = max.coerceIn(1, 200), cont = cont) }
            if (s.items.isEmpty()) break
            out += s.items.map { it.toEntity().copy(laterSaved = true) }
            val next = s.continuation
            if (next.isNullOrBlank() || next == cont) break
            cont = next
        }
        return out
    }
    /**
     * IDs seuls des « Starred items » (voie légère, même plafond de 200 que les contenus,
     * les plus récents d'abord des deux côtés) — voir Feedly. Paginés jusqu'à épuisement
     * pour que l'empreinte couvre tout le tableau.
     */
    override suspend fun readLaterIds(token: String?, max: Int): IdSet? = runCatching {
        val ids = HashSet<String>()
        var cont: String? = null
        while (true) {
            val page = call(token) { h -> api.itemsIds(h, "user/-/state/com.google/starred", n = max.coerceIn(1, 200), cont = cont) }
            if (page.itemRefs.isEmpty()) break
            page.itemRefs.mapTo(ids) { it.id }
            val next = page.continuation
            if (next.isNullOrBlank() || next == cont) break
            cont = next
        }
        IdSet(ids, true)
    }.getOrNull()
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
            val s = call(token) { h -> api.stream(h, "user/-/state/com.google/reading-list", n = 1000, cont = cont, xt = READ_STATE) }
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
            // Inoreader (API Reader) date en secondes, Feedly en millisecondes : même
            // normalisation que la réconciliation et la fenêtre d'historique.
            published = publishedMs(published), unread = !categories.contains(READ_STATE),
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
    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?, newerThanMs: Long?): StreamPage {
        val targets = if (feedId != null) feeds.filter { it.id == feedId } else feeds
        // En parallèle (8 flux à la fois) : en séquentiel, un seul flux lent (timeout 15 s)
        // retardait tous les autres. Le tri final rend l'ordre d'arrivée indifférent.
        // Aucun plafond sur le nombre de flux : un compte sans cloud peut en avoir autant que
        // voulu, le parallélisme borné suffit à tenir le rythme (un `take(30)` en laissait
        // silencieusement une partie sans articles).
        val permits = Semaphore(8)
        val results = coroutineScope {
            targets.map { f ->
                async {
                    permits.withPermit {
                        val res = LocalRssFetcher.fetch(f.url.ifBlank { f.id }, f.id, max)
                        // Un flux qui échoue ne doit pas disparaître dans le silence : c'est
                        // ainsi qu'une URL enregistrée avec un caractère parasite restait invisible.
                        if (res.error != null) android.util.Log.w("PressIA", "flux local ${f.id} : ${res.error}")
                        res
                    }
                }
            }.awaitAll()
        }
        val all = results.flatMap { it.articles }
        // Nom du flux (chaîne YouTube, site…) : il remplace l'URL dans les listes.
        val titles = results.mapNotNull { r -> r.title?.takeIf { it.isNotBlank() }?.let { r.feedId to it } }.toMap()
        return StreamPage(all.sortedByDescending { it.published }.take(max), feedTitles = titles)
    }
    override suspend fun markRead(token: String?, articleIds: List<String>) {}
    override suspend fun markUnread(token: String?, articleIds: List<String>) {}
    override suspend fun markAllRead(token: String?, feedIds: List<String>) {}
}
