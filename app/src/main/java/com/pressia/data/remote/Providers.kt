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
 * Identifiants d'articles par appel `markers` : 500 tient en ~38 Ko de corps et a été
 * **vérifié en direct** (500 identifiants dans un seul appel, 499 appliqués — un identifiant
 * ignoré par le serveur, ce qui arrive aussi hors lot). C'était 100 : un « tout marquer lu »
 * de 3 700 non-lus faisait 38 appels au lieu de 8.
 *
 * Un marqueur peut être accepté puis ignoré (HTTP 200, aucun effet) : ne jamais conclure du
 * code de retour que l'état a changé, et garder la réconciliation comme filet.
 */
private const val MARKER_CHUNK = 500

/**
 * Nombre d'entrées par page chez Feedly : **250 est le maximum documenté**, pour
 * `streams/contents` comme pour `streams/ids`. Au-delà, le serveur rogne en silence — le code
 * croyait paginer par 500 ou 1 000 et paginera de fait par 250.
 */
private const val FEEDLY_PAGE_MAX = 250

/**
 * Plafond de pages pour le tableau « Read later » : un serveur qui renvoie une continuation
 * cyclique, ou un tableau démesuré, ne doit pas bloquer la synchronisation indéfiniment
 * (200 pages ≈ 40 000 entrées, bien au-delà d'un usage réel).
 */
private const val LATER_MAX_PAGES = 200

/**
 * Plancher de `newerThan` chez Feedly : la doc interdit une borne plus vieille que 31 jours
 * (« cannot be older than 31 days ago »). La fenêtre d'historique peut être réglée au-delà
 * d'un mois ; le filtre fin reste côté client (`withinHistory`), la tête n'a pas besoin
 * d'être bornée plus loin.
 */
const val FEEDLY_MAX_AGE_MS = 31L * 24 * 3600 * 1000

/**
 * `newerThan` borné à ce que Feedly accepte : jamais plus vieux que 31 jours. Une valeur
 * nulle ou négative reste nulle (pas de filtre). Pure, testée.
 */
fun feedlyNewerThan(newerThanMs: Long?, now: Long): Long? =
    newerThanMs?.takeIf { it > 0L }?.coerceAtLeast(now - FEEDLY_MAX_AGE_MS)

/**
 * Identifiants par `edit-tag` Inoreader : `i` est répétable, tout un lot de la file (une page
 * de 100) part en un appel — ~1,2 Ko de corps.
 */
private const val TAG_BATCH = 100

/** Non-lus par page de `stream/items/ids` : la pagination suit `continuation` sans plafond. */
private const val MARK_ALL_PAGE = 200

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
     * 404. On récupère donc l'identifiant réel du compte via le profil et on le met en cache
     * **par jeton** : un cache unique pour le provider servirait l'identifiant d'un autre compte
     * dès que plusieurs sont connectés (le flux global de l'un chez l'autre).
     */
    private val userIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun cachedUserId(token: String?): String {
        val key = token.orEmpty()
        if (key.isBlank()) return ""
        userIds[key]?.let { return it }
        val id = runCatching { api.profile(auth(token)).id?.takeIf { it.isNotBlank() } }.getOrNull()
        if (id != null) userIds[key] = id
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

    /**
     * `POST /v3/subscriptions` sur un flux déjà abonné réécrit son abonnement : la liste des
     * dossiers envoyée **remplace** celle du serveur (vide = sans dossier). Le titre est
     * renvoyé tel quel, sinon Feedly le réinitialise au titre du flux.
     */
    override suspend fun moveSubscription(token: String?, feedId: String, title: String?, fromCategoryId: String?, to: CategoryEntity?) {
        val cats = to?.let { listOf(FeedlyCategory(id = it.id, label = it.label)) }.orEmpty()
        api.updateSubscription(auth(token), FeedlySubscriptionUpdate(id = feedId, title = title, categories = cats))
    }

    /** `POST /v3/categories/<id>` avec le nouveau libellé : un appel, l'identifiant ne bouge pas. */
    override suspend fun renameCategory(token: String?, feeds: List<String>, from: CategoryEntity, to: String) {
        api.updateCategory(auth(token), from.id, FeedlyCategory(id = from.id, label = to))
    }

    /** `DELETE /v3/categories/<id>` : Feedly détache lui-même les flux restés dedans. */
    override suspend fun deleteCategory(token: String?, category: CategoryEntity, feeds: List<String>) {
        api.deleteCategory(auth(token), category.id)
    }

    /**
     * L'ordre des dossiers n'est pas dans `/v3/categories` (pas de rang) : Feedly le tient dans la
     * préférence `categoriesOrderingId`, un tableau JSON d'identifiants que son application web lit
     * pour ordonner ses collections. `POST /v3/preferences` attend une **table** `{clé: valeur}` :
     * la forme `{"name":…,"value":…}` répond 200 sans rien écrire (vérifié en direct). Guillemets et
     * barres inverses sont échappés, la valeur étant du JSON dans du JSON.
     */
    override suspend fun reorderCategories(token: String?, orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val value = orderedIds.joinToString(",", "[", "]") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        api.preferences(auth(token), mapOf("categoriesOrderingId" to value)).close()
    }

    /**
     * S'abonner et modifier passent par le même appel ; sans titre, Feedly reprend celui du flux
     * annoncé par son URL. L'identifiant d'un flux est `feed/<url>` (même convention que les
     * abonnements déjà reçus du serveur).
     */
    override suspend fun addSubscription(token: String?, url: String): String {
        val id = "feed/$url"
        api.updateSubscription(auth(token), FeedlySubscriptionUpdate(id = id))
        return id
    }

    override suspend fun removeSubscription(token: String?, feedId: String) {
        api.deleteSubscription(auth(token), feedId)
    }

    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?, newerThanMs: Long?): StreamPage {
        val streamId = feedId ?: categoryId ?: feedlyGlobalStream(cachedUserId(token))
            // Profil injoignable : rendre une page vide la ferait passer pour une synchro réussie
            // (aucun échec enregistré, LAST_SYNC_AT avance, aucun nouvel article).
            ?: throw IllegalStateException("feedly : profil injoignable")
        // `newerThan` plus vieux que 31 jours est refusé par Feedly : la fenêtre d'historique
        // peut aller au-delà, mais la tête n'a pas à demander plus loin (le rattrapage filtre
        // côté client). Une date nulle reste nulle (pas de filtre).
        val newerThan = feedlyNewerThan(newerThanMs, System.currentTimeMillis())
        val s = api.stream(auth(token), streamId, count = max.coerceIn(1, FEEDLY_PAGE_MAX), cont = continuation, newerThan = newerThan)
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
     * le marquage local, lui, est déjà à jour. L'énumération va **jusqu'à épuisement** (pas de
     * plafond) : un flux — ou le compte entier — à plus de 5000 non-lus laissait sinon les plus
     * anciens non lus côté serveur, rouverts par la réconciliation suivante.
     */
    private suspend fun markStreamRead(token: String?, streamId: String) {
        val ids = unreadIdsOfStream(token, streamId, Int.MAX_VALUE)
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
    /** Tableau « Read later » du compte : le périmètre additionnel demandé pour les favoris. */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val scope = cachedUserId(token)
        if (scope.isBlank()) return emptyList()
        // Paginé jusqu'à épuisement : un grand tableau ne doit pas être tronqué à la page.
        val out = ArrayList<ArticleEntity>()
        var cont: String? = null
        val seen = HashSet<String>()
        var pages = 0
        while (pages++ < LATER_MAX_PAGES) {
            val s = api.stream(auth(token), "user/$scope/tag/global.saved", count = max.coerceIn(1, FEEDLY_PAGE_MAX), cont = cont)
            if (s.items.isEmpty()) break
            out += s.items.map { it.toEntity().copy(laterSaved = true) }
            val next = s.continuation
            if (next.isNullOrBlank() || !seen.add(next)) break
            cont = next
        }
        return out
    }
    /**
     * IDs seuls du tableau (même endpoint léger que les non-lus) : une page au même plafond
     * que les contenus (250, les plus récents d'abord des deux côtés) — au-delà
     * (continuation), `complete` est faux et on redescend tout, sans sauter.
     */
    override suspend fun readLaterIds(token: String?, max: Int): IdSet? {
        val scope = cachedUserId(token)
        if (scope.isBlank()) return null
        return runCatching {
            val ids = HashSet<String>()
            var cont: String? = null
            val seen = HashSet<String>()
            var pages = 0
            var complete = false
            while (pages++ < LATER_MAX_PAGES) {
                val page = api.streamIds(auth(token), "user/$scope/tag/global.saved", count = max.coerceIn(1, FEEDLY_PAGE_MAX), cont = cont)
                if (page.ids.isEmpty()) { complete = true; break }
                ids += page.ids
                val next = page.continuation
                if (next.isNullOrBlank()) { complete = true; break }
                if (!seen.add(next)) break
                cont = next
            }
            // Plafond de pages ou continuation cyclique : la liste est partielle, `complete`
            // reste faux et l'appelant redescend tout (voir le contrat de `readLaterIds`).
            IdSet(ids, complete)
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
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
     * Événements de lecture depuis [sinceMs] (`GET /v3/markers/reads`) : le delta que Feedly
     * expose pour synchroniser l'état lu/non-lu. Un appel remplace l'énumération des non-lus
     * (14 pages pour un compte à 3 500 non-lus).
     *
     * Un échec rend `null` : l'appelant refait une réconciliation complète, comportement
     * d'avant — jamais de passage en lu massif sur une réponse douteuse. L'annulation, elle,
     * remonte (une synchro annulée ne doit pas continuer en tâche de fond).
     */
    override suspend fun readMarks(token: String?, sinceMs: Long): ReadMarks? =
        runCatching { api.readMarks(auth(token), sinceMs) }
            .onFailure { if (it is CancellationException) throw it }
            .map { m ->
                ReadMarks(
                    read = m.entries.toHashSet(),
                    keptUnread = m.unread.toHashSet(),
                    feeds = m.feeds
                        .filter { it.id.isNotBlank() && it.asOf > 0L }
                        .associate { it.id to it.asOf },
                    watermark = m.updated
                )
            }
            .getOrNull()

    override val hasReadMarks: Boolean get() = true

    /**
     * Non-lus d'un flux quelconque (global du compte, flux précis…), avec l'exhaustivité de
     * l'énumération : voie légère `streams/ids` d'abord, repli `streams/contents` si elle est
     * refusée.
     */
    private suspend fun unreadSetOfStream(token: String?, streamId: String, maxIds: Int): UnreadSet =
        runCatching { unreadIdsViaIds(token, streamId, maxIds) }
            .onSuccess { android.util.Log.d("PressIA", "feedly unreadIds via ids (${it.ids.size})") }
            .onFailure {
                // Ne jamais avaler l'annulation : le repli relancerait une pagination entière.
                if (it is CancellationException) throw it
                android.util.Log.d("PressIA", "feedly streams/ids refusé, repli contents")
            }
            .getOrNull() ?: unreadIdsViaContents(token, streamId, maxIds)

    /** IDs seuls, pour le marquage de masse (voir [markStreamRead] : énumération jusqu'à
     *  épuisement, `Int.MAX_VALUE`). */
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
            val page = api.streamIds(auth(token), streamId, count = FEEDLY_PAGE_MAX, unreadOnly = true, cont = cont)
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
        val seen = HashSet<String>()
        var cont: String? = null
        var complete = false
        while (ids.size < maxIds) {
            val s = api.stream(auth(token), streamId, count = FEEDLY_PAGE_MAX, unreadOnly = true, cont = cont)
            if (s.items.isEmpty()) { complete = true; break }
            s.items.filter { it.unread && publishedMs(it.published) >= cutoff }.forEach { ids += it.id }
            val oldest = s.items.minOf { publishedMs(it.published) }
            val next = s.continuation
            // Page entièrement hors fenêtre (dates renseignées) : les suivantes sont plus vieilles.
            // Une continuation déjà vue (serveur cyclique) ferait boucler : on s'arrête.
            if ((oldest in 1 until cutoff) || next.isNullOrBlank() || !seen.add(next)) { complete = true; break }
            cont = next
        }
        return UnreadSet(ids, complete)
    }

    /** Total des non-lus en un appel (`markers/counts`), pour le détecteur de changement. */
    override suspend fun unreadTotal(token: String?): Int? = runCatching {
        // La réponse porte **les flux, les dossiers et `global.all`** : additionner le tout
        // comptait plusieurs fois les mêmes non-lus (et ne pouvait donc jamais correspondre au
        // total retenu par la réconciliation). `global.all` est le total ; à défaut, les flux
        // seuls. Les compteurs d'un flux sont plafonnés à 1 000, le total ne l'est pas.
        val counts = api.unreadCounts(auth(token)).unreadcounts
        counts.firstOrNull { it.id.endsWith("/category/global.all") }?.count
            ?: counts.filter { it.id.startsWith("feed/") }.sumOf { it.count }
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
            titleHash = dedupHash(t, link),
            // Source portée par l'entrée : elle survit à la suppression du flux et couvre les
            // articles du tableau « Read later » dont le flux n'est pas abonné.
            feedTitle = origin?.title.orEmpty())
    }
}

/**
 * Provider Inoreader (OAuth2 + API Google-Reader-like).
 *
 * Comme Feedly, son étiquette `starred` **est** le tableau « Read later » du compte (le site
 * l'appelle ainsi) : l'étoile PressIA d'un article Inoreader la pose ou la retire
 * (`markSaved` / `markUnsaved`), et le tableau redescend à la synchronisation. Les favoris des
 * autres flux restent un état local de l'application.
 */
open class InoreaderProvider(private val api: InoreaderService = InoreaderService.create()) : FeedProvider {
    open override val id = "inoreader"

    /**
     * Service d'API du compte.
     *
     * Inoreader a un serveur unique, donc un service unique. FreshRSS ([FreshRssProvider])
     * hérite de tout ce fournisseur et remplace seulement cette fabrique : le sien dépend de
     * l'adresse du serveur, que porte le jeton du compte.
     */
    protected open fun apiFor(token: String?): InoreaderService = api

    /**
     * Identifiant de flux tel qu'il part dans le **chemin** de `stream/contents/…`.
     *
     * L'API Google Reader attend cet identifiant encodé (`feed%2Fhttp%3A%2F%2F…`), mais le
     * chemin Retrofit est déclaré `encoded = true` : la valeur brute part telle quelle, et
     * Inoreader l'accepte (vérifié en direct). FreshRSS, lui, découpe le chemin sur les barres :
     * son fournisseur encode donc lui-même (voir [FreshRssProvider.streamPath]).
     */
    protected open fun streamPath(stream: String): String = stream

    /**
     * En-têtes d'authentification d'un compte Inoreader.
     *
     * Un compte connecté par OAuth porte un jeton d'accès → `Authorization: GoogleLogin
     * auth=…`. Un compte connecté par la page Inoreader (WebView) porte l'en-tête `Cookie`
     * de la session web → `Cookie: al=…` (+ `ssid`…), **sans** `Authorization` : c'est
     * exactement ce que fait l'extension officielle du navigateur, et l'API l'accepte tel
     * quel sur tous les endpoints `reader/api/0/`.
     */
    protected open fun headers(token: String?): Map<String, String> = when (val a = inoreaderAuth(token)) {
        is InoreaderAuth.OAuth -> mapOf("Authorization" to inoreaderAuthHeader(a.token))
        is InoreaderAuth.WebCookie -> mapOf("Cookie" to a.cookie)
        null -> emptyMap()
    }

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
    private suspend fun <T> call(token: String?, block: suspend (Map<String, String>) -> T): T {
        val h = headers(token)
        return try {
            block(h)
        } catch (e: CancellationException) {
            throw e   // une annulation ne doit jamais passer pour un jeton à renouveler
        } catch (e: Exception) {
            if (!isAuthFailure(e)) throw e
            // Seul un compte OAuth sait se renouveler (il a un refresh_token) : une
            // session web n'en a pas, `renewInoreaderToken` rend null et le 401 remonte.
            val fresh = renewToken?.invoke(token.orEmpty()) ?: throw e
            block(headers(fresh))
        }
    }

    override suspend fun categories(token: String?): List<CategoryEntity> {
        val tags = call(token) { h -> apiFor(token).tags(h) }
        return tags.tags.filter { it.id.contains("label/") || it.id.contains("folder/") }
            .map { CategoryEntity(id = it.id, label = it.label ?: it.id.substringAfterLast("/"), provider = id) }
    }
    override suspend fun subscriptions(token: String?): List<SubscriptionEntity> {
        val l = call(token) { h -> apiFor(token).subs(h) }
        return l.subscriptions.map {
            SubscriptionEntity(id = it.id, title = it.title, url = it.url.orEmpty(), siteUrl = it.htmlUrl.orEmpty(),
                categoryId = it.categories.firstOrNull()?.id, iconUrl = it.iconUrl, provider = id)
        }
    }
    /**
     * `subscription/edit` avec `ac=edit` : `a` ajoute le dossier, `r` retire l'ancien. Les
     * paramètres sont ceux de l'API Google Reader — `ac` est l'**action**, pas le dossier
     * (inversés, le serveur répond « OK » en créant un dossier nommé « edit »). FreshRSS
     * hérite de ce chemin (même API).
     */
    override suspend fun moveSubscription(token: String?, feedId: String, title: String?, fromCategoryId: String?, to: CategoryEntity?) {
        val add = to?.id
        val remove = fromCategoryId?.takeIf { it != add }
        if (add == null && remove == null) return
        call(token) { h ->
            apiFor(token).editSubscription(h, feedId, csrf(token, h), add = add, remove = remove)
        }
    }
    /**
     * L'API Google Reader n'a pas de renommage d'étiquette : chaque flux du dossier est ajouté à
     * l'étiquette au nouveau nom puis retiré de l'ancienne, que le service supprime une fois
     * vide (même chemin que le déplacement). FreshRSS hérite de ce comportement.
     */
    override suspend fun renameCategory(token: String?, feeds: List<String>, from: CategoryEntity, to: String) {
        val newId = renamedFolderId(id, from.id, to)
        if (newId == from.id) return
        val targets = feedsIn(token, from.id, feeds)
        if (targets.isEmpty()) return
        call(token) { h ->
            val t = csrf(token, h)
            targets.forEach { apiFor(token).editSubscription(h, it, t, add = newId, remove = from.id) }
        }
    }
    /**
     * Retire l'étiquette de chaque flux : l'API Google Reader n'a pas de suppression d'étiquette,
     * et le service efface celle qui reste vide (vérifié en direct chez Inoreader).
     */
    override suspend fun deleteCategory(token: String?, category: CategoryEntity, feeds: List<String>) {
        val targets = feedsIn(token, category.id, feeds)
        if (targets.isEmpty()) return
        call(token) { h ->
            val t = csrf(token, h)
            targets.forEach { apiFor(token).editSubscription(h, it, t, remove = category.id) }
        }
    }
    /**
     * Flux que le compte range dans [categoryId]. La liste **vient du serveur** : un flux peut y
     * porter plusieurs dossiers alors que la base locale n'en garde qu'un (`categories.firstOrNull`),
     * et retirer l'étiquette du seul dossier connu laissait l'ancienne en place côté service
     * (constaté : un dossier supprimé dans l'application revenait à la synchronisation suivante).
     */
    private suspend fun feedsIn(token: String?, categoryId: String, local: List<String>): List<String> =
        (local + call(token) { h -> apiFor(token).subs(h) }
            .subscriptions.filter { s -> s.categories.any { it.id == categoryId } }
            .map { it.id }).distinct()

    /**
     * `subscription/quickadd` : le serveur résout l'adresse et rend le flux, ou `numResults = 0`
     * quand ce n'en est pas un (refus explicite plutôt qu'un flux fantôme en base). Le dossier,
     * lui, est posé ensuite par `moveSubscription`. FreshRSS hérite du même chemin.
     */
    override suspend fun addSubscription(token: String?, url: String): String {
        val stream = "feed/$url"
        var created = stream
        call(token) { h ->
            val t = csrf(token, h)
            val added = apiFor(token).quickAdd(h, stream, t)
            if (added.numResults <= 0) throw IllegalStateException(added.error ?: "flux introuvable")
            // Le serveur rend le flux **résolu** (redirection, barre finale, http→https) : c'est
            // lui qui fait foi, sinon la ligne locale désigne un flux que le compte ne connaît
            // pas - zéro article reçu, dossier refusé, puis ligne supprimée à la passe suivante.
            created = added.streamId?.takeIf { it.isNotBlank() } ?: stream
        }
        return created
    }

    /** `subscription/edit` avec `ac=unsubscribe` : un appel, jeton CSRF de la session. */
    override suspend fun removeSubscription(token: String?, feedId: String) {
        call(token) { h ->
            apiFor(token).editSubscription(h, feedId, csrf(token, h), action = "unsubscribe")
        }
    }
    override suspend fun stream(token: String?, feedId: String?, categoryId: String?, max: Int, continuation: String?, newerThanMs: Long?): StreamPage {
        val stream = feedId ?: categoryId ?: "user/-/state/com.google/reading-list"
        // `newerThanMs` ignoré : l'équivalent Reader API (`nt`) n'est pas vérifié en live
        // (unité/sémantique incertaines) — le filtre par date reste client, comme avant.
        val s = call(token) { h -> apiFor(token).stream(h, streamPath(stream), n = max.coerceIn(1, 200), cont = continuation) }
        return StreamPage(s.items.map { it.toEntity() }, s.continuation)
    }
    /** Jeton CSRF de la session, cherché avec le même en-tête que l'appel qu'il protège. */
    private suspend fun csrf(token: String?, h: Map<String, String>): String =
        try { apiFor(token).token(h).string().trim() } catch (_: Exception) { "" }

    private suspend fun tag(token: String?, ids: List<String>, add: String?, remove: String?) {
        if (ids.isEmpty()) return
        call(token) { h ->
            // Il vaut pour la session : le redemander à chaque article doublait les requêtes
            // (un GET token + un POST edit-tag par article).
            val t = csrf(token, h)
            // `i` est répétable : tout un lot de la file part en un seul appel.
            ids.distinct().chunked(TAG_BATCH).forEach { apiFor(token).editTag(h, it, add, remove, t) }
        }
    }
    override suspend fun markRead(t: String?, ids: List<String>) = tag(t, ids, READ_STATE, null)
    override suspend fun markUnread(t: String?, ids: List<String>) = tag(t, ids, null, READ_STATE)
    /**
     * Marquage de masse **réversible** : Inoreader a bien un bouton « Marquer tout comme lu »
     * (`mark-all-as-read`), mais il avance `firstitemmsec` du flux et l'API refuse ensuite
     * silencieusement de rouvrir un article plus ancien — « Annuler » ne vaudrait que pour
     * l'affichage local. On énumère donc les non-lus (`stream/items/ids`, `xt` = lue) et on
     * pose l'étiquette lue par lots : le retour en arrière repart exactement d'où l'on était
     * (20 → 0 → 20 vérifié en direct). Un dossier est marqué flux par flux par l'appelant.
     */
    override val bulkMarkAllRead = true
    override suspend fun markAllRead(t: String?, feedIds: List<String>) {
        feedIds.distinct().forEach { markStreamRead(t, it) }
    }
    override suspend fun markAllReadGlobal(t: String?) =
        markStreamRead(t, "user/-/state/com.google/reading-list")

    private suspend fun markStreamRead(token: String?, stream: String) {
        val ids = unreadIdsOf(token, stream)
        android.util.Log.d("PressIA", "inoreader tout lu : ${ids.size} non-lus sur $stream")
        tag(token, ids, READ_STATE, null)
    }
    /** IDs non lus d'un flux, pagination suivie jusqu'à épuisement (aucun plafond). */
    private suspend fun unreadIdsOf(token: String?, stream: String): List<String> {
        val ids = ArrayList<String>()
        val seen = HashSet<String>()
        var cont: String? = null
        do {
            val page = call(token) { h ->
                apiFor(token).itemsIds(h, stream, n = MARK_ALL_PAGE, cont = cont, xt = READ_STATE)
            }
            ids += page.itemRefs.map { it.id }
            val next = page.continuation
            // Une continuation déjà vue (serveur cyclique, cas d'un FreshRSS auto-hébergé)
            // ferait boucler ici pour toujours : on s'arrête.
            cont = if (next.isNullOrBlank() || !seen.add(next)) null else next
        } while (cont != null)
        return ids
    }
    /**
     * Tableau « Read later » du compte : c'est l'étiquette `starred` d'Inoreader (son site la
     * nomme « Read later », titre du flux vérifié en direct). L'étoile PressIA y range
     * l'article, et le tableau redescend à la synchronisation comme celui de Feedly.
     */
    override suspend fun markSaved(token: String?, articleIds: List<String>) =
        tag(token, articleIds, SAVED_STATE, null)
    override suspend fun markUnsaved(token: String?, articleIds: List<String>) =
        tag(token, articleIds, null, SAVED_STATE)
    override suspend fun profile(token: String?): String? {
        val u = call(token) { h -> apiFor(token).userInfo(h) }
        return listOfNotNull(u.userName?.takeIf { it.isNotBlank() }, u.userEmail?.takeIf { it.isNotBlank() })
            .joinToString(" · ").takeIf { it.isNotBlank() }
    }
    /**
     * Une session web capturée (cookie de session d'une WebView) est-elle acceptée ?
     *
     * [cookieHeaders] : en-têtes `Cookie` candidats, dans l'ordre de préférence (voir
     * `inoreaderCookieCandidates`). Chacun est essayé en en-tête `Cookie` sur un vrai
     * appel `user-info` — **jamais sur une simple ressemblance** : c'est l'API qui dit
     * si la session est exploitable. On s'arrête au premier profil qui répond
     * (Inoreader compte les requêtes au quota, inutile d'insister).
     *
     * Le jeton stocké est préfixé `cookie:` : le fournisseur saura, à chaque appel
     * ultérieur, envoyer un en-tête `Cookie` plutôt qu'un `Authorization`.
     */
    override suspend fun verifyWebSession(cookieHeaders: List<String>): WebSession? {
        val appId = InoreaderAppAuth.appId.trim()
        val appKey = InoreaderAppAuth.appKey.trim()
        if (appId.isEmpty() || appKey.isEmpty()) return null
        for (raw in cookieHeaders) {
            for (cookie in inoreaderCookieCandidates(raw)) {
                val label = try {
                    api.userInfo(mapOf("Cookie" to cookie)).let { u ->
                        listOfNotNull(
                            u.userName?.takeIf { it.isNotBlank() },
                            u.userEmail?.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                if (label.isNotBlank()) {
                    return WebSession(token = INOREADER_COOKIE_PREFIX + cookie, label = label)
                }
            }
        }
        return null
    }

    /**
     * Identifiant distant stable (voir l'interface) : `userId` du profil Inoreader. */
    override suspend fun userId(token: String?): String =
        runCatching { call(token) { h -> apiFor(token).userInfo(h) }.userId?.takeIf { it.isNotBlank() } }.getOrNull().orEmpty()
    /** Total des non-lus en un appel (`unread-count`), pour le détecteur de changement. */
    override suspend fun unreadTotal(token: String?): Int? = runCatching {
        // Seuls les flux comptent : la réponse inclut aussi les dossiers, qui doublent les
        // flux (même règle que côté Feedly).
        call(token) { h -> apiFor(token).unreadCount(h) }.unreadcounts
            .filter { it.id.startsWith("feed/") }
            .sumOf { it.count }
    }.getOrNull()
    /** Non-lus par flux (`unread-count` donne aussi le détail par flux). */
    override suspend fun unreadCountsByFeed(token: String?): Map<String, Int>? = runCatching {
        call(token) { h -> apiFor(token).unreadCount(h) }.unreadcounts.associate { it.id to it.count }
    }.getOrNull()
    /** Étoiles du compte (« Starred items » chez Inoreader, son « Read later »), paginées. */
    override suspend fun readLater(token: String?, max: Int): List<ArticleEntity> {
        val out = ArrayList<ArticleEntity>()
        var cont: String? = null
        val seen = HashSet<String>()
        var pages = 0
        while (pages++ < LATER_MAX_PAGES) {
            val s = call(token) { h -> apiFor(token).stream(h, streamPath(SAVED_STATE), n = max.coerceIn(1, 200), cont = cont) }
            if (s.items.isEmpty()) break
            out += s.items.map { it.toEntity().copy(laterSaved = true) }
            val next = s.continuation
            if (next.isNullOrBlank() || !seen.add(next)) break
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
        val seen = HashSet<String>()
        var pages = 0
        var complete = false
        while (pages++ < LATER_MAX_PAGES) {
            val page = call(token) { h -> apiFor(token).itemsIds(h, SAVED_STATE, n = max.coerceIn(1, 200), cont = cont) }
            if (page.itemRefs.isEmpty()) { complete = true; break }
            page.itemRefs.mapTo(ids) { it.id }
            val next = page.continuation
            if (next.isNullOrBlank()) { complete = true; break }
            if (!seen.add(next)) break
            cont = next
        }
        // Plafond de pages ou continuation cyclique : la liste est partielle, `complete` reste
        // faux et l'appelant redescend tout (voir le contrat de `readLaterIds`).
        IdSet(ids, complete)
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    /**
     * Non-lus du compte sur 30 jours (`reading-list` sans l'état « lu », du plus récent au plus
     * ancien) : même usage que côté Feedly. Le filtre se fait aussi sur les catégories de chaque
     * article, au cas où `xt` serait ignoré ; arrêt dès qu'une page est entièrement hors fenêtre.
     */
    override suspend fun unreadIds(token: String?, maxIds: Int): UnreadSet? {
        val cutoff = System.currentTimeMillis() - RECONCILE_WINDOW_MS
        val ids = HashSet<String>()
        val seen = HashSet<String>()
        var cont: String? = null
        var complete = false
        while (ids.size < maxIds) {
            val s = call(token) { h -> apiFor(token).stream(h, streamPath("user/-/state/com.google/reading-list"), n = 1000, cont = cont, xt = READ_STATE) }
            if (s.items.isEmpty()) { complete = true; break }
            s.items
                .filter { !it.categories.contains(READ_STATE) && publishedMs(it.published) >= cutoff }
                .forEach { ids += it.id }
            val oldest = s.items.minOf { publishedMs(it.published) }
            val next = s.continuation
            // Continuation déjà vue (serveur cyclique) : on s'arrête au lieu de boucler.
            if ((oldest in 1 until cutoff) || next.isNullOrBlank() || !seen.add(next)) { complete = true; break }
            cont = next
        }
        return UnreadSet(ids, complete)
    }

    companion object {
        const val READ_STATE = "user/-/state/com.google/read"
        /** Tableau « Read later » du compte (nom donné par Inoreader à cette étiquette). */
        const val SAVED_STATE = "user/-/state/com.google/starred"
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
            titleHash = dedupHash(tt, link),
            // Même raison que côté Feedly : la source vient de l'entrée, pas seulement de
            // l'abonnement (tableau « à lire plus tard », flux supprimé depuis).
            feedTitle = origin?.title.orEmpty())
    }
}

/**
 * Provider FreshRSS : l'API Google Reader d'un serveur **auto-hébergé**.
 *
 * Tout le travail (dossiers, abonnements, articles, lu/non-lu, étoiles, marquage de masse,
 * compteurs, profil) est celui d'Inoreader — mêmes endpoints, mêmes réponses. Deux choses
 * changent, et c'est tout ce que cette classe remplace :
 * - le **serveur**, que porte le jeton du compte (`freshrss|<serveur>|<auth>`) : un service
 *   Retrofit par serveur, mis en cache ;
 * - l'**authentification** : un simple `Authorization: GoogleLogin auth=…` — pas de cookie de
 *   session web (la connexion se fait par identifiant et mot de passe, voir [FreshRss.login])
 *   et pas d'authentification applicative (`AppId`/`AppKey`, propres à Inoreader).
 *
 * L'étoile PressIA est l'étiquette `starred` de FreshRSS, ses « favoris » — voir
 * [providerUsesSaved].
 */
class FreshRssProvider : InoreaderProvider() {
    override val id = "freshrss"

    /** Un service par serveur : l'adresse d'un FreshRSS est propre à chaque compte. */
    private val services = java.util.concurrent.ConcurrentHashMap<String, InoreaderService>()

    override fun headers(token: String?): Map<String, String> =
        freshrssAuth(token)?.let { mapOf("Authorization" to inoreaderAuthHeader(it.token)) }
            ?: emptyMap()

    override fun apiFor(token: String?): InoreaderService {
        val base = freshrssAuth(token)?.baseUrl ?: return super.apiFor(token)
        return services.getOrPut("$base/api/greader.php/") {
            InoreaderService.create("$base/api/greader.php/", appAuth = false)
        }
    }

    /**
     * FreshRSS découpe le chemin sur les barres : l'identifiant d'un flux
     * (`feed/http://…/rss`) doit donc y arriver encodé, comme le veut l'API Google Reader.
     */
    override fun streamPath(stream: String): String =
        java.net.URLEncoder.encode(stream, "UTF-8").replace("+", "%20")

    /** Rien à capturer : FreshRSS ne se connecte pas par une session web dans l'application. */
    override suspend fun verifyWebSession(tokens: List<String>): WebSession? = null
}

/** Provider 100 % local : N flux RSS/Atom saisis par l'utilisateur, sans cloud. */
class LocalProvider : FeedProvider {
    override val id = "local"
    var feeds: List<SubscriptionEntity> = emptyList() // injecté depuis Room
    /**
     * Appelé à chaque flux terminé (libellé, rang, total) : le bandeau de synchro nomme le
     * flux en cours. Les flux partent 8 en parallèle, l'ordre d'arrivée varie.
     */
    var onFeed: ((label: String, done: Int, total: Int) -> Unit)? = null
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
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            targets.map { f ->
                async {
                    permits.withPermit {
                        val res = LocalRssFetcher.fetch(f.url.ifBlank { f.id }, f.id, max)
                        // Un flux qui échoue ne doit pas disparaître dans le silence : c'est
                        // ainsi qu'une URL enregistrée avec un caractère parasite restait invisible.
                        if (res.error != null) android.util.Log.w("PressIA", "flux local ${f.id} : ${res.error}")
                        onFeed?.invoke(f.title.ifBlank { f.id }, done.incrementAndGet(), targets.size)
                        res
                    }
                }
            }.awaitAll()
        }
        val all = results.flatMap { it.articles }
        // Nom du flux (chaîne YouTube, site…) : il remplace l'URL dans les listes.
        val titles = results.mapNotNull { r -> r.title?.takeIf { it.isNotBlank() }?.let { r.feedId to it } }.toMap()
        // Plafond **par flux** seulement (`fetch(max)`) : un `take(max)` global ici triait tous
        // les flux ensemble et laissait de côté les articles des flux les moins bavards.
        return StreamPage(all.sortedByDescending { it.published }, feedTitles = titles)
    }
    override suspend fun markRead(token: String?, articleIds: List<String>) {}
    override suspend fun markUnread(token: String?, articleIds: List<String>) {}
    override suspend fun markAllRead(token: String?, feedIds: List<String>) {}
}
