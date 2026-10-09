package com.pressia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pressia.data.local.Prefs
import kotlinx.coroutines.launch

/** Écran flux façon Press : titre périmètre + actions sync / tout-lu / filtre lus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScaffold(vm: PressVM, title: String, onOpen: (com.pressia.data.local.ArticleEntity) -> Unit, onMenu: () -> Unit) {
    val viewMode by vm.viewMode.collectAsState()
    val unreadOnly by vm.unreadOnly.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.syncMsg) {
        vm.syncMsg?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); vm.syncMsg = null }
    }
    // Liste réactive : les nouveaux articles enregistrés par la synchronisation (bouton ou tâche
    // d'arrière-plan) apparaissent immédiatement, sans rechargement manuel de l'écran.
    val live = remember(vm.scope, unreadOnly) { vm.streamList(unreadOnly) }
    val reactive by live.collectAsState(initial = vm.articles)
    val localArticles = reactive
    val feed = vm.subs.firstOrNull { it.id == vm.scope.feedId }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (feed != null) {
                            FeedAvatar(feed, 28)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") } },
                actions = {
                    IconButton({
                        val next = !unreadOnly
                        scope.launch {
                            // Repasser en « non-lus seuls » masque les lus de la session : on oublie
                            // les articles gardés « collants », sinon l'œil semblait inopérant.
                            if (next) vm.clearKept()
                            vm.repo.prefs.set(Prefs.SHOW_UNREAD_ONLY, next)
                            toast(ctx, if (next) "Non-lus seuls" else "Tous les articles, lus inclus")
                            vm.refresh()
                        }
                    }) {
                        Icon(
                            if (unreadOnly) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (unreadOnly) "afficher aussi les lus" else "ne montrer que les non-lus"
                        )
                    }
                    SyncButton(vm)
                    IconButton({
                        vm.markAllRead()
                        toast(ctx, "Tous marqués comme lus")
                    }) { Icon(Icons.Filled.DoneAll, "tout marquer lu") }
                }
            )
            if (vm.syncing) SyncProgress()
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (localArticles.isEmpty() && vm.loaded && !vm.syncing) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        if (!unreadOnly) "Rien ici — synchronisez."
                        else "Rien ici — synchronisez ou touchez l'œil pour voir les lus.",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                // Tirer la liste vers le bas synchronise, comme le bouton d'en haut : l'indicateur
                // suit la synchronisation en cours, et son message arrive dans le bandeau ci-dessus.
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = vm.syncing,
                    onRefresh = { if (!vm.syncing) vm.sync() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Flux d'un seul site : la source se répète sous chaque titre, on ne garde que l'heure.
                    ArticleList(localArticles, viewMode, onOpen, vm, showSource = vm.scope.feedId == null)
                }
            }
        }
    }
}

/**
 * Écran Favoris : le périmètre affiché est explicite et modifiable, comme celui de la recherche.
 *
 * Deux dimensions sont exposées, parce que les deux sont ambiguës d'elles-mêmes :
 *  - le **jeu de favoris** : les étoiles de PressIA, le tableau « Read later » du compte, ou les deux ;
 *  - le **flux** couvert : tous, un dossier, ou un flux donné.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavorisScreen(vm: PressVM, onOpen: (com.pressia.data.local.ArticleEntity) -> Unit, onMenu: () -> Unit) {
    val viewMode by vm.viewMode.collectAsState()
    val set by vm.favSet.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var favUnreadOnly by remember { mutableStateOf(false) }
    var showSet by remember { mutableStateOf(false) }
    var showFeed by remember { mutableStateOf(false) }
    // Le flux couvert n'est pas celui du flux principal : les favoris se consultent souvent
    // « tous flux » alors qu'on se trouve dans un dossier précis.
    var feedScope by remember { mutableStateOf(Scope(label = "Tous les articles")) }
    val fav = FavScope(set, feedScope)

    LaunchedEffect(vm.favSet, vm.hasCloudAccounts) {
        if (vm.favSet.value == "later" && !vm.hasCloudAccounts) {
            vm.setFavSet("all")
        }
    }
    LaunchedEffect(vm.syncMsg) {
        vm.syncMsg?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); vm.syncMsg = null }
    }
    val live = remember(set, feedScope, favUnreadOnly) { vm.streamList(favUnreadOnly, fav) }
    val articles by live.collectAsState(initial = emptyList())

    val feed = vm.subs.firstOrNull { it.id == feedScope.feedId }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        drawerIconFor("starred")?.let {
                            Icon(it, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (feed != null) {
                            FeedAvatar(feed, 28)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Favoris", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") } },
                actions = {
                    IconButton({
                        val next = !favUnreadOnly
                        favUnreadOnly = next
                        // Même « collant » que le flux principal : réactiver le filtre oublie les lus.
                        if (next) vm.clearKept()
                        toast(ctx, if (next) "Non-lus seuls" else "Tous les favoris, lus inclus")
                    }) {
                        Icon(
                            if (favUnreadOnly) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            if (favUnreadOnly) "afficher aussi les favoris lus" else "ne montrer que les favoris non lus"
                        )
                    }
                    // Le tableau du compte est distant : son rafraîchissement est une action explicite.
                    SyncButton(vm, "actualiser les favoris") {
                        if (set == "later") {
                            scope.launch {
                                vm.reloadReadLater()
                                toast(ctx, "« Read later » actualisé")
                            }
                        } else vm.sync()
                    }
                }
            )
            if (vm.syncing) SyncProgress()
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                FavScopeChip(Icons.Filled.Star, "Favoris : ${favSetLabel(set)}") { showSet = !showSet; if (showSet) showFeed = false }
                if (showSet) {
                    FavScopeChooser(
                        vm = vm,
                        set = set,
                        showSet = true,
                        onPickSet = { vm.setFavSet(it); showSet = false },
                        onPickFeed = { feedScope = it; showFeed = false }
                    )
                }
                Spacer(Modifier.height(4.dp))
                FavScopeChip(Icons.Filled.RssFeed, "Flux : ${feedScope.label}") { showFeed = !showFeed; if (showFeed) showSet = false }
                if (showFeed) {
                    FavScopeChooser(
                        vm = vm,
                        set = set,
                        showSet = false,
                        onPickSet = { vm.setFavSet(it); showSet = false },
                        onPickFeed = { feedScope = it; showFeed = false }
                    )
                }
            }
            when {
                articles.isEmpty() && vm.loaded && !vm.syncing -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        when (set) {
                            "later" -> "Rien dans le tableau « Read later » — touchez l'actualisation pour le recharger."
                            "all" -> "Aucun favori dans ce périmètre : ni étoile PressIA, ni article dans « Read later »."
                            else -> "Aucun favori PressIA dans ce périmètre."
                        },
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                else -> ArticleList(articles, viewMode, onOpen, vm, showSource = feedScope.feedId == null)
            }
        }
    }
}

@Composable
private fun FavScopeChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Sélecteur du périmètre affiché, déplié sous le bandeau. Le jeu de favoris offre les deux
 * ensembles plus leur réunion ; le flux couvert offre l'ensemble, puis chaque dossier et chaque flux.
 */
@Composable
private fun FavScopeChooser(
    vm: PressVM,
    set: String,
    showSet: Boolean,
    onPickSet: (String) -> Unit,
    onPickFeed: (Scope) -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState())
                .heightIn(max = 320.dp)
        ) {
            if (showSet) {
                FavChoiceRow("Tous les favoris", set == "all") { onPickSet("all") }
                FavChoiceRow("Favoris PressIA", set == "app") { onPickSet("app") }
                if (vm.hasCloudAccounts) {
                    FavChoiceRow("À lire plus tard - Feedly", set == "later") { onPickSet("later") }
                }
            } else {
                FavChoiceRow("Tous les articles") { onPickFeed(Scope(label = "Tous les articles")) }
                vm.folders.forEach { f ->
                    FavChoiceRow(f.label) { onPickFeed(Scope(categoryId = f.id, label = f.label)) }
                }
                vm.subs.forEach { s ->
                    val pretty = prettyFeedTitle(s.title)
                    FavChoiceRow(pretty) { onPickFeed(Scope(feedId = s.id, label = pretty)) }
                }
            }
        }
    }
}

@Composable
private fun FavChoiceRow(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check, null, Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
        )
    }
}
