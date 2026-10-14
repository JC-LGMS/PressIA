package com.pressia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.pressia.di.Locator
import com.pressia.ui.*
import com.pressia.ui.theme.PressIATheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
                    if (route == "home") {
                        // Revenir à l'accueil, c'est revenir à « Tous les articles » : sans cela le
                        // flux courant (et la portée de recherche qui le suit) restait bloqué sur le
                        // dernier flux visité.
                        vm.switchScope(Scope(label = "Tous les articles"))
                    }
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

                // Bandeau de synchro : le vrai SnackbarHost (géométrie et animations
                // d'origine), mais le texte affiché suit vm.syncMsg en direct via le slot
                // custom — pas de dismiss/re-show à chaque mise à jour du compteur
                // de miniatures (« … 45/200 »), le texte se rafraîchit en place.
                val snackbarHostState = remember { SnackbarHostState() }
                // Un seul bandeau du début de la synchro jusqu'à la fin du message final :
                // le show reste actif dans le scope (pas dans l'effet, sinon sa fin de
                // synchro l'annulerait et le bandeau se fermerait avant de se rouvrir
                // pour le message final). Le slot ci-dessous en ignore le contenu et
                // rend vm.syncMsg, qui évolue seul.
                // Invariant : jamais de bandeau sans message — seul un pipeline de
                // synchro (étapes dans syncStep, recopiées dans syncMsg) allume le bandeau.
                var bannerJob by remember { mutableStateOf<Job?>(null) }
                // Hauteur du visuel du bandeau (voir slot ci-dessous) : les boutons haut/bas
                // flottants des listes se soulèvent juste au-dessus quand il est affiché.
                var bannerLift by remember { mutableStateOf(0.dp) }
                fun launchBanner() {
                    bannerJob = scope.launch {
                        snackbarHostState.showSnackbar("Synchronisation en cours…", duration = SnackbarDuration.Indefinite)
                    }
                }
                LaunchedEffect(vm.syncing, vm.syncMsg) {
                    val msg = vm.syncMsg
                    if (msg == null) {
                        // Plus rien à afficher : refermer un éventuel bandeau persistant.
                        bannerJob?.let {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            bannerJob = null
                        }
                        bannerLift = 0.dp
                        return@LaunchedEffect
                    }
                    if (vm.syncing) {
                        if (bannerJob == null) {
                            launchBanner()
                        } else if (bannerJob?.isCompleted == true && snackbarHostState.currentSnackbarData == null) {
                            // Balayé par l'utilisateur en cours de synchro : on réaffiche,
                            // comme chaque étape le faisait avant (le compteur continue).
                            launchBanner()
                        }
                        // Sinon : le bandeau persistant est déjà affiché, le slot suit
                        // msg en direct.
                    } else if (bannerJob != null) {
                        // Fin de synchro : le bandeau reste ouvert, seul le texte passe au
                        // message final — aucune fermeture/réouverture intermédiaire.
                        delay(4000)
                        snackbarHostState.currentSnackbarData?.dismiss()
                        bannerJob = null
                        if (vm.syncMsg == msg) vm.syncMsg = null
                    } else {
                        // Hors synchro (autre info) : affichage classique, puis effacé.
                        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Long)
                        if (vm.syncMsg == msg) {
                            vm.syncMsg = null
                        }
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalBannerLift provides bannerLift) {
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
                                     ScaffoldWithMenu("Recherche", { openDrawer() }, drawerIconFor("search"), vm) {
                                         SearchScreen(vm, onOpen = { openArticle(it.id) })
                                     }
                                 }
                                 composable("dedup") {
                                     BackRule()
                                     ScaffoldWithMenu("Doublons", { openDrawer() }, drawerIconFor("dedup"), vm) {
                                         DedupScreen(vm, onOpen = { openArticle(it.id) })
                                     }
                                 }
                                 composable("stats") {
                                     BackRule()
                                     ScaffoldWithMenu("Stats", { openDrawer() }, drawerIconFor("stats"), vm) {
                                         StatsScreen(vm)
                                     }
                                 }
                                 composable("settings") {
                                     BackRule()
                                     ScaffoldWithMenu("Réglages", { openDrawer() }, drawerIconFor("settings"), vm) {
                                         SettingsScreen(vm)
                                     }
                                 }
                                 composable("login") {
                                     BackRule()
                                     ScaffoldWithMenu("Comptes", { openDrawer() }, drawerIconFor("login"), vm) {
                                         LoginScreen(vm, onOAuth = { p -> nav.navigate("oauth/$p") },
                                             onFeedlySignIn = { nav.navigate("feedly-signin") })
                                     }
                                 }
                                 composable("about") {
                                     BackRule()
                                     ScaffoldWithMenu("À propos", { openDrawer() }, drawerIconFor("about"), vm) {
                                         AboutScreen()
                                     }
                                 }
                                composable("feedly-signin") {
                                    BackRule()
                                    FeedlySignInScreen(vm, onBack = { nav.popBackStack() },
                                        onDone = { msg ->
                                            vm.authMsg = msg
                                            // Retour à l'accueil avant la première synchro (lancée
                                            // par l'écran après ce retour), pas à la page des comptes.
                                            goHome()
                                        })
                                }
                                composable("oauth/{provider}") { backStack ->
                                    BackRule()
                                    val p = backStack.arguments?.getString("provider") ?: "feedly"
                                    OAuthWebViewScreen(vm, p,
                                        onBack = { nav.popBackStack() },
                                        // Connexion réussie : retour à l'accueil avant la première
                                        // synchro (lancée par l'écran après ce retour).
                                        onDone = { goHome() })
                                }
                            }
                            // Destination Instapaper (mode « choisir à chaque envoi »), au-dessus de
                            // tout écran : listes, swipes et écran article y aboutissent.
                            val pending = vm.instapaperPickerFor
                            if (pending != null) {
                                InstapaperTargetDialog(vm, pending.id, pending.title) { toast(ctx, it) }
                            }
                        }
                    }
                    } // CompositionLocalProvider(LocalBannerLift) : le bandeau reste hors périmètre.
                    SnackbarHost(
                        snackbarHostState,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    ) {
                        // Même padding que le slot par défaut (Snackbar(snackbarData)) : sans
                        // lui le bandeau est plein-bleed, trop large et collé en bas.
                        // Mesure du visuel seul (après le padding) : les boutons haut/bas se
                        // soulèvent juste au-dessus, sans le trop d'espace de l'hôte.
                        Snackbar(modifier = Modifier.padding(12.dp).onSizeChanged { bannerLift = with(density) { it.height.toDp() } }) { Text(vm.syncMsg ?: "") }
                    }
                    // Toast applicatif (voir ToastBus) : pastille sombre au-dessus du bandeau,
                    // qui s'efface seule après ~2 s. Au-dessus du SnackbarHost pour passer devant.
                    val appToast = ToastBus.current
                    androidx.compose.animation.AnimatedVisibility(
                        visible = appToast != null,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 200.dp),
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut()
                    ) {
                        appToast?.let { (id, msg) ->
                            LaunchedEffect(id) {
                                kotlinx.coroutines.delay(2000)
                                ToastBus.clear(id)
                            }
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                                color = androidx.compose.ui.graphics.Color(0xE6141414),
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ) {
                                Text(msg, Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
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
fun ScaffoldWithMenu(
    title: String,
    onMenu: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    vm: PressVM,
    content: @Composable () -> Unit
) {
    Scaffold(topBar = {
        Column {
            // Barre de synchro tout en haut de l'écran, au-dessus du titre.
            if (vm.syncing) SyncProgress()
            TopAppBar(title = { ViewTitle(icon, title) },
                navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") } })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) { content() }
    }
}
