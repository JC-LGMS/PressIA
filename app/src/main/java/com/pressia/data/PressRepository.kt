package com.pressia.data

import android.content.Context
import androidx.room.Room
import com.pressia.data.local.*
import com.pressia.data.readability.Readability
import com.pressia.data.remote.*
import com.pressia.data.sync.isWifiOnlyBlocked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Préfixe des identifiants de dossiers fusionnés (voir [PressRepository.folders]) :
 * les flux et le drawer n'ont ainsi qu'un seul identifiant à manipuler par dossier affiché.
 */
const val MERGED_PREFIX = "merged:"

/**
 * Convertit le jeu de favoris choisi dans l'UI en valeur attendue par les requêtes de favoris.
 *
 * L'UI et la préférence historique parlent de « app » ; la base ne connaît que « starred ».
 * Sans cette conversion, « Favoris PressIA » ne correspondait à aucun prédicat et l'écran
 * Favoris restait vide.
 */
fun favScopeArg(set: String): String = when (set) {
    "later" -> "later"
    "all" -> "all"
    "starred" -> "starred"
    // "" = écran de flux ordinaire : aucun filtre de favori. Sans ce cas, la valeur retombait sur
    // "starred" et le flux de lecture n'affichait plus que les articles étoilés.
    else -> ""
}

/**
 * Repository offline-first façon Press :
 * - lecture depuis Room (hors-ligne OK)
 * - sync descendante : cloud/RSS → Room (limitée par taille max)
 * - sync montante : file sync_queue poussée au retour réseau (lu/star)
 */
class PressRepository(
    private val ctx: Context,
    val db: PressDatabase,
    val prefs: Prefs,
    private val feedly: FeedProvider = FeedlyProvider(),
    private val inoreader: FeedProvider = InoreaderProvider(),
    private val local: LocalProvider = LocalProvider()
) {
    private val dao get() = db.dao()

    // ----- Comptes -----

    /**
     * Tous les comptes externes connectés. L'ancien couple [Prefs.PROVIDER] / [Prefs.TOKEN] est
     * repris tel quel au premier lancement : un utilisateur déjà connecté ne perd donc rien et se
     * retrouve avec un registre contenant son compte.
     */
    suspend fun cloudAccounts(): List<CloudAccount> {
        val stored = AccountStore.parse(prefs.data.first()[Prefs.ACCOUNTS])
        if (stored.isNotEmpty()) return stored
        val data = prefs.data.first()
        val provider = data[Prefs.PROVIDER] ?: LOCAL_PROVIDER
        val token = data[Prefs.TOKEN]?.takeIf { it.isNotBlank() }
        if (provider == LOCAL_PROVIDER || token == null) return emptyList()
        return listOf(CloudAccount(accountIdOf(provider, token), provider, token))
    }

    /**
     * Reprend l'ancien couple [Prefs.PROVIDER] / [Prefs.TOKEN] dans le registre, une fois pour
     * toutes. Sans cette écriture, le compte d'un utilisateur déjà connecté resterait invisible
     * dans l'écran Comptes : le registre est vide et le repli de [cloudAccounts] ne sert qu'au
     * bon fonctionnement interne.
     */
    suspend fun migrateLegacyAccount() {
        val data = prefs.data.first()
        if (!data[Prefs.ACCOUNTS].isNullOrBlank()) return
        val provider = data[Prefs.PROVIDER] ?: return
        val token = data[Prefs.TOKEN]?.takeIf { it.isNotBlank() } ?: return
        if (isLocalProvider(provider)) return
        val account = CloudAccount(accountIdOf(provider, token), provider, token)
        prefs.set(Prefs.ACCOUNTS, AccountStore.encode(listOf(account)))
        prefs.set(Prefs.ACTIVE_ACCOUNT, account.id)
    }

    /** Enregistre (ou met à jour) un compte et en fait le compte actif. */
    suspend fun addAccount(service: String, token: String, name: String = ""): CloudAccount = withContext(Dispatchers.IO) {
        val accounts = AccountStore.refreshToken(cloudAccounts(), service, token, name)
        val account = accounts.first { it.token == token && it.service == service.lowercase() }
        prefs.set(Prefs.ACCOUNTS, AccountStore.encode(accounts))
        setActiveAccount(account.id)
        account
    }

    /**
     * Supprime un compte : ses dossiers, ses flux et les articles mis en cache disparaissent.
     * Les autres comptes — y compris un compte du même service — n'en pâtissent pas.
     *
     * Les préférences héritées suivent, et c'est indispensable : [Prefs.TOKEN] est le repli de
     * [cloudAccounts] quand le registre est vide. Un jeton laissé en place après la suppression
     * ferait réapparaître le compte supprimé à la prochaine lecture ; à l'inverse, [Prefs.PROVIDER]
     * pouvait désigner un service alors que [Prefs.TOKEN] appartenait encore à l'ancien compte.
     */
    suspend fun deleteAccount(accountId: String) = withContext(Dispatchers.IO) {
        val accounts = cloudAccounts().filterNot { it.id == accountId }
        prefs.set(Prefs.ACCOUNTS, AccountStore.encode(accounts))
        dao.clearArticlesOf(accountId)
        gcThumbs()
        dao.moveFeeds(dao.subscriptionsForCategories(dao.categories().filter { it.provider == accountId }.map { it.id }).map { it.id }, null)
        dao.clearSubscriptionsOf(accountId)
        dao.clearCategoriesOf(accountId)

        if (accounts.isEmpty()) {
            prefs.set(Prefs.ACTIVE_ACCOUNT, "")
            prefs.set(Prefs.PROVIDER, LOCAL_PROVIDER)
            prefs.set(Prefs.TOKEN, "")
            prefs.set(Prefs.REFRESH, "")
            prefs.set(Prefs.USER_ID, "")
        } else {
            // Le compte actif peut être celui qu'on vient de supprimer : `setActiveAccount` réaligne
            // alors les trois préférences d'un coup. Sinon on n'a rien à changer.
            val activeWasRemoved = prefs.data.first()[Prefs.ACTIVE_ACCOUNT] == accountId
            if (activeWasRemoved) setActiveAccount(accounts.first().id)
        }
    }

    /** Compte servant aux opérations courantes (le premier connecté si aucun n'est désigné). */
    suspend fun activeAccount(): CloudAccount? {
        val accounts = cloudAccounts()
        if (accounts.isEmpty()) return null
        val wanted = prefs.data.first()[Prefs.ACTIVE_ACCOUNT]
        return accounts.firstOrNull { it.id == wanted } ?: accounts.first()
    }

    /** Bascule le compte actif, en tenant à jour les préférences historiques. */
    suspend fun setActiveAccount(accountId: String) {
        val account = cloudAccounts().firstOrNull { it.id == accountId }
        if (account == null) {
            prefs.set(Prefs.ACTIVE_ACCOUNT, "")
            prefs.set(Prefs.PROVIDER, LOCAL_PROVIDER)
            return
        }
        prefs.set(Prefs.ACTIVE_ACCOUNT, account.id)
        prefs.set(Prefs.PROVIDER, account.provider)
        prefs.set(Prefs.TOKEN, account.token)
    }

    /** Provider d'un service donné (« feedly », « inoreader », « local »). */
    private fun providerFor(service: String?): FeedProvider = when (service) {
        "feedly" -> feedly
        "inoreader" -> inoreader
        else -> local
    }

    /** Provider du compte actif, ou le fournisseur RSS local quand aucun compte n'est connecté. */
    suspend fun provider(): FeedProvider {
        val account = activeAccount() ?: return local.also { it.feeds = dao.subscriptions() }
        return providerFor(account.provider)
    }

    /** Jeton du compte actif. */
    suspend fun token(): String? = activeAccount()?.token ?: prefs.data.first()[Prefs.TOKEN]
    suspend fun maxItems(): Int = prefs.data.first()[Prefs.MAX_ITEMS] ?: 1000

    /**
     * Taille d'une page de l'approvisionnement initial. 500 est le maximum accepté par Feedly ;
     * Inoreader plafonne de son côté à 200 et reçoit donc la même valeur, sans conséquence.
     */
    private val pageSize = 500

    /**
     * Plafond de sécurité de l'approvisionnement ([backfillAccount]) : borne le nombre d'articles
     * lus lors d'une connexion, pour qu'un compte de plusieurs années d'archives ne bloque pas
     * l'application pendant un temps indéterminé. Assez large pour qu'aucun compte réel ne
     * l'atteigne — il s'agit d'un garde-fou, pas d'une limite de conception.
     */
    private val backfillSafetyCap = 20000
    /** Vrai si aucun compte n'est connecté : la file de sync n'a alors aucun sens. */
    suspend fun isLocal(): Boolean = activeAccount() == null

    // ----- Lecture locale -----
    suspend fun categories() = dao.categories()
    suspend fun subscriptions(catId: String? = null) =
        if (catId == null) dao.subscriptions() else dao.subscriptionsForCategory(catId)

    // ----- Dossiers (dossiers) -----

    /** Un dossier affiché : un seul, ou le regroupement de dossiers homonymes de plusieurs comptes. */
    data class Folder(
        /** Identifiant utilisé par l'UI : l'id réel, ou « merged:Nom ». */
        val id: String,
        val label: String,
        /** Identifiants réellement stockés (plusieurs si le dossier est fusionné). */
        val ids: List<String>,
        /** Comptes owners : sert à distinguer deux « News » quand les dossiers sont séparés. */
        val providers: List<String>
    ) {
        val merged: Boolean get() = ids.size > 1
    }

/**
 * Dossiers prêts à afficher dans l'écran Accueil et le drawer.
 *
 * L'ordre des dossiers est celui de `dao.categories()`, donc celui choisi par l'utilisateur ; le
 * tri alphabétique n'est plus refait ici, il est déjà appliqué en base (rang, puis libellé).
 *
 * [merge] = un seul dossier par nom commun à tous les comptes ; sinon un dossier par compte,
 * le compte étant ajouté au libellé pour que deux dossiers homonymes restent distincts.
 */
    suspend fun folders(merge: Boolean = true): List<Folder> {
        val cats = dao.categories()
        if (merge) {
            // `groupBy` conserve l'ordre du premier dossier de chaque groupe : un dossier fusionné
            // reste donc à la place de celui qui vient en premier, pas en fin de liste.
            return cats.groupBy { it.label.trim().lowercase() }
                .map { (key, group) ->
                    val id = if (group.size > 1) MERGED_PREFIX + key else group.first().id
                    Folder(id, group.first().label, group.map { it.id }, providersFor(group))
                }
        }
        // Non fusionné : le compte n'est ajouté au libellé que pour les noms homonymes, sinon
        // « RSS local / News » deviendrait illisible au quotidien. Les dossiers d'un même compte
        // restent dans l'ordre choisi ; c'est l'ordre des comptes qui est fixé.
        val homonymes = cats.groupBy { it.label.trim().lowercase() }.filterValues { it.size > 1 }.keys
        val labels = accountLabels()
        return cats.sortedBy { providerOrder(it.provider) }
            .map {
                val label = if (it.label.trim().lowercase() in homonymes) {
                    "${it.label} · ${labels[it.provider] ?: providerLabel(it.provider)}"
                } else it.label
                Folder(it.id, label, listOf(it.id), listOf(it.provider))
            }
    }

    /**
     * Libellé d'un compte pour distinguer des dossiers homonymes. Un service represented par un
     * seul compte s'affiche tel quel ; au-delà on suffixe par le nom du compte, sans quoi deux
     * comptes Feedly donneraient deux dossiers visuellement identiques.
     */
    private suspend fun accountLabels(): Map<String, String> {
        val accounts = cloudAccounts()
        val perService = accounts.groupingBy { it.service }.eachCount()
        return accounts.associate { acc ->
            val base = serviceLabel(acc.service)
            val sameService = perService[acc.service] ?: 0
            acc.id to if (sameService > 1 && acc.name.isNotBlank()) "$base · ${acc.name}" else base
        }
    }

    /** Libellé d'un compte, pour distinguer les dossiers homonymes quand on ne les fusionne pas. */
    private fun providerLabel(p: String?): String =
        if (isLocalProvider(p)) "RSS local" else serviceLabel(providerBase(p))

    private fun providerOrder(p: String?): Int = when {
        isLocalProvider(p) -> 0
        providerBase(p) == "feedly" -> 1
        else -> 2
    }

    private fun providersFor(group: List<CategoryEntity>): List<String> = group.map { it.provider }.distinct()

    /**
     * Dossiers réellement couverts par un identifiant de dossier affiché : un dossier fusionné
     * (`merged:Nom`) englobe tous les dossiers homonymes, quel que soit le compte.
     */
    suspend fun resolveCategoryIds(catId: String?): List<String>? {
        if (catId == null) return null
        if (!catId.startsWith(MERGED_PREFIX)) return listOf(catId)
        val key = catId.removePrefix(MERGED_PREFIX)
        val ids = dao.categories().filter { it.label.trim().lowercase() == key }.map { it.id }
        return ids.ifEmpty { listOf(catId) }
    }

    /** Flux d'un dossier affiché (fusionné ou non). */
    suspend fun folderFeeds(catId: String): List<String> {
        val ids = resolveCategoryIds(catId).orEmpty()
        return if (ids.isEmpty()) emptyList() else dao.subscriptionsForCategories(ids).map { it.id }
    }

    /** Crée un dossier pour les flux RSS locaux ; renvoie son identifiant. */
    suspend fun createFolder(label: String): String? = withContext(Dispatchers.IO) {
        val name = label.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return@withContext null
        val slug = name.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-').ifEmpty { "dossier" }
        var id = ""
        var attempt = 0
        while (id.isEmpty() && attempt < 8) {
            val cand = "local-$slug-" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff).take(5)
            if (dao.categoryExists(cand) == 0) id = cand
            attempt++
        }
        if (id.isEmpty()) return@withContext null
        dao.insertCategory(CategoryEntity(id = id, label = name, provider = LOCAL_PROVIDER))
        id
    }

    /**
     * Dossier local portant le même nom qu'un dossier de compte, créé s'il n'existe pas.
     *
     * Indispensable pour deux raisons : ranger un flux RSS local dans un dossier qui n'existe
     * que chez Feedly doit rester possible sans rattacher le flux au compte (sinon son dossier
     * disparaît à la synchronisation suivante), et le mode « Séparer » doit alors montrer bien
     * deux dossiers homonymes — celui du compte et celui du flux local.
     */
    private suspend fun localMirror(label: String): String? {
        val name = label.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return null
        val key = name.lowercase()
        dao.categories().firstOrNull { isLocalProvider(it.provider) && it.label.trim().lowercase() == key }
            ?.let { return it.id }
        return createFolder(name)
    }

    /**
     * Renomme un dossier. Sur un dossier fusionné, tous ses dossiers locaux sont
     * renommés ensemble : en renommer un seul le séparerait aussitôt du groupe.
     */
    suspend fun renameFolder(id: String, label: String) {
        val name = label.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return
        val cats = dao.categories()
        val targets = if (id.startsWith(MERGED_PREFIX)) {
            val key = id.removePrefix(MERGED_PREFIX)
            cats.filter { isLocalProvider(it.provider) && it.label.trim().lowercase() == key }
        } else cats.filter { it.id == id }
        targets.forEach { dao.renameCategory(it.id, name) }
    }

    /**
     * Supprime un dossier ; les flux qu'il contenait restent abonnés et remontent dans
     * « Flux sans dossier ». Un dossier fusionné n'est supprimé que s'il n'appartient qu'à un compte.
     */
    suspend fun deleteFolder(id: String, providers: List<String>, merged: Boolean) = withContext(Dispatchers.IO) {
        if (merged && providers.size > 1) return@withContext
        val cat = dao.categories().firstOrNull { it.id == id } ?: return@withContext
        if (!isLocalProvider(cat.provider)) return@withContext   // les dossiers du compte restent gérés par le compte
        // Les flux contenus sont explicitement détachés : la clé étrangère les détachait de la
        // même façon, mais on veut un état net explicite et indépendant du ordre des requêtes.
        dao.moveFeeds(dao.subscriptionsForCategory(cat.id).map { it.id }, null)
        dao.deleteCategory(cat.id)
    }

/**
     * Déplace des flux dans un dossier (ou les en sort avec [categoryId] à null).
     *
     * Un flux est toujours rangé dans un dossier de son propre compte. Sur un dossier
     * fusionné, chaque flux vise donc le dossier de son compte s'il en existe un, à défaut un
     * dossier homonyme qui lui est propre ; sans cela un flux RSS local rangé chez Feedly
     * verrait son dossier s'effacer à la synchronisation suivante.
     */
    suspend fun moveFeedsToFolder(feedIds: List<String>, categoryId: String?) = withContext(Dispatchers.IO) {
        if (feedIds.isEmpty()) return@withContext
        if (categoryId == null) return@withContext dao.moveFeeds(feedIds, null)
        val ids = resolveCategoryIds(categoryId).orEmpty()
        if (ids.isEmpty()) return@withContext
        val cats = dao.categories().filter { it.id in ids }
        if (cats.isEmpty()) return@withContext dao.moveFeeds(feedIds, ids.first())
        val subs = dao.subscriptions().filter { it.id in feedIds }.associateBy { it.id }
        feedIds.forEach { feedId ->
            val owner = subs[feedId]?.provider ?: LOCAL_PROVIDER
            // Même compte d'abord, même service ensuite, sinon le premier dossier du lot.
            val target = cats.firstOrNull { sameAccount(it.provider, owner) }
                ?: cats.firstOrNull { providerBase(it.provider) == providerBase(owner) }
                ?: cats.first()
            val dest = if (sameAccount(target.provider, owner)) target.id else localMirror(target.label)
            dao.moveFeeds(listOf(feedId), dest)
        }
    }

    /**
     * Monte ou descend un dossier d'une place, sans tri intermédiaire.
     *
     * On travaille sur la liste déjà ordonnée par la base : échanger le rang du dossier visé avec
     * celui de son voisin produit exactement le déplacement demandé, et les autres dossiers ne
     * bougent pas. `sortOrder` vaut 0 pour les dossiers venus d'un compte, qui restent donc
     * ordonnés par libellé entre eux : l'échange porte sur les rangs, pas sur une liste reconstruite.
     */
    suspend fun moveFolder(id: String, delta: Int) = withContext(Dispatchers.IO) {
        val cats = dao.categories()
        val index = cats.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in cats.indices) return@withContext
        val a = cats[index]
        val b = cats[target]
        // Rangs égaux (dossiers d'un compte, tous à 0) : on énumère toute la liste pour donner à
        // chacun un rang distinct, sinon l'échange resterait sans effet visible.
        val ordered = if (a.sortOrder == b.sortOrder) {
            cats.indices.toList()
        } else null
        if (ordered != null) {
            val moved = ordered.toMutableList()
            moved.add(target, moved.removeAt(index))
            dao.reorderCategories(moved.map { cats[it].id })
        } else {
            dao.setCategoryOrder(a.id, b.sortOrder)
            dao.setCategoryOrder(b.id, a.sortOrder)
        }
    }

    /**
     * Monte ou descend un flux d'une place, dans la liste visible à l'écran : celle de son
     * dossier, ou celle des « flux sans dossier ». On laisse [delta] entre les rangs voisins.
     */
    suspend fun moveSubscription(feedId: String, delta: Int, categoryId: String?) =
        withContext(Dispatchers.IO) {
            val list = if (categoryId == null) dao.subscriptions().filter { it.categoryId == null }
            else dao.subscriptionsForCategory(categoryId)
            val index = list.indexOfFirst { it.id == feedId }
            val target = index + delta
            if (index < 0 || target !in list.indices) return@withContext
            if (list[index].sortOrder == list[target].sortOrder) {
                val moved = list.indices.toMutableList()
                moved.add(target, moved.removeAt(index))
                dao.reorderSubscriptions(moved.map { list[it].id })
            } else {
                dao.setSubscriptionOrder(list[index].id, list[target].sortOrder)
                dao.setSubscriptionOrder(list[target].id, list[index].sortOrder)
            }
        }

    suspend fun stream(
        feedId: String?, catId: String?, unreadOnly: Boolean, favScope: String = "",
        limit: Int = 2000, offset: Int = 0
    ): List<ArticleEntity> {
        val catFeeds = catId?.let { folderFeeds(it) } ?: emptyList()
        return dao.stream(feedId, catFeeds, if (catFeeds.isEmpty()) 1 else 0, if (unreadOnly) 1 else 0, favScopeArg(favScope), limit, offset)
    }
    /**
     * Flux du flux de lecture : la liste affichée se met à jour toute seule quand la
     * synchronisation (bouton ou tâche en arrière-plan) insère de nouveaux articles.
     */
    fun streamFlow(feedId: String?, catId: String?, unreadOnly: Boolean, favScope: String = "", limit: Int = 2000, offset: Int = 0): Flow<List<ArticleEntity>> =
        scopeFlow(catId, unreadOnly, favScope) { feeds, empty, un, fav ->
            dao.streamFlow(feedId, feeds, empty, un, fav, limit, offset)
        }

    /** Idem, en gardant visibles les articles listés puis passés en lus ([keepIds]). */
    fun streamFlow(feedId: String?, catId: String?, unreadOnly: Boolean, favScope: String, keepIds: Set<String>, limit: Int = 2000, offset: Int = 0): Flow<List<ArticleEntity>> =
        scopeFlow(catId, unreadOnly, favScope) { feeds, empty, un, fav ->
            dao.streamFlowSticky(feedId, feeds, empty, un, fav, limit, offset, keepIds.toList())
        }

    /**
     * Résout le périmètre (dossier éventuellement fusionné) avant d'ouvrir le flux réactif :
     * sans cela un dossier affichait tous les flux au lieu des siens.
     */
    private fun <T> scopeFlow(
        catId: String?, unreadOnly: Boolean, favScope: String,
        block: (List<String>, Int, Int, String) -> Flow<T>
    ): Flow<T> = flow {
        val catFeeds = catId?.let { folderFeeds(it) } ?: emptyList()
        emitAll(
            block(catFeeds, if (catFeeds.isEmpty()) 1 else 0, if (unreadOnly) 1 else 0, favScopeArg(favScope))
        )
    }

    suspend fun article(id: String) = dao.article(id)
    suspend fun search(q: String, feedId: String?, catId: String?): List<ArticleEntity> {
        val feedIds = catId?.let { folderFeeds(it) } ?: emptyList()
        // 1) essai serveur
        try {
            val p = provider(); val t = token()
            p.search(t, q, feedId)?.let { if (it.isNotEmpty()) return it }
        } catch (_: Exception) {}
        // 2) fallback local (périmètre respecté)
        return dao.search(q, feedId, feedIds, if (feedIds.isEmpty()) 1 else 0)
    }

    // ----- Actions utilisateur (optimiste + file de sync sauf en local) -----
    suspend fun setRead(id: String, unread: Boolean) = withContext(Dispatchers.IO) {
        val a = dao.article(id) ?: return@withContext
        dao.setRead(id, unread, if (!unread) System.currentTimeMillis() else null)
        if (!isLocal()) {
            dao.enqueue(SyncActionEntity(articleId = id, feedId = a.feedId, kind = if (unread) "unread" else "read"))
            pushQueue()
        }
    }
    /**
     * Bascule un favori PressIA.
     *
     * L'étoile est un état **local**, jamais poussé vers le compte : chez Feedly comme chez
     * Inoreader, le seul état « enregistré » du compte est précisément son tableau « Read later ».
     * Pousser l'étoile vers lui aurait fait remonter les favoris de PressIA dans le filtre
     * « Read later » — exactement le problème que ce filtre doit éviter.
     */
    suspend fun setStarred(id: String, starred: Boolean) = withContext(Dispatchers.IO) {
        dao.setStarred(id, starred)
    }
    suspend fun markAllRead(feedIds: List<String>) = withContext(Dispatchers.IO) {
        // readAt alimenté aussi pour les marquages groupés : permet de distinguer
        // « passé en lu sans ouvrir l'article » d'une vraie ouverture (stats).
        val now = System.currentTimeMillis()
        if (feedIds.isEmpty()) dao.markAllRead(now) else dao.markFeedsRead(feedIds, now)
        if (!isLocal()) {
            feedIds.forEach { dao.enqueue(SyncActionEntity(articleId = "", feedId = it, kind = "markAllRead")) }
            if (feedIds.isEmpty()) dao.enqueue(SyncActionEntity(articleId = "", kind = "markAllRead"))
            pushQueue()
        }
    }
    suspend fun logReading(articleId: String, feedId: String, durationSec: Int) {
        dao.logReading(ReadingEventEntity(articleId = articleId, feedId = feedId, durationSec = durationSec, completed = durationSec > 15))
    }

    // ----- Article complet (double-tap) -----
    data class FullFetch(val html: String?, val blocked: Boolean)

    suspend fun ensureFull(id: String): FullFetch = withContext(Dispatchers.IO) {
        val a = dao.article(id) ?: return@withContext FullFetch(null, false)
        if (a.fullFetched && Readability.isUsableFull(a.content)) {
            return@withContext FullFetch(a.content, false)
        }
        if (a.url.isBlank()) return@withContext FullFetch(a.content, false)
        return@withContext when (val r = Readability.fetchFullResult(a.url)) {
            is Readability.FullResult.Ok -> {
                dao.saveFullContent(id, r.html)
                dao.logExtract(ExtractEventEntity(articleId = id, feedId = a.feedId, ok = true))
                FullFetch(r.html, false)
            }
            is Readability.FullResult.Blocked -> {
                // Purge un éventuel boilerplate paywall mis en cache précédemment
                if (!a.summary.isNullOrBlank()) dao.saveFullContent(id, a.summary)
                dao.logExtract(ExtractEventEntity(articleId = id, feedId = a.feedId, ok = false))
                FullFetch(a.summary ?: a.content, true)
            }
            is Readability.FullResult.Failed -> {
                dao.logExtract(ExtractEventEntity(articleId = id, feedId = a.feedId, ok = false))
                FullFetch(a.content ?: a.summary, false)
            }
        }
    }

    // ----- Sync descendante -----

    /**
     * Récupère les articles de tous les comptes et renvoie le **nombre de nouveaux articles**
     * enregistrés, pas le total reçu : annoncer « 240 articles récupérés » à chaque
     * synchronisation alors que tout était déjà connu rend le message illisible.
     *
     * Chaque compte est traité séparément avec son propre jeton et ses propres dossiers.
     */
    suspend fun syncDown(feedId: String? = null, categoryId: String? = null): Int = withContext(Dispatchers.IO) {
        val max = maxItems()
        var added = 0
        // Flux RSS saisis à la main : sans compte, donc toujours synchronisés.
        runCatching {
            local.feeds = dao.subscriptions()
            val page = local.stream(null, feedId, categoryId, max)
            added += storeArticles(page.articles)
        }.onFailure { android.util.Log.e("PressIA", "sync local failed", it) }

        // Comptes cloud. Sur un flux précis, seul le compte qui le détient est interrogé.
        val owner = feedId?.let { dao.subscription(it)?.provider }
        for (account in cloudAccounts()) {
            if (owner != null && !sameAccount(owner, account.id)) continue
            runCatching { added += syncAccount(account, feedId, categoryId, max) }
                .onFailure { android.util.Log.e("PressIA", "sync ${account.id} failed", it) }
        }
        replayRestoredStarred()
        trimToMax(max)
        syncThumbs()
        added
    }

    /**
     * Récupère les miniatures de tous les articles synchronisés (lus comme non lus) dont le
     * fichier local manque — y compris tout le stock après un vidage du cache, le suivi
     * [ArticleEntity.imageCached] ayant alors été réinitialisé.
     *
     * Inactif si « Images hors-ligne » est coupé, ou si « Wi-Fi uniquement » est actif hors
     * Wi-Fi/Ethernet : une passe manuelle sur données mobiles ne doit pas vider le forfait.
     * Idempotent : seules les URLs manquantes sont téléchargées.
     */
    suspend fun syncThumbs(): Int = withContext(Dispatchers.IO) {
        val data = prefs.data.first()
        if (data[Prefs.IMAGES_OFFLINE] == false) return@withContext 0
        if (isWifiOnlyBlocked(ctx, data[Prefs.WIFI_ONLY] ?: true)) return@withContext 0
        val urls = dao.pendingThumbUrls()
        if (urls.isEmpty()) return@withContext 0
        val (maxW, maxH) = ThumbStore.maxThumbSize(ctx)
        val permits = Semaphore(4)
        return@withContext coroutineScope {
            urls.map { url ->
                async {
                    permits.withPermit {
                        if (!ThumbStore.fetch(ctx, url, maxW, maxH)) return@withPermit false
                        dao.markThumbCached(url)
                        true
                    }
                }
            }.awaitAll().count { it }
        }
    }

    /**
     * Approvisionnement d'un compte nouvellement connecté : remonte tout son historique.
     *
     * Distinct de [syncDown], qui ne descend que la fenêtre récente pour rester rapide. Voir
     * [backfillAccount] pour la raison des pages successives et des bornes de sécurité.
     */
    suspend fun backfill(accountId: String): Int = withContext(Dispatchers.IO) {
        val account = cloudAccounts().firstOrNull { it.id == accountId } ?: return@withContext 0
        backfillAccount(account)
    }

    /** Synchronise un compte : flux d'articles, puis dossiers et abonnements si vue globale. */
    private suspend fun syncAccount(account: CloudAccount, feedId: String?, categoryId: String?, max: Int): Int {
        val p = providerFor(account.provider)
        val page = p.stream(account.token, feedId, categoryId, max)
        var added = storeArticles(page.articles)
        if (feedId == null && categoryId == null) {
            val cats = p.categories(account.token).map { it.copy(provider = account.id) }
            replaceCategories(account.id, cats)
            val subs = p.subscriptions(account.token).map { it.copy(provider = account.id) }
            // On ne retire que les flux de ce compte : ceux des autres comptes, et surtout
            // les flux RSS locaux ajoutés à la main, doivent survivre.
            // Même raison que pour les dossiers : le rang choisi par l'utilisateur est réinjecté,
            // sinon la réinsertion le remettrait à zéro et l'ordre saisi disparaîtrait.
            val ranks = dao.subscriptions().associate { it.id to it.sortOrder }
            dao.clearSubscriptionsOf(account.id)
            dao.upsertSubscriptions(subs.map { s -> s.copy(sortOrder = ranks[s.id] ?: 0) })
        }
        return added
    }

    /**
     * Synchronisation d'approvisionnement : remonte tout l'historique disponible, page après page.
     *
     * Le réglage « nombre d'articles conservés » ne s'applique pas ici. À la connexion d'un compte,
     * il plafonne à 250 articles par flux, ce qui ne remplit que quelques heures de lecture : le
     * reste de l'historique du compte n'arriverait jamais, puisque les synchronisations suivantes
     * ne redescendent que la fenêtre la plus récente. On suit donc [StreamPage.continuation]
     * jusqu'à épuisement, en gardant [pageSize] articles par page — la limite de l'API.
     *
     * La boucle s'arrête dans trois cas, tous sûrs : plus de jeton de continuation, une page vide
     * (le compte renvoie indéfiniment la même page), ou [safetyCap] articles lus au total. Ce dernier
     * garde la mémoire et le temps de calcul bornés sur un compte des années d'archives ; il est
     * assez large pour qu'aucun compte réel ne l'atteigne.
     */
    private suspend fun backfillAccount(account: CloudAccount): Int = withContext(Dispatchers.IO) {
        val p = providerFor(account.provider)
        val cats = p.categories(account.token).map { it.copy(provider = account.id) }
        replaceCategories(account.id, cats)
        val subs = p.subscriptions(account.token).map { it.copy(provider = account.id) }
        val ranks = dao.subscriptions().associate { it.id to it.sortOrder }
        dao.clearSubscriptionsOf(account.id)
        dao.upsertSubscriptions(subs.map { s -> s.copy(sortOrder = ranks[s.id] ?: 0) })

        var added = 0
        var continuation: String? = null
        var seen = 0
        while (seen < backfillSafetyCap) {
            // `getOrNull` et non `getOrElse` : sortir de la boucle depuis une lambda inline
            // (`break`) est une fonctionnalité expérimentale de Kotlin.
            val page = runCatching { p.stream(account.token, null, null, pageSize, continuation) }
                .onFailure { android.util.Log.e("PressIA", "backfill ${account.id} page failed", it) }
                .getOrNull() ?: break
            if (page.articles.isEmpty()) break
            added += storeArticles(page.articles)
            seen += page.articles.size
            val next = page.continuation
            // Une continuation identique ferait boucler la même page indéfiniment.
            if (next.isNullOrBlank() || next == continuation) break
            continuation = next
        }
        android.util.Log.i("PressIA", "backfill ${account.id}: $added articles, $seen lus")
        syncThumbs()
        added
    }

    /**
     * Enregistre les articles et renvoie combien étaient inconnus. L'identification passe par lots :
     * SQLite refuse au-delà de ~999 variables liées dans une requête `IN`.
     *
     * Les drapeaux de favoris sont **préservés** : `upsertArticles` remplace la ligne entière, et
     * les étoiles PressIA comme le tableau « Read later » sont des états locaux qu'aucun flux
     * distant ne renvoie. Seule exception : un article qui arrive explicitement marqué
     * `laterSaved` (import du tableau du compte) gagne ce drapeau, sans jamais le perdre.
     */
    private suspend fun storeArticles(articles: List<ArticleEntity>): Int {
        if (articles.isEmpty()) return 0
        val known = HashSet<String>()
        val prev = HashMap<String, FavFlags>()
        val thumbs = HashMap<String, Boolean>()
        articles.map { it.id }.chunked(400).forEach { chunk ->
            known += dao.existingIds(chunk)
            dao.favFlagsOf(chunk).forEach { prev[it.id] = it }
            dao.thumbFlagsOf(chunk).forEach { thumbs[it.id] = it.imageCached }
        }
        val merged = articles.map { a ->
            val p = prev[a.id]
            // La miniature déjà stockée survit à la réinsertion : sans cela chaque
            // synchronisation (REPLACE) oublierait le fichier local et tout retéléchargerait.
            val cached = thumbs[a.id] ?: a.imageCached
            if (p == null) return@map if (cached) a.copy(imageCached = true) else a
            a.copy(starred = p.starred, laterSaved = p.laterSaved || a.laterSaved, imageCached = cached)
        }
        val fresh = merged.count { it.id !in known }
        dao.upsertArticles(merged)
        return fresh
    }

    /**
     * Réécrit les dossiers d'un compte sans perdre les affectations de flux.
     *
     * `subscriptions.categoryId` référence `categories.id` avec ON DELETE SET NULL : effacer les
     * dossiers d'un compte détachait donc de fait les flux, y compris ceux d'autres comptes qui
     * y étaient rangés — cas d'un flux RSS local rangé dans un dossier Feedly. On mémorise donc
     * les affectations avant, et on restaure après réinsertion celles qui se sont volatilisées.
     *
     * L'ordre choisi par l'utilisateur est lui aussi mémorisé : les dossiers arrivent du compte
     * avec un rang nul, et une réinsertion naïve remettrait tous les dossiers dans l'ordre
     * alphabétique à chaque synchronisation.
     */
    private suspend fun replaceCategories(accountId: String, incoming: List<CategoryEntity>) {
        val before = dao.subscriptions().mapNotNull { s -> s.categoryId?.let { s.id to it } }.toMap()
        val labels = dao.categories().associate { it.id to it.label }
        val ranks = dao.categories().associate { it.id to it.sortOrder }
        val present = incoming.map { it.id }.toSet()
        dao.clearCategoriesOf(accountId)
        dao.upsertCategories(incoming.map { c -> c.copy(sortOrder = ranks[c.id] ?: 0) })
        dao.subscriptions().forEach { s ->
            val wanted = before[s.id] ?: return@forEach
            if (s.categoryId == wanted) return@forEach
            val restored = when {
                wanted in present -> wanted
                // Le dossier visé n'existe plus côté compte : on le recrée chez les flux locaux
                // pour ne pas perdre l'organisation saisie par l'utilisateur.
                isLocalProvider(s.provider) -> localMirror(labels[wanted].orEmpty())
                else -> null
            }
            if (s.categoryId != restored) dao.moveFeeds(listOf(s.id), restored)
        }
    }

    /**
     * Actualise le tableau « Read later » des comptes. Le marquage est propre au compte et
     * indépendant des étoiles PressIA : un article peut être dans l'un, l'autre, ou les deux.
     */
    suspend fun syncReadLater(): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (account in cloudAccounts()) {
            runCatching {
                val items = providerFor(account.provider).readLater(account.token)
                if (items.isEmpty()) return@runCatching
                // On repart d'un tableau vierge pour ce compte : un article retiré des « à lire
                // » plus tard chez Feedly ne doit pas rester marqué indéfiniment.
                val feeds = dao.subscriptions().filter { sameAccount(it.provider, account.id) }.map { it.id }
                if (feeds.isNotEmpty()) dao.clearLater(feeds)
                added += storeArticles(items)
            }.onFailure { android.util.Log.e("PressIA", "read later ${account.id} failed", it) }
        }
        syncThumbs()
        added
    }

    private suspend fun trimToMax(maxPerFeed: Int) {
        // garde les favoris + prune global > 30j (le quota fin par flux est assuré à l'affichage + prune)
        if (dao.prune(System.currentTimeMillis() - 30L * 24 * 3600 * 1000) > 0) gcThumbs()
    }

    /** Supprime les miniatures dont plus aucun article n'a besoin (purge, compte supprimé). */
    private suspend fun gcThumbs() {
        val keep = dao.allThumbUrls().mapTo(HashSet()) { ThumbStore.thumbFileName(it) }
        ThumbStore.gc(ctx, keep)
    }

    /**
     * Rejoue les favoris venus d'une restauration de sauvegarde sur les articles désormais présents.
     * Seuls ceux qui sont effectivement arrivés sont retirés de la mémoire, les autres attendront
     * une synchronisation ultérieure.
     */
    private suspend fun replayRestoredStarred() {
        val pending = prefs.flow(Prefs.RESTORED_STARRED, "").first()
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (pending.isEmpty()) return
        val arrived = dao.existingIds(pending.toList()).toSet()
        if (arrived.isEmpty()) return
        dao.markStarred(arrived.toList())
        val stillWaiting = pending - arrived
        prefs.set(Prefs.RESTORED_STARRED, stillWaiting.joinToString("\n"))
    }

    // ----- Sync montante (file bidirectionnelle) -----

/**
     * Pousse les actions en attente vers le bon compte. L'action est rattachée au compte qui détient
     * le flux concerné, pas au compte actif : sans cela, un article d'un second compte Feedly
     * serait marqué lu chez le mauvais compte.
 *
     * Les favorites ne figurent plus dans la file : l'étoile PressIA est locale. Les éventuelles
     * lignes « star » / « unstar » laissées par une version antérieure sont simplement vidées,
     * plutôt que poussées vers le tableau « Read later » du compte où elles apparîtraient à tort.
 */
    suspend fun pushQueue(): Int = withContext(Dispatchers.IO) {
        val pending = dao.pendingSync()
        if (pending.isEmpty()) return@withContext 0
        val accounts = cloudAccounts()
        if (accounts.isEmpty()) { pending.forEach { dao.dequeue(it.rowId) }; return@withContext pending.size }
        var done = 0
        pending.forEach { a ->
            val account = accountFor(a, accounts) ?: run { dao.bumpTry(a.rowId); return@forEach }
            val p = providerFor(account.provider)
            try {
                when (a.kind) {
                    "read" -> p.markRead(account.token, listOf(a.articleId))
                    "unread" -> p.markUnread(account.token, listOf(a.articleId))
                    "star", "unstar" -> Unit // état local : rien à pousser
                    "markAllRead" -> p.markAllRead(account.token, listOfNotNull(a.feedId))
                }
                dao.dequeue(a.rowId); done++
            } catch (_: Exception) { dao.bumpTry(a.rowId) }
        }
        done
    }

    /** Compte responsable d'une action en attente, déduit du flux puis de l'article. */
    private suspend fun accountFor(action: SyncActionEntity, accounts: List<CloudAccount>): CloudAccount? {
        val feedId = action.feedId ?: action.articleId.takeIf { it.isNotBlank() }?.let { dao.article(it)?.feedId }
        val owner = feedId?.let { dao.subscription(it)?.provider }
        return accounts.firstOrNull { sameAccount(it.id, owner) } ?: accounts.first()
    }

    /** Vide la file de synchronisation (bascule cloud → RSS local). */
    suspend fun purgeQueue() = withContext(Dispatchers.IO) { dao.clearQueue() }

    /** File de synchronisation ignorée en RSS local (aucun compteur, aucune action en attente). */
    suspend fun pendingCount(): Int = if (isLocal()) 0 else dao.pendingCount()
    suspend fun unreadByFeed(): Map<String, Int> =
        dao.unreadByFeed().associate { it.feedId to it.c }

    /**
     * Non-lus agrégés par dossier affiché (drawer). Un dossier fusionné additionne ses
     * dossiers d'origine ; un flux sans dossier n'apparaît pas.
     */
    suspend fun unreadByFolder(): Map<String, Int> {
        val perFeed = unreadByFeed()
        val subs = dao.subscriptions()
        val perCat = HashMap<String, Int>()
        subs.forEach { s ->
            val catId = s.categoryId ?: return@forEach
            perCat[catId] = (perCat[catId] ?: 0) + (perFeed[s.id] ?: 0)
        }
        val cats = dao.categories()
        val out = HashMap<String, Int>()
        cats.forEach { cat ->
            val n = perCat[cat.id] ?: 0
            out[cat.id] = n
            // Les dossiers homonymes d'un dossier fusionné partagent le compteur.
            val key = MERGED_PREFIX + cat.label.trim().lowercase()
            out[key] = (out[key] ?: 0) + n
        }
        return out
    }
    /** Nom + icône d'origine d'un flux (affichés sous le titre article). */
    suspend fun feedTitle(feedId: String): String? = dao.feedTitle(feedId)
    suspend fun feedIcon(feedId: String): String? = dao.feedIcon(feedId)
    suspend fun subscription(feedId: String): SubscriptionEntity? = dao.subscription(feedId)

    /** Taille du cache local (base Room + images Coil + miniatures) en octets. */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {        var total = 0L
        val dbDir = ctx.getDatabasePath("pressia.db")?.parentFile
        dbDir?.listFiles { f -> f.name.startsWith("pressia.db") }?.forEach { total += it.length() }
        total += runCatching { coil.Coil.imageLoader(ctx).diskCache?.size ?: 0L }.getOrDefault(0L)
        total += runCatching { ThumbStore.sizeBytes(ctx) }.getOrDefault(0L)
        total
    }

    /** Taille du cache formatée pour l'affichage (« 12,4 Mo »). */
    suspend fun cacheInfo(): String {
        val b = cacheSizeBytes()
        if (b < 1024) return "$b o"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.FRANCE, "%.1f Ko", kb)
        return String.format(java.util.Locale.FRANCE, "%.1f Mo", kb / 1024.0)
    }

    /**
     * Vide le cache : articles (favoris conservés) + images. Le suivi des miniatures est
     * réinitialisé : la prochaine synchronisation récupère toutes les images des articles
     * conservés.
     */
    suspend fun clearCache(keepStarred: Boolean = true) = withContext(Dispatchers.IO) {
        if (keepStarred) dao.prune(Long.MAX_VALUE) // prune() ne supprime que les non-favoris
        else dao.clearArticles()
        ThumbStore.clear(ctx)
        dao.resetThumbsCached()
        runCatching { coil.Coil.imageLoader(ctx).diskCache?.clear() }
    }

    /**
     * Ne vide que les images (force le re-téléchargement, dont les favicons). Comme pour
     * [clearCache], tout est récupéré à la prochaine synchronisation.
     */
    suspend fun clearImages() = withContext(Dispatchers.IO) {
        ThumbStore.clear(ctx)
        dao.resetThumbsCached()
        runCatching { coil.Coil.imageLoader(ctx).diskCache?.clear() }
    }

    // ----- Déduplication -----
    /**
     * Groupes de doublons **non lus** : on ne renvoie que les articles encore non lus du groupe,
     * et un groupe n'est retenu que s'il en compte au moins deux. Un groupe réduit à un seul
     * article n'est plus un doublon, donc « tout marquer lu sauf le + récent » vide bien l'écran.
     */
    suspend fun duplicateGroups(): List<List<ArticleEntity>> = withContext(Dispatchers.IO) {
        val seeds = dao.duplicateGroupsSeedUnread()
        seeds.mapNotNull { s ->
            val g = dao.duplicatesOf(s.titleHash).filter { it.unread }
            if (g.size > 1) g else null
        }.take(50)
    }

    // ----- Stats -----
    /** Années ayant au moins une lecture ( ex. [2026, 2025] ). */
    suspend fun statsYears(): List<Int> = withContext(Dispatchers.IO) {
        dao.readingYears().mapNotNull { it.toIntOrNull() }
    }

    /** Première lecture enregistrée (0 si aucune). */
    suspend fun firstReadingAt(): Long = withContext(Dispatchers.IO) { dao.firstReadingAt() }

    suspend fun stats(from: Long, to: Long): ReadingStats = withContext(Dispatchers.IO) {
        val total = dao.readsBetween(from, to)
        val perDay = dao.readsPerLocalDay(from, to)
        val seconds = dao.secondsBetween(from, to)
        val quick = dao.readWithoutOpeningBetween(from, to)
        val titles = dao.subscriptions().associateBy({ it.id }, { it.title })
        val cats = dao.categories().associateBy({ it.id }, { it.label })
        val perCategory = dao.secondsByCategory(from, to)
            .map { (cats[it.categoryId] ?: it.categoryId.orEmpty()) to it.secs }
            .filter { it.first.isNotBlank() }
        val perFeed = dao.secondsByFeed(from, to).map { f ->
            (titles[f.feedId] ?: f.feedId) to (f.secs to f.c)
        }
        val ex = dao.extractStatsBetween(from, to).associate { it.ok to it.c }
        ReadingStats(
            totalOpens = total,
            readWithoutOpening = quick,
            totalSeconds = seconds,
            perCategory = perCategory,
            perFeed = perFeed,
            perDay = perDay.map { it.day to it.c },
            readabilityOk = ex[true] ?: 0,
            readabilityKo = ex[false] ?: 0,
            from = from,
            to = to
        )
    }

    // ----- Compte -----
    /** Infos du compte connecté (aucun appel réseau si hors-ligne : on retombe sur le statut local). */
    suspend fun accountInfo(): AccountInfo {
        val acc = activeAccount()
            ?: return AccountInfo(LOCAL_PROVIDER, "Flux RSS locaux", null, localFeeds().size)
        val fetched = runCatching { providerFor(acc.provider).profile(acc.token) }.getOrNull().orEmpty()
        val name = acc.name.ifBlank { fetched }.trim().ifBlank { null }
        val feeds = dao.subscriptions().count { sameAccount(it.provider, acc.id) }
        return AccountInfo(acc.id, "${serviceLabel(acc.service)} · compte actif", name, feeds)
    }

    /** Flux RSS saisis localement (provider « local » uniquement). */
    suspend fun localFeeds(): List<SubscriptionEntity> =
        dao.subscriptions().filter { isLocalProvider(it.provider) }.sortedBy { it.title.lowercase() }

    /**
     * Ajoute un flux RSS saisi à la main, éventuellement rangé dans un dossier. L'identifiant
     * est l'URL elle-même : PressIA n'attribue pas d'identifiant fictif, ce qui garde la
     * correspondance avec les URL affichées dans les écrans de compte.
     */
    suspend fun addLocalFeed(url: String, folderId: String? = null): String? = withContext(Dispatchers.IO) {
        val link = url.trim()
        if (link.isEmpty()) return@withContext null
        dao.upsertSubscriptions(
            listOf(SubscriptionEntity(id = link, title = link, url = link, siteUrl = link, provider = LOCAL_PROVIDER))
        )
        if (folderId != null) dao.moveFeeds(listOf(link), folderId)
        link
    }

    /**
     * Désabonne un flux. Un flux appartenant à un compte est retiré chez ce compte ; un flux RSS
     * local est simplement effacé de la base, avec ses articles.
     */
    suspend fun deleteLocalFeed(feedId: String) = withContext(Dispatchers.IO) {
        val sub = dao.subscription(feedId) ?: return@withContext
        if (!isLocalProvider(sub.provider)) return@withContext
        dao.deleteSubscription(feedId)
        dao.deleteArticlesOfFeed(feedId)
    }

    /**
     * Vérifie un cookie de session Feedly (page de connexion Feedly ouverte dans l'application).
     * Renvoie le nom de compte si le jeton est valide, null sinon.
     */
    suspend fun verifyFeedlySession(token: String): String? = withContext(Dispatchers.IO) {
        runCatching { feedly.profile(token)?.takeIf { it.isNotBlank() } }.getOrNull()
    }
}

data class AccountInfo(
    val provider: String,
    val label: String,
    /** Nom / e-mail du compte côté cloud (null si l'API ne l'a pas donné). */
    val user: String?,
    val feeds: Int
)

data class ReadingStats(
    /** Articles ouverts au moins une fois. */
    val totalOpens: Int,
    /** Articles passés en lu sans ouverture. */
    val readWithoutOpening: Int,
    /** Temps de lecture cumulé (secondes). */
    val totalSeconds: Int,
    /** Temps par dossier : label → secondes. */
    val perCategory: List<Pair<String, Int>>,
    /** Temps par flux : label → (secondes, ouvertures). */
    val perFeed: List<Pair<String, Pair<Int, Int>>>,
    /** Ouvertures par jour local : « 2026-10-01 » → nombre. */
    val perDay: List<Pair<String, Int>>,
    val readabilityOk: Int,
    val readabilityKo: Int,
    /** Bornes de la période affichée (ms). */
    val from: Long = 0L,
    val to: Long = 0L
) {
    /** Durée moyenne d'une ouverture (secondes). */
    val avgSeconds: Int get() = if (totalOpens == 0) 0 else totalSeconds / totalOpens
}

/** Périodes proposées dans les stats. */
enum class StatsPeriod(val id: String, val label: String) {
    DAY("day", "Aujourd'hui"),
    WEEK("7d", "7 jours"),
    MONTH("30d", "30 jours"),
    ALL("all", "Depuis le début");

    companion object {
        fun of(id: String): StatsPeriod = entries.firstOrNull { it.id == id } ?: WEEK
    }
}

/**
 * Bornes `[from, to]` d'une période stats.
 * - [year] > 0 force l'année civile (prioritaire sur [period]).
 * - `first` = timestamp de la toute première lecture (0 si aucune).
 */
fun statsRange(period: StatsPeriod, year: Int = 0, now: Long = System.currentTimeMillis(), first: Long = 0L): LongArray {
    if (year > 0) {
        val c = java.util.Calendar.getInstance()
        c.clear(); c.set(year, 0, 1, 0, 0, 0)
        val from = c.timeInMillis
        c.set(year + 1, 0, 1, 0, 0, 0)
        val to = c.timeInMillis - 1
        return longArrayOf(from, if (to < now) to else now)
    }
    val from = when (period) {
        StatsPeriod.DAY -> startOfDay(now)
        StatsPeriod.WEEK -> startOfDay(now) - 6L * 86400_000L
        StatsPeriod.MONTH -> startOfDay(now) - 29L * 86400_000L
        StatsPeriod.ALL -> if (first > 0L) first else 0L
    }
    return longArrayOf(from, now)
}

/** Début de la journée locale (00:00) contenant [ts]. */
fun startOfDay(ts: Long): Long {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ts
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** Bornes `[from, to]` d'une année civile. */
fun yearRange(year: Int): LongArray = statsRange(StatsPeriod.ALL, year = year, now = Long.MAX_VALUE, first = 0L)

/** Tri du bloc « temps par flux ». */
enum class FeedSort(val label: String) {
    TIME_DESC("Temps ↓"), TIME_ASC("Temps ↑"), OPENS_DESC("Ouvertures ↓"), OPENS_ASC("Ouvertures ↑")
}

/** Trie les flux par temps ou nombre d'ouvertures, croissant ou décroissant. */
fun sortFeeds(feeds: List<Pair<String, Pair<Int, Int>>>, sort: FeedSort): List<Pair<String, Pair<Int, Int>>> = when (sort) {
    FeedSort.TIME_DESC -> feeds.sortedByDescending { it.second.first }
    FeedSort.TIME_ASC -> feeds.sortedBy { it.second.first }
    FeedSort.OPENS_DESC -> feeds.sortedByDescending { it.second.second }
    FeedSort.OPENS_ASC -> feeds.sortedBy { it.second.second }
}

/** Libellé court d'un jour stats (« 2026-10-01 » → « jeu. 1/10 »). */
fun dayLabelShort(day: String): String {
    val p = day.split("-").mapNotNull { it.toIntOrNull() }
    if (p.size != 3) return day
    val c = java.util.Calendar.getInstance()
    c.clear(); c.set(p[0], p[1] - 1, p[2])
    val dow = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
        2 -> "lun."; 3 -> "mar."; 4 -> "mer."; 5 -> "jeu."; 6 -> "ven."; 7 -> "sam."; else -> "dim."
    }
    return "$dow ${p[2]}/${p[1]}"
}

/** Durée lisible : « 12 h 34 min », « 4 min 05 s », « 42 s ». */
fun fmtDuration(sec: Int): String {
    if (sec <= 0) return "0 s"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "${h} h ${"%02d".format(m)} min"
        m > 0 -> "${m} min ${"%02d".format(s)} s"
        else -> "${s} s"
    }
}

fun buildDatabase(ctx: Context): PressDatabase =
    Room.databaseBuilder(ctx, PressDatabase::class.java, "pressia.db")
        .addMigrations(PressDatabase.MIGRATION_1_2, PressDatabase.MIGRATION_2_3, PressDatabase.MIGRATION_3_4)
        .build()
