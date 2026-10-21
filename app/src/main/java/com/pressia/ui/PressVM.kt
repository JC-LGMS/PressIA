package com.pressia.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pressia.data.PressRepository
import com.pressia.data.providerBase
import com.pressia.data.isSyncFresh
import com.pressia.data.rateLimitMessage
import com.pressia.data.ReadingStats
import com.pressia.data.StatsPeriod
import com.pressia.data.isLocalProvider
import com.pressia.data.local.*
import com.pressia.data.statsRange
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val set: String = "all",
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

/**
 * Valeur sûre d'un réglage de geste d'article : l'inconnu (vieille sauvegarde, saisie
 * extérieure) retombe sur le défaut au lieu de désactiver le geste en silence.
 */
fun articleGestureAction(raw: String, default: String, valid: List<String>): String =
    raw.takeIf { it in valid } ?: default

/** Actions du double-tap sur un article ouvert (voir `DOUBLE_TAP_ACTION`). */
val DOUBLE_TAP_IDS = listOf("readability", "star", "read", "share", "instapaper", "none")

/** Actions des balayages dans un article ouvert (« older »/« newer » = navigation). */
val ARTICLE_SWIPE_IDS = listOf("older", "newer", "star", "read", "share", "instapaper", "none")

/**
 * Nombre de non-lus d'un périmètre, pour le compteur affiché entre parenthèses à côté du titre
 * d'une liste : le flux lui-même, la somme de ses flux pour un dossier — un dossier fusionné
 * compris, `folderUnreads` portant les deux clés (voir `unreadByFolder`) — et tous les flux pour
 * « Tous les articles ». Pur et testé.
 */
fun scopeUnread(s: Scope, unreads: Map<String, Int>, folderUnreads: Map<String, Int>): Int = when {
    s.feedId != null -> unreads[s.feedId] ?: 0
    s.categoryId != null -> folderUnreads[s.categoryId] ?: 0
    else -> unreads.values.sum()
}

/** Icônes d'action proposées pour la barre d'un article ouvert, dans l'ordre canonique. */
val ARTICLE_ACTION_IDS = listOf("star", "read", "share", "readability", "instapaper")

/** Icônes affichées par défaut : favoris, lu/non-lu, partage. */
const val ARTICLE_ACTIONS_DEFAULT = "star,read,share"

/** Libellé d'une action de geste d'article (double-tap, balayages), pour les réglages. */
fun articleNavLabel(id: String): String = when (id) {
    "older" -> "Article plus ancien"
    "newer" -> "Article plus récent"
    "readability" -> "Readability"
    "star" -> "Favoris"
    "read" -> "Lu/non-lu"
    "share" -> "Partager"
    "instapaper" -> "Instapaper"
    else -> "Aucun"
}

/** Libellé d'une icône d'action d'article, pour les réglages. */
fun articleActionLabel(id: String): String = when (id) {
    "star" -> "Favoris"
    "read" -> "Lu/non-lu"
    "share" -> "Partager"
    "readability" -> "Readability"
    "instapaper" -> "Instapaper"
    else -> id
}

/**
 * Icônes activées, dans l'ordre d'affichage : inconnus et doublons ignorés (une sauvegarde
 * extérieure ne doit jamais faire tomber la barre d'actions).
 */
fun parseArticleActions(raw: String): List<String> =
    raw.split(",").map { it.trim() }.filter { it in ARTICLE_ACTION_IDS }.distinct()

class PressVM(val repo: PressRepository) : ViewModel() {
    val prefs = repo.prefs
    var scope by mutableStateOf(Scope())
    var articles by mutableStateOf<List<ArticleEntity>>(emptyList())
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
    /** Dossiers affichés (un par dossier, ou fusionnés entre comptes selon [mergeFolders]). */
    var folders by mutableStateOf<List<PressRepository.Folder>>(emptyList())
    var subs by mutableStateOf<List<SubscriptionEntity>>(emptyList())
    /**
     * Synchronisations de premier plan en cours (manuelle, démarrage, favicons…).
     * Un compteur plutôt qu'un booléen : deux opérations parties ensemble ne doivent
     * pas éteindre l'indicateur à la première finie.
     */
    private var syncJobs by mutableStateOf(0)
    /** Une synchronisation est en cours (l'app ne synchronise que lorsqu'elle est ouverte). */
    val syncing: Boolean get() = syncJobs > 0
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
    /**
     * Mode « article complet » (Readability) épinglé pour la session : quand l'icône
     * Readability l'active, tous les articles ouverts passent en complet. Sauf réglage
     * « Readability persistant », la sortie de l'écran article le désépingle (voir
     * [onLeftArticle]) ; le désactiver manuellement le coupe aussitôt.
     */
    var readabilityOn by mutableStateOf(false)

    /**
     * Sortie de la vue article : sauf « Readability persistant », le mode complet épinglé
     * retombe — les articles suivants s'ouvrent en extrait RSS jusqu'à réactivation.
     *
     * Le balayage d'un article à l'autre ne quitte pas l'écran (même destination) : le mode y
     * survit, comme demandé. Sont donc concernés aussi bien le retour à la liste, l'accueil ou
     * un dossier que le passage par un écran utilitaire (Favoris, Réglages…).
     */
    suspend fun onLeftArticle() {
        if (prefs.data.first()[Prefs.READABILITY_STICKY] != true) readabilityOn = false
    }
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
    /**
     * Ajout d'un flux RSS local en cours (téléchargement de la première page + première
     * synchronisation) : l'écran Comptes désactive son bouton pendant ce temps, sinon un second
     * appui relançait tout l'enregistrement pour rien.
     */
    var localFeedAdding by mutableStateOf(false)
        private set

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
    val articleTitle: StateFlow<Int> = prefs.flow(Prefs.ARTICLE_TITLE_SIZE, 19).stateIn(viewModelScope, SharingStarted.Eagerly, 19)
    val dateFont: StateFlow<Int> = prefs.flow(Prefs.DATE_FONT_SIZE, 12).stateIn(viewModelScope, SharingStarted.Eagerly, 12)
    val trailingAction: StateFlow<String> = prefs.flow(Prefs.TRAILING_ACTION, "read").stateIn(viewModelScope, SharingStarted.Eagerly, "read")
    /** Icônes d'action de l'écran article, dans l'ordre d'affichage (menu toujours en dernier). */
    val articleActions: StateFlow<List<String>> = prefs.flow(Prefs.ARTICLE_ACTIONS, ARTICLE_ACTIONS_DEFAULT)
        .map { parseArticleActions(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, parseArticleActions(ARTICLE_ACTIONS_DEFAULT))
    /** Double-tap sur un article ouvert : readability (défaut) | star | read | share | instapaper | none. */
    val doubleTapAction: StateFlow<String> = prefs.flow(Prefs.DOUBLE_TAP_ACTION, Prefs.DOUBLE_TAP_DEFAULT)
        .map { articleGestureAction(it, Prefs.DOUBLE_TAP_DEFAULT, DOUBLE_TAP_IDS) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Prefs.DOUBLE_TAP_DEFAULT)
    /** Balayage gauche dans un article : older (défaut, article plus ancien) | action | none. */
    val articleSwipeLeft: StateFlow<String> = prefs.flow(Prefs.ARTICLE_SWIPE_LEFT, Prefs.ARTICLE_SWIPE_LEFT_DEFAULT)
        .map { articleGestureAction(it, Prefs.ARTICLE_SWIPE_LEFT_DEFAULT, ARTICLE_SWIPE_IDS - "newer") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Prefs.ARTICLE_SWIPE_LEFT_DEFAULT)
    /** Balayage droit dans un article : newer (défaut, article plus récent) | action | none. */
    val articleSwipeRight: StateFlow<String> = prefs.flow(Prefs.ARTICLE_SWIPE_RIGHT, Prefs.ARTICLE_SWIPE_RIGHT_DEFAULT)
        .map { articleGestureAction(it, Prefs.ARTICLE_SWIPE_RIGHT_DEFAULT, ARTICLE_SWIPE_IDS - "older") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Prefs.ARTICLE_SWIPE_RIGHT_DEFAULT)
    /** Glissement d'un article vers la gauche : "star" (défaut) | "read" | "share" | "instapaper" | "none". */
    val swipeAction: StateFlow<String> = prefs.flow(Prefs.SWIPE_ACTION, "star").stateIn(viewModelScope, SharingStarted.Eagerly, "star")
    /** Glissement d'un article vers la droite : mêmes options, "read" par défaut. */
    val swipeRightAction: StateFlow<String> = prefs.flow(Prefs.SWIPE_RIGHT_ACTION, "read").stateIn(viewModelScope, SharingStarted.Eagerly, "read")
    /** Identifiant Instapaper connecté, vide si aucun (le mot de passe reste dans les prefs). */
    val instapaperUser: StateFlow<String> = prefs.flow(Prefs.INSTAPAPER_USER, "").stateIn(viewModelScope, SharingStarted.Eagerly, "")
    /** Envoi Instapaper : "direct" (API simple) ou "choose" (dossier + tags via API v2). */
    val instapaperMode: StateFlow<String> = prefs.flow(Prefs.INSTAPAPER_MODE, "direct").stateIn(viewModelScope, SharingStarted.Eagerly, "direct")
    /** Session web Instapaper active (dossiers + tags), dernier état vérifié. */
    val instapaperWeb: StateFlow<Boolean> = prefs.flow(Prefs.INSTAPAPER_WEB, false).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /**
     * Comptes à la session expirée (voir `EXPIRED_ACCOUNTS`) : l'écran Comptes les signale avec
     * une réinvite à reconnecter, et la synchro l'annonce au lieu d'un vague échec réseau.
     */
    val expiredAccounts: StateFlow<Set<String>> = prefs.flow(Prefs.EXPIRED_ACCOUNTS, "")
        .map { com.pressia.data.parseExpired(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /**
     * Article en attente de sa destination Instapaper (mode « choose ») : tant qu'il est non nul,
     * le dialogue dossier + tags est affiché. Positionné par [askInstapaperTarget], effacé à
     * l'envoi ou à l'annulation.
     */
    var instapaperPickerFor by mutableStateOf<ArticleEntity?>(null)
    /**
     * Page d'accueil de démarrage (« subs » = Accueil, « stream » = Tous les articles).
     * Initialement **null** : DataStore répond de façon asynchrone, et l'ancien défaut « subs »
     * exposé dès la première composition faisait démarrer sur « Accueil » même quand le réglage
     * disait « Tous les articles » (valeur par défaut figée par `remember`, bug signalé).
     */
    val homeScreen: StateFlow<String?> = prefs.flow(Prefs.HOME_SCREEN, "subs").stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** Ouverture d'un article : « internal » (lecture dans PressIA) ou « external » (navigateur). */
    val openMode: StateFlow<String> = prefs.flow(Prefs.OPEN_MODE, "internal").stateIn(viewModelScope, SharingStarted.Eagerly, "internal")
    val hasCloudAccounts: Boolean get() = accounts.isNotEmpty()
    /** Faux = un dossier par compte même si les noms se ressemblent. */
    val mergeFolders: StateFlow<Boolean> = prefs.flow(Prefs.MERGE_FOLDERS, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Jeu de favoris retenu par l'écran Favoris : « app » (étoiles) | « later » (Read later) | « all ». */
    val favSet: StateFlow<String> = prefs.flow(Prefs.STARRED_SCOPE, "all").stateIn(viewModelScope, SharingStarted.Eagerly, "all")
    /** Masquage des flux sans article non lu, pour l'écran Accueil (indépendant du drawer). */
    val hideEmptySubs: StateFlow<Boolean> = prefs.flow(Prefs.HIDE_EMPTY_SUBS, false).stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Échelle des libellés du drawer, en pourcentage. */
    val tapFaviconRead: StateFlow<Boolean> = prefs.flow(Prefs.TAP_FAVICON_READ, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val drawerScale: StateFlow<Int> = prefs.flow(Prefs.DRAWER_TEXT_SCALE, 100).stateIn(viewModelScope, SharingStarted.Eagerly, 100)
    /** Échelle des libellés dossier / flux de l'écran Accueil, en pourcentage. */
    val homeScale: StateFlow<Int> = prefs.flow(Prefs.HOME_TEXT_SCALE, 100).stateIn(viewModelScope, SharingStarted.Eagerly, 100)
    /** Synchronisation automatique au lancement de l'application. */
    val syncOnLaunch: StateFlow<Boolean> = prefs.flow(Prefs.SYNC_ON_LAUNCH, true).stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Identifiant du compte actif, null en RSS local — sert à cocher le compte dans l'écran Comptes. */
    val activeAccountId: StateFlow<String?> = prefs.flow(Prefs.ACTIVE_ACCOUNT, "")
        .map { it.ifBlank { null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { migrateAppearance() }
        // Identifiants d'application Inoreader (`AppId` / `AppKey`) : l'API les exige sur
        // chaque appel, en plus de l'authentification utilisateur. Ils sont publiés au
        // démarrage (l'utilisateur peut les redéfinir dans les Réglages) — sans eux,
        // toute lecture de compte Inoreader se heurte à un 403.
        viewModelScope.launch { repo.refreshInoreaderAppAuth() }
        // Étapes publiées par le pipeline partagé (manuel, lancement) : le bandeau
        // les suit quel que soit le déclencheur. Les nulls (fin de passe, voir les
        // `finally` du dépôt) sont ignorés : sans cela l'écran rouvert plus tard dans le
        // même processus rejouait la dernière étape en bandeau.
        viewModelScope.launch { repo.syncStep.collect { if (it != null) syncMsg = it } }
        // Contenu stocké modifié en cours de route (abonnements approvisionnés, fin de passe
        // articles ou « Read later ») : l'accueil et les compteurs suivent sans attendre la
        // fin de la synchronisation. Les listes d'articles, elles, sont déjà réactives.
        viewModelScope.launch { repo.listsChanged.collect { refreshNow() } }
        // Les dossiers suivent le réglage fusion / séparation : la liste est reconstruite à chaque changement.
        viewModelScope.launch { mergeFolders.collect { folders = repo.folders(it) } }
        // Dossiers repliés : conservés d'une session à l'autre.
        viewModelScope.launch {
            collapsedFolders = prefs.flow(Prefs.COLLAPSED_FOLDERS, "").first()
                .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        // Bascule vers le RSS local : plus rien à pousser, on vide la file.
        // `drop(1)` : l'état initial de `provider` est « local » tant que la préférence n'est
        // pas lue. Sans ce filtre, la file était vidée à *chaque* création du ViewModel — donc
        // à chaque démarrage : les actions en attente (marquage groupé, lecture hors-ligne)
        // ne partaient jamais, et la synchronisation suivante rouvrait les articles.
        viewModelScope.launch {
            provider.drop(1).collect { p -> if (isLocalProvider(p)) repo.purgeQueue() }
        }
        // Filtre du périmètre de démarrage : sans cela l'œil partirait du défaut global.
        refreshUnreadOnly()
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
        if (s.feedId != scope.feedId || s.categoryId != scope.categoryId) {
            keepIds.value = emptySet()
            articles = emptyList()  // vide l'écran immédiatement, évite le flash du flux précédent
        }
        // Changer de périmètre (ou quitter la page), c'est abandonner l'annulation en cours :
        // elle ne porte que sur le marquage qu'on vient de voir.
        dismissUndo()
        scope = s
        refreshUnreadOnly()
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
     * Filtre « non-lus seuls » du périmètre courant : chaque flux rouvre avec son dernier
     * choix d'œil ([Prefs.UNREAD_ONLY_BY_FEED]) ; un périmètre jamais réglé repart sur
     * « non-lus seuls ». L'écran Favoris garde son propre filtre local, hors persistance.
     */
    var unreadOnly by mutableStateOf(true)

    private fun unreadScopeKey(s: Scope): String = when {
        s.feedId != null -> "feed:${s.feedId}"
        s.categoryId != null -> "cat:${s.categoryId}"
        else -> "all"
    }

    /** Recharge le filtre du périmètre courant (démarrage, changement de flux, import). */
    fun refreshUnreadOnly() = viewModelScope.launch {
        val data = prefs.data.first()
        val key = unreadScopeKey(scope)
        val per: Boolean? = runCatching {
            val o = org.json.JSONObject(data[Prefs.UNREAD_ONLY_BY_FEED].orEmpty())
            if (o.has(key)) o.optBoolean(key) else null
        }.getOrNull()
        unreadOnly = per ?: true
    }

    /** Pose le filtre du périmètre courant (œil), sans toucher aux autres flux. */
    fun setUnreadOnly(next: Boolean) = viewModelScope.launch {
        // Même « collant » que le flux principal : réactiver le filtre oublie les lus.
        if (next) clearKept()
        val key = unreadScopeKey(scope)
        val raw = prefs.data.first()[Prefs.UNREAD_ONLY_BY_FEED].orEmpty()
        val o = runCatching { org.json.JSONObject(raw) }.getOrDefault(org.json.JSONObject())
        o.put(key, next)
        prefs.set(Prefs.UNREAD_ONLY_BY_FEED, o.toString())
        unreadOnly = next
        refreshNow()
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

    /** Verrouille / déverrouille l'indicateur (toujours appariés, en `finally`). */
    private fun lockSync() { syncJobs++ }
    private fun unlockSync() { syncJobs = (syncJobs - 1).coerceAtLeast(0) }

    fun refresh() = viewModelScope.launch { refreshNow() }

    /** Vrai une fois le premier remplissage terminé : avant, vide = « pas encore chargé ». */
    var loaded by mutableStateOf(false)
        private set

    /** Remplit l'état local depuis la base (version suspendable de [refresh]). */
    private suspend fun refreshNow() {
        try {
        // Un compte connecté avant la version multi-comptes doit être repris avant d'être listé.
        repo.migrateLegacyAccount()
        categories = repo.categories()
        folders = repo.folders(mergeFolders.value)
        subs = repo.subscriptions()
        unreads = repo.unreadByFeed()
        folderUnreads = repo.unreadByFolder()
        articles = repo.stream(scope.feedId, scope.categoryId, unreadOnly)
        accounts = repo.cloudAccounts()
        localFeeds = repo.localFeeds()
        } finally {
            loaded = true
        }
    }

    /** Bascule « masquer les flux sans dossier », pour l'écran Accueil. */
    fun toggleHideEmptySubs() = viewModelScope.launch {
        prefs.set(Prefs.HIDE_EMPTY_SUBS, !hideEmptySubs.value)
    }

    /** Non-lus du périmètre affiché, pour le compteur du titre (voir [scopeUnread]). */
    fun unreadCountFor(s: Scope = scope): Int = scopeUnread(s, unreads, folderUnreads)

    /** Supprime un flux RSS local ; les autres comptes ne sont pas touchés. */
    fun deleteLocalFeed(feedId: String) = viewModelScope.launch {
        repo.deleteLocalFeed(feedId)
        refreshNow()
        // Le compteur de l'écran Comptes (`AccountInfo.feeds`) ne suit pas `refreshNow`.
        account = repo.accountInfo()
    }

    /**
     * Ajoute un flux RSS local et le synchronise une première fois. [localFeedAdding] couvre
     * toute l'opération (enregistrement + première sync) : le bouton de l'écran Comptes reste
     * désactivé jusque-là, `done` étant appelé dans tous les cas (échec compris).
     *
     * La première synchronisation est annoncée comme les autres (bandeau + snackbar, dès qu'une
     * URL valide est reconnue) : sans cela l'ajout restait muet pendant tout le premier
     * téléchargement, alors qu'il peut durer (flux de plusieurs centaines d'articles).
     */
    fun addLocalFeed(url: String, folderId: String? = null, done: (Boolean) -> Unit = {}) = viewModelScope.launch {
        if (localFeedAdding) {
            done(false)
            return@launch
        }
        localFeedAdding = true
        var ok = false
        try {
            val link = repo.addLocalFeed(url, folderId)
            if (link != null) {
                lockSync()
                try {
                    syncMsg = "Réception des articles du nouveau flux…"
                    repo.syncDown(link)
                    ok = true
                } finally {
                    unlockSync()
                }
            }
        } finally {
            refreshNow()
            // Voir deleteLocalFeed : le compteur Comptes ne suit pas `refreshNow`.
            account = repo.accountInfo()
            localFeedAdding = false
            done(ok)
        }
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
     * Synchronisation complète, en attente du résultat : file montante, nouveaux articles de
     * tous les comptes et flux locaux, puis tableau « Read later » — le même contenu quel que
     * soit le déclencheur (bouton, lancement). [sync] en est la version non bloquant.
     *
     * Tente le verrou partagé [PressRepository.withSyncLock] sans attendre : si une synchro
     * est déjà en cours (lancement ou manuelle), la demande est annulée et le
     * message le dit.
     *
     * Sans source (ni compte ni flux), on ne lance même pas le pipeline : il n'y aurait
     * que des étapes à vide — dont « Récupération des favicons… » pour zéro abonnement.
     * Le message le dit au lieu d'un succès aveugle.
     *
     * Chaque grande étape est publiée dans [syncMsg] (bandeau) au fil de l'eau, pour suivre
     * l'avancement ; le message final (nouveautés, fin ou échec) reste celui rendu ici.
     *
     * Le message ne parle que des **nouveaux** articles : le nombre d'articles déjà connus n'apporte
     * rien à l'utilisateur et masquait le seul chiffre utile.
     */
    suspend fun syncNow(): String {
        // Pas de file d'attente pour le manuel : si une synchro est déjà en cours, la demande
        // est annulée au lieu de retélécharger derrière — la flèche tourne déjà, inutile
        // d'empiler.
        if (!repo.isOnline()) return "Pas de réseau : synchronisation impossible"
        if (!repo.hasSources()) return "Aucun compte ni flux : rien à synchroniser"
        return repo.tryWithSyncLock { doSyncNow() } ?: "Synchronisation déjà en cours"
    }

    /** Corps de [syncNow] : le pipeline partagé, verrou déjà acquis (voir aussi [syncOnLaunch]). */
    private suspend fun doSyncNow(): String {
        lockSync()
        try {
            return finishSync(repo.syncPipeline())
        } catch (e: Exception) {
            return failSync()
        } finally {
            unlockSync()
        }
    }

    /**
     * Fin commune manuel/lancement : affiche le résultat et rend le message du bandeau.
     */
    private suspend fun finishSync(n: Int): String {
        // Refresh attendu, et non lancé en arrière-plan : sans cela on pouvait annoncer
        // « connecté » en revenant sur des listes encore vides.
        refreshNow()
        // Les échecs par compte sont avalés par la sync (les autres comptes passent quand
        // même) : si un jeton est mort, on le dit au lieu d'annoncer un succès aveugle.
        if (repo.expiredAccounts().isNotEmpty()) return "Session expirée : reconnectez le compte dans Comptes"
        // Un 429 quelque part dans le pipeline : la synchro n'a rien pu récupérer (ou pas
        // tout) — le dire avec le délai du serveur plutôt qu'un succès aveugle.
        val rl = repo.syncRateLimit
        if (n == 0 && rl != null) return rateLimitMessage(rl.service, rl.retryAfterSec, rl.header)
        val base = if (n > 0) "$n nouvel${if (n > 1) "aux" else ""} article${if (n > 1) "s" else ""}"
        else "Synchronisation terminée"
        if (rl != null) return "$base. ${rateLimitMessage(rl.service, rl.retryAfterSec, rl.header)}"
        return base
    }

    /** Échec commun manuel/lancement : une session connue comme expirée mérite mieux
     * qu'un vague problème de connexion. */
    private suspend fun failSync(): String {
        if (repo.expiredAccounts().isNotEmpty()) return "Session expirée : reconnectez le compte dans Comptes"
        return "Échec de synchronisation : vérifiez la connexion"
    }

    /** Déclenche [syncNow] en tâche de fond et publie le résultat dans [syncMsg]. */
    fun sync() = launchSync { syncMsg = syncNow() }

    /**
     * Une seule synchro effective à la fois, même entre déclencheurs : une nouvelle demande
     * annule la précédente au lieu d'empiler (même sémantique que « déjà en cours », sans
     * redémarrage visible quand il n'y a rien à annuler). Indispensable aussi à la
     * déconnexion, qui doit stopper tout travail avant de supprimer.
     */
    private var syncJob: Job? = null
    private fun launchSync(block: suspend () -> Unit) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch { block() }
    }

    /**
     * Synchronisation au démarrage : exactement la même que la manuelle ([syncNow]).
     *
     * Elle n'a lieu que si l'option est active, qu'il y a de quoi synchroniser (compte ou
     * flux — sinon premier démarrage ou après déconnexion : rien à récupérer, silence total)
     * et qu'aucune synchro bien finie ne date de moins de X minutes (X = réglage
     * « Fréquence de synchronisation »). Sinon elle est totalement silencieuse : option
     * coupée, aucune source ou synchro récente = rien n'est récupéré (ni réseau, ni message,
     * ni animation) — le [refresh] qui suit dans MainActivity suffit à remplir les listes
     * depuis le cache.
     *
     * Le lancement ne précharge pas les favicons plus que le bouton : la passe n'a lieu que
     * lorsqu'elle est due (voir `faviconsDue`), sinon les icônes déjà en cache se rechargent à
     * l'affichage sans requête.
     *
     * Option et fraîcheur sont revérifiées sous le verrou partagé (l'app ne synchronise
     * que lorsqu'elle est ouverte : pas d'autre déclencheur concurrent).
     */
    fun syncOnLaunch() = launchSync {
        repo.withSyncLock {
            val prefs = repo.prefs
            // Lecture directe du réglage, pas du StateFlow : au démarrage celui-ci vaut encore son
            // initial (vrai) tant que le DataStore n'a pas émis — l'option coupée serait ignorée.
            if (prefs.flow(Prefs.SYNC_ON_LAUNCH, true).first()) {
                val last = prefs.flow(Prefs.LAST_SYNC_AT, 0L).first()
                val windowMin = prefs.flow(Prefs.SYNC_INTERVAL_MIN, Prefs.SYNC_INTERVAL_DEFAULT).first()
                val now = System.currentTimeMillis()
                val ageMin = if (last > 0L) (now - last) / 60_000L else -1L
                if (isSyncFresh(last, now, windowMin)) {
                    android.util.Log.i("PressIA", "sync au lancement sautée (dernière synchro il y a $ageMin min, fenêtre $windowMin min)")
                } else if (!repo.hasSources()) {
                    android.util.Log.i("PressIA", "sync au lancement sautée (aucune source : ni compte ni flux)")
                } else {
                    android.util.Log.i("PressIA", "sync au lancement (dernière synchro il y a $ageMin min, fenêtre $windowMin min)")
                    // Même contenu, mêmes indicateurs et même message que la synchro manuelle
                    // (nouveautés, fin ou échec) : le verrou d'indicateur est requis comme
                    // dans doSyncNow, sinon ni flèche ni bandeau bleu pendant la synchro.
                    lockSync()
                    try {
                        syncMsg = try {
                            if (!repo.isOnline()) "Pas de réseau : synchronisation impossible"
                            else finishSync(repo.syncPipeline())
                        } catch (e: Exception) {
                            failSync()
                        }
                    } finally {
                        unlockSync()
                    }
                }
            }
        }
    }
    fun setRead(id: String, unread: Boolean) = viewModelScope.launch {
        // Passer en lu : rendre l'article « collant » AVANT l'écriture — la réémission du flux
        // qui suit l'écriture le trouve déjà gardé, sinon la ligne disparaissait puis
        // réapparaissait (filtre non-lus + pas encore collant entre les deux émissions).
        // Repasser en non-lu : l'oublier APRÈS l'écriture, pour la raison symétrique.
        if (!unread) keepIds.value = keepIds.value + id
        repo.setRead(id, unread)
        if (unread) keepIds.value = keepIds.value - id
        if (articles.none { it.id == id }) {
            // L'article manque à la liste héritée (arrivé par le flux réactif après le refresh,
            // ou rouvert sous filtre non-lus) : l'y mettre pour un retour sur la liste sans flash —
            // la ligne ne fait que changer d'apparence (estompage).
            repo.article(id)?.let { full ->
                articles = (articles + full).sortedByDescending { it.published }
                return@launch
            }
        }
        articles = articles.map { if (it.id == id) it.copy(unread = unread) else it }
    }
    fun setStar(id: String, star: Boolean) = viewModelScope.launch {
        repo.setStarred(id, star)
        // Le dépôt a écrit le bon drapeau (étoile locale, ou « Read later » pour Feedly) :
        // on relit l'article plutôt que de deviner lequel, l'affichage suit.
        repo.article(id)?.let { full ->
            articles = articles.map { if (it.id == id) full else it }
        }
    }
    /** Article d'un flux Feedly : son étoile est le « Read later » du compte. */
    fun isFeedlyArticle(a: ArticleEntity): Boolean =
        subs.firstOrNull { it.id == a.feedId }?.provider?.let { providerBase(it) == "feedly" } == true
    /** Étoile affichée : étoile locale, ou « Read later » pour un article Feedly. */
    fun starState(a: ArticleEntity): Boolean = a.starred || (isFeedlyArticle(a) && a.laterSaved)
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
        // IDs avant marquage (exactement ceux qui vont passer en lus, favoris/read later
        // exclus) : l'annulation sait quoi rouvrir.
        val marked = if (ids.isEmpty()) repo.allMarkableIds() else repo.feedsMarkableIds(ids)
        // Annulation proposée AVANT l'écriture et la poussée au compte : le bouton de la barre
        // bascule en flèche d'annulation dès l'appui, sans attendre la file montante ni le réseau
        // (plusieurs secondes sur « tout le compte »).
        offerUndo(marked)
        repo.markAllRead(ids)
        // Tout est lu : rien à garder « collant », sinon la liste « non-lus seuls » restait pleine.
        keepIds.value = emptySet()
        refresh()
    }

    /**
     * « Tout marquer lu » sur un périmètre choisi ailleurs que dans la liste courante : l'appui
     * long sur un flux ou un dossier de l'accueil (voir `SubscriptionsScreen`). Même mécanique que
     * [markAllRead] — annulation proposée avant l'écriture, rien de « collant » ensuite — mais le
     * périmètre est celui de la ligne touchée, pas celui de l'écran ouvert. Une liste vide ne
     * déclenche rien : il n'y a pas de « tout le compte » implicite ici.
     */
    fun markAllReadFor(feedIds: List<String>) = viewModelScope.launch {
        if (feedIds.isEmpty()) return@launch
        val marked = repo.feedsMarkableIds(feedIds)
        offerUndo(marked)
        repo.markAllRead(feedIds)
        refresh()
    }
    /**
     * Marque des articles lus à l'unité (menu d'appui long « plus récents/anciens ») : gardés
     * visibles (collants, comme une lecture simple qui ne fait pas disparaître la ligne).
     */
    fun markIdsRead(ids: List<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        // Même ordre que « tout marquer lu » : l'annulation est proposée avant la poussée.
        offerUndo(ids)
        repo.markArticlesRead(ids)
        keepIds.value = keepIds.value + ids.toSet()
        refresh()
    }
    /** Proposition d'annulation d'un marquage groupé (« tout marquer lu », menu appui long). */
    data class UndoOffer(val ids: List<String>, val label: String)
    var undoOffer by mutableStateOf<UndoOffer?>(null)
        private set
    /**
     * Pastille d'annulation en bas d'écran : elle, elle s'efface au bout de quelques secondes
     * (voir MainActivity). L'annulation elle-même ([undoOffer]) reste disponible tant qu'on
     * n'a pas quitté la page — via le bouton de la barre du haut.
     */
    var undoPillVisible by mutableStateOf(false)
        private set
    /** Propose l'annulation : vide = rien à annuler, pas de bouton. */
    fun offerUndo(ids: List<String>) {
        undoOffer = if (ids.isEmpty()) null else UndoOffer(
            ids,
            if (ids.size == 1) "1 article marqué lu" else "${ids.size} articles marqués lus"
        )
        undoPillVisible = undoOffer != null
    }
    /** Efface la pastille (délai écoulé) sans abandonner l'annulation (voir `undoOffer`). */
    fun dismissUndoPill() {
        undoPillVisible = false
    }
    /** Abandonne l'annulation (changement de page, annulation consommée, autre marquage). */
    fun dismissUndo() {
        undoOffer = null
        undoPillVisible = false
    }
    /** Annule le marquage : les articles repassent en non-lus, ici et côté compte. */
    fun undoMarkRead() = viewModelScope.launch {
        val o = undoOffer ?: return@launch
        dismissUndo()
        repo.markArticlesUnread(o.ids)
        keepIds.value = keepIds.value - o.ids.toSet()
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
    /** Version attendable : l'écran n'annonce « terminé » qu'une fois la liste posée. */
    suspend fun loadDupsNow() {
        dups = repo.duplicateGroups()
        dupScanned = true
    }
    fun loadDups() = viewModelScope.launch { loadDupsNow() }
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

    // ----- Images -----
    /**
     * Actualisation des favicons en cours (bouton Réglages). Tourne dans le scope du
     * modèle, donc survit à la navigation : quitter l'écran n'interrompt plus le travail
     * (le scope d'écran précédent annulait tout en silence : bouton figé et jamais de toast).
     */
    var refreshingFavicons by mutableStateOf(false)
        private set
    /**
     * Actualise les favicons avec progression dans le bandeau (syncMsg) : l'indicateur
     * est allumé pour le mode persistant (étapes en direct, message final sans
     * fermeture/réouverture). Le travail vit dans le modèle et survit à la navigation.
     */
    fun refreshFavicons() = viewModelScope.launch {
        if (refreshingFavicons) return@launch
        if (!repo.isOnline()) { syncMsg = "Pas de réseau : actualisation impossible"; return@launch }
        refreshingFavicons = true
        lockSync()
        val n = try {
            syncMsg = "Actualisation des favicons en cours…"
            repo.refreshFavicons().also { refreshNow() }
        } finally {
            unlockSync()
            refreshingFavicons = false
        }
        syncMsg = if (n < 0) "Images hors-ligne coupées ou Wi-Fi uniquement : rien d'effacé"
        else "Favicons actualisés ($n favicons)"
    }

    // ----- Favoris -----
    fun setFavSet(set: String) = viewModelScope.launch { prefs.set(Prefs.STARRED_SCOPE, set) }

    // ----- Comptes -----
    /**
     * Connecte le compte Instapaper : les identifiants sont validés par `/api/authenticate`
     * avant d'être conservés (l'identifiant part en sauvegarde, jamais le mot de passe).
     *
     * La session web (dossiers + tags) est ouverte en arrière-plan dans la foulée, avec les
     * mêmes identifiants : en mode « choisir à chaque envoi », tout est prêt sans second bouton.
     */
    fun connectInstapaper(user: String, pass: String, done: (String) -> Unit) = viewModelScope.launch {
        val u = user.trim()
        if (u.isBlank() || pass.isEmpty()) {
            done("Identifiant et mot de passe requis")
            return@launch
        }
        val ok = try {
            com.pressia.data.remote.Instapaper.authenticate(u, pass)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            prefs.set(Prefs.INSTAPAPER_USER, u)
            prefs.set(Prefs.INSTAPAPER_PASS, pass)
            repo.clearAccountExpired("instapaper")
            done("Compte Instapaper connecté")
            // Session web en arrière-plan : silencieuse, le drapeau sert au mode « choose ».
            try {
                withWebSession()
            } catch (_: Exception) { /* réessayée au premier envoi */ }
        } else {
            done("Connexion refusée : vérifiez identifiant et mot de passe")
        }
    }

    /** Déconnecte Instapaper : identifiants, mot de passe et session web sont effacés. */
    fun disconnectInstapaper() = viewModelScope.launch {
        prefs.set(Prefs.INSTAPAPER_USER, "")
        prefs.set(Prefs.INSTAPAPER_PASS, "")
        prefs.set(Prefs.INSTAPAPER_WEB, false)
        repo.clearAccountExpired("instapaper")
        repo.clearAccountExpired("instapaper_web")
        try {
            com.pressia.data.remote.InstapaperWeb.logout()
        } catch (_: Exception) { /* cookies déjà partis */ }
    }

    /**
     * Envoie un article vers Instapaper (Simple API `/api/add`). Le titre affiché est nettoyé
     * comme dans les listes ; sans compte connecté, le message renvoie vers l'écran Comptes.
     */
    fun sendToInstapaper(article: ArticleEntity, done: (String) -> Unit) = viewModelScope.launch {
        val user = prefs.flow(Prefs.INSTAPAPER_USER, "").first()
        val pass = prefs.flow(Prefs.INSTAPAPER_PASS, "").first()
        if (user.isBlank() || pass.isEmpty()) {
            done("Instapaper non connecté : ajoutez le compte depuis Comptes")
            return@launch
        }
        val url = article.url
        if (url.isBlank()) {
            done("Cet article n'a pas de lien à envoyer")
            return@launch
        }
        val title = com.pressia.data.remote.cleanTitle(article.title).ifBlank { "(sans titre)" }
        val res = com.pressia.data.remote.Instapaper.add(user, pass, url, title)
        // Mot de passe refusé = session expirée : l'écran Comptes le signale avec réinvite.
        if (res == com.pressia.data.remote.Instapaper.AddResult.InvalidCredentials) repo.markAccountExpired("instapaper")
        else if (res == com.pressia.data.remote.Instapaper.AddResult.Ok) repo.clearAccountExpired("instapaper")
        done(
            when (res) {
                com.pressia.data.remote.Instapaper.AddResult.Ok -> "Envoyé vers Instapaper"
                com.pressia.data.remote.Instapaper.AddResult.BadRequest -> "Envoi refusé par Instapaper"
                com.pressia.data.remote.Instapaper.AddResult.InvalidCredentials -> "Instapaper : identifiants refusés, reconnectez le compte (Comptes)"
                com.pressia.data.remote.Instapaper.AddResult.ServerError -> "Instapaper indisponible, réessayez"
                com.pressia.data.remote.Instapaper.AddResult.NetworkError -> "Envoi impossible : vérifiez la connexion"
            }
        )
    }

    /** Mémorise le mode d'envoi Instapaper : "direct" (sans dossier ni tag) ou "choose" (à chaque fois). */
    fun setInstapaperMode(mode: String) = viewModelScope.launch {
        prefs.set(Prefs.INSTAPAPER_MODE, if (mode == "choose") "choose" else "direct")
    }

    /**
     * Point d'entrée unique des boutons / swipes Instapaper : envoi direct en mode « direct »,
     * ouverture du dialogue dossier + tags en mode « choose » ([instapaperPickerFor]).
     */
    fun sendOrAskInstapaper(article: ArticleEntity, done: (String) -> Unit) {
        if (instapaperMode.value == "choose") instapaperPickerFor = article
        else sendToInstapaper(article, done)
    }

    /** Ferme le dialogue dossier + tags sans envoyer. */
    fun cancelInstapaperPicker() { instapaperPickerFor = null }

    /**
     * Session web Instapaper utilisable : vérifie `user_session`, sinon tente une reconnexion
     * silencieuse avec les identifiants du compte simple. Le drapeau est recalé à chaque appel.
     *
     * Seul un refus net lève l'alerte « session expirée » : une panne réseau ou l'absence
     * d'identifiants n'ont rien à reconnecter et restent silencieuses.
     */
    suspend fun withWebSession(): Boolean {
        if (com.pressia.data.remote.InstapaperWeb.session() != null) {
            prefs.set(Prefs.INSTAPAPER_WEB, true)
            repo.clearAccountExpired("instapaper")
            repo.clearAccountExpired("instapaper_web")
            return true
        }
        val user = prefs.data.first()[Prefs.INSTAPAPER_USER].orEmpty()
        val pass = prefs.data.first()[Prefs.INSTAPAPER_PASS].orEmpty()
        if (user.isBlank() || pass.isEmpty()) {
            prefs.set(Prefs.INSTAPAPER_WEB, false)
            return false
        }
        // `login` lève en cas de panne réseau (pas d'alerte) et renvoie faux si refusé.
        val ok = try {
            com.pressia.data.remote.InstapaperWeb.login(user, pass)
        } catch (_: Exception) {
            prefs.set(Prefs.INSTAPAPER_WEB, false)
            return false
        }
        prefs.set(Prefs.INSTAPAPER_WEB, ok)
        // Refus net avec les identifiants mémorisés : c'est le mot de passe stocké qui est
        // mauvais, donc l'alerte visible du compte (« instapaper ») est levée. Un succès efface
        // aussi l'ancien drapeau « instapaper_web » (versions précédentes).
        if (ok) {
            repo.clearAccountExpired("instapaper")
            repo.clearAccountExpired("instapaper_web")
        } else {
            repo.markAccountExpired("instapaper")
        }
        return ok
    }

    /** Dossiers du compte Instapaper (liste d'accueil exclue : c'est le choix « Accueil »). */
    suspend fun instapaperFolders(): List<com.pressia.data.remote.InstapaperWeb.Folder> {
        val s = try {
            com.pressia.data.remote.InstapaperWeb.session()
        } catch (_: Exception) { null }
        if (s != null) return s.folders
        if (!withWebSession()) return emptyList()
        return try {
            com.pressia.data.remote.InstapaperWeb.session()?.folders.orEmpty()
        } catch (_: Exception) { emptyList() }
    }

    /** Tags du compte, les plus utilisés d'abord. */
    suspend fun instapaperTags(): List<com.pressia.data.remote.InstapaperWeb.Tag> {
        val s = try {
            com.pressia.data.remote.InstapaperWeb.session()
        } catch (_: Exception) { null }
        if (s != null) return s.tags
        if (!withWebSession()) return emptyList()
        return try {
            com.pressia.data.remote.InstapaperWeb.session()?.tags.orEmpty()
        } catch (_: Exception) { emptyList() }
    }

    /** Crée un dossier Instapaper, null si refusé (titre déjà pris). */
    suspend fun createInstapaperFolder(title: String): com.pressia.data.remote.InstapaperWeb.Folder? {
        if (title.isBlank()) return null
        val s = try {
            com.pressia.data.remote.InstapaperWeb.session()
        } catch (_: Exception) { null }
        val sess = s ?: if (!withWebSession()) return null else try {
            com.pressia.data.remote.InstapaperWeb.session()
        } catch (_: Exception) { null }
        val key = com.pressia.data.remote.InstapaperWeb.formKey(
            com.pressia.data.remote.InstapaperWeb.cookieHeader(), sess?.formKey
        )
        return try {
            com.pressia.data.remote.InstapaperWeb.createFolder(title.trim(), key)
        } catch (_: Exception) { null }
    }

    /**
     * Envoie un article vers Instapaper web : enregistrement (`post_v6`, comme l'extension),
     * puis rangement dans le dossier et pose des tags. Sans session, le message renvoie vers
     * l'écran Comptes.
     */
    fun sendToInstapaperTarget(article: ArticleEntity, folderId: Long?, tags: List<String>, done: (String) -> Unit) = viewModelScope.launch {
        instapaperPickerFor = null
        val url = article.url
        if (url.isBlank()) {
            done("Cet article n'a pas de lien à envoyer")
            return@launch
        }
        val title = com.pressia.data.remote.cleanTitle(article.title).ifBlank { "(sans titre)" }
        suspend fun attempt(): String {
            val (res, saved) = try {
                com.pressia.data.remote.InstapaperWeb.save(url, title)
            } catch (_: Exception) {
                Pair(com.pressia.data.remote.InstapaperWeb.SendResult.NetworkError, null)
            }
            return when (res) {
                com.pressia.data.remote.InstapaperWeb.SendResult.Ok -> {
                    val id = saved!!.bookmarkId
                    if (folderId != null && folderId != 0L) com.pressia.data.remote.InstapaperWeb.move(id, folderId)
                    val sess = try {
                        com.pressia.data.remote.InstapaperWeb.session()
                    } catch (_: Exception) { null }
                    // La pose des tags demande la clé anti-CSRF (cookie `form_key` ou session).
                    if (tags.isNotEmpty()) {
                        val key = com.pressia.data.remote.InstapaperWeb.formKey(
                            com.pressia.data.remote.InstapaperWeb.cookieHeader(), sess?.formKey
                        )
                        com.pressia.data.remote.InstapaperWeb.addTags(id, tags, key)
                    }
                    "Envoyé vers Instapaper"
                }
                com.pressia.data.remote.InstapaperWeb.SendResult.Expired -> "EXPIRED"
                com.pressia.data.remote.InstapaperWeb.SendResult.Refused -> "Envoi refusé par Instapaper"
                com.pressia.data.remote.InstapaperWeb.SendResult.ServerError -> "Instapaper indisponible, réessayez"
                com.pressia.data.remote.InstapaperWeb.SendResult.NetworkError -> "Envoi impossible : vérifiez la connexion"
            }
        }
        var msg = attempt()
        // Session expirée en cours de route : une reconnexion silencieuse puis un seul nouvel essai.
        if (msg == "EXPIRED") {
            prefs.set(Prefs.INSTAPAPER_WEB, false)
            msg = if (withWebSession()) attempt() else "EXPIRED"
            if (msg == "EXPIRED") msg = "Instapaper : reconnectez le compte (Comptes)"
        }
        done(msg)
    }
    /**
     * Connecte un compte et le rend actif, en un seul passage.
     *
     * La première synchronisation remonte l'historique depuis le plus récent
     * ([PressRepository.initialSync], bornes quota / fenêtre / plafond) : le récent est
     * inclus, pas de synchro préalable qui retéléchargerait les mêmes pages. L'application
     * reste utilisable pendant la passe (listes réactives + un seul récit au bandeau), avec
     * un seul message final. Une synchro manuelle entre-temps est refusée (« déjà en
     * cours »), comme pendant une synchro normale.
     *
     * Un seul approvisionnement à la fois : une reconnexion rapide annule le précédent.
     */
    private var backfillJob: Job? = null
    /**
     * Enregistre un compte connecté et lance sa première synchronisation.
     *
     * [refreshToken] est le jeton de renouvellement OAuth : il accompagne le compte dans le
     * registre et lui permet de renouveler son jeton d'accès expiré sans refaire la
     * connexion.
     */
    fun connectAccount(service: String, token: String, name: String = "", refreshToken: String = "") {
        backfillJob?.cancel()
        backfillJob = viewModelScope.launch {
            val connected = repo.addAccount(service, token, name, refreshToken)
            refreshNow()
            account = repo.accountInfo()
            lockSync()
            try {
                syncMsg = try {
                    if (!repo.isOnline()) "Pas de réseau : synchronisation impossible"
                    else finishSync(repo.initialSync(connected.id))
                } catch (e: CancellationException) {
                    // Déconnexion pendant la passe : ne pas annoncer d'échec, juste s'arrêter.
                    throw e
                } catch (e: Exception) {
                    failSync()
                }
            } finally {
                unlockSync()
                refreshNow()
            }
        }
    }
    fun useAccount(acc: com.pressia.data.CloudAccount) = viewModelScope.launch {
        repo.setActiveAccount(acc.id)
        refreshNow()
        account = repo.accountInfo()
    }
    fun disconnectAccount(acc: com.pressia.data.CloudAccount) = viewModelScope.launch {
        // Stoppe tout travail en cours (synchro, approvisionnement) puis supprime sous verrou :
        // sinon leurs écritures resurrectent des articles après le ménage — compteur, listes
        // et favoris fantômes. L'attente est bornée : l'annulation débloque vite le verrou.
        backfillJob?.cancel()
        backfillJob?.join()
        syncJob?.cancel()
        repo.withSyncLock { repo.deleteAccount(acc.id) }
        refreshNow()
        account = repo.accountInfo()
    }
}
