package com.pressia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.pressia.di.Locator
import com.pressia.ui.*
import com.pressia.ui.theme.PressIATheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barre de navigation Android noire, icônes claires.
        @Suppress("DEPRECATION") window.navigationBarColor = android.graphics.Color.BLACK
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = false
        val repo = Locator.repo(this)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T = PressVM(repo) as T
        }
        setContent {
            val vm: PressVM = androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            val theme by vm.theme.collectAsState()
            val accent by vm.accent.collectAsState()
            val bg by vm.bg.collectAsState()
            val home by vm.homeScreen.collectAsState()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // deep link OAuth pressia://oauth?code=..&state=..
            LaunchedEffect(Unit) {
                intent?.data?.let { uri ->
                    if (uri.host == "oauth") {
                        launch { handleOAuthDeepLink(vm, uri); vm.refresh() }
                    }
                }
                vm.syncOnLaunch()
                vm.refresh()
            }
            PressIATheme(theme, accent, bg) {
                val nav = rememberNavController()
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route?.substringBefore("/") ?: startOf(home)
                val startRoute = startOf(home)
                // « Page d'accueil » = l'écran de démarrage. En mode « Tous les articles » comme accueil,
                // il faut aussi que le flux courant soit bien « Tous les articles ».
                val atHome = if (startRoute == "home") current == "home"
                else current == "stream" && vm.scope.feedId == null && vm.scope.categoryId == null

                // Dernière destination de contenu réellement visitée (accueil, dossier ou article).
                // Les écrans utilitaires — Favoris, Recherche, Doublons, Stats, Réglages, Comptes,
                // À propos — s'appuient dessus pour renvoyer là où l'utilisateur se trouvait,
                // plutôt que de remonter une pile qui peut ne plus contenir cette destination.
                var lastContent by rememberSaveable { mutableStateOf(startRoute) }
                LaunchedEffect(current) {
                    if (current in CONTENT_ROUTES) lastContent = current
                }

                /**
                 * Ouvre une destination de contenu en remplaçant la pile : on ne veut garder qu'une
                 * seule destination « de fond », sinon le bouton retour remonterait par-dessus des
                 * écrans utilitaires déjà quittés.
                 */
                fun openContent(route: String, apply: () -> Unit = {}) {
                    apply()
                    if (route == "stream") vm.refresh()
                    nav.navigate(route) {
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                        launchSingleTop = true
                    }
                }

                fun openArticle(id: String) {
                    // Le réglage est lu au moment du clic, pas capturé à la composition : une
                    // valeur périmée ouvrait les articles dans le mauvais mode.
                    val mode = vm.openMode.value
                    // « Navigateur » et « onglet personnalisé » ouvrent tous deux l'article hors de
                    // l'application : il est seulement marqué lu, pas empilé dans l'aperçu interne.
                    if (mode == "external" || mode == "custom") {
                        scope.launch {
                            val a = vm.repo.article(id)
                            val url = a?.url.orEmpty()
                            if (url.isBlank()) {
                                vm.syncMsg = "Cet article n'a pas de lien lisible"
                            } else {
                                if (a?.unread == true) vm.setRead(id, false)
                                if (mode == "custom") openCustomTab(ctx, url) else openUrl(ctx, url)
                            }
                        }
                        return
                    }
                    vm.selectedArticleId = id
                    nav.navigate("article")
                }

                /** Retour à l'accueil, quelle que soit la route de départ. */
                fun goHome() {
                    if (startRoute == "home") {
                        openContent("home")
                    } else {
                        // Accueil = « Tous les articles » : on change de flux, on ne quitte pas l'écran.
                        vm.switchScope(Scope(label = "Tous les articles")); vm.refresh()
                    }
                }

                fun openDrawer() = scope.launch { drawer.open() }
                fun closeDrawer() = scope.launch { drawer.close() }

                /**
                 * Ramène à la dernière destination de contenu visitée. Depuis un écran utilitaire,
                 * un seul retour doit suffire : les étapes intermédiaires sont sautées.
                 */
                fun backToLastContent() {
                    val target = lastContent
                    if (!nav.popBackStack(target, inclusive = false)) {
                        // La destination n'est plus dans la pile (changement d'accueil) : on la recrée.
                        nav.navigate(target) {
                            popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    if (target == "stream") vm.refresh()
                }

                /**
                 * Comportement du bouton retour, identique sur toutes les destinations :
                 *  - drawer ouvert          → il se referme ;
                 *  - écran utilitaire       → retour à la dernière page d'accueil / catégorie / article ;
                 *  - flux (dossier ou flux) → retour à l'accueil ;
                 *  - sur la page d'accueil  → l'application se ferme ;
                 *  - article                 → retour à l'écran précédent (liste, dossier, article précédent).
                 *
                 * Doit être déclarée DANS la destination : la NavHost enregistre son propre
                 * gestionnaire de retour, et seul le dernier gestionnaire activé l'emporte.
                 */
                @Composable
                fun BackRule() {
                    BackHandler(enabled = true) {
                        when {
                            drawer.currentValue != DrawerValue.Closed -> closeDrawer()
                            current in UTILITY_ROUTES -> backToLastContent()
                            current == "article" -> nav.popBackStack()
                            current == "stream" && !atHome -> goHome()
                            atHome -> finish()
                            else -> nav.popBackStack()
                        }
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        PressDrawer(vm, currentRoute = current,
                            onNavigate = { r ->
                                // Le drawer a déjà positionné le flux ou le dossier voulu :
                                // un tel écran est une destination de contenu, on repart donc d'une
                                // pile propre. Les écrans utilitaires se posent par-dessus.
                                if (r in CONTENT_ROUTES) openContent(r) else nav.navigate(r) { launchSingleTop = true }
                            }) {
                            closeDrawer()
                        }
                    }
                ) {
                    key(home) {
                        NavHost(nav, startDestination = startRoute) {
                            composable("home") {
                                BackRule()
                                SubscriptionsScreen(vm,
                                    onOpenStream = { openContent("stream") },
                                    onMenu = { openDrawer() })
                            }
                            composable("stream") {
                                BackRule()
                                StreamScaffold(vm, vm.scope.label,
                                    onOpen = { openArticle(it.id) },
                                    onMenu = { openDrawer() })
                            }
                            composable("article") {
                                BackRule()
                                val id = vm.selectedArticleId
                                if (id == null) {
                                    LaunchedEffect(Unit) { nav.popBackStack() }
                                } else {
                                    ArticleScreen(vm, id,
                                        onBack = { nav.popBackStack() },
                                        onMenu = { openDrawer() })
                                }
                            }
                            composable("starred") {
                                BackRule()
                                FavorisScreen(vm,
                                    onOpen = { openArticle(it.id) },
                                    onMenu = { openDrawer() })
                            }
                            composable("search") {
                                BackRule()
                                ScaffoldWithMenu("Recherche", { openDrawer() }) {
                                    SearchScreen(vm, onOpen = { openArticle(it.id) })
                                }
                            }
                            composable("dedup") {
                                BackRule()
                                ScaffoldWithMenu("Doublons", { openDrawer() }) {
                                    DedupScreen(vm, onOpen = { openArticle(it.id) })
                                }
                            }
                            composable("stats") {
                                BackRule()
                                ScaffoldWithMenu("Stats", { openDrawer() }) {
                                    StatsScreen(vm)
                                }
                            }
                            composable("settings") {
                                BackRule()
                                ScaffoldWithMenu("Réglages", { openDrawer() }) {
                                    SettingsScreen(vm)
                                }
                            }
                            composable("login") {
                                BackRule()
                                ScaffoldWithMenu("Comptes", { openDrawer() }) {
                                    LoginScreen(vm, onOAuth = { p -> nav.navigate("oauth/$p") },
                                        onFeedlySignIn = { nav.navigate("feedly-signin") })
                                }
                            }
                            composable("about") {
                                BackRule()
                                ScaffoldWithMenu("À propos", { openDrawer() }) {
                                    AboutScreen()
                                }
                            }
                            composable("feedly-signin") {
                                BackRule()
                                FeedlySignInScreen(vm, onBack = { nav.popBackStack() },
                                    onDone = { msg ->
                                        vm.authMsg = msg
                                        // Retour automatique sur la liste des comptes.
                                        nav.popBackStack("login", inclusive = false)
                                    })
                            }
                            composable("oauth/{provider}") { backStack ->
                                BackRule()
                                val p = backStack.arguments?.getString("provider") ?: "feedly"
                                OAuthWebViewScreen(vm, p, onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

/** Route de démarrage : l'accueil est « Accueil » ou « Tous les articles » selon le réglage. */
private fun startOf(home: String): String = if (home == "subs") "home" else "stream"

/** Écrans de contenu : ce que l'utilisateur consulte réellement (accueil, flux, article). */
private val CONTENT_ROUTES = setOf("home", "stream", "article")

/**
 * Écrans utilitaires : on les quitte d'un seul retour vers la dernière destination de contenu,
 * sans avoir à remonter l'empilement des écrans précédents.
 */
private val UTILITY_ROUTES = setOf(
    "starred", "search", "dedup", "stats", "settings", "login", "feedly-signin", "oauth", "about"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaffoldWithMenu(title: String, onMenu: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) },
            navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") } })
    }) { pad ->
        Box(Modifier.padding(pad)) { content() }
    }
}
