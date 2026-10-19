package com.pressia.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.pressia.data.local.*
import com.pressia.data.readability.Readability
import com.pressia.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Préfixe des identifiants de dossiers fusionnés (voir [PressRepository.folders]) :
 * les flux et le drawer n'ont ainsi qu'un seul identifiant à manipuler par dossier affiché.
 */
const val MERGED_PREFIX = "merged:"

/**
 * Pages de file montante poussées en un appel de [PressRepository.pushQueue]. La file se sert
 * par pages de 100 (`pendingSync`) : une seule page par appel laissait les gros marquages
 * groupés en attente plusieurs synchros durant. Plafond de sécurité (réseau lent, quota).
 */
private const val PUSH_MAX_PAGES = 20

/**
 * Libellé des lectures rattachées à un flux qui n'existe plus (compte supprimé, désabonnement) :
 * leurs traces sont conservées pour que les statistiques ne repartent pas de zéro, et l'écran
 * Stats les regroupe sous ce nom faute de titre en base.
 */
private const val DELETED_FEED_LABEL = "Flux supprimé"

/**
 * Convertit le jeu de favoris choisi dans l'UI en valeur attendue par les requêtes de favoris.
 *
 * L'UI et la préférence historique parlent de « app » ; la base ne connaît que « starred ».
 * Sans cette conversion, « Favoris PressIA » retombait sur « aucun filtre » et l'écran
 * Favoris affichait tous les articles au lieu des seuls étoilés.
 */
fun favScopeArg(set: String): String = when (set) {
    "later" -> "later"
    "all" -> "all"
    // L'UI et la préférence historique parlent de « app » ; la base ne connaît que « starred ».
    "app", "starred" -> "starred"
    // "" = écran de flux ordinaire : aucun filtre de favori. Sans ce cas, la valeur retombait sur
    // "starred" et le flux de lecture n'affichait plus que les articles étoilés.
    else -> ""
}

/**
 * Vrai si une synchronisation finie à [lastMs] suffit encore : moins de [windowMin] minutes.
 * Fenêtre nulle ou négative (fréquence « manuelle ») : jamais frais, on synchronise dès que
 * l'option de lancement est active. Utilisée au lancement : une synchro récente suffit,
 * on ne refait pas le travail deux fois.
 */
fun isSyncFresh(lastMs: Long, nowMs: Long, windowMin: Int): Boolean =
    lastMs > 0L && windowMin > 0 && nowMs - lastMs < windowMin * 60_000L

/**
 * Début de la fenêtre d'historique à la connexion : 1 mois vaut exactement 31 jours
 * (fenêtre au-delà de laquelle Feedly passe en lu), 2 mois et plus sont calendaires
 * (même jour du mois). Zéro ou négatif = sans limite de temps (0 ms : aucun article
 * n'est plus vieux).
 */
fun historyCutoffMs(nowMs: Long, months: Int): Long {
    if (months <= 0) return 0L
    if (months == 1) return nowMs - 31L * 24 * 3600 * 1000
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = nowMs
    c.add(java.util.Calendar.MONTH, -months)
    return c.timeInMillis
}

/**
 * Fenêtre serveur (`newerThan`) pour un historique de [months] mois et son [cutoffMs] :
 * 1 mois (31 jours fixes, le défaut rapide) part directement à Feedly, qui coupe côté
 * serveur au lieu de nous faire paginer jusqu'au cutoff. Au-delà, null : le filtre reste
 * client (sémantique serveur non validée pour les autres fenêtres). Pur, testé.
 */
fun historyNewerThan(months: Int, cutoffMs: Long): Long? = if (months == 1) cutoffMs else null

/**
 * Ne garde que les articles dans la fenêtre d'historique — sauf déjà connus (contenu à
 * rafraîchir) ou marqués (favoris, « Read later » : la durée est « hors favoris », et la
 * taille les exempte de toute façon). Sans date (0), on garde : l'âge est inconnu, pas
 * dépassé. Fenêtre nulle (sans limite) : tout passe.
 */
fun withinHistory(
    articles: List<ArticleEntity>,
    cutoffMs: Long,
    known: Set<String>
): List<ArticleEntity> {
    if (cutoffMs <= 0L) return articles
    return articles.filter { a ->
        publishedMs(a.published) !in 1 until cutoffMs || a.starred || a.laterSaved || a.id in known
    }
}

/**
 * Octets formatés pour l'affichage (« 12,4 Mo », voir `cacheInfo`) : un seul formatage pour
 * le total et le détail, sinon « 17,3 Mo » et « 17.3 Mo » coexisteraient selon l'écran.
 */
fun formatCacheBytes(b: Long): String {
    if (b < 1024) return "$b o"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.FRANCE, "%.1f Ko", kb)
    return String.format(java.util.Locale.FRANCE, "%.1f Mo", kb / 1024.0)
}

/**
 * Flux d'un compte sans aucun article : jamais approvisionnés (le global ne couvre que les
 * flux déjà connus au moment de la connexion). Vide quand le compte n'a encore aucun
 * article du tout — c'est alors l'approvisionnement global qui s'en charge, pas encore passé.
 */
fun discoverFeeds(subIds: List<String>, knownIds: Set<String>): List<String> =
    if (subIds.any { it in knownIds }) subIds.filter { it !in knownIds } else emptyList()

/**
 * Flux désabonnés côté compte : présents avant, absents après. Leurs articles partent avec
 * eux (voir syncAccount) — sinon ils survivraient comme orphelins visibles partout.
 */
fun removedFeeds(before: Set<String>, after: Set<String>): Set<String> = before - after

/**
 * Découpe la file montante en segments de lignes **consécutives** de même sorte, dans l'ordre
 * de la file (`createdAt`, voir `pendingSync`) : `pushQueue` pousse chaque segment en un appel,
 * en respectant la chronologie.
 *
 * Ne jamais regrouper par sorte sur toute la file : l'ordre des actions d'un même article
 * (ou d'un `markAllRead` face aux `unread` d'une annulation) serait inversé, et le serveur
 * finirait sur l'état le plus ancien. Les lignes « star » / « unstar » d'anciennes versions
 * sont écartées (état local : rien à pousser, juste à vider).
 */
fun syncActionRuns(actions: List<SyncActionEntity>): List<List<SyncActionEntity>> {
    val runs = mutableListOf<MutableList<SyncActionEntity>>()
    actions.filter { it.kind != "star" && it.kind != "unstar" }.forEach { a ->
        val last = runs.lastOrNull()
        if (last != null && last.first().kind == a.kind) last.add(a) else runs.add(mutableListOf(a))
    }
    return runs
}

/**
 * Cibles d'un segment de « tout marquer lu » : les flux listés, et/ou tout le compte.
 *
 * Une ligne **sans flux** (`feedId` nul ou vide) est un « tout le compte » (vue « Tous les
 * articles »), pas une ligne à jeter : c'est justement ce que l'ancien vidage faisait, si bien
 * que le marquage global n'atteignait jamais le serveur et que la synchro suivante rouvrait
 * les articles.
 */
data class MarkAllReadPush(val feedIds: List<String>, val global: Boolean) {
    /** Rien à pousser (ligne d'une version antérieure retombée sans cible). */
    val nothing: Boolean get() = feedIds.isEmpty() && !global
}

fun markAllReadPush(rows: List<SyncActionEntity>): MarkAllReadPush = MarkAllReadPush(
    feedIds = rows.mapNotNull { it.feedId?.takeIf { it.isNotBlank() } }.distinct(),
    global = rows.any { it.feedId.isNullOrBlank() }
)

/**
 * Flux sous-approvisionnés à rattraper : le compte dit plus de non-lus que le local n'en
 * connaît, alors que le flux n'a pas atteint son quota (il reste de la place pour
 * l'historique manquant). Un flux au quota est exclu : la taille supprime au-delà du
 * quota de toute façon, le rattrapage n'y changerait rien.
 * Les flux déjà couverts par la découverte ([discoverFeeds]) sont exclus par l'appelant.
 */
fun laggingFeeds(
    serverUnread: Map<String, Int>,
    localUnread: Map<String, Int>,
    quota: Map<String, Int>,
    keep: Int
): List<String> = serverUnread
    .filter { (id, count) -> count > (localUnread[id] ?: 0) && (quota[id] ?: 0) < keep }
    .keys.toList()

/**
 * Flux saturé : le quota est atteint et la page n'apporte rien à rafraîchir — inutile
 * d'aller chercher plus vieux, tout serait jeté. Un flux jamais rempli ou dont la page
 * recroise du connu continue (rafraîchissement du contenu).
 */
fun feedSaturated(count: Int, keep: Int, hasKnown: Boolean): Boolean =
    count >= keep && !hasKnown

/**
 * Ne garde que les articles à insérer sans dépasser le quota par flux : au-delà de [keep]
 * articles comptés pour un flux, les suivants sont sautés (la taille les supprimerait de
 * toute façon). Les compteurs [counts] (issus de [PressDao.quotaCounts]) sont incrémentés
 * au fil de l'acceptation ; les articles déjà connus ou marqués (favoris, « Read later »)
 * passent toujours — les connus pour rafraîchir leur contenu, les marqués car la taille
 * les exempte de toute façon.
 */
fun capByQuota(
    articles: List<ArticleEntity>,
    counts: MutableMap<String, Int>,
    known: Set<String>,
    keep: Int
): List<ArticleEntity> = articles.filter { a ->
    if (a.id in known || a.starred || a.laterSaved) true
    else if ((counts[a.feedId] ?: 0) < keep) {
        counts[a.feedId] = (counts[a.feedId] ?: 0) + 1
        true
    } else false
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
    /** Contexte applicatif. */
    val appCtx: Context get() = ctx.applicationContext
    private val dao get() = db.dao()

    /**
     * Verrou partagé de synchronisation (bouton, lancement : même processus,
     * donc un Mutex suffit). Une seule synchro effective à la fois : les déclenchements
     * concurrents s'exécutent en file, et chacun revérifie la fraîcheur une fois le verrou
     * acquis — le second constate que le premier vient de synchroniser et saute son tour.
     */
    private val syncMutex = Mutex()
    /** Sérialise la mise à jour des empreintes (lecture-modification-écriture d'une map JSON). */
    private val hashMutex = Mutex()
    /**
     * Verrou de la passe images. Deux passes se chevauchaient à chaque synchro — le pipeline en
     * lance une et le tableau « Read later », exécuté en parallèle, en relançait une de son côté :
     * les deux lisaient la même liste d'articles et téléchargeaient deux fois les mêmes images.
     * La seconde ne trouve plus rien à faire une fois la première finie (les articles marqués
     * sont déjà sortis de la file), elle rend donc 0 aussitôt.
     */
    private val imagesMutex = Mutex()
    /**
     * Verrou de la file montante. `pushQueue` part de sept endroits (lecture d'un article,
     * étoile, « tout marquer lu », marquages à l'unité et leurs annulations, pipeline, synchro
     * de connexion) et seuls les deux derniers tiennent [syncMutex] : deux appels qui se
     * chevauchaient relisaient la **même** page de file (la lecture précède la poussée, qui
     * dure un aller-retour réseau) et poussaient deux fois les mêmes lignes, chacun annonçant
     * le total de la file dans le log (« file: N lignes poussées », N compté deux fois).
     * Un verrou dédié, et non [syncMutex] : `syncPipeline` le détient déjà quand il appelle
     * `pushQueue`, un Mutex n'étant pas réentrant, ce serait un interblocage.
     */
    private val pushMutex = Mutex()

    suspend fun <T> withSyncLock(block: suspend () -> T): T = syncMutex.withLock { block() }
    /**
     * Variante non bloquante : exécute [block] sous verrou, ou rend null sans attendre si une
     * synchro est déjà en cours (le déclenchement manuel est alors annulé, pas mis en queue).
     */
    suspend fun <T> tryWithSyncLock(block: suspend () -> T): T? {
        if (!syncMutex.tryLock()) return null
        try { return block() } finally { syncMutex.unlock() }
    }

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

    /**
     * Enregistre (ou met à jour) un compte et en fait le compte actif. Une reconnexion du
     * même utilisateur (nouveau jeton) remplace le jeton sur place au lieu d'ajouter une
     * seconde ligne : deux fois le même compte est interdit (dossiers et flux dupliqués).
     */
    suspend fun addAccount(service: String, token: String, name: String = ""): CloudAccount = withContext(Dispatchers.IO) {
        val svc = service.lowercase()
        val p = providerFor(svc)
        val userId = runCatching { p.userId(token) }.getOrDefault("")
        var accounts = cloudAccounts()
        // Comptes préexistants sans identifiant distant (versions antérieures) : une seule
        // résolution chacun, persistée — sinon chaque reconnexion recréerait un doublon
        // que le rapprochement ne verrait pas.
        accounts.filter { it.service == svc && it.userId.isBlank() }.forEach { acc ->
            runCatching { p.userId(acc.token) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { uid ->
                accounts = accounts.map { if (it.id == acc.id) it.copy(userId = uid) else it }
            }
        }
        accounts = AccountStore.refreshToken(accounts, service, token, name, userId)
        val account = accounts.first { it.token == token && it.service == svc }
        prefs.set(Prefs.ACCOUNTS, AccountStore.encode(accounts))
        setActiveAccount(account.id)
        // Une reconnexion du même service lève l'alerte des anciens jetons : l'utilisateur a
        // déjà agi, inutile de continuer à lui demander.
        clearExpiredOfService(service)
        account
    }

    /**
     * Supprime un compte : ses dossiers, ses flux et les articles mis en cache disparaissent.
     * Les autres comptes — y compris un compte du même service — n'en pâtissent pas.
     *
     * Ordre voulu : registre, base, préférences, fichiers. Les suppressions SQL sont rapides ;
     * le ménage des fichiers est lent (milliers de miniatures) et passe en dernier pour
     * qu'une interruption ne laisse que des fichiers orphelins (inoffensifs, repris par le
     * prochain nettoyage) au lieu d'abonnements fantômes. Le cache Coil (favicons, images
     * plein format des articles) part aussi : sans cela « Cache utilisé » restait
     * plein de Mo orphelins après la déconnexion. Idem pour la file de sync du compte :
     * sans flux, ses actions partiraient chez un compte survivant, en pure perte.
     * Les préférences héritées suivent,
     * et c'est indispensable : [Prefs.TOKEN] est le repli de [cloudAccounts] quand le registre
     * est vide. Un jeton laissé en place après la suppression ferait réapparaître le compte
     * supprimé à la prochaine lecture ; à l'inverse, [Prefs.PROVIDER] pouvait désigner un
     * service alors que [Prefs.TOKEN] appartenait encore à l'ancien compte.
     */
    suspend fun deleteAccount(accountId: String) = withContext(Dispatchers.IO) {
        val accounts = cloudAccounts().filterNot { it.id == accountId }
        prefs.set(Prefs.ACCOUNTS, AccountStore.encode(accounts))
        clearAccountExpired(accountId)
        // Flux du compte avant leur effacement : leur file de sync part avec leurs articles
        // (même motif que le désabonnement en synchro). Leurs traces de lecture, elles, restent
        // (voir plus bas) : les statistiques ne repartent pas de zéro avec un compte supprimé.
        val feedsOfAccount = dao.subscriptions().filter { it.provider == accountId }.map { it.id }
        dao.clearArticlesOf(accountId)
        dao.moveFeeds(dao.subscriptionsForCategories(dao.categories().filter { it.provider == accountId }.map { it.id }).map { it.id }, null)
        dao.clearSubscriptionsOf(accountId)
        dao.clearCategoriesOf(accountId)
        // Puis les orphelins d'avant (flux retiré sans ses articles, résidu de passe
        // interrompue) : sans flux, ils pollueraient sinon le global, les non-lus et
        // les favoris alors qu'il n'y a plus de compte.
        dao.deleteOrphanArticles()
        // Les traces de lecture et d'extraction **restent** : elles ne servent qu'aux
        // statistiques, qui ne doivent pas repartir de zéro parce qu'un compte est supprimé
        // (au plus leurs lectures s'affichent sous « Flux supprimé »). Seule la file montante
        // du compte part avec lui.
        if (feedsOfAccount.isNotEmpty()) dao.deleteQueueOfFeeds(feedsOfAccount)

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
        // Fichiers en dernier (lent, tuable sans casse) : ne restent au pire que des orphelins.
        // Coil d'abord (favicons, plein format) : testé, il gardait ~2 Mo après déconnexion.
        runCatching { coil.Coil.imageLoader(ctx).diskCache?.clear() }
        gcThumbs()
        // Puis la base, dont les articles viennent de partir : sans VACUUM le fichier garde
        // les pages libérées et « Cache utilisé » ne bouge pas d'un octet.
        vacuum()
    }

    /**
     * Sessions expirées (voir `EXPIRED_ACCOUNTS`) : identifiants de comptes cloud en 401/403,
     * plus le pseudo-compte `"instapaper"` (mot de passe refusé, API simple comme session web).
     * L'écran Comptes les affiche avec une réinvite à reconnecter ; un appel authentifié réussi
     * retire le drapeau aussitôt.
     */
    suspend fun expiredAccounts(): Set<String> =
        parseExpired(prefs.data.first()[Prefs.EXPIRED_ACCOUNTS])

    suspend fun markAccountExpired(id: String) {
        val key = id.trim()
        if (key.isBlank()) return
        val cur = expiredAccounts()
        if (key in cur) return
        prefs.set(Prefs.EXPIRED_ACCOUNTS, encodeExpired(cur + key))
    }

    suspend fun clearAccountExpired(id: String) {
        val key = id.trim()
        if (key.isBlank()) return
        val cur = expiredAccounts()
        if (key !in cur) return
        prefs.set(Prefs.EXPIRED_ACCOUNTS, encodeExpired(cur - key))
    }

    /** Oublie les alertes d'un service (reconnecté) : seuls ses comptes sont concernés. */
    suspend fun clearExpiredOfService(service: String) {
        val s = service.trim().lowercase()
        if (s.isBlank()) return
        val cur = expiredAccounts()
        val kept = cur.filterNot { providerBase(it) == s }.toSet()
        if (kept.size == cur.size) return
        prefs.set(Prefs.EXPIRED_ACCOUNTS, encodeExpired(kept))
    }

    /** Compte servant aux opérations courantes (le premier connecté si aucun n'est désigné). */
    suspend fun activeAccount(): CloudAccount? {        val accounts = cloudAccounts()
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
     * Plafond de sécurité de l'approvisionnement ([backfillArticles]) : borne le nombre d'articles
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
     * synchronisation insère de nouveaux articles.
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
     * Bascule un favori.
     *
     * L'étoile reste un état **local**, jamais poussé vers le compte — sauf pour un article
     * Feedly : Feedly ne connaît qu'un seul état « enregistré » (son tableau « Read later »),
     * donc l'étoile y est rangée (`save` / `unsave` en file, comme le lu/non-lu) au lieu d'un
     * favori local. Inoreader et les flux locaux gardent l'étoile locale.
     */
    suspend fun setStarred(id: String, starred: Boolean) = withContext(Dispatchers.IO) {
        val a = dao.article(id)
        val owner = a?.feedId?.let { dao.subscription(it)?.provider }
        val feedly = cloudAccounts().any { sameAccount(it.id, owner) && providerBase(it.id) == "feedly" }
        if (a != null && feedly) {
            // Pas de favori local pour Feedly (son étoile = « Read later »), mais on efface
            // aussi l'étoile locale héritée d'avant : sinon elle ne part plus jamais.
            dao.setStarred(id, false)
            if (starred) dao.markLaterSaved(listOf(id)) else dao.unmarkLaterSaved(listOf(id))
            dao.enqueue(SyncActionEntity(articleId = id, feedId = a.feedId, kind = if (starred) "save" else "unsave"))
            pushQueue()
        } else {
            dao.setStarred(id, starred)
        }
    }
    suspend fun markAllRead(feedIds: List<String>) = withContext(Dispatchers.IO) {
        // readAt alimenté aussi pour les marquages groupés : permet de distinguer
        // « passé en lu sans ouvrir l'article » d'une vraie ouverture (stats).
        val now = System.currentTimeMillis()
        // Les favoris / « read later » ne passent jamais en lu, ni ici ni côté compte : un
        // marquage par flux les passerait en lus distants, et la réconciliation les passerait
        // alors en lus locaux au tour suivant. S'il y en a parmi les non-lus du périmètre, on
        // marque à l'unité (poussée article par article, même protection que les lectures
        // simples) au lieu du marquage par flux.
        val excluded = if (feedIds.isEmpty()) dao.allUnreadExcludedIds() else dao.feedsUnreadExcludedIds(feedIds)
        // Même repli à l'unité quand le compte ne sait pas pousser un marquage de masse
        // (voir `FeedProvider.bulkMarkAllRead`) : un marquage poussé dans le vide serait
        // rouvert par la réconciliation suivante.
        val bulk = bulkMarkAllReadAvailable(feedIds)
        if (excluded.isEmpty() && bulk) {
            if (feedIds.isEmpty()) dao.markAllRead(now) else dao.markFeedsRead(feedIds, now)
            feedIds.forEach { dao.enqueue(SyncActionEntity(articleId = "", feedId = it, kind = "markAllRead")) }
            if (feedIds.isEmpty()) dao.enqueue(SyncActionEntity(articleId = "", kind = "markAllRead"))
            pushQueue()
            return@withContext
        }
        val ids = if (feedIds.isEmpty()) dao.allMarkableIds() else dao.feedsMarkableIds(feedIds)
        markArticlesRead(ids)
    }

    /**
     * Marquage de masse poussable ? Vrai si **tous** les comptes concernés savent le faire
     * (voir `FeedProvider.bulkMarkAllRead`) : sans ça, on marque article par article, seule
     * façon de ne pas perdre le marquage sur un compte qui ignore les marqueurs de masse.
     */
    private suspend fun bulkMarkAllReadAvailable(feedIds: List<String>): Boolean {
        if (isLocal()) return false
        val services = if (feedIds.isEmpty()) cloudAccounts().map { it.service }
        else feedIds.mapNotNull { dao.subscription(it)?.provider }.map { providerBase(it) }
        return services.isNotEmpty() && services.distinct().all { providerFor(it).bulkMarkAllRead }
    }

    /**
     * Marque des articles lus à l'unité (menu « plus récents/anciens », repli du « tout
     * marquer lu » quand des favoris/read later traînent). Les favoris/read later n'y entrent
     * jamais : filtrés par l'appelant via l'étoile effective (voir `starState`), et poussés
     * article par article côté compte — sinon un marquage de flux les passerait en lus
     * distants et la réconciliation les repasserait en lus au tour suivant.
     */
    suspend fun markArticlesRead(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        ids.chunked(500).forEach { dao.markIdsRead(it, now) }
        if (!isLocal()) {
            val rows = ids.chunked(500).flatMap { chunk ->
                val feeds = dao.feedOfIds(chunk).associate { it.id to it.feedId }
                chunk.map { SyncActionEntity(articleId = it, feedId = feeds[it].orEmpty(), kind = "read") }
            }
            dao.enqueueAll(rows)
            pushQueue()
        }
    }
    /**
     * Inverse de [markArticlesRead] (bouton « Annuler » après un marquage groupé) : les
     * articles repassent en non-lus localement et côté compte (file « unread »), avec la
     * même granularité à l'unité — jamais de passage en lu massif dans l'autre sens.
     */
    suspend fun markArticlesUnread(ids: List<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        ids.chunked(500).forEach { dao.markIdsUnread(it) }
        if (!isLocal()) {
            val rows = ids.chunked(500).flatMap { chunk ->
                val feeds = dao.feedOfIds(chunk).associate { it.id to it.feedId }
                chunk.map { SyncActionEntity(articleId = it, feedId = feeds[it].orEmpty(), kind = "unread") }
            }
            dao.enqueueAll(rows)
            pushQueue()
        }
    }
    /** Non-lus marquables en masse du périmètre (voir `markAllRead`) : l'annulation sait quoi rouvrir. */
    suspend fun allMarkableIds(): List<String> = withContext(Dispatchers.IO) { dao.allMarkableIds() }
    /** Variante par flux (voir `markAllRead`). */
    suspend fun feedsMarkableIds(feedIds: List<String>): List<String> = withContext(Dispatchers.IO) {
        dao.feedsMarkableIds(feedIds)
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
                // La version du flux est mise de côté avant d'être écrasée (voir le DAO) : c'est
                // elle que « Revenir à l'extrait RSS » doit retrouver, pas le petit extrait.
                dao.saveExtractedContent(id, r.html, fromFeed = !a.fullFetched)
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

    /**
     * Coupe « article complet » d'un article : la version du flux redevient son contenu (voir
     * ArticleScreen). L'annulation vaut pour les réouvertures de CET article — le mode épinglé
     * par l'icône, lui, est celui de la session et reste piloté par `readabilityOn`.
     */
    suspend fun revertFullContent(id: String, html: String) = withContext(Dispatchers.IO) {
        dao.revertFullContent(id, html)
    }

    // ----- Sync descendante -----

    /**
     * Un réseau utilisable est-il disponible ? Sans lui, une synchronisation ne peut
     * rien récupérer — l'appelant affiche « pas de réseau » au lieu d'échouer en vague.
     */
    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Étape de synchronisation en cours, manuelle ou lancement ; `null` = aucune.
     * Le modèle la recopie dans `syncMsg` (en ignorant les nulls)
     * pour le bandeau : une seule source pour les étapes, quel que soit l'appelant.
     * Remis à null en fin de passe (voir les `finally`) : sans cela un écran rouvert
     * dans le même processus rejouait la dernière étape.
     * Écrit depuis des fils IO (StateFlow thread-safe), lu sur le fil principal.
     */
    val syncStep = MutableStateFlow<String?>(null)

    /**
     * Listes à rafraîchir : émis quand le contenu stocké change sous les pieds de l'interface
     * (abonnements approvisionnés, fin de passe articles ou « Read later »), pour que l'accueil
     * et les compteurs suivent sans attendre la fin de la synchronisation. Le modèle collecte
     * et relance son remplissage — sans effet sur le bandeau, qui suit [syncStep].
     */
    val listsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Signale que les listes doivent se rafraîchir (voir [listsChanged]). Ne suspend jamais. */
    fun pokeLists() {
        listsChanged.tryEmit(Unit)
    }

    /** Un pipeline tient-il le verrou partagé ? L'approvisionnement tait ses étapes dans ce cas. */
    val isSyncLocked: Boolean get() = syncMutex.isLocked

    /**
     * Un 429 a frappé pendant la synchronisation en cours (remis à zéro à chaque pipeline) :
     * le message final l'explique avec le délai du serveur au lieu d'un succès aveugle.
     * Écrit depuis des fils IO (plusieurs comptes en parallèle : le dernier gagne, ils sont
     * tous interchangeables ici), lu par le message final sous le verrou partagé.
     */
    @Volatile var syncRateLimit: RateLimit? = null
        private set

    /**
     * Mémorise un 429 (avec son `Retry-After`) sans avaler l'erreur : les échecs par compte
     * restent contenus, mais le message final dira que la synchro n'a pas pu avoir lieu.
     */
    fun noteRateLimit(e: Throwable, service: String) {
        rateLimitOf(e, service)?.let { syncRateLimit = it }
    }

    /**
     * De quoi synchroniser ? Un compte cloud ou au moins un abonnement (local ou orphelin).
     * Sans source, la synchronisation de lancement ne part pas : premier démarrage ou après
     * déconnexion, elle n'aurait rien à récupérer.
     */
    suspend fun hasSources(): Boolean = cloudAccounts().isNotEmpty() || dao.subscriptions().isNotEmpty()

    /**
     * Pipeline de synchronisation complet, commun à tous les déclencheurs : file
     * montante, articles (+ taille), puis miniatures et « Read later » en parallèle,
     * et favicons (sauf [withFavicons] faux : le lancement ne précharge pas les favicons —
     * ils sont déjà en cache Coil et se rechargent à l'affichage, seul le manuel le fait ;
     * et seulement s'il y a des abonnements : pas d'étape à vide pour zéro flux).
     * Publie les étapes dans [syncStep] au fil de l'eau et recule
     * [Prefs.LAST_SYNC_AT] en cas de succès (lève en cas d'échec, comme avant).
     * Le verrou partagé, l'indicateur et le message final restent à l'appelant
     * (verrou déjà acquis ici).
     *
     * Images et « Read later » partent ensemble : le Read later est bref (1 appel
     * par compte) et silencieux — le bandeau garde le compteur des images. Le
     * [supervisorScope] préserve la sémantique d'échec séquentielle : chaque branche
     * va au bout, la première exception remonte et annule le succès (pas de
     * [Prefs.LAST_SYNC_AT]) — sans qu'un échec des images n'interrompe le tableau,
     * ni l'inverse. La taille ayant déjà eu lieu dans [syncDown], aucun article que le
     * Read later marque ne peut être supprimé entre-temps.
     */
    suspend fun syncPipeline(withFavicons: Boolean = true): Int {
        // Un seul pipeline à la fois (verrou partagé) : ce drapeau est celui de la synchro
        // en cours, lu par le message final.
        syncRateLimit = null
        val t0 = System.currentTimeMillis()
        try {
        if (hasPendingSync()) syncStep.value = "Envoi des modifications…"
        val tPush = System.currentTimeMillis()
        pushQueue()
        val tDown = System.currentTimeMillis()
        syncStep.value = "Réception des articles…"
        val n = syncDown(withThumbs = false, onSource = { src -> syncStep.value = "Réception des articles… ($src)" })
        val tImg = System.currentTimeMillis()
        val withThumbs = imagesAllowed()
        if (withThumbs) syncStep.value = "Téléchargement des images…"
        supervisorScope {
            val thumbs = async {
                if (withThumbs) syncImages { done, total -> syncStep.value = "Téléchargement des images… $done/$total" }
            }
            val later = async { syncReadLater() }
            thumbs.await()
            later.await()
        }
        val tFav = System.currentTimeMillis()
        if (withFavicons && withThumbs && dao.subscriptions().isNotEmpty()) {
            syncStep.value = "Récupération des favicons…"
            syncFavicons()
        }
        // Toute synchro bien finie recule le garde-fou : le lancement suivant saute si
        // elle date de moins de X minutes.
        prefs.set(Prefs.LAST_SYNC_AT, System.currentTimeMillis())
        // Chronométrage par phase (poussée, descente, images+tableau, favicons) : la descente
        // et les images dominent à tour de rôle selon les nouveautés — sans lui on optimise à l'aveugle.
        android.util.Log.i("PressIA", "pipeline: montée=${tDown - tPush}ms descente=${tImg - tDown}ms images+tableau=${tFav - tImg}ms favicons=${System.currentTimeMillis() - tFav}ms total=${System.currentTimeMillis() - t0}ms")
        return n
        } finally {
            // Plus aucune étape : sans cela l'écran rouvert plus tard dans le même processus
            // rejouait la dernière étape en bandeau (« Récupération des favicons… »).
            // Le message final passe par `syncMsg` (qui ignore les nulls), pas d'interruption.
            syncStep.value = null
        }
    }

    /**
     * Récupère les articles de tous les comptes et renvoie le **nombre de nouveaux articles**
     * enregistrés, pas le total reçu : annoncer « 240 articles récupérés » à chaque
     * synchronisation alors que tout était déjà connu rend le message illisible.
     *
     * Chaque compte est traité séparément avec son propre jeton et ses propres dossiers.
     *
     * [onSource] annonce chaque source contactée (« flux locaux », « Feedly »…) : le bandeau
     * précise ainsi d'où viennent les articles. Appelé depuis des fils IO (comme [onProgress]
     * de [syncImages]) ; les comptes cloud partent en parallèle, l'ordre varie.
     */
    suspend fun syncDown(feedId: String? = null, categoryId: String? = null, withThumbs: Boolean = true, onSource: ((String) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        val max = maxItems()
        val t0 = System.currentTimeMillis()
        var added = 0
        // Flux RSS saisis à la main : sans compte, donc toujours synchronisés.
        runCatching {
            local.feeds = dao.subscriptions()
            if (local.feeds.any { isLocalProvider(it.provider) }) onSource?.invoke("flux locaux")
            val page = local.stream(null, feedId, categoryId, max)
            // keepRead : le flux RSS ne porte aucun état de lecture, tout article déjà connu
            // garde donc son lu/non-lu local (voir `storeArticles`).
            added += storeArticles(page.articles, keepRead = true)
        }.onFailure { android.util.Log.e("PressIA", "sync local failed", it) }

        // Comptes cloud en parallèle : chacun a son jeton et ses propres dossiers, et les
        // écritures concurrentes sont sérialisées par Room. Sur un flux précis, seul le
        // compte qui le détient est interrogé.
        val owner = feedId?.let { dao.subscription(it)?.provider }
        added += coroutineScope {
            cloudAccounts()
                .filter { owner == null || sameAccount(owner, it.id) }
                .map { account ->
                    async {
                        onSource?.invoke(account.label)
                        // Avancement de la réception par flux (découverte d'un flux jamais
                        // approvisionné, puis rattrapage des flux en retard) : c'est la partie
                        // longue d'un compte, et sans compteur le bandeau semble figé. Le pas est
                        // celui du compte ; plusieurs comptes en parallèle partagent le bandeau,
                        // comme l'étape elle-même.
                        val step: (Int, Int) -> Unit = { d, t ->
                            syncStep.value = "Réception des articles… (${account.label}, flux $d/$t)"
                        }
                        runCatching { syncAccount(account, feedId, categoryId, max, step) }
                            .onFailure {
                                // Ne jamais avaler l'annulation : sinon la synchro
                                // « annulée » continue en tâche de fond (résurrection
                                // d'articles après une déconnexion, verrou jamais rendu).
                                if (it is CancellationException) throw it
                                noteRateLimit(it, account.label)
                                android.util.Log.e("PressIA", "sync ${account.id} failed", it)
                            }
                            .getOrDefault(0)
                    }
                }.awaitAll().sum()
        }
        replayRestoredStarred()
        replayRestoredRead()
        trimToMax()
        // Listes et compteurs à jour avant la (longue) passe miniatures : l'accueil n'attend
        // pas la fin de la synchronisation. Les listes d'articles, elles, sont réactives.
        pokeLists()
        // Les images peuvent être sorties pour annoncer leur propre étape (synchro manuelle) :
        // par défaut elles suivent les articles, comme avant.
        if (withThumbs) syncImages()
        android.util.Log.i("PressIA", "syncDown: $added nouveaux en ${System.currentTimeMillis() - t0} ms")
        added
    }

    /**
     * Le « Wi-Fi uniquement » bloque-t-il les téléchargements ? Vrai si le réglage est actif
     * et que le réseau courant n'est ni Wi-Fi ni Ethernet.
     */
    fun isWifiOnlyBlocked(wifiOnly: Boolean): Boolean {
        if (!wifiOnly) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Téléchargement d'images autorisé en ce moment ? Faux si « Images hors-ligne » est coupé,
     * ou si « Wi-Fi uniquement » est actif hors Wi-Fi/Ethernet : même une passe manuelle sur
     * données mobiles ne doit pas vider le forfait.
     */
    suspend fun imagesAllowed(): Boolean {
        val data = prefs.data.first()
        if (data[Prefs.IMAGES_OFFLINE] == false) return false
        return !isWifiOnlyBlocked(data[Prefs.WIFI_ONLY] ?: true)
    }

    /**
     * Récupère les images manquantes des articles — la miniature déclarée par le flux **et** les
     * images du corps —, les articles les plus récents d'abord, sans borne : la première
     * synchronisation télécharge tout le stock (c'est normal qu'elle soit lente), les suivantes
     * ne récupèrent que les nouveautés. Y compris tout le stock après un vidage du cache, le
     * suivi [ArticleEntity.imageCached] ayant alors été réinitialisé.
     *
     * La miniature déclarée va dans [ThumbStore] : un fichier au gabarit, jamais évincé, que
     * l'écran article affiche en priorité (voir `thumbModel`) — le retélécharger n'aurait aucun
     * sens. Les images du corps vont dans le cache disque de Coil, comme les favicons
     * ([prefetchImage]) : ce sont des images pleine taille, en trop grand nombre pour un fichier
     * dédié, et Coil les sert ensuite sans requête à l'affichage. Une URL déjà servie par un
     * fichier local est donc sautée, et une URL partagée par plusieurs articles n'est demandée
     * qu'une fois (l'attente du réseau reste bornée à six requêtes à la fois).
     *
     * Inactif si « Images hors-ligne » est coupé, ou si « Wi-Fi uniquement » est actif hors
     * Wi-Fi/Ethernet : une passe manuelle sur données mobiles ne doit pas vider le forfait.
     * Idempotent : seules les URLs manquantes sont téléchargées.
     *
     * [onProgress] est appelé sur le fil IO avec le nombre déjà téléchargé et le total —
     * d'abord `0/total` dès le départ (le total est connu aussitôt la requête faite), puis au
     * plus toutes les 200 ms, et toujours à la fin. Le palier est temporel plutôt que par
     * nombre : ~5 affichages/s quel que soit le débit — ni inondation du bandeau quand les
     * fichiers répondent aussitôt (déjà en cache), ni compteur figé quand le réseau traîne.
     * Seules les réussites incrémentent : des URL en échec ou lentes retardent les paliers,
     * mais le total s'affiche sans attendre.
     */
    suspend fun syncImages(onProgress: ((done: Int, total: Int) -> Unit)? = null): Int =
        imagesMutex.withLock { syncImagesLocked(onProgress) }

    /**
     * Corps de [syncImages], sous son verrou (voir [imagesMutex]) : jamais deux passes en
     * parallèle sur les mêmes articles.
     */
    private suspend fun syncImagesLocked(onProgress: ((done: Int, total: Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        if (!imagesAllowed()) return@withContext 0
        val rows = dao.articlesPendingImages()
        if (rows.isEmpty()) return@withContext 0
        // URLs attendues, article par article (miniature déclarée puis corps) : relevées une
        // seule fois, elles servent au téléchargement comme au marquage final.
        val perArticle = rows.map { r -> r.id to articleImageUrls(r.imageUrl, r.content, r.summary, r.url) }
        val thumbs = rows.mapNotNull { r -> r.imageUrl?.trim()?.takeIf { it.startsWith("http") } }.distinct()
        val bodies = perArticle.flatMap { (_, urls) -> urls }.distinct().filterNot { it in thumbs }
        val work: List<Pair<String, Boolean>> = thumbs.map { it to true } + bodies.map { it to false }
        if (work.isEmpty()) {
            // Articles sans image : marqués tout de suite, sinon leur HTML serait relu à chaque
            // synchronisation sans rien à faire.
            rows.map { it.id }.chunked(500).forEach { dao.markArticlesImagesCached(it) }
            return@withContext 0
        }
        val total = work.size
        onProgress?.invoke(0, total)
        val (maxW, maxH) = ThumbStore.maxThumbSize(ctx)
        val permits = Semaphore(6)
        val doneCount = AtomicInteger(0)
        // Dernier affichage publié (horloge monotone) : les 6 téléchargements parallèles
        // partagent ce garde-fou via compareAndSet, sans verrou.
        val lastPublish = AtomicLong(0L)
        val done = coroutineScope {
            work.map { (url, isThumb) ->
                async {
                    val ok = permits.withPermit {
                        if (isThumb) ThumbStore.fetch(ctx, url, maxW, maxH)
                        // Image du corps déjà présente en local (miniature d'un autre article,
                        // image partagée) : rien à demander.
                        else ThumbStore.cachedFile(ctx, url) != null || prefetchImage(url)
                    }
                    if (ok && onProgress != null) {
                        val n = doneCount.incrementAndGet()
                        if (n == total) {
                            onProgress(n, total)
                        } else {
                            val now = android.os.SystemClock.uptimeMillis()
                            val prev = lastPublish.get()
                            if (now - prev >= 200 && lastPublish.compareAndSet(prev, now)) {
                                onProgress(n, total)
                            }
                        }
                    }
                    ok
                }
            }.awaitAll()
        }
        onProgress?.invoke(doneCount.get().coerceAtMost(total), total)
        // Marquage groupé, article par article : un article dont une seule image manque reste
        // en attente (elle serait sinon jamais reprise). Une requête par paquet.
        val okUrls = work.filterIndexed { i, _ -> done[i] }.mapTo(HashSet()) { it.first }
        perArticle.filter { (_, urls) ->
            urls.all { it in okUrls || ThumbStore.cachedFile(ctx, it) != null }
        }.map { (id, _) -> id }.chunked(500).forEach { dao.markArticlesImagesCached(it) }
        done.count { it }
    }

    /**
     * Précharge une image dans le cache disque de Coil, sans l'afficher (même mécanisme que les
     * favicons). La taille demandée est minuscule à dessein : c'est le cache **disque** qui
     * évite la requête à l'affichage, et il garde les octets d'origine quelle que soit la taille
     * demandée ; le décodage n'a donc pas à porter l'image entière, et le cache mémoire (borné)
     * n'a rien à retenir d'une image que personne n'a encore regardée.
     */
    private suspend fun prefetchImage(url: String): Boolean = runCatching {
        coil.Coil.imageLoader(ctx).execute(
            coil.request.ImageRequest.Builder(ctx)
                .data(url)
                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                .size(coil.size.Size(1, 1))
                .build()
        )
    }.getOrNull() is coil.request.SuccessResult

    /**
     * Première synchronisation d'un compte nouvellement connecté, en un seul passage.
     *
     * L'historique remonte flux par flux depuis le plus récent ([backfillArticles], bornes
     * quota / fenêtre / plafond) : le récent est inclus, aucune synchro préalable ne
     * retélécharge les mêmes pages. Suivent la vérification des flux ([provisionHistory] :
     * un flux dont la première page est vide restait sinon vide jusqu'à la prochaine
     * synchro), puis ce que la synchro faisait en plus — file montante d'abord (actions
     * hors-ligne en attente), réconciliation du lu/non-lu, rejeux de restauration, taille,
     * puis miniatures + « Read later » en parallèle et favicons — avec un seul récit au
     * bandeau et un seul [Prefs.LAST_SYNC_AT].
     *
     * Seul le compte connecté est couvert : les autres comptes et les flux locaux suivent
     * leur rythme (synchros manuelle / lancement), sans repasser par le réseau ici.
     * Le verrou partagé est tenu pendant toute la passe : une synchro manuelle entre-temps
     * est refusée (« déjà en cours ») au lieu de s'entrelacer.
     */
    suspend fun initialSync(accountId: String): Int = withSyncLock {
        withContext(Dispatchers.IO) {
            try {
            syncRateLimit = null
            val account = cloudAccounts().firstOrNull { it.id == accountId } ?: return@withContext 0
            if (hasPendingSync()) syncStep.value = "Envoi des modifications…"
            pushQueue()
            syncStep.value = "Réception des articles…"
            val (n, backfillUnread) = backfillArticles(account)
            // Vérification des flux (découverte + rattrapage, mêmes bornes) : à la connexion,
            // un flux muet reste sinon vide — et ses non-lus avec.
            syncStep.value = "Vérification des flux…"
            val prefsData = prefs.data.first()
            val keep = (prefsData[Prefs.MAX_ITEMS] ?: 1000).coerceAtLeast(1)
            val cutoff = historyCutoffMs(System.currentTimeMillis(), prefsData[Prefs.HISTORY_MONTHS] ?: Prefs.HISTORY_MONTHS_DEFAULT)
            val counts = dao.quotaCounts().associate { it.feedId to it.c }.toMutableMap()
            val subs = dao.subscriptions().filter { sameAccount(it.provider, account.id) }
            // Compteurs déjà remontés par le backfill si joignables : pas de 3e appel au même
            // endpoint (sinon `provisionHistory` les récupère lui-même, avec son nouvel essai).
            // Fenêtre serveur comme le backfill : à 1 mois, Feedly coupe côté serveur.
            val newerThanInit = historyNewerThan(prefsData[Prefs.HISTORY_MONTHS] ?: Prefs.HISTORY_MONTHS_DEFAULT, cutoff)
            val (provAdded, serverTotal) = provisionHistory(account, subs, counts, keep, cutoff, backfillUnread, newerThanInit) { d, t ->
                syncStep.value = "Vérification des flux… $d/$t"
            }
            val completed = n + provAdded
            syncStep.value = "Réconciliation…"
            reconcileAccount(account, completed, serverTotal)
            replayRestoredStarred()
            replayRestoredRead()
            // Même ordre que la synchro normale : la taille avant les miniatures (ni images
            // pour des lignes aussitôt supprimées), listes à jour avant la longue passe.
            syncStep.value = "Application du quota…"
            trimToMax()
            pokeLists()
            val withThumbs = imagesAllowed()
            if (withThumbs) syncStep.value = "Téléchargement des images…"
            supervisorScope {
                val thumbs = async {
                    if (withThumbs) syncImages { done, total -> syncStep.value = "Téléchargement des images… $done/$total" }
                }
                val later = async { syncReadLater() }
                thumbs.await()
                later.await()
            }
            if (withThumbs && dao.subscriptions().isNotEmpty()) {
                syncStep.value = "Récupération des favicons…"
                syncFavicons()
            }
            prefs.set(Prefs.LAST_SYNC_AT, System.currentTimeMillis())
            completed
            } finally {
                // Voir syncPipeline : aucune étape ne doit survivre à la passe.
                syncStep.value = null
            }
        }
    }

    /**
     * Réconciliation du lu/non-lu distant d'un compte : les articles ne redescendent que par
     * fenêtre récente, les lus / non lus d'ailleurs plus anciens n'y figureraient jamais sans
     * elle — d'où la comparaison aux non-lus du compte sur 30 jours (borné : au-delà, on ne
     * fait que rouvrir, jamais passer en lu à l'aveugle — de toute façon le local ne conserve
     * que ça, et Feedly marque lu à 31 j). Énumération sautée si la passe n'a rien apporté,
     * sans action locale en attente et total distant inchangé : 1 appel léger au lieu de N
     * pages (voir `reconcileUnchanged`). Partagée par la synchro normale et la première
     * synchronisation (qui remonte l'historique en un seul passage).
     */
    private suspend fun reconcileAccount(account: CloudAccount, added: Int, total: Int? = null) {
        val p = providerFor(account.provider)
        runCatching {
            // Total mutualisé quand l'appelant a déjà les compteurs par flux (même endpoint
            // que `unreadTotal`, voir levier 2) : sinon un appel dédié, comme avant. Seuls les
            // flux comptent (la réponse inclut aussi les dossiers, qui doublent les flux) : la
            // première passe après ce changement refait une réconciliation complète, sans danger.
            val t = total ?: p.unreadTotal(account.token)
            val pending = dao.pendingSync().isNotEmpty()
            val last = reconcileTotals()[account.id]
            if (com.pressia.data.remote.reconcileUnchanged(added, pending, last, t)) {
                android.util.Log.i("PressIA", "reconcile ${account.id} sauté (total $t inchangé)")
            } else {
                val (read, unread) = reconcileReadState(account)
                if (t != null) storeReconcileTotal(account.id, t)
                if (read > 0 || unread > 0) android.util.Log.i("PressIA", "reconcile ${account.id}: $read passés en lus, $unread rouverts")
            }
        }.onFailure {
            if (it is CancellationException) throw it
            noteRateLimit(it, account.label)
            android.util.Log.e("PressIA", "reconcile ${account.id} failed", it)
        }
    }

    /**
     * Compteurs de non-lus par flux du compte, restreints à ses abonnements. Null si
     * l'appel échoue (le rattrapage est sauté, pas la synchro) — voir l'essai unique
     * dans [provisionHistory] : sous la rafale de la connexion, cet appel flanche parfois.
     */
    private suspend fun fetchServerUnread(
        account: CloudAccount,
        subIds: Set<String>
    ): Map<String, Int>? =
        runCatching { providerFor(account.provider).unreadCountsByFeed(account.token) }
            .onFailure {
                if (it is CancellationException) throw it
                noteRateLimit(it, account.label)
                android.util.Log.w("PressIA", "rattrapage ${account.id} : compteurs injoignables ($it)")
            }
            .getOrNull()?.filterKeys { it in subIds }

    /**
     * Approvisionne l'historique manquant d'un compte : découverte des flux jamais vus +
     * rattrapage des flux sous-approvisionnés (dont repli dossier quand le flux propre est
     * muet), mêmes bornes partout (quota, fenêtre, plafond). Silencieux (récit nul) :
     * l'appelant tient le bandeau. Renvoie les nouveaux articles.
     * Partagé par la synchro normale et la première synchronisation : un flux dont la
     * première page est vide à la connexion est réparé dans la même passe, pas à la
     * synchro suivante.
     */
    private suspend fun provisionHistory(
        account: CloudAccount,
        subs: List<SubscriptionEntity>,
        counts: MutableMap<String, Int>,
        keep: Int,
        cutoff: Long,
        prefetched: Map<String, Int>? = null,
        newerThanMs: Long? = null,
        onFeed: ((done: Int, total: Int) -> Unit)? = null
    ): Pair<Int, Int?> {
        var added = 0
        val folders = subs.mapNotNull { s -> s.categoryId?.let { s.id to it } }.toMap()
        val subIds = subs.map { it.id }.toSet()
        // Compteurs déjà récupérés par l'appelant (tête parallèle, même endpoint qu'avant) :
        // sinon un appel dédié + un nouvel essai, comme avant.
        var serverUnread = prefetched
        if (serverUnread == null) {
            serverUnread = fetchServerUnread(account, subIds)
            if (serverUnread == null) {
                // Compteurs injoignables sous la rafale (constaté à la connexion : la passe
                // manuelle suivante les obtient sans faute) — un seul nouvel essai après une
                // courte pause, sinon le rattrapage sauterait en silence. Annulable (déconnexion).
                kotlinx.coroutines.delay(3000)
                serverUnread = fetchServerUnread(account, subIds)
            }
        }
        // Repli dossier seulement si le serveur signale des non-lus manquants (levier 4) :
        // ratisser tout un dossier pour un flux à zéro ne rapporte rien (constaté +110/14000
        // lus pour un seul flux). Compteurs absents : repli autorisé (comportement d'avant).
        val localUnread = serverUnread?.let { dao.unreadByFeed().associate { r -> r.feedId to r.c } }.orEmpty()
        val allowFallback = { feedId: String ->
            serverUnread == null || (serverUnread[feedId] ?: 0) > (localUnread[feedId] ?: 0)
        }
        val fresh = discoverFeeds(subs.map { it.id }, dao.articleFeedIds().toSet())
        // Compteur de flux (découverte + rattrapage sans chevauchement : le rattrapage exclut
        // les découverts) : l'appelant affiche « x/y » s'il tient le bandeau, sinon silencieux
        // (plusieurs comptes partagent le même bandeau en synchro normale).
        // Le total est celui de la phase en cours — le rattrapage n'est connu qu'après (il dépend
        // des compteurs mis à jour par la découverte) : « 1/3 » puis « 4/5 » reste croissant et
        // finit sur le vrai total, là où un total figé au nombre d'abonnements annonçait « 3/42 »
        // puis s'arrêtait.
        var feedDone = 0
        if (fresh.isNotEmpty()) {
            android.util.Log.i("PressIA", "découverte ${account.id} : ${fresh.size} flux à approvisionner")
            fresh.forEach { feedId ->
                added += healFeed(account, feedId, folders[feedId], counts, keep, cutoff, allowFallback = allowFallback, newerThanMs = newerThanMs).first
                feedDone++
                onFeed?.invoke(feedDone, fresh.size)
            }
        }
        // Flux sous-approvisionnés (page vide transitoire, passe interrompue) : le compte
        // dit plus de non-lus que le local n'en connaît. Flux au quota exclus (voir
        // laggingFeeds), déjà découverts aussi. Rien à faire dans le cas courant (une
        // différence de cartes en mémoire + un appel compteurs léger).
        if (serverUnread == null) {
            android.util.Log.w("PressIA", "rattrapage ${account.id} : compteurs toujours injoignables, passé (prochaine synchro)")
        } else if (serverUnread.isNotEmpty()) {
            val lagging = laggingFeeds(serverUnread, localUnread, counts, keep)
                .filter { it !in fresh }
            if (lagging.isNotEmpty()) {
                android.util.Log.i("PressIA", "rattrapage ${account.id} : ${lagging.size} flux en retard, historique remonté")
                lagging.forEach { feedId ->
                    // healFeed refait la passe par flux (rapide si déjà connue) puis le repli :
                    // le +A/L distingue un compteur fantôme d'un vrai manque.
                    val (a, s, _) = healFeed(account, feedId, folders[feedId], counts, keep, cutoff, allowFallback = allowFallback, newerThanMs = newerThanMs)
                    added += a
                    feedDone++
                    onFeed?.invoke(feedDone, fresh.size + lagging.size)
                    android.util.Log.i("PressIA", "rattrapage ${account.id} : $feedId +$a/$s")
                }
            }
        }
        return added to serverUnread?.values?.sum()
    }

    /** Synchronise un compte : flux d'articles, puis dossiers, abonnements (dont articles
     * et miniatures des flux désabonnés), historique des flux découverts et rattrapage des
     * flux sous-approvisionnés si vue globale. [onFeed] fait avancer le bandeau flux par flux
     * (voir `syncDown`), silencieux depuis la première synchronisation qui a son propre récit. */
    private suspend fun syncAccount(
        account: CloudAccount,
        feedId: String?,
        categoryId: String?,
        max: Int,
        onFeed: ((done: Int, total: Int) -> Unit)? = null
    ): Int {
        try {
            val p = providerFor(account.provider)
            if (feedId != null || categoryId != null) {
                // Périmètre restreint : le flux seul, comme avant (ni dossiers ni compteurs).
                val only = p.stream(account.token, feedId, categoryId, max)
                val added = storeArticles(only.articles)
                // Le jeton a parlé : la session est bonne, on oublie une éventuelle alerte.
                clearAccountExpired(account.id)
                return added
            }
            // Vue globale : les 4 appels indépendants partent ensemble (flux récent, dossiers,
            // abonnements, compteurs) — latence du plus lent, pas somme (levier 1). Un échec
            // annule ses voisins et remonte comme avant (même traitement auth en bas) ; seul un
            // jeton mort gaspille 3 appels légers, cas rare.
            // Les compteurs servent deux fois (réconciliation + rattrapage) : un seul appel au
            // même endpoint au lieu de `unreadTotal` + `unreadCountsByFeed` (levier 2).
            // Fenêtre serveur lue avant la tête : à 1 mois (31 j), Feedly coupe côté serveur.
            val prefsHead = prefs.data.first()
            val monthsHead = prefsHead[Prefs.HISTORY_MONTHS] ?: Prefs.HISTORY_MONTHS_DEFAULT
            val newerThan = historyNewerThan(monthsHead, historyCutoffMs(System.currentTimeMillis(), monthsHead))
            var msStream = 0L; var msCats = 0L; var msSubs = 0L; var msCounts = 0L
            val page: StreamPage
            val cats: List<CategoryEntity>
            val subs: List<SubscriptionEntity>
            val rawCounts: Map<String, Int>?
            coroutineScope {
                val dPage = async {
                    val s = System.currentTimeMillis()
                    p.stream(account.token, null, null, max, newerThanMs = newerThan).also { msStream = System.currentTimeMillis() - s }
                }
                val dCats = async {
                    val s = System.currentTimeMillis()
                    p.categories(account.token).map { it.copy(provider = account.id) }
                        .also { msCats = System.currentTimeMillis() - s }
                }
                val dSubs = async {
                    val s = System.currentTimeMillis()
                    p.subscriptions(account.token).map { it.copy(provider = account.id) }
                        .also { msSubs = System.currentTimeMillis() - s }
                }
                val dCounts = async {
                    val s = System.currentTimeMillis()
                    runCatching { p.unreadCountsByFeed(account.token) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            noteRateLimit(it, account.label)
                        }
                        .getOrNull().also { msCounts = System.currentTimeMillis() - s }
                }
                page = dPage.await(); cats = dCats.await(); subs = dSubs.await(); rawCounts = dCounts.await()
            }
            var added = storeArticles(page.articles)
            val subIds = subs.map { it.id }.toSet()
            val serverUnread = rawCounts?.filterKeys { it in subIds }
            android.util.Log.i(
                "PressIA",
                "sync ${account.id} tête: flux=${msStream}ms dossiers=${msCats}ms abos=${msSubs}ms " +
                    "compteurs=${msCounts}ms (${if (serverUnread != null) "mutualisés" else "repliés"}, " +
                    "newerThan=${if (newerThan != null) "31j" else "-"})"
            )
            reconcileAccount(account, added, serverUnread?.values?.sum())
            replaceCategories(account.id, cats)
            // On ne retire que les flux de ce compte : ceux des autres comptes, et surtout
            // les flux RSS locaux ajoutés à la main, doivent survivre.
            // Même raison que pour les dossiers : le rang choisi par l'utilisateur est réinjecté,
            // sinon la réinsertion le remettrait à zéro et l'ordre saisi disparaîtrait.
            val current = dao.subscriptions()
            val ranks = current.associate { it.id to it.sortOrder }
            val gone = removedFeeds(
                current.filter { sameAccount(it.provider, account.id) }.map { it.id }.toSet(),
                subs.map { it.id }.toSet()
            )
            dao.clearSubscriptionsOf(account.id)
            dao.upsertSubscriptions(subs.map { s -> s.copy(sortOrder = ranks[s.id] ?: 0) })
            // Flux désabonnés côté compte : leurs articles partent avec eux (sinon orphelins
            // visibles partout : global, non-lus, favoris), avec leurs HTML extraits
            // (Readability, colonne content), puis leurs miniatures orphelines. Les traces de
            // lecture/extraction restent : les statistiques ne repartent pas de zéro parce
            // qu'un flux a disparu côté serveur (voir deleteAccount).
            // Rien à faire dans le cas courant (ensemble vide : une différence en mémoire).
            if (gone.isNotEmpty()) {
                android.util.Log.i("PressIA", "désabonnement ${account.id} : ${gone.size} flux retirés, articles supprimés")
                gone.forEach { dao.deleteArticlesOfFeed(it) }
                gcThumbs()
                vacuum()
            }
            // Flux jamais approvisionnés (abonnés côté compte après la connexion) : leur
            // historique ne viendrait jamais par la fenêtre récente — passe dédiée, mêmes
            // bornes que l'approvisionnement global (quota, fenêtre, plafond). Les nouveaux
            // comptent dans le total. Le bandeau « Réception des articles… » suit le compteur
            // de flux ([onFeed]), cette passe étant la partie longue d'un compte.
            val prefsData = prefsHead
            val keep = (prefsData[Prefs.MAX_ITEMS] ?: 1000).coerceAtLeast(1)
            val cutoff = historyCutoffMs(System.currentTimeMillis(), monthsHead)
            val counts = dao.quotaCounts().associate { it.feedId to it.c }.toMutableMap()
            added += provisionHistory(account, subs, counts, keep, cutoff, serverUnread, newerThan, onFeed).first
        // Le jeton a parlé : la session est bonne, on oublie une éventuelle alerte.
        clearAccountExpired(account.id)
        return added
        } catch (e: Exception) {
            // 401/403 = session morte : on lève le drapeau pour l'écran Comptes, puis on
            // laisse l'appelant compter zéro (comportement inchangé pour le reste).
            if (isAuthFailure(e)) markAccountExpired(account.id)
            throw e
        }
    }

    /**
     * Derniers totaux de non-lus par compte (détecteur de changement de la réconciliation).
     * Cache dérivé hors sauvegarde : une restauration refait une passe complète, sans danger.
     */
    private suspend fun reconcileTotals(): Map<String, Int> = runCatching {
        val raw = prefs.data.first()[Prefs.RECONCILE_TOTALS].orEmpty()
        if (raw.isBlank()) return@runCatching emptyMap<String, Int>()
        val o = org.json.JSONObject(raw)
        o.keys().asSequence().associateWith { o.optInt(it) }
    }.getOrDefault(emptyMap())

    private suspend fun storeReconcileTotal(accountId: String, total: Int) {
        val o = org.json.JSONObject()
        (reconcileTotals() + (accountId to total)).forEach { (k, v) -> o.put(k, v) }
        prefs.set(Prefs.RECONCILE_TOTALS, o.toString())
    }

    /**
     * Cache dérivé hors sauvegarde : une restauration refait un import complet, sans danger.
     */
    private suspend fun readLaterHashes(): Map<String, String> = runCatching {
        val raw = prefs.data.first()[Prefs.READLATER_HASHES].orEmpty()
        if (raw.isBlank()) return@runCatching emptyMap<String, String>()
        val o = org.json.JSONObject(raw)
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    private suspend fun storeReadLaterHash(accountId: String, hash: String) = hashMutex.withLock {
        val o = org.json.JSONObject()
        (readLaterHashes() + (accountId to hash)).forEach { (k, v) -> o.put(k, v) }
        prefs.set(Prefs.READLATER_HASHES, o.toString())
    }

    /**
     * Propage le lu/non-lu distant : les articles lus ailleurs (hors fenêtre récente) passent en
     * lus, ceux rouverts ailleurs repassent en non-lus.
     *
     * Les actions locales non poussées gagnent toujours : le serveur est périmé pour elles
     * (file « read » / « unread », ou « markAllRead » en attente qui interdit tout passage en lu).
     * Renvoie (passés en lus, rouverts).
     */
    suspend fun reconcileReadState(account: CloudAccount, maxIds: Int = 5000): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val remote = providerFor(account.provider).unreadIds(account.token, maxIds) ?: return@withContext 0 to 0
        // Toute la file, pas seulement la page de `pendingSync` : sinon un marquage groupé
        // plus gros qu'une page n'était protégé qu'en partie et la réconciliation rouvrait
        // les articles dont l'action attendait encore (le serveur ne les avait pas vus).
        val pendingIds = dao.pendingReadIds().toSet()
        val hasPendingMarkAll = dao.pendingMarkAllCount() > 0
        // Une seule passe par paquet (plafond ~999 variables) au lieu de deux requêtes :
        // les paires (id, unread) se répartissent ensuite entre les deux ensembles purs.
        // Sans ID distant, `IN ()` serait invalide : on retombe sur les seuls non-lus locaux
        // (qui suffisent : rien à rouvrir quand le compte n'a aucun non-lu).
        val states = if (remote.ids.isEmpty()) {
            dao.unreadIdsOfProvider(account.id).map { ReconcileState(it, true) }
        } else {
            remote.ids.chunked(500).flatMap { dao.readStatesOfProvider(account.id, it) }
        }
        val localUnread = states.filter { it.unread }.map { it.id }.toSet()
        val localReadAmongRemote = states.filter { !it.unread }.map { it.id }.toSet()
        val split = computeReadReconciliation(localUnread, remote.ids, localReadAmongRemote, pendingIds, remote.complete, hasPendingMarkAll)
        var read = 0
        var unread = 0
        if (split.markRead.isNotEmpty()) {
            val now = System.currentTimeMillis()
            split.markRead.chunked(500).forEach { dao.markIdsRead(it, now); read += it.size }
        }
        if (split.markUnread.isNotEmpty()) {
            split.markUnread.chunked(500).forEach { dao.markIdsUnread(it); unread += it.size }
        }
        read to unread
    }

    /**
     * Remontée d'historique flux par flux, page après page (moteur de [initialSync]).
     *
     * La récupération ne ramène que ce qui sera gardé : au plus le quota par flux
     * (« Articles conservés ») et jamais plus vieux que la fenêtre d'historique
     * (« Historique remonté », mois calendaires, 0 = sans limite) — le plafond de sécurité
     * borne le tout en absolu. Chaque flux s'arrête dès son quota atteint (sans recroiser
     * que du déjà-connu à rafraîchir) : pas de pages téléchargées pour être aussitôt jetées.
     * Le périmètre reste large parce que les synchronisations suivantes ne redescendent que
     * la fenêtre la plus récente : ce qui n'est pas pris ici n'arrivera jamais. On suit
     * [StreamPage.continuation] jusqu'à épuisement, en gardant [pageSize] articles
     * par page — la limite de l'API. Sans abonnement connu, repli sur une passe globale.
     * La taille et les miniatures sont à l'appelant ([initialSync]) : sans la taille d'abord,
     * on téléchargerait les images de lignes aussitôt supprimées.
     *
     * Chaque passe s'arrête dans cinq cas, tous sûrs : quota du flux atteint sans rien de
     * nouveau à rafraîchir, plus de jeton de continuation, 3 pages vides d'affilée (une
     * isolée ne vaut pas un flux vide : le compte hoquette parfois), une page entièrement
     * plus vieille que la fenêtre d'historique, ou [safetyCap] articles lus au total. Ce dernier garde la mémoire et le
     * temps de calcul bornés sur un compte des années d'archives ; il est assez large pour
     * qu'aucun compte réel ne l'atteigne.
     */
    private suspend fun backfillArticles(account: CloudAccount): Pair<Int, Map<String, Int>?> = withContext(Dispatchers.IO) {
        try {
            val p = providerFor(account.provider)
            // Dossiers et abonnements en parallèle (même esprit que la tête de `syncAccount`).
            val (cats, subs) = coroutineScope {
                val dCats = async { p.categories(account.token).map { it.copy(provider = account.id) } }
                val dSubs = async { p.subscriptions(account.token).map { it.copy(provider = account.id) } }
                dCats.await() to dSubs.await()
            }
            replaceCategories(account.id, cats)
            val ranks = dao.subscriptions().associate { it.id to it.sortOrder }
            dao.clearSubscriptionsOf(account.id)
            dao.upsertSubscriptions(subs.map { s -> s.copy(sortOrder = ranks[s.id] ?: 0) })
            // Les flux sont connus dès maintenant : l'accueil les affiche sans attendre les
            // milliers d'articles qui suivent.
            pokeLists()
            // Compteurs serveur en un appel (même endpoint que la réconciliation et le
            // rattrapage suivants) : le repli dossier n'est tenté que pour les flux qui en ont
            // besoin (levier 4). Absents : repli autorisé partout (comportement d'avant).
            val serverUnread = fetchServerUnread(account, subs.map { it.id }.toSet())
            val localUnread = serverUnread?.let { dao.unreadByFeed().associate { r -> r.feedId to r.c } }.orEmpty()
            val allowFallback = { feedId: String ->
                serverUnread == null || (serverUnread[feedId] ?: 0) > (localUnread[feedId] ?: 0)
            }

        var added = 0
        var seen = 0
        var authFailed = false
        // Quota et fenêtre temporelle : l'approvisionnement n'insère jamais au-delà du
        // quota par flux (la taille le supprimerait aussitôt), ni plus vieux que le réglage
        // d'historique — le plafond de sécurité reste le garde-fou absolu.
        val prefsData = prefs.data.first()
        val keep = (prefsData[Prefs.MAX_ITEMS] ?: 1000).coerceAtLeast(1)
        val cutoff = historyCutoffMs(System.currentTimeMillis(), prefsData[Prefs.HISTORY_MONTHS] ?: Prefs.HISTORY_MONTHS_DEFAULT)
        val counts = dao.quotaCounts().associate { it.feedId to it.c }.toMutableMap()
        // Fenêtre serveur (1 mois = 31 j) : Feedly coupe côté serveur, moins de pages lues.
        val newerThan = historyNewerThan(prefsData[Prefs.HISTORY_MONTHS] ?: Prefs.HISTORY_MONTHS_DEFAULT, cutoff)
        if (newerThan != null) android.util.Log.i("PressIA", "backfill ${account.id} : newerThan serveur actif")
        // Progression (lus / plafond, jamais l'inverse : le total réel est inconnu d'avance,
        // chaque passe s'arrête avant si épuisée). Toujours annoncée : l'unique appelant
        // ([initialSync]) tient le bandeau pour cette passe (la découverte en synchro passe
        // par [backfillPages] avec un récit nul, elle reste silencieuse). `base` cumule les
        // flux précédents : le compteur ne repart jamais de zéro en cours de route.
        fun progress(base: Int, feedDone: Int, feedTotal: Int): (Int) -> Unit = { seenNow ->
            syncStep.value = "Récupération de l'historique… ${minOf(base + seenNow, backfillSafetyCap)}/$backfillSafetyCap (flux $feedDone/$feedTotal)"
        }
        val feeds = subs.map { it.id }
        if (feeds.isEmpty()) {
            // Sans abonnement connu : passe globale historique (rien à perdre, rien à arrêter).
            val (a, s, f) = backfillPages(account, null, counts, keep, cutoff, progress(0, 0, 1), newerThan)
            added += a; seen += s; authFailed = f
        } else {
            val folders = subs.mapNotNull { s -> s.categoryId?.let { s.id to it } }.toMap()
            feeds.forEachIndexed { i, feedId ->
                if (seen >= backfillSafetyCap) return@forEachIndexed
                val base = seen
                val (a, s, f) = healFeed(account, feedId, folders[feedId], counts, keep, cutoff, progress(base, i + 1, feeds.size), allowFallback, newerThan)
                added += a; seen += s; authFailed = authFailed || f
            }
        }
        android.util.Log.i("PressIA", "backfill ${account.id}: $added articles, $seen lus")
        // Pas d'alerte si tout a parlé, sauf si une page a justement calé en 401/403.
        // Taille, miniatures et « Read later » : à l'appelant (voir initialSync).
        if (!authFailed) clearAccountExpired(account.id)
        added to serverUnread
        } catch (e: Exception) {
            if (isAuthFailure(e)) markAccountExpired(account.id)
            throw e
        }
    }

    /**
     * Repli par dossier pour un flux dont le flux propre rend vide ([backfillPages] +0/0) :
     * remonte le flux du DOSSIER page après page et ne garde que les lignes du flux visé
     * (mêmes bornes quota / fenêtre / plafond, même retour). Le global prouve qu'un index
     * par flux peut être muet alors que le contenu existe — le dossier suit un autre
     * chemin. Silencieux (récit nul) : seul le rattrapage l'appelle.
     * Stoppe aussi après 10 pages sans ligne du flux (déplacé, autre dossier) : un flux
     * clairsemé dans un dossier prolifique ne doit pas faire marcher toute la catégorie.
     */
    private suspend fun backfillCategoryForFeed(
        account: CloudAccount,
        feedId: String,
        categoryId: String,
        counts: MutableMap<String, Int>,
        keep: Int,
        cutoff: Long,
        newerThanMs: Long? = null
    ): Triple<Int, Int, Boolean> {
        val p = providerFor(account.provider)
        var added = 0
        var seen = 0
        var missingStreak = 0
        var continuation: String? = null
        var authFailed = false
        while (seen < backfillSafetyCap) {
            val page = runCatching { p.stream(account.token, null, categoryId, pageSize, continuation, newerThanMs) }
                .onFailure {
                    if (it is CancellationException) throw it
                    noteRateLimit(it, account.label)
                    android.util.Log.e("PressIA", "backfill ${account.id} page failed", it)
                    if (isAuthFailure(it)) {
                        authFailed = true
                        markAccountExpired(account.id)
                    }
                }
                .getOrNull() ?: break
            val next = page.continuation
            val advanced = !next.isNullOrBlank() && next != continuation
            val mine = page.articles.filter { it.feedId == feedId }
            if (mine.isEmpty()) {
                if (advanced && ++missingStreak <= 10) { continuation = next; continue }
                break
            }
            missingStreak = 0
            if (cutoff > 0) {
                val oldest = page.articles.minOf { publishedMs(it.published) }
                if (oldest in 1 until cutoff) break
            }
            val known = mine.map { it.id }.chunked(500).flatMap { dao.existingIds(it) }.toSet()
            val fresh = withinHistory(mine, cutoff, known)
            added += storeArticles(capByQuota(fresh, counts, known, keep))
            seen += page.articles.size
            if (feedSaturated(counts[feedId] ?: 0, keep, mine.any { it.id in known })) break
            if (!advanced) break
            continuation = next
        }
        return Triple(added, seen, authFailed)
    }

    /**
     * Passe d'historique d'un flux + repli dossier s'il est muet (voir
     * [backfillCategoryForFeed]). Renvoie (nouveaux, lus, échec d'authentification).
     * Un flux sans aucune page lue est anormal (soit vraiment vide, soit page vide
     * transitoire) : visible dans le log, puis réparé aussitôt si son dossier est connu —
     * sans attendre les compteurs serveur (injoignables une fois sous la rafale de
     * connexion). [onProgress] suit la passe par flux ; le repli reste silencieux.
     */
    private suspend fun healFeed(
        account: CloudAccount,
        feedId: String,
        categoryId: String?,
        counts: MutableMap<String, Int>,
        keep: Int,
        cutoff: Long,
        onProgress: ((seen: Int) -> Unit)? = null,
        allowFallback: (String) -> Boolean = { true },
        newerThanMs: Long? = null
    ): Triple<Int, Int, Boolean> {
        val (a, s, f) = backfillPages(account, feedId, counts, keep, cutoff, onProgress, newerThanMs)
        if (a != 0 || s != 0) return Triple(a, s, f)
        android.util.Log.i("PressIA", "backfill ${account.id} : $feedId sans article (0 lu)")
        if (categoryId == null) return Triple(a, s, f)
        // Le serveur ne signale aucun non-lu manquant pour ce flux : son dossier n'apporterait
        // rien, on saute le ratissage (voir `allowFallback`).
        if (!allowFallback(feedId)) {
            android.util.Log.i("PressIA", "rattrapage ${account.id} : $feedId repli dossier sauté (compteur serveur à 0)")
            return Triple(a, s, f)
        }
        val (fa, fs, ff) = backfillCategoryForFeed(account, feedId, categoryId, counts, keep, cutoff, newerThanMs)
        android.util.Log.i("PressIA", "rattrapage ${account.id} : $feedId repli dossier +$fa/$fs")
        return Triple(a + fa, s + fs, f || ff)
    }

    /**
     * Pages d'historique pour un périmètre (première synchro, ou un flux nouvellement
     * découvert ou en retard) : mêmes bornes partout — quota par flux, fenêtre d'historique,
     * plafond de sécurité — et mêmes garanties (jamais d'avalage d'annulation, 429 signalé,
     * pages vides isolées suivies jusqu'à 3 d'affilée). [onProgress] reçoit les articles lus
     * au fil de l'eau (bandeau) ; null = silencieux (le récit courant du bandeau reste).
     * Renvoie (nouveaux, lus, échec d'authentification).
     */
    private suspend fun backfillPages(
        account: CloudAccount,
        feedId: String?,
        counts: MutableMap<String, Int>,
        keep: Int,
        cutoff: Long,
        onProgress: ((seen: Int) -> Unit)? = null,
        newerThanMs: Long? = null
    ): Triple<Int, Int, Boolean> {
        val p = providerFor(account.provider)
        var added = 0
        var seen = 0
        var emptyStreak = 0
        var continuation: String? = null
        var authFailed = false
        while (seen < backfillSafetyCap) {
            // `getOrNull` et non `getOrElse` : sortir de la boucle depuis une lambda inline
            // (`break`) est une fonctionnalité expérimentale de Kotlin.
            val page = runCatching { p.stream(account.token, feedId, null, pageSize, continuation, newerThanMs) }
                .onFailure {
                    if (it is CancellationException) throw it
                    noteRateLimit(it, account.label)
                    android.util.Log.e("PressIA", "backfill ${account.id} page failed", it)
                    if (isAuthFailure(it)) {
                        authFailed = true
                        markAccountExpired(account.id)
                    }
                }
                .getOrNull() ?: break
            val next = page.continuation
            // Jeton neuf (ni absent ni identique : il ferait boucler la même page) ?
            val advanced = !next.isNullOrBlank() && next != continuation
            if (page.articles.isEmpty()) {
                // Page vide isolée avec un jeton neuf : le compte hoquette parfois, et une
                // page vide ne vaut pas un flux vide — on suit le jeton, au plus 3 vides
                // d'affilée, sinon une première page vide transitoire effaçait tout un flux
                // en silence. Au-delà, c'est vraiment la fin.
                if (advanced && ++emptyStreak <= 3) { continuation = next; continue }
                break
            }
            emptyStreak = 0
            // Pages triées du plus récent au plus ancien : une page entièrement plus vieille
            // que la fenêtre termine (même motif que la réconciliation).
            if (cutoff > 0) {
                val oldest = page.articles.minOf { publishedMs(it.published) }
                if (oldest in 1 until cutoff) break
            }
            val known = page.articles.map { it.id }.chunked(500).flatMap { dao.existingIds(it) }.toSet()
            // Hors favoris : les lignes plus vieilles que la fenêtre sautent (sauf déjà
            // connues ou marquées, voir withinHistory).
            val fresh = withinHistory(page.articles, cutoff, known)
            added += storeArticles(capByQuota(fresh, counts, known, keep))
            seen += page.articles.size
            onProgress?.invoke(seen)
            // Passe par flux : quota atteint sans rien de nouveau à rafraîchir — les pages
            // suivantes (plus vieilles) seraient intégralement jetées. En passe globale les
            // flux se mélangent : on ne peut pas conclure, on continue.
            if (feedId != null && feedSaturated(counts[feedId] ?: 0, keep, page.articles.any { it.id in known })) break
            // Jeton épuisé ou identique (même page en boucle) : fin de la passe.
            if (!advanced) break
            continuation = next
        }
        return Triple(added, seen, authFailed)
    }

    /**
     * Enregistre les articles et renvoie combien étaient inconnus. L'identification passe par lots :
     * SQLite refuse au-delà de ~999 variables liées dans une requête `IN`.
     *
     * Les drapeaux de favoris sont **préservés** : `upsertArticles` remplace la ligne entière, et
     * les étoiles PressIA comme le tableau « Read later » sont des états locaux qu'aucun flux
     * distant ne renvoie. Seule exception : un article qui arrive explicitement marqué
     * `laterSaved` (import du tableau du compte) gagne ce drapeau, sans jamais le perdre.
     *
     * [keepRead] préserve en plus l'état lu/non-lu des articles déjà connus, pour les flux RSS
     * locaux : le fichier du flux ne porte aucune information de lecture, donc chaque
     * resynchronisation les repassait tous en non lus. Les comptes cloud gardent l'inverse —
     * l'état distant fait foi (la file montante a déjà été poussée avant la descente).
     */
    private suspend fun storeArticles(articles: List<ArticleEntity>, keepRead: Boolean = false): Int {
        if (articles.isEmpty()) return 0
        val known = HashSet<String>()
        val prev = HashMap<String, ArticleFlags>()
        // Une seule requête par paquet (drapeaux + existence) au lieu de trois : les
        // drapeaux ne portent que sur des lignes existantes, leurs IDs suffisent.
        articles.map { it.id }.chunked(500).forEach { chunk ->
            dao.flagsOf(chunk).forEach {
                known += it.id
                prev[it.id] = it
            }
        }
        val merged = articles.map { a -> mergeIncomingArticle(a, prev[a.id], keepRead) }
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
        val accounts = cloudAccounts()
        if (accounts.isEmpty()) return@withContext 0
        // Étoiles posées hors-ligne (file « save » / « unsave » non poussée) : le tableau vierge
        // reparti par compte les effacerait alors que le serveur ne les a pas encore — on les
        // repose après chaque replongée (lus une fois, la liste ne change pas pendant la sync).
        // Une étoile en attente interdit aussi le saut par empreinte : le serveur n'est pas à jour.
        // Toute la file (et non la page de `pendingSync`) : au-delà de 100 poses hors-ligne,
        // les suivantes étaient perdues par la replongée du tableau.
        val keepSaved = dao.pendingSavedIds()
        val keepUnsaved = dao.pendingUnsavedIds()
        val added = coroutineScope {
            accounts.map { account ->
                async {
                    var alive = false
                    runCatching {
                        // Voie légère : mêmes IDs qu'au dernier import (liste complète, rien en
                        // attente) = rien à redescendre — ni contenus, ni stockage, ni miniatures
                        // (la passe avec compteur couvre déjà les miniatures des articles).
                        // Un appel IDs réussi prouve déjà la session (quitus ci-dessous).
                        val idSet = providerFor(account.provider).readLaterIds(account.token)
                        if (idSet != null) alive = true
                        if (idSet != null && idSet.complete && keepSaved.isEmpty() && keepUnsaved.isEmpty()) {
                            val hash = com.pressia.data.remote.readLaterHash(idSet.ids)
                            if (readLaterHashes()[account.id] == hash) {
                                android.util.Log.i("PressIA", "read later ${account.id} inchangé (${idSet.ids.size} articles)")
                                return@runCatching 0
                            }
                        }
                        val items = providerFor(account.provider).readLater(account.token)
                        android.util.Log.d("PressIA", "read later ${account.id} : ${items.size} articles")
                        if (items.isNotEmpty()) alive = true
                        if (items.isEmpty()) {
                            // Tableau vraiment vide (appel abouti) : l'empreinte du vide est
                            // stockée pour sauter aussi ce cas ; état inconnu (aucun appel
                            // abouti : pas de scope, endpoint refusé) : on ne touche à rien —
                            // ni tableau, ni quitus de session (le drapeau expiré éventuel
                            // posé par la passe articles doit survivre).
                            if (idSet != null) storeReadLaterHash(account.id, com.pressia.data.remote.readLaterHash(emptySet()))
                            return@runCatching 0
                        }
                        // On repart d'un tableau vierge pour ce compte : un article retiré des « à lire
                        // » plus tard chez Feedly ne doit pas rester marqué indéfiniment.
                        val feeds = dao.subscriptions().filter { sameAccount(it.provider, account.id) }.map { it.id }
                        if (feeds.isNotEmpty()) dao.clearLater(feeds)
                        // Dernier mot au compteur (Int) : les rattrapages ci-dessous sont des
                        // instructions, le bloc doit toujours s'évaluer en nombre d'articles.
                        val n = storeArticles(items)
                        if (keepSaved.isNotEmpty()) dao.markLaterSaved(keepSaved)
                        if (keepUnsaved.isNotEmpty()) dao.unmarkLaterSaved(keepUnsaved)
                        storeReadLaterHash(account.id, com.pressia.data.remote.readLaterHash(items.map { it.id }.toSet()))
                        n
                    }.onSuccess { if (alive) clearAccountExpired(account.id) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            noteRateLimit(it, account.label)
                            android.util.Log.e("PressIA", "read later ${account.id} failed", it)
                            if (isAuthFailure(it)) markAccountExpired(account.id)
                        }
                        .getOrDefault(0)
                }
            }.awaitAll().sum()
        }
        // Pas de passe miniatures ici : les appelants (pipeline, première synchronisation) en
        // lancent déjà une en parallèle, et un second passage simultané doublait les
        // téléchargements. `pokeLists` suffit pour faire suivre le tableau.
        // Le tableau a changé : les favoris suivent sans attendre la fin du pipeline.
        pokeLists()
        added
    }

    private suspend fun trimToMax() {
        // Rétention au seul réglage « Articles conservés par flux » : au-delà du quota,
        // les plus anciens de chaque flux partent — favoris et « Read later » conservés.
        // Seuls les flux en dépassement sont balayés (détectés en une requête) : les autres
        // ne peuvent pas avoir gonflé, inutile de les vérifier à chaque synchronisation.
        val keep = maxItems().coerceAtLeast(1)
        var deleted = 0
        dao.overQuotaFeedIds(keep).forEach { feed ->
            dao.overflowArticleIds(feed, keep).chunked(500).forEach { deleted += dao.deleteArticlesByIds(it) }
        }
        if (deleted > 0) {
            android.util.Log.i("PressIA", "trimToMax: $deleted articles au-delà du quota ($keep/flux)")
            gcThumbs()
        }
    }

    /** Supprime les miniatures dont plus aucun article n'a besoin (purge, compte supprimé). */
    private suspend fun gcThumbs() {
        val keep = dao.allThumbUrls().mapTo(HashSet()) { ThumbStore.thumbFileName(it) }
        ThumbStore.gc(ctx, keep)
    }

    /**
     * Récupère l'espace libéré par une suppression de masse (compte, flux, remise à zéro des
     * stats). SQLite laisse les pages libérées dans le fichier : « Cache utilisé » ne redescend
     * pas alors que plus rien n'y est lu — le fichier garde sa taille jusqu'au prochain VACUUM.
     *
     * Le `VACUUM` réécrit la base **dans le journal WAL** ; sans le point de contrôle qui suit,
     * le fichier principal garde sa taille et le WAL, lui, gonfle : le cache affiché montait
     * même après une suppression. `TRUNCATE` recopie les pages compactées dans le fichier
     * principal et ramène le WAL à zéro, seul état où la taille affichée redevient vraie.
     *
     * Hors transaction, `VACUUM` la refuse, et jamais bloquant pour l'appelant : un ménage
     * impossible ne laisse qu'un fichier plus gros que nécessaire.
     */
    private suspend fun vacuum() = withContext(Dispatchers.IO) {
        runCatching {
            db.openHelper.writableDatabase.execSQL("VACUUM")
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }.onFailure { android.util.Log.w("PressIA", "VACUUM impossible : ${it.message}") }
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

    /**
     * Rejoue les lus locaux venus d'une restauration, sur le même principe : seuls les articles
     * arrivés dont le flux est (toujours) local sont passés en lus, directement par le DAO pour
     * ne jamais enfiler de poussée cloud. Les autres attendent, les flux cloud sont oubliés.
     */
    private suspend fun replayRestoredRead() {
        val pending = parseRestoredRead(prefs.flow(Prefs.RESTORED_READ, "").first())
        if (pending.isEmpty()) return
        val arrived = dao.existingIds(pending.map { it.id }).toSet()
        if (arrived.isEmpty()) return
        pending.filter { it.id in arrived }.forEach { r ->
            val feedId = dao.article(r.id)?.feedId ?: return@forEach
            if (dao.subscription(feedId)?.provider?.let { isLocalProvider(it) } == true) {
                dao.setRead(r.id, false, r.readAt)
            }
        }
        val stillWaiting = pending.filter { it.id !in arrived }
        prefs.set(Prefs.RESTORED_READ, encodeRestoredRead(stillWaiting))
    }

    // ----- Sync montante (file bidirectionnelle) -----

/**
     * Pousse les actions en attente vers le bon compte. L'action est rattachée au compte qui détient
     * le flux concerné, pas au compte actif : sans cela, un article d'un second compte Feedly
     * serait marqué lu chez le mauvais compte.
 *
 * Les favorites ne figurent plus dans la file : l'étoile PressIA est locale. Les éventuelles
 * lignes « star » / « unstar » laissées par une version antérieure sont simplement vidées.
 * Seules les étoiles d'articles Feedly y transitent (« save » / « unsave » → « Read later »).
 */
    /** File montante non vide ? (pour n'annoncer l'envoi que s'il a vraiment lieu). */
    suspend fun hasPendingSync(): Boolean = withContext(Dispatchers.IO) { dao.pendingSync().isNotEmpty() }

    /**
     * Pousse la file montante, sous verrou (voir [pushMutex]) : deux appels concurrents
     * attendent leur tour au lieu de repousser les mêmes lignes.
     */
    suspend fun pushQueue(): Int = pushMutex.withLock { pushQueueUnlocked() }

    private suspend fun pushQueueUnlocked(): Int = withContext(Dispatchers.IO) {
        val accounts = cloudAccounts()
        var done = 0
        var pages = 0
        // La file se sert par pages (`pendingSync` en rend 100) : s'arrêter à la première
        // laissait un marquage groupé de plusieurs centaines d'articles aux trois quarts dans
        // la file, et la synchro suivante rouvrait ce que le serveur n'avait pas encore vu.
        while (pages < PUSH_MAX_PAGES) {
            val pending = dao.pendingSync()
            if (pending.isEmpty()) break
            val processed = if (accounts.isEmpty()) {
                // Plus aucun compte cloud : la file n'a plus de destinataire, on la vide.
                pending.size.also { pending.forEach { r -> dao.dequeue(r.rowId) } }
            } else {
                pushPage(pending, accounts)
            }
            done += processed
            // Rien n'a pu partir (réseau en échec, quota atteint) : rejouer la même page
            // n'apporterait rien.
            if (processed == 0) break
            pages++
        }
        if (done > 0) android.util.Log.i("PressIA", "file: $done lignes poussées ($pages pages)")
        done
    }

    /**
     * Pousse une page de la file (voir [pushQueue]) vers le compte de chaque action. Renvoie le
     * nombre de lignes traitées (poussées ou vidées).
     */
    private suspend fun pushPage(pending: List<SyncActionEntity>, accounts: List<CloudAccount>): Int {
        var done = 0
        // Regroupées par compte, puis poussées dans l'ordre de la file par segments de même
        // sorte : 1 appel réseau par segment (les API acceptent des listes) au lieu d'1 par
        // article — une file hors-ligne de 50 lus part en 1 appel.
        pending.groupBy { accountFor(it, accounts) }.forEach { (account, actions) ->
            // Flux local (ou compte retiré) : rien à pousser, la ligne part — la garder la
            // ferait réessayer à chaque synchro sans jamais pouvoir aboutir.
            if (account == null) { actions.forEach { dao.dequeue(it.rowId); done++ }; return@forEach }
            val p = providerFor(account.provider)
            suspend fun drop(rows: List<SyncActionEntity>) {
                rows.forEach { dao.dequeue(it.rowId); done++ }
                clearAccountExpired(account.id)
            }
            suspend fun postpone(rows: List<SyncActionEntity>, e: Throwable) {
                noteRateLimit(e, account.label)
                if (isAuthFailure(e)) markAccountExpired(account.id)
                rows.forEach { dao.bumpTry(it.rowId) }
            }
            // Un lot en échec retombe à l'unité (ancien comportement) : un seul identifiant
            // rejeté ne bloque pas le lot. Sauf session morte ou quota : réessayer à l'unité
            // n'apporterait rien (et aggraverait le 429) — on reporte tout le lot.
            suspend fun flushRows(rows: List<SyncActionEntity>, call: suspend (List<String>) -> Unit) {
                if (rows.isEmpty()) return
                runCatching { call(rows.map { it.articleId }) }
                    .onSuccess { drop(rows) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        if (isAuthFailure(it) || rateLimitOf(it, account.label) != null) {
                            postpone(rows, it)
                        } else {
                            rows.forEach { row ->
                                val one = listOf(row)
                                runCatching { call(listOf(row.articleId)) }
                                    .onSuccess { drop(one) }
                                    .onFailure { e ->
                                        if (e is CancellationException) throw e
                                        postpone(one, e)
                                    }
                            }
                        }
                    }
            }
            // États locaux d'anciennes versions : rien à pousser, juste à vider.
            actions.filter { it.kind == "star" || it.kind == "unstar" }.forEach { dao.dequeue(it.rowId); done++ }
            // Vidage DANS L'ORDRE DE LA FILE (createdAt), segment par segment : voir
            // `syncActionRuns`. Un regroupement par sorte inverserait les actions d'un même
            // article — et surtout un `markAllRead` en attente repartirait APRÈS les `unread`
            // d'un « Annuler », réécrivant l'annulation côté serveur.
            syncActionRuns(actions).forEach { run ->
                when (run.first().kind) {
                    "read" -> flushRows(run) { p.markRead(account.token, it) }
                    "unread" -> flushRows(run) { p.markUnread(account.token, it) }
                    "save" -> flushRows(run) { p.markSaved(account.token, it) }
                    "unsave" -> flushRows(run) { p.markUnsaved(account.token, it) }
                    // « Tout marquer lu » : un appel par flux (liste acceptée) et/ou un appel
                    // « tout le compte » quand une ligne sans flux existe (vue « Tous les
                    // articles ») : la jeter sans réseau perdait silencieusement le marquage.
                    "markAllRead" -> {
                        val push = markAllReadPush(run)
                        if (push.nothing) drop(run)
                        else runCatching {
                            if (push.feedIds.isNotEmpty()) p.markAllRead(account.token, push.feedIds)
                            // « Tout le compte » (ligne sans flux) vaut pour **tous** les comptes
                            // cloud : rattachée au premier seulement, elle laissait les articles
                            // des autres non lus côté serveur, que la réconciliation suivante
                            // rouvrait localement. Chaque compte part donc séparément ; un échec
                            // reporte la ligne entière — le marquage est idempotent, les comptes
                            // déjà servis le seront à nouveau sans dommage.
                            if (push.global) accounts.forEach { acc ->
                                runCatching { providerFor(acc.provider).markAllReadGlobal(acc.token) }
                                    .onSuccess { clearAccountExpired(acc.id) }
                                    .onFailure { e ->
                                        if (e is CancellationException) throw e
                                        noteRateLimit(e, acc.label)
                                        if (isAuthFailure(e)) markAccountExpired(acc.id)
                                        throw e
                                    }
                            }
                        }
                            .onSuccess { drop(run) }
                            .onFailure {
                                if (it is CancellationException) throw it
                                postpone(run, it)
                            }
                    }
                    // Sorte inconnue (file d'une version plus récente) : rien à pousser, on la
                    // vide pour ne pas bloquer celles qui suivent.
                    else -> drop(run)
                }
            }
        }
        return done
    }

    /**
     * Compte responsable d'une action en attente, déduit du flux puis de l'article.
     *
     * `null` = **rien à pousser** : action d'un flux qui n'appartient à aucun compte cloud
     * (RSS local, ou flux désabonné depuis). Elle est alors vidée sans réseau — l'état local
     * est déjà à jour. Sans ça, elle partait au premier compte cloud, qui recevait des
     * identifiants d'articles qu'il ne connaît pas (rejet, ligne en échec à chaque synchro).
     */
    private suspend fun accountFor(action: SyncActionEntity, accounts: List<CloudAccount>): CloudAccount? {
        val feedId = action.feedId ?: action.articleId.takeIf { it.isNotBlank() }?.let { dao.article(it)?.feedId }
        // Sans flux : « tout le compte » (vue « Tous les articles »), ou état d'un article dont
        // le flux n'est plus connu. La ligne est traitée avec le premier compte (`pushPage`
        // pousse alors le marquage global à **tous** les comptes, voir la branche
        // « markAllRead ») : n'importe lequel fait l'affaire comme porteur du groupe.
        if (feedId.isNullOrBlank()) return accounts.first()
        val owner = dao.subscription(feedId)?.provider
        if (isLocalProvider(owner)) return null
        // Compte du flux : identifiant complet (« feedly#xxx »), ou service seul sur une base
        // écrite par une version antérieure (« feedly ») → premier compte de ce service.
        return accounts.firstOrNull { sameAccount(it.id, owner) }
            ?: accounts.firstOrNull { providerBase(it.id) == providerBase(owner) }
    }

    /** Vide la file de synchronisation (bascule cloud → RSS local). */
    suspend fun purgeQueue() = withContext(Dispatchers.IO) {
        android.util.Log.i("PressIA", "file montante vidée (RSS local)")
        dao.clearQueue()
    }

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
    suspend fun subscription(feedId: String): SubscriptionEntity? = dao.subscription(feedId)

    /** Taille du cache local (base Room + images Coil + miniatures) en octets. */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {        var total = 0L
        val dbDir = ctx.getDatabasePath("pressia.db")?.parentFile
        dbDir?.listFiles { f -> f.name.startsWith("pressia.db") }?.forEach { total += it.length() }
        total += runCatching { coil.Coil.imageLoader(ctx).diskCache?.size ?: 0L }.getOrDefault(0L)
        total += runCatching { ThumbStore.sizeBytes(ctx) }.getOrDefault(0L)
        total
    }

    /** Détail du cache local, dans le même ordre que [cacheSizeBytes] (base, images, miniatures). */
    data class CacheBreakdown(val dbBytes: Long, val coilBytes: Long, val thumbsBytes: Long) {
        val totalBytes: Long get() = dbBytes + coilBytes + thumbsBytes
    }

    /** Détail du cache local pour l'affichage (voir l'écran Réglages). */
    suspend fun cacheBreakdown(): CacheBreakdown = withContext(Dispatchers.IO) {
        var db = 0L
        val dbDir = ctx.getDatabasePath("pressia.db")?.parentFile
        dbDir?.listFiles { f -> f.name.startsWith("pressia.db") }?.forEach { db += it.length() }
        val coil = runCatching { coil.Coil.imageLoader(ctx).diskCache?.size ?: 0L }.getOrDefault(0L)
        val thumbs = runCatching { ThumbStore.sizeBytes(ctx) }.getOrDefault(0L)
        CacheBreakdown(db, coil, thumbs)
    }

    /** Taille du cache formatée pour l'affichage (« 12,4 Mo »). */
    suspend fun cacheInfo(): String = formatCacheBytes(cacheSizeBytes())

    /** Détail du cache formaté (« articles 17,3 Mo · images 68,0 Ko · miniatures 284,0 Ko »). */
    suspend fun cacheDetail(): String {
        val (db, coil, thumbs) = cacheBreakdown()
        // « images » = cache disque de Coil : les images d'articles (bannière en pleine taille,
        // corps) et les favicons — les images du corps y sont préchargées par syncImages, les
        // miniatures (filesDir/thumbs, un fichier par article au gabarit de l'écran) en sont donc
        // distinctes. Le libellé le dit, la question revenait.
        return "articles ${formatCacheBytes(db)} · images ${formatCacheBytes(coil)} · " +
            "miniatures ${formatCacheBytes(thumbs)}"
    }

    /**
     * Vide le cache : images (miniatures + cache Coil) et HTML extraits via Readability.
     * Les articles sont conservés avec leur extrait RSS : seul le suivi des images est
     * réinitialisé (la prochaine synchronisation récupère les images des articles
     * conservés), et la prochaine ouverture en mode complet ré-extrait l'article.
     */
    suspend fun clearCache() = clearImages()

    /**
     * Ne vide que les images et les extractions (force le re-téléchargement). Les images
     * d'articles sont récupérées à la prochaine synchronisation, les favicons au prochain
     * affichage — voir [refreshFavicons] pour un re-téléchargement immédiat des favicons.
     * Les contenus extraits repartent de l'extrait RSS au prochain double-tap.
     */
    suspend fun clearImages() = withContext(Dispatchers.IO) {
        ThumbStore.clear(ctx)
        dao.resetThumbsCached()
        dao.clearFullContents()
        runCatching { coil.Coil.imageLoader(ctx).diskCache?.clear() }
    }

    /**
     * Pré-télécharge les favicons de tous les abonnements (mise en cache disque Coil, sans
     * afficher). Renvoie le nombre récupéré, ou -1 si les réglages l'interdisent (images
     * hors-ligne coupées, ou Wi-Fi uniquement hors Wi-Fi).
     *
     * Quasi gratuit en synchro courante : les favicons déjà en cache disque ne repartent pas
     * sur le réseau, seuls les nouveaux abonnements téléchargent vraiment.
     */
    suspend fun syncFavicons(): Int = withContext(Dispatchers.IO) {
        if (!imagesAllowed()) return@withContext -1
        val loader = coil.Coil.imageLoader(ctx)
        // Même résolution qu'à l'affichage (voir feedFaviconUrl) : le réglage de source vaut
        // pour les deux, sinon le préchargement remplirait le cache d'URL jamais affichées.
        val direct = prefs.data.first()[Prefs.FAVICON_SOURCE] == FAVICON_SOURCE_DIRECT
        val subs = dao.subscriptions()
        // Source directe : l'icône déclarée par chaque site (lue dans son HTML, en cache —
        // `/favicon.ico` seul ne suffit pas, beaucoup de sites n'en exposent pas et l'icône
        // du compte peut être périmée). Déjà résolus = réutilisés, sans nouveau téléchargement.
        val resolved = if (direct) parseResolved(prefs.data.first()[Prefs.FAVICON_RESOLVED]).toMutableMap()
        else mutableMapOf()
        // En parallèle (borné) : des icônes de quelques Ko sur des hôtes variés — 1 par 1,
        // chaque favicon lent retardait tous les suivants.
        val permits = Semaphore(6)
        val done = AtomicInteger(0)
        coroutineScope {
            subs.map { sub ->
                async {
                    permits.withPermit {
                        val siteIcon = if (direct) {
                            resolved[sub.id] ?: resolveSiteIcon(sub)?.also { resolved[sub.id] = it }
                        } else null
                        val url = faviconUrlFor(sub.iconUrl, sub.siteUrl, sub.url, sub.id, direct, siteIcon) ?: return@async
                        // Préchargement Coil : télécharge et met en cache disque sans afficher.
                        val ok = runCatching {
                            loader.execute(coil.request.ImageRequest.Builder(ctx).data(url).build())
                        }.onFailure {
                            android.util.Log.w("PressIA", "favicon ${sub.title} <$url> : $it")
                        }.getOrNull() is coil.request.SuccessResult
                        if (ok) done.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        if (direct) prefs.set(Prefs.FAVICON_RESOLVED, encodeResolved(resolved))
        val n = done.get()
        android.util.Log.i("PressIA", "favicons : $n/${subs.size}")
        n
    }

    private val iconHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Icône déclarée par le site de l'abonnement (`<link rel="icon">` de sa page d'accueil),
     * ou null. Ne lit que le début du HTML (les icônes vivent dans `<head>`) : rapide même
     * sur les pages lourdes. Échec silencieux (null) : l'appelant retombe sur l'icône du
     * compte puis `/favicon.ico`.
     */
    private suspend fun resolveSiteIcon(sub: SubscriptionEntity): String? = withContext(Dispatchers.IO) {
        val page = sitePageUrl(sub.siteUrl, sub.url, sub.id) ?: return@withContext null
        val html = runCatching {
            iconHttp.newCall(okhttp3.Request.Builder().url(page).header("User-Agent", "PressIA").get().build())
                .execute().use { res ->
                    if (!res.isSuccessful) return@withContext null
                    val stream = res.body?.byteStream() ?: return@withContext null
                    stream.use { s ->
                        val out = java.io.ByteArrayOutputStream(128 * 1024)
                        val buf = ByteArray(32 * 1024)
                        var total = 0
                        while (total < 128 * 1024) {
                            val n = s.read(buf, 0, minOf(buf.size, 128 * 1024 - total))
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    }
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching { parseSiteIcon(html, page) }.getOrNull()
    }

    /**
     * Rafraîchit les favicons maintenant : les entrées de favicons sont retirées du cache disque
     * (et seulement elles — les images d'articles que [syncImages] y a préchargées restent en
     * place), puis toutes les icônes sont re-téléchargées. Ne touche ni aux articles ni aux
     * miniatures. Renvoie le nombre de favicons récupérés, ou -1 si les réglages l'interdisent
     * (images hors-ligne coupées, ou Wi-Fi uniquement hors Wi-Fi) : rien n'est alors effacé.
     * Étapes dans [syncStep], comme les pipelines (remis à null en fin, voir `syncPipeline`).
     */
    suspend fun refreshFavicons(): Int = withSyncLock {
        withContext(Dispatchers.IO) {
            try {
                if (!imagesAllowed()) return@withContext -1
                // Les URLs d'icônes **telles qu'elles étaient en cache** : la même résolution
                // que `syncFavicons`/l'affichage, avec les icônes résolues encore en préférences.
                val prefsHead = prefs.data.first()
                val direct = prefsHead[Prefs.FAVICON_SOURCE] == FAVICON_SOURCE_DIRECT
                val resolved = parseResolved(prefsHead[Prefs.FAVICON_RESOLVED])
                purgeImages(
                    dao.subscriptions().mapNotNull { sub ->
                        faviconUrlFor(sub.iconUrl, sub.siteUrl, sub.url, sub.id, direct, resolved[sub.id])
                    }
                )
                // Les icônes résolues aussi : une refonte du site change son icône, l'ancienne
                // résolue serait périmée — tout est redécouvert (voir `syncFavicons`).
                prefs.set(Prefs.FAVICON_RESOLVED, "")
                syncStep.value = "Favicons en cours…"
                syncFavicons()
            } finally {
                syncStep.value = null
            }
        }
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
    /** Années ayant au moins une lecture ( ex. [2026, 2025] ), depuis la borne de remise à zéro. */
    suspend fun statsYears(): List<Int> = withContext(Dispatchers.IO) {
        dao.readingYears(statsSince()).mapNotNull { it.toIntOrNull() }
    }

    /** Première lecture enregistrée (0 si aucune). */
    suspend fun firstReadingAt(): Long = withContext(Dispatchers.IO) { dao.firstReadingAt() }

    /** Borne basse des statistiques : 0 = depuis toujours (voir [resetStats]). */
    private suspend fun statsSince(): Long = prefs.data.first()[Prefs.STATS_SINCE] ?: 0L

    /**
     * Remet les statistiques à zéro : les traces de lecture et d'extraction sont effacées et la
     * borne [Prefs.STATS_SINCE] est posée à maintenant. Cette borne est nécessaire pour le
     * compteur « passés en lu sans ouvrir », qui se déduit des articles (`readAt`) : `readAt`
     * reste, lui, un état d'article qu'une remise à zéro des stats n'a pas à toucher.
     */
    suspend fun resetStats() = withContext(Dispatchers.IO) {
        dao.clearReadingEvents()
        dao.clearExtractEvents()
        prefs.set(Prefs.STATS_SINCE, System.currentTimeMillis())
        vacuum()
    }

    suspend fun stats(from: Long, to: Long): ReadingStats = withContext(Dispatchers.IO) {
        // Une remise à zéro borne le bas de la période : rien de ce qui précède n'est compté,
        // pas même les articles marqués lus avant elle.
        val f = statsLowerBound(from, statsSince())
        val total = dao.readsBetween(f, to)
        val perDay = dao.readsPerLocalDay(f, to)
        val seconds = dao.secondsBetween(f, to)
        val quick = dao.readWithoutOpeningBetween(f, to)
        val titles = dao.subscriptions().associateBy({ it.id }, { it.title })
        val cats = dao.categories().associateBy({ it.id }, { it.label })
        val perCategory = dao.secondsByCategory(f, to)
            .map { (cats[it.categoryId] ?: it.categoryId.orEmpty()) to it.secs }
            .filter { it.first.isNotBlank() }
        // Les traces d'un flux supprimé sont conservées (les stats ne repartent pas de zéro avec
        // un compte) : sans titre en base, ses lectures se regroupent sous un même libellé.
        val perFeed = dao.secondsByFeed(f, to)
            .groupBy { titles[it.feedId] ?: DELETED_FEED_LABEL }
            .map { (label, rows) -> label to (rows.sumOf { it.secs } to rows.sumOf { it.c }) }
            .sortedByDescending { it.second.first }
        val ex = dao.extractStatsBetween(f, to).associate { it.ok to it.c }
        ReadingStats(
            totalOpens = total,
            readWithoutOpening = quick,
            totalSeconds = seconds,
            perCategory = perCategory,
            perFeed = perFeed,
            perDay = perDay.map { it.day to it.c },
            readabilityOk = ex[true] ?: 0,
            readabilityKo = ex[false] ?: 0,
            from = f,
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
     * local est simplement effacé de la base, avec ses articles et ses HTML extraits. Ses traces
     * de lecture ne partent pas (elles nourrissent les statistiques, qui survivent à un
     * désabonnement comme à une suppression de compte). Puis les miniatures orphelines (même
     * motif que le désabonnement en synchro : sinon les images du flux supprimé resteraient sur
     * disque, sans article pour les réclamer), et les images pleine taille du cache Coil.
     */
    suspend fun deleteLocalFeed(feedId: String) = withContext(Dispatchers.IO) {
        val sub = dao.subscription(feedId) ?: return@withContext
        if (!isLocalProvider(sub.provider)) return@withContext
        // URLs d'images relevées avant la suppression des lignes : miniature déclarée et images
        // du corps (HTML complet ou extrait). Les images des autres flux partageant une URL
        // restent en cache — le filtre est appliqué plus bas, une fois les articles partis.
        val images = dao.articleMediaOfFeed(feedId)
            .flatMap { m -> articleImageUrls(m.imageUrl, m.content, m.summary, m.url) }
            .distinct()
        dao.deleteSubscription(feedId)
        dao.deleteArticlesOfFeed(feedId)
        // Ses traces de lecture et d'extraction restent : elles ne servent qu'aux statistiques,
        // qui doivent survivre au désabonnement (elles s'afficheront sous « Flux supprimé »).
        // Seules les images sans plus aucun article pour les réclamer sont retirées.
        gcThumbs()
        purgeImages(images.filterNot { it in dao.allThumbUrls() })
        // Le fichier de base garde sinon les pages libérées par les articles partis.
        vacuum()
    }

    /**
     * Retire du cache disque Coil les URLs données : images d'un flux supprimé (bannière en
     * pleine taille, images du corps) ou entrées de favicons d'une actualisation. `gcThumbs` ne
     * couvre que les fichiers de miniatures de [ThumbStore] ; tout ce que Coil range sous une URL
     * y échappe — sans cette passe ces entrées resteraient sur l'appareil, invisibles mais bien
     * présentes.
     */
    private suspend fun purgeImages(urls: List<String>) {
        if (urls.isEmpty()) return
        val cache = coil.Coil.imageLoader(appCtx).diskCache ?: return
        val n = urls.count { url -> runCatching { cache.remove(url) }.getOrDefault(false) }
        android.util.Log.i("PressIA", "cache Coil : $n/${urls.size} entrées retirées")
    }

    /**
     * Vérifie un cookie de session Feedly (page de connexion Feedly ouverte dans l'application).
     * Renvoie le nom de compte si le jeton est valide, null sinon.
     */
    suspend fun verifyFeedlySession(token: String): String? = withContext(Dispatchers.IO) {
        runCatching { feedly.profile(token)?.takeIf { it.isNotBlank() } }.getOrNull()
    }
}

/**
 * URLs absolues des images d'un HTML d'article (corps, extrait), résolues contre [baseUrl].
 * Même repérage que l'affichage (`articleBlocks`) : sert à purger le cache d'images d'un flux
 * supprimé, où les images du corps comptent autant que la vignette déclarée par le flux.
 */
fun htmlImageUrls(html: String?, baseUrl: String): List<String> {
    if (html.isNullOrBlank()) return emptyList()
    return try {
        org.jsoup.Jsoup.parse(html, baseUrl).select("img[src]").mapNotNull { el ->
            val raw = el.absUrl("src").ifBlank { el.attr("src") }.trim()
            val abs = if (raw.startsWith("//")) "https:$raw" else raw
            abs.takeIf { it.startsWith("http") }
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Toutes les images d'un article, dans l'ordre d'affichage : la miniature déclarée par le flux
 * (`imageUrl`, bandeau de liste et image de tête) puis les images du corps — extrait RSS et HTML
 * complet extrait. Même repérage que l'affichage ([articleBlocks]) et que le nettoyage d'un flux
 * supprimé. Les URLs non `http` (data:, relatives) sont écartées : elles ne se mettent pas en
 * cache sous une clé d'URL.
 */
fun articleImageUrls(
    imageUrl: String?,
    content: String?,
    summary: String?,
    baseUrl: String
): List<String> = buildList {
    imageUrl?.trim()?.takeIf { it.startsWith("http") }?.let(::add)
    addAll(htmlImageUrls(content, baseUrl))
    addAll(htmlImageUrls(summary, baseUrl))
}.distinct()

/**
 * Reporte sur un article resynchronisé les drapeaux que seul l'appareil connaît : étoile
 * PressIA, « Read later » du compte (que l'article peut apporter sans jamais le perdre) et
 * suivi des miniatures — sans quoi chaque insertion (REPLACE) les effacerait, et tout serait
 * retéléchargé. [keepRead] ajoute l'état lu/non-lu, qui n'a de sens localement que pour un flux
 * RSS sans compte : le fichier du flux ne le porte pas, l'écraser repassait tous ses articles
 * en non lus à chaque synchronisation.
 */
fun mergeIncomingArticle(incoming: ArticleEntity, prev: ArticleFlags?, keepRead: Boolean): ArticleEntity {
    if (prev == null) return incoming
    return incoming.copy(
        starred = prev.starred,
        laterSaved = prev.laterSaved || incoming.laterSaved,
        imageCached = prev.imageCached,
        unread = if (keepRead) prev.unread else incoming.unread,
        readAt = if (keepRead) prev.readAt else incoming.readAt
    )
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

/**
 * Borne basse effective des statistiques : la période demandée, jamais avant la remise à zéro
 * (voir [Prefs.STATS_SINCE]). Fonction pure, testable sans base ni appareil.
 */
fun statsLowerBound(from: Long, since: Long): Long = maxOf(from, since)

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
        .addMigrations(PressDatabase.MIGRATION_1_2, PressDatabase.MIGRATION_2_3, PressDatabase.MIGRATION_3_4, PressDatabase.MIGRATION_4_5)
        .build()
