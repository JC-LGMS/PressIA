package com.pressia.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Écran flux façon Press : titre périmètre + actions sync / tout-lu / filtre lus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamScaffold(vm: PressVM, title: String, onOpen: (com.pressia.data.local.ArticleEntity) -> Unit, onMenu: () -> Unit) {
    // Filtre mémorisé par périmètre (voir PressVM.unreadOnly) : suit le flux courant.
    val viewMode by vm.viewMode.collectAsState()
    val unreadOnly = vm.unreadOnly
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // Liste réactive : les nouveaux articles enregistrés par la synchronisation (bouton ou tâche
    // d'arrière-plan) apparaissent immédiatement, sans rechargement manuel de l'écran.
    // L'initial est null (« rien émis pour ce périmètre ») et non l'ancien snapshot `vm.articles` :
    // à l'ouverture d'un autre flux, on ne revoit plus brièvement les articles du précédent en
    // attendant la première émission du nouveau flux (un blanc d'une image, Room répond aussitôt).
    // Le « Rien ici » ne s'affiche que sur une vraie liste vide, pas pendant ce blanc.
    val live = remember(vm.scope, unreadOnly) { vm.streamList(unreadOnly) }
    val reactive by live.collectAsState(initial = null)
    val feed = vm.subs.firstOrNull { it.id == vm.scope.feedId }
    Scaffold(
        topBar = {
            // Column explicite : sans elle, SyncProgress et TopAppBar se superposent dans le
            // slot topBar et le titre opaque couvre la barre.
            Column {
                // Barre de synchro tout en haut de l'écran, au-dessus du titre.
                if (vm.syncing) SyncProgress()
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
                            // Pose persistée par flux (voir PressVM.setUnreadOnly), toast immédiat.
                            vm.setUnreadOnly(next)
                            toast(ctx, if (next) "Non-lus seuls" else "Tous les articles, lus inclus")
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
            }
        }
    ) { pad ->
        // Le PullToRefreshBox enveloppe TOUS les états (chargement, vide, articles) : le geste de
        // tirage vers le bas doit marcher même quand aucun article n'est visible (avant, le
        // composant n'existait que dans la branche « articles », donc rien à tirer en zone vide).
        // Attention : il ne faut PAS envelopper l'ArticleList (elle-même défilante) dans un autre
        // conteneur défilant — Compose refuse une liste mesurée avec une hauteur infinie. Les deux
        // états sans article ont donc chacun leur petit LazyColumn (assez pour capter le tirage).
        var gestureRefresh by remember { mutableStateOf(false) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = gestureRefresh,
            onRefresh = {
                scope.launch {
                    gestureRefresh = true
                    // Par vm.sync() (suivi, annulable) plutôt qu'un lancement direct :
                    // une déconnexion arrête aussi les synchronisations au geste.
                    vm.sync()
                    kotlinx.coroutines.delay(100)
                    gestureRefresh = false
                }
            },
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            val localArticles = reactive
            if (localArticles == null) {
                // Changement de périmètre : blanc d'une image, jamais les anciens articles.
                LazyColumn(Modifier.fillMaxSize()) { item { Spacer(Modifier.height(1.dp)) } }
            } else if (localArticles.isEmpty() && vm.loaded && !vm.syncing) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (!unreadOnly) "Rien ici — synchronisez."
                                else "Rien ici — synchronisez ou touchez l'œil pour voir les lus.",
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                }
            } else {
                // Flux d'un seul site : la source se répète sous chaque titre, on ne garde que l'heure.
                ArticleList(localArticles, viewMode, onOpen, vm, showSource = vm.scope.feedId == null)
            }
        }
    }
}
