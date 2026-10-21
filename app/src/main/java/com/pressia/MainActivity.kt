package com.pressia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.pressia.data.FAVICON_SOURCE_AGGREGATOR
import com.pressia.data.FAVICON_SOURCE_DIRECT
import com.pressia.data.parseResolved
import com.pressia.data.local.Prefs
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
    /**
     * Dernier retour OAuth reçu, en création comme par [onNewIntent] : la composition le
     * consomme. Sans cet état, un `pressia://oauth` reçu par une activité déjà vivante
     * (relance, `singleTop`) n'était jamais traité.
     */
    private val oauthLink = mutableStateOf<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.takeIf { it.host == "oauth" }?.let { oauthLink.value = it }
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
            val link by oauthLink
            LaunchedEffect(link) {
                val uri = link ?: return@LaunchedEffect
                oauthLink.value = null
                // Le message était jeté : un échange refusé restait totalement silencieux.
                vm.authMsg = handleOAuthDeepLink(vm, uri)
                vm.refresh()
            }
            LaunchedEffect(Unit) {
                vm.syncOnLaunch()
                vm.refresh()
            }
            PressIATheme(theme, accent, bg) {
                // Réglage « page d'accueil » pas encore lu (DataStore répond de façon
                // asynchrone, voir PressVM.homeScreen) : ne pas composer la navigation. Elle
                // démarrerait sur le défaut « Accueil », et le NavController garderait cette
                // entrée quand le réglage arrive — l'app s'ouvrait alors sur Accueil alors que
                // le réglage dit « Tous les articles » (constaté). Le blanc dure une image, le
                // thème est déjà posé.
                if (home == null) {
                    Box(Modifier.fillMaxSize())
                    return@PressIATheme
                }
                val nav = rememberNavController()
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val backStack by nav.currentBackStackEntryAsState()
                // Page d'accueil : `home` suit le réglage en direct (null tant que DataStore n'a
                // pas répondu, voir PressVM.homeScreen). Seule la route de départ du NavHost est
                // figée (`startRoute`, lue une fois) : la recréer éjectait l'utilisateur de
                // l'écran Réglages au moment même du choix.
                //
                // Le réglage est malgré tout pris en compte immédiatement : `homeRoute` suit la
                // valeur courante, et la règle de retour est « on remonte vers l'accueil
                // configuré, on ne ferme qu'une fois dessus ». Changer la page d'accueil depuis
                // Réglages ne déplace donc personne ; c'est le retour suivant (depuis Réglages,
                // puis depuis la page courante) qui mène vers la nouvelle page d'accueil.
                val sessionHome = rememberSaveable { mutableStateOf<String?>(null) }
                if (sessionHome.value == null && home != null) sessionHome.value = home
                // Départ du NavHost (figé) et accueil configuré (suit le réglage).
                val startRoute = startOf(sessionHome.value ?: "subs")
                val homeRoute = startOf(home ?: sessionHome.value ?: "subs")
                val current = backStack?.destination?.route?.substringBefore("/") ?: startRoute
                // Accueil « Tous les articles » : hiérarchie racine → Accueil → dossier/flux.
                // Le geste vers la droite et le retour remontent donc d'un niveau au lieu de
                // quitter le périmètre courant, et le tiroir s'ouvre depuis la racine.
                val hierarchical = homeRoute == "stream"
                // Racine : « Tous les articles » (aucun dossier, aucun flux sélectionné).
                val atRoot = current == "stream" && vm.scope.feedId == null && vm.scope.categoryId == null
                // Sur la page d'accueil configurée. En mode hiérarchique, c'est la racine
                // (« Tous les articles »), pas la page Accueil qui n'en est qu'une étape.
                // Seul cet endroit-là ferme l'application au retour.
                val atHome = if (hierarchical) atRoot else current == "home"

                // Dernière destination de contenu réellement visitée (accueil, dossier ou article).
                // Les écrans utilitaires — Favoris, Recherche, Doublons, Stats, Réglages, Comptes,
                // Sauvegarde/Restauration, À propos — s'appuient dessus pour renvoyer là où l'utilisateur se trouvait,
                // plutôt que de remonter une pile qui peut ne plus contenir cette destination.
                var lastContent by rememberSaveable { mutableStateOf(startRoute) }
                LaunchedEffect(current) {
                    if (current in CONTENT_ROUTES) lastContent = current
                    // Sortie de la vue article (liste, accueil, dossier, écran utilitaire) : sauf
                    // réglage « Readability persistant », le mode complet épinglé retombe. Le
                    // balayage d'un article à l'autre ne change pas de destination : il le garde,
                    // comme demandé.
                    if (current != "article") vm.onLeftArticle()
                }

                /**
                 * Ouvre une destination de contenu en remplaçant la pile : on ne veut garder qu'une
                 * seule destination « de fond », sinon le bouton retour remonterait par-dessus des
                 * écrans utilitaires déjà quittés.
                 */
                fun openContent(route: String, apply: () -> Unit = {}) {
                    apply()
                    // Pas de switchScope ici pour "home" : il a lieu à l'arrivée (voir le
                    // LaunchedEffect(current) ci-dessus).
                    if (route == "stream") vm.refresh()
                    // Revenir à l'accueil désépingle le mode Readability (sauf réglage
                    // « persistant ») : voir `onLeftArticle`.
                    if (route == "home") scope.launch { vm.onLeftArticle() }
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

                /**
                 * Remonte vers la page d'accueil configurée (réglage lu en direct).
                 *
                 * Accueil = « Tous les articles » : racine (« Tous les articles ») → Accueil
                 * → dossier/flux. Depuis un dossier ou un flux, on revient à la page Accueil ;
                 * depuis l'Accueil, à la racine. Un retour à la racine ferme l'application.
                 *
                 * Accueil = « Accueil » : tout (dossier, flux, ou « Tous les articles ») ramène
                 * à la page Accueil, qui est alors le dernier niveau.
                 */
                fun goHome() {
                    if (hierarchical && current == "home") {
                        // Accueil → racine : dernier niveau, le flux courant redevient
                        // « Tous les articles ».
                        vm.switchScope(Scope(label = "Tous les articles")); vm.refresh()
                        openContent("stream")
                        return
                    }
                    // Dossier/flux → Accueil (mode hiérarchique), ou n'importe quelle page de
                    // contenu → Accueil (mode classique).
                    openContent("home")
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
                 *  - écran utilitaire       → dernière page de contenu (l'article ou la liste d'où
                 *                             l'on vient, pas la pile des écrans déjà quittés) ;
                 *  - Accueil (mode hiérarchique) → la racine « Tous les articles » ;
                 *  - « Tous les articles », dossier, flux → la page d'accueil configurée
                 *                             (lue en direct : un réglage changé depuis Réglages
                 *                             est donc pris en compte tout de suite) ;
                 *  - sur la page d'accueil configurée → l'application se ferme ;
                 *  - article                → retour à l'écran précédent (liste, dossier, article précédent).
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
                            // Accueil intermédiaire (mode hiérarchique) : un cran au-dessus,
                            // c'est-à-dire la racine. En mode « Accueil » classique, l'accueil
                            // est le point d'entrée : retour = fermeture.
                            current == "home" -> if (hierarchical) goHome() else finish()
                            // « Tous les articles », dossier ou flux : on va vers l'accueil
                            // configuré (la racine si c'est elle, l'Accueil classique sinon).
                            current == "stream" && !atHome -> goHome()
                            atHome -> finish()
                            else -> nav.popBackStack()
                        }
                    }
                }

                /**
                 * Périmètre orphelin : le flux (ou le dossier) courant vient d'être supprimé
                 * depuis un autre écran — typiquement un compte déconnecté depuis l'écran
                 * Comptes. Sans ce nettoyage, le retour arrière remonterait sur une liste vide,
                 * et « Tous les articles » resterait filtré sur un flux disparu.
                 *
                 * L'accueil redevient la « dernière page de contenu » : les écrans utilitaires
                 * (Comptes, Réglages, Stats…) y renvoient ensuite, y compris après un détour par
                 * une autre page secondaire, au lieu de remonter sur la page morte.
                 */
                LaunchedEffect(vm.loaded, vm.scope.feedId, vm.scope.categoryId, vm.subs, vm.categories, vm.folders) {
                    if (!vm.loaded) return@LaunchedEffect
                    val feed = vm.scope.feedId
                    val cat = vm.scope.categoryId
                    // Dossier : valider aussi contre `folders`, un dossier fusionné portant un
                    // identifiant « merged:… » absent de la liste brute des catégories.
                    val orphan = (feed != null && vm.subs.none { it.id == feed }) ||
                        (cat != null && vm.categories.none { it.id == cat } &&
                            vm.folders.none { it.id == cat })
                    if (orphan) {
                        // Le retour arrière ne quitte pas l'écran : on ne fait que réparer le
                        // périmètre et la destination de retour, l'utilisateur reste où il est.
                        vm.switchScope(Scope(label = "Tous les articles"))
                        vm.refresh()
                        lastContent = homeRoute
                    }
                }

                // Bandeau de synchro : le vrai SnackbarHost (géométrie et animations
                // d'origine), mais le texte affiché suit vm.syncMsg en direct via le slot
                // custom — pas de dismiss/re-show à chaque mise à jour du compteur
                // d'images (« … 45/200 »), le texte se rafraîchit en place.
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
                    val faviconDirect by remember(vm.repo.prefs) {
                        vm.repo.prefs.flow(Prefs.FAVICON_SOURCE, FAVICON_SOURCE_AGGREGATOR)
                    }.collectAsState(initial = FAVICON_SOURCE_AGGREGATOR)
                    // Icônes de sites résolues (source directe) : décodées une fois ici, lues par
                    // les avatars via le CompositionLocal (voir `FeedAvatar`).
                    val faviconResolved by remember(vm.repo.prefs) {
                        vm.repo.prefs.flow(Prefs.FAVICON_RESOLVED, "")
                    }.collectAsState(initial = "")
                    val resolvedIcons = remember(faviconResolved) { parseResolved(faviconResolved) }
                    CompositionLocalProvider(
                        LocalBannerLift provides bannerLift,
                        LocalFaviconDirect provides (faviconDirect == FAVICON_SOURCE_DIRECT),
                        LocalFaviconResolved provides resolvedIcons
                    ) {
                    ModalNavigationDrawer(
                        drawerState = drawer,
                        // Balayage de bord : actif partout, sauf là où le geste vers la droite a
                        // déjà un sens — écran article (article précédent, partagé avec le
                        // détecteur de l'article), Accueil et listes de dossier/flux en mode
                        // hiérarchique (montée d'un niveau, voir StreamScaffold/SubscriptionsScreen).
                        // Tiroir ouvert, le balayage est toujours actif : c'est lui qui permet de
                        // le refermer (geste ou clic hors du panneau — le clic de fermeture du
                        // Material ne suit pas `gesturesEnabled` à lui seul).
                        gesturesEnabled = drawer.isOpen || when {
                            current == "article" -> false
                            // Accueil « Tous les articles » : le geste remonte la hiérarchie
                            // (il ouvre le tiroir à la racine, via StreamScaffold).
                            hierarchical -> current != "home"
                            else -> !(current == "stream" && !atHome)
                        },
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
                        key(startRoute) {
                            // Fondu de navigation réduit (150 ms au lieu des 700 ms par défaut) :
                            // la transition reste douce mais ne donne plus l'impression de coller.
                            NavHost(nav, startDestination = startRoute,
                                enterTransition = { fadeIn(tween(150)) },
                                exitTransition = { fadeOut(tween(150)) },
                                popEnterTransition = { fadeIn(tween(150)) },
                                popExitTransition = { fadeOut(tween(150)) }) {
                                composable("home") {
                                    BackRule()
                                    // Revenir à l'accueil, c'est revenir à « Tous les articles »
                                    // (la recherche suit vm.scope) : une fois la sortie de l'écran
                                    // précédent terminée seulement (fondu de 150 ms, voir le NavHost
                                    // ci-dessus — le délai couvre avec de la marge). Tout reset plus
                                    // tôt repeignait l'écran quitté, encore composé pendant le fondu
                                    // (titre « Tous les articles », sources sur chaque ligne).
                                    // Invisible sur l'accueil, il ne sert qu'aux écrans ouverts
                                    // ensuite (recherche…).
                                    LaunchedEffect(Unit) {
                                        kotlinx.coroutines.delay(300)
                                        vm.switchScope(Scope(label = "Tous les articles"))
                                    }
                                    SubscriptionsScreen(vm,
                                        onOpenStream = { openContent("stream") },
                                        onMenu = { openDrawer() },
                                        // Hiérarchie « Tous les articles » : l'Accueil est une
                                        // étape, un balayage vers la droite remonte à la racine.
                                        onSwipeBack = if (hierarchical) ({ goHome() }) else null)
                                }
                                composable("stream") {
                                    BackRule()
                                    StreamScaffold(vm, vm.scope.label,
                                        onOpen = { openArticle(it.id) },
                                        onMenu = { openDrawer() },
                                        // Balayage droit : même action que le bouton retour. À la
                                        // racine « Tous les articles » il ouvre le tiroir (comme sur
                                        // les autres écrans d'accueil) ; dans un dossier ou un flux
                                        // il remonte d'un niveau. Tiroir ouvert, c'est lui qui est
                                        // visible : le geste ne fait rien de plus.
                                        onSwipeBack = {
                                            if (drawer.currentValue == DrawerValue.Closed) {
                                                if (hierarchical && atRoot) openDrawer() else goHome()
                                            }
                                        },
                                        // Le repère suit l'action : tiroir à la racine, montée
                                        // d'un niveau ailleurs.
                                        swipeIcon = if (hierarchical && atRoot) Icons.Filled.Menu
                                        else Icons.Filled.Home,
                                        swipeLabel = if (hierarchical && atRoot) "ouvrir le menu"
                                        else "retour à l'accueil")
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
                                             onFeedlySignIn = { nav.navigate("feedly-signin") },
                                             onInoreaderSignIn = { nav.navigate("inoreader-signin") })
                                     }
                                 }
                                 composable("backup") {
                                     BackRule()
                                     ScaffoldWithMenu("Sauvegarde/Restauration", { openDrawer() }, drawerIconFor("backup"), vm) {
                                         BackupScreen(vm)
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
                                composable("inoreader-signin") {
                                    BackRule()
                                    InoreaderSignInScreen(vm, onBack = { nav.popBackStack() },
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
                        // En thème sombre, le fond par défaut (inverseSurface, clair) est assombri
                        // d'un cran pour moins claquer la nuit ; en thème clair, inchangé.
                        val snackCs = MaterialTheme.colorScheme
                        Snackbar(
                            modifier = Modifier.padding(12.dp).onSizeChanged { bannerLift = with(density) { it.height.toDp() } },
                            containerColor = if (snackCs.background.luminance() < 0.5f) lerp(snackCs.inverseSurface, Color.Black, 0.15f) else snackCs.inverseSurface,
                            contentColor = snackCs.inverseOnSurface
                        ) { Text(vm.syncMsg ?: "") }
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
                    // Annulation d'un marquage groupé (« tout marquer lu », menu appui long) :
                    // pastille avec bouton, entre le bandeau et le toast. Limitée à la page
                    // courante (le changement de périmètre l'efface, voir `switchScope`) et à
                    // ~6 s : au-delà, elle disparaît — mais l'annulation reste à portée dans la
                    // barre du haut jusqu'au changement de page (voir `undoOffer`).
                    val undo = vm.undoOffer
                    androidx.compose.animation.AnimatedVisibility(
                        visible = vm.undoPillVisible,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 130.dp),
                        enter = androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.fadeOut()
                    ) {
                        undo?.let { o ->
                            LaunchedEffect(o) {
                                kotlinx.coroutines.delay(6000)
                                if (vm.undoOffer === o) vm.dismissUndoPill()
                            }
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                                color = androidx.compose.ui.graphics.Color(0xE6141414),
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(o.label)
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(
                                        { vm.undoMarkRead() },
                                        colors = ButtonDefaults.textButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)
                                    ) { Text("Annuler") }
                                }
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
    "starred", "search", "dedup", "stats", "settings", "login", "backup", "feedly-signin", "inoreader-signin", "oauth", "about"
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
