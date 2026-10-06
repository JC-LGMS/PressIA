package com.pressia.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pressia.data.PressRepository
import com.pressia.data.ReadingStats
import com.pressia.data.StatsPeriod
import com.pressia.data.isLocalProvider
import com.pressia.data.local.*
import com.pressia.data.statsRange
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class Scope(val feedId: String? = null, val categoryId: String? = null, val label: String = "Tous les articles")

/**
 * Périmètre affiché par l'écran Favoris : le jeu de favoris d'un côté (« mes étoiles », le tableau
 * « Read later » du compte, ou les deux réunis), le flux ou le dossier de l'autre. Les deux sont
 * affichés en permanence et modifiables sur place.
 *
 * Les deux jeux sont disjoints par construction : l'étoile est un état local de PressIA, le
 * tableau « Read later » appartient au compte.
 */
data class FavScope(
    val set: String = "app",
    val scope: Scope = Scope(label = "Tous les articles")
) {
    val label: String get() = favSetLabel(set)
}

/** Libellé court du jeu de favoris, pour les sélecteurs. */
fun favSetLabel(set: String): String = when (set) {
    "later" -> "Read later"
    "all" -> "Tous les favoris"
    else -> "Favoris PressIA"
}

class PressVM(val repo: PressRepository) : ViewModel() {
    val prefs = repo.prefs
    var scope by mutableStateOf(Scope())
    var articles by mutableStateOf<List<ArticleEntity>>(emptyList())
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
    /** Dossiers affichés (un par dossier, ou fusionnés entre comptes selon [mergeFolders]). */
    var folders by mutableStateOf<List<PressRepository.Folder>>(emptyList())
    var subs by mutableStateOf<List<SubscriptionEntity>>(emptyList())
    var syncing by mutableStateOf(false)
    var stats by mutableStateOf<ReadingStats?>(null)
    /** Années avec des données de lecture (sélecteur « par année »). */
    var statsYears by mutableStateOf<List<Int>>(emptyList())
    var dups by mutableStateOf<List<List<ArticleEntity>>>(emptyList())
    /** Dernier résultat de synchronisation (affiché en snackbar, puis effacé). */
    var syncMsg by mutableStateOf<String?>(null)
    /** Dernier résultat d'authentification OAuth (affiché sur l'écran Comptes). */
    var authMsg by mutableStateOf<String?>(null)
    /** Article ouvert via la route "article" (évite les caractères spéciaux dans la navigation). */
    var selectedArticleId by mutableStateOf<String?>(null)
    /** Infos du compte connecté (écran Comptes). */
    var account by mutableStateOf<com.pressia.data.AccountInfo?>(null)
    /** Compteurs de non-lus par flux (feedId → count), pour l'écran Accueil et le drawer. */
    var unreads by mutableStateOf<Map<String, Int>>(emptyMap())
    /** Non-lus agrégés par dossier affiché (drawer). */
    var folderUnreads by mutableStateOf<Map<String, Int>>(emptyMap())
    /** Comptes externes connectés (plusieurs comptes par service possibles). */
    var accounts by mutableStateOf<List<com.pressia.data.CloudAccount>>(emptyList())
    /** Dossiers repliés : la flèche et son état survivent à l'application. */
    var collapsedFolders by mutableStateOf<Set<String>>(emptySet())
    /** Flux RSS saisis à la main, pour l'écran Comptes. */
    var localFeeds by mutableStateOf<List<SubscriptionEntity>>(emptyList())

    /** Apparence choisie dans le drawer : auto / jour / nuit. */
    val appearance: StateFlow<String> = prefs.flow(Prefs.APPEARANCE, "auto").stateIn(viewModelScope, SharingStarted.Eagerly, "auto")
    /** Thème retenu en mode nuit : sombre ou AMOLED (choisi dans les Réglages). */
    val nightTheme: StateFlow<String> = prefs.flow(Prefs.NIGHT_THEME, "dark").stateIn(viewModelScope, SharingStarted.Eagerly, "dark")
    /**
     * Thème effectif. Le drawer ne choisit plus qu'entre trois apparences ; « nuit » applique le
     * thème nocturne défini dans les Réglages (AMOLED par défaut = Sombre).
     */
    val theme: StateFlow<String> = combine(appearance, nightTheme) { app, night ->
        when (app) {
            "light" -> "light"
            "night" -> if (night == "amoled") "amoled" else "dark"
            else -> "system"
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val provider: StateFlow<String> = prefs.flow(Prefs.PROVIDER, "local").stateIn(viewModelScope, SharingStarted.Eagerly, "local")
    val accent: StateFlow<String> = prefs.flow(Prefs.ACCENT, "red").stateIn(viewModelScope, SharingStarted.Eagerly, "red")
    val bg: StateFlow<String> = prefs.flow(Prefs.BG, "default").stateIn(viewModelScope, SharingStarted.Eagerly, "default")
    val viewMode: StateFlow<String> = prefs.flow(Prefs.VIEW_MODE, "list").stateIn(viewModelScope, SharingStarted.Eagerly, "list")
    val fontSize: StateFlow<Int> = prefs.flow(Prefs.FONT_SIZE, 16).stateIn(viewModelScope, SharingStarted.Eagerly, 16)
    val titleFont: StateFlow<Int> = prefs.flow(Prefs.TITLE_FONT_SIZE, 16).stateIn(viewModelScope, SharingStarted.Eagerly, 16)
    val articleTitle: StateFlow<Int> = prefs.flow(Prefs.ARTICLE_TITLE_SIZE, 20).stateIn(viewModelScope, SharingStarted.Eagerly, 20)
    val dateFont: StateFlow<Int> = prefs.flow(Prefs.DATE_FONT_SIZE, 12).stateIn(viewModelScope, SharingStarted.Eagerly, 12)
    val trailingAction: StateFlow<String> = prefs.flow(Prefs.TRAILING_ACTION, "read").stateIn(viewModelScope, SharingStarted.Eagerly, "read")
    val homeScreen: StateFlow<String> = prefs.flow(Prefs.HOME_SCREEN, "subs").stateIn(viewModelScope, SharingStarted.Eagerly, "subs")
    val justify: StateFlow<Boolean> = prefs.flow(Prefs.JUSTIFY, false).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val unreadOnly: StateFlow<Boolean> = prefs.flow(Prefs.SHOW_UNREAD_ONLY, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Ouverture d'un article : « internal » (lecture dans PressIA) ou « external » (navigateur). */
    val openMode: StateFlow<String> = prefs.flow(Prefs.OPEN_MODE, "internal").stateIn(viewModelScope, SharingStarted.Eagerly, "internal")
    val hasCloudAccounts: Boolean get() = accounts.isNotEmpty()
    /** Faux = un dossier par compte même si les noms se ressemblent. */
    val mergeFolders: StateFlow<Boolean> = prefs.flow(Prefs.MERGE_FOLDERS, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Jeu de favoris retenu par l'écran Favoris : « app » (étoiles) | « later » (Read later) | « all ». */
    val favSet: StateFlow<String> = prefs.flow(Prefs.STARRED_SCOPE, "app").stateIn(viewModelScope, SharingStarted.Eagerly, "app")
    /** Masquage des flux sans article non lu, pour l'écran Accueil (indépendant du drawer). */
    val hideEmptySubs: StateFlow<Boolean> = prefs.flow(Prefs.HIDE_EMPTY_SUBS, false).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Échelle des libellés du drawer, en pourcentage. */
    val tapFaviconRead: StateFlow<Boolean> = prefs.flow(Prefs.TAP_FAVICON_READ, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val drawerScale: StateFlow<Int> = prefs.flow(Prefs.DRAWER_TEXT_SCALE, 100).stateIn(viewModelScope, SharingStarted.Eagerly, 100)
    /** Synchronisation automatique au lancement de l'application. */
    val syncOnLaunch: StateFlow<Boolean> = prefs.flow(Prefs.SYNC_ON_LAUNCH, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Identifiant du compte actif, null en RSS local — sert à cocher le compte dans l'écran Comptes. */
    val activeAccountId: StateFlow<String?> = prefs.flow(Prefs.ACTIVE_ACCOUNT, "")
        .map { it.ifBlank { null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { migrateAppearance() }
        // Les dossiers suivent le réglage fusion / séparation : la liste est reconstruite à chaque changement.
        viewModelScope.launch { mergeFolders.collect { folders = repo.folders(it) } }
        // Dossiers repliés : conservés d'une session à l'autre.
        viewModelScope.launch {
            collapsedFolders = prefs.flow(Prefs.COLLAPSED_FOLDERS, "").first()
                .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        // Bascule vers le RSS local : plus rien à pousser, on vide la file.
        viewModelScope.launch {
            provider.collect { p -> if (isLocalProvider(p)) repo.purgeQueue() }
        }
    }

    /** Apparence choisie dans le drawer : auto / jour / nuit. */
    fun setAppearance(value: String) = viewModelScope.launch { prefs.set(Prefs.APPEARANCE, value) }

    /** Replie ou déplie un dossier (drawer et écran Accueil partagent le même état). */
    fun toggleFolder(folderId: String) {
        val next = if (folderId in collapsedFolders) collapsedFolders - folderId else collapsedFolders + folderId
        collapsedFolders = next
        viewModelScope.launch { prefs.set(Prefs.COLLAPSED_FOLDERS, next.sorted().joinToString(",")) }
    }

    fun isCollapsed(folderId: String): Boolean = folderId in collapsedFolders

    /**
     * Ancienne version : un seul réglage [Prefs.THEME] valait à la fois apparence et thème nocturne.
     * On le convertit une seule fois en [Prefs.APPEARANCE] + [Prefs.NIGHT_THEME], sans changer
     * d'apparence ni de couleurs pour l'utilisateur.
     */
    private suspend fun migrateAppearance() {
        val d = prefs.data.first()
        if (d.contains(Prefs.APPEARANCE)) return
        when (d[Prefs.THEME] ?: "system") {
            "light" -> prefs.set(Prefs.APPEARANCE, "light")
            "dark" -> prefs.set(Prefs.APPEARANCE, "night")
            "amoled" -> {
                prefs.set(Prefs.APPEARANCE, "night")
                prefs.set(Prefs.NIGHT_THEME, "amoled")
            }
            else -> prefs.set(Prefs.APPEARANCE, "auto")
        }
    }

/**
 * Articles à garder visibles dans le flux courant : passés en lus pendant la session, ils ne
 * disparaissent pas de la liste tant qu'on reste dans le même flux (ils sont oubliés au changement de flux).
 */
    private val keepIds = MutableStateFlow<Set<String>>(emptySet())

    /** Change de flux (flux, catégorie ou « Tous les articles ») : la liste « collante » repart à zéro. */
    fun switchScope(s: Scope) {
        if (s.feedId != scope.feedId || s.categoryId != scope.categoryId) keepIds.value = emptySet()
        scope = s
    }

    /**
     * Oublie les articles « collants » : passés en lus pendant la session, ils disparaissent
     * de la liste dès que le filtre « non-lus seuls » est réactivé (œil). Sans cela, réactiver
     * le filtre gardait les lus à l'écran via la requête collante et l'œil semblait inopérant.
     */
    fun clearKept() {
        keepIds.value = emptySet()
    }

    /**
     * Flux du flux affiché : la liste se rafraîchit toute seule dès que la synchronisation
     * (bouton ou tâche d'arrière-plan) enregistre de nouveaux articles, sans rechargement manuel.
     * [fav] n'est fourni que par l'écran Favoris, qui a son propre périmètre.
     */
    fun streamList(onlyUnread: Boolean, fav: FavScope? = null): Flow<List<ArticleEntity>> {
        val s = fav?.scope ?: scope
        val kind = if (fav == null) "" else fav.set
        return keepIds.flatMapLatest { keep ->
            repo.streamFlow(s.feedId, s.categoryId, onlyUnread, kind, keep)
        }
    }

    fun refresh() = viewModelScope.launch {
        syncing = true
        try { refreshNow() } finally { syncing = false }
    }

    /** Remplit l'état local depuis la base (version suspendable de [refresh]). */
    private suspend fun refreshNow() {
        // Un compte connecté avant la version multi-comptes doit être repris avant d'être listé.
        repo.migrateLegacyAccount()
        categories = repo.categories()
        folders = repo.folders(mergeFolders.value)
        subs = repo.subscriptions()
        unreads = repo.unreadByFeed()
        folderUnreads = repo.unreadByFolder()
        articles = repo.stream(scope.feedId, scope.categoryId, unreadOnly.value)
        accounts = repo.cloudAccounts()
        localFeeds = repo.localFeeds()
    }

    /** Bascule « masquer les flux sans dossier », pour l'écran Accueil. */
    fun toggleHideEmptySubs() = viewModelScope.launch {
        prefs.set(Prefs.HIDE_EMPTY_SUBS, !hideEmptySubs.value)
    }

    /** Supprime un flux RSS local ; les autres comptes ne sont pas touchés. */
    fun deleteLocalFeed(feedId: String) = viewModelScope.launch {
        repo.deleteLocalFeed(feedId)
        refreshNow()
    }

    /** Ajoute un flux RSS local et le synchronise une première fois. */
    fun addLocalFeed(url: String, folderId: String? = null, done: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val link = repo.addLocalFeed(url, folderId)
        if (link == null) {
            done(false)
            return@launch
        }
        repo.syncDown(link)
        refreshNow()
        done(true)
    }

    // ----- Dossiers -----
    /** Crée un dossier pour les flux RSS locaux et rouvre l'écran sur la liste à jour. */
    fun createFolder(label: String, onCreated: (PressRepository.Folder?) -> Unit = {}) = viewModelScope.launch {
        val id = repo.createFolder(label)
        refreshNow()
        onCreated(folders.firstOrNull { it.id == id })
    }

    fun renameFolder(folder: PressRepository.Folder, label: String) = viewModelScope.launch {
        // `folder.id` porte le préfixe `merged:` pour un dossier fusionné : c'est ce qui permet
        // au dépôt de renommer tous ses dossiers locaux d'un coup.
        repo.renameFolder(folder.id, label)
        refreshNow()
    }

    fun deleteFolder(folder: PressRepository.Folder) = viewModelScope.launch {
        repo.deleteFolder(folder.id, folder.providers, folder.merged)
        // On sort du dossier supprimé pour ne pas rester sur un écran vide.
        if (scope.categoryId == folder.id || scope.categoryId in folder.ids) {
            switchScope(Scope(label = "Tous les articles"))
        }
        refreshNow()
    }

    /** Déplace un flux dans un dossier (ou hors de tout dossier avec [folder] à null). */
    fun moveFeed(feedId: String, folder: PressRepository.Folder?) = viewModelScope.launch {
        repo.moveFeedsToFolder(listOf(feedId), folder?.id)
        refreshNow()
    }

    /**
     * Monte ou descend un dossier d'une place dans la liste affichée, puis rafraîchit l'écran.
     *
     * Le rafraîchissement attend la fin de l'écriture : sans cela, la liste se recomposait avec
     * l'ancien ordre et le dossier revenait à sa place initiale sous le doigt de l'utilisateur.
     */
    fun moveFolder(folder: PressRepository.Folder, delta: Int) = viewModelScope.launch {
        repo.moveFolder(folder.id, delta)
        refreshNow()
    }

    /** Idem pour un flux, dans la liste de son dossier ou dans « Flux sans dossier ». */
    fun moveFeedOrder(feedId: String, delta: Int, categoryId: String?) = viewModelScope.launch {
        repo.moveSubscription(feedId, delta, categoryId)
        refreshNow()
    }

    /**
     * Synchronisation complète, en attente du résultat : tous les comptes et tous les abonnements,
     * quel que soit l'écran qui la déclenche. [sync] en est la version non bloquant.
     *
     * Le message ne parle que des **nouveaux** articles : le nombre d'articles déjà connus n'apporte
     * rien à l'utilisateur et masquait le seul chiffre utile.
     */
    suspend fun syncNow(): String {
        syncing = true
        try {
            val pushed = repo.pushQueue()
            val n = repo.syncDown()
            // Refresh en attente, et non lancé en arrière-plan : sans cela on pouvait annoncer
            // « connecté » en revenant sur des listes encore vides.
            refreshNow()
            return if (n > 0) "$n nouvel${if (n > 1) "s" else ""} article${if (n > 1) "s" else ""}"
            else "Aucun nouvel article"
        } catch (e: Exception) {
            return "Échec de synchronisation : vérifiez la connexion"
        } finally {
            syncing = false
        }
    }

    /** Déclenche [syncNow] en tâche de fond et publie le résultat dans [syncMsg]. */
    fun sync() = viewModelScope.launch { syncMsg = syncNow() }

    /**
     * Durée minimale entre deux synchronisations automatiques au lancement.
     *
     * Sans ce délai, ouvrir l'application cinq fois dans la matinée coûtait cinq requêtes
     * complètes alors que la première avait déjà tout apporté. Dix minutes est assez court pour
     * que « j'ouvre l'app, je lis » reste frais, et assez long pour que les ouvertures enchaînées
     * ne se multiplient pas.
     */
    private val launchSyncDelayMs = 10L * 60L * 1000L

    /**
     * Synchronisation au démarrage, si l'option est active et que [Prefs.LAST_SYNC_AT] est
     * plus vieux que [launchSyncDelayMs].
     *
     * La file d'attente locale est toujours vidée : elle ne coûte aucune requête réseau et
     * ne dépend que des actions de lecture en attente depuis la dernière visite.
     */
    fun syncOnLaunch() = viewModelScope.launch {
        val prefs = repo.prefs
        val last = prefs.flow(Prefs.LAST_SYNC_AT, 0L).first()
        val fresh = last > 0L && System.currentTimeMillis() - last < launchSyncDelayMs
        if (syncOnLaunch.value && !fresh) {
            repo.pushQueue().also { repo.syncDown() }
            prefs.set(Prefs.LAST_SYNC_AT, System.currentTimeMillis())
        }
        repo.syncReadLater()
        refreshNow()
    }
    fun setRead(id: String, unread: Boolean) = viewModelScope.launch {
        repo.setRead(id, unread)
        // Filtre « non-lus seuls » : garder l'article à l'écran plutôt que le faire disparaître.
        // Repasser en non-lu l'oublie : inutile de garder un article déjà visible par le filtre.
        keepIds.value = if (!unread) keepIds.value + id else keepIds.value - id
        articles = articles.map { if (it.id == id) it.copy(unread = unread) else it }
    }
    fun setStar(id: String, star: Boolean) = viewModelScope.launch {
        repo.setStarred(id, star)
        articles = articles.map { if (it.id == id) it.copy(starred = star) else it }
    }
    /** Journalise une lecture (viewModelScope : survit à la fermeture de l'écran article). */
    fun logReading(articleId: String, feedId: String, durationSec: Int) = viewModelScope.launch {
        repo.logReading(articleId, feedId, durationSec)
    }
    fun markAllRead() = viewModelScope.launch {
        val ids = when {
            scope.feedId != null -> listOf(scope.feedId!!)
            scope.categoryId != null -> repo.folderFeeds(scope.categoryId!!)
            else -> emptyList()
        }
        repo.markAllRead(ids)
        // Tout est lu : rien à garder « collant », sinon la liste « non-lus seuls » restait pleine.
        keepIds.value = emptySet()
        refresh()
    }
    fun loadStats(period: StatsPeriod = StatsPeriod.WEEK, year: Int = 0) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val first = repo.firstReadingAt()
        val r = statsRange(period, year, now, first)
        statsYears = repo.statsYears()
        stats = repo.stats(r[0], r[1])
    }
    /** Aucune analyse au démarrage de l'écran : le scan ne part que sur demande explicite. */
    var dupScanned by mutableStateOf(false)
    fun loadDups() = viewModelScope.launch { dups = repo.duplicateGroups(); dupScanned = true }
    fun loadAccount() = viewModelScope.launch {
        repo.migrateLegacyAccount()
        account = repo.accountInfo()
        accounts = repo.cloudAccounts()
        localFeeds = repo.localFeeds()
    }

    /** Marque comme lus tous les doublons sauf le plus récent de chaque groupe. */
    fun markAllDupsRead() = viewModelScope.launch {
        val ids = dups.flatMap { group -> group.drop(1).map { it.id } }
        if (ids.isEmpty()) return@launch
        ids.forEach { repo.setRead(it, false) }
        dups = repo.duplicateGroups()
        refreshNow()
    }

    // ----- Favoris -----
    fun setFavSet(set: String) = viewModelScope.launch { prefs.set(Prefs.STARRED_SCOPE, set) }
    /** Recharge le tableau « Read later » des comptes, à la demande. */
    fun reloadReadLater() = viewModelScope.launch {
        syncing = true
        try { repo.syncReadLater(); refreshNow() } finally { syncing = false }
    }

    // ----- Comptes -----
    /**
     * Connecte un compte et le rend actif.
     *
     * L'approvisionnement ([PressRepository.backfill]) est lancé après la première
     * synchronisation, jamais avant : il se poursuit en arrière-plan pendant que l'application est
     * déjà utilisable, et l'utilisateur voit du contenu dès les premières secondes. Sans cela, la
     * connexion le bloquerait sur un compteur d'archives.
     */
    fun connectAccount(service: String, token: String, name: String = "") = viewModelScope.launch {
        val connected = repo.addAccount(service, token, name)
        refreshNow()
        account = repo.accountInfo()
        syncing = true
        syncMsg = try {
            val n = repo.backfill(connected.id)
            if (n > 0) "Historique du compte récupéré : $n articles" else null
        } finally {
            syncing = false
            refreshNow()
        }
    }
    fun useAccount(acc: com.pressia.data.CloudAccount) = viewModelScope.launch {
        repo.setActiveAccount(acc.id)
        refreshNow()
        account = repo.accountInfo()
    }
    fun disconnectAccount(acc: com.pressia.data.CloudAccount) = viewModelScope.launch {
        repo.deleteAccount(acc.id)
        refreshNow()
        account = repo.accountInfo()
    }
}
