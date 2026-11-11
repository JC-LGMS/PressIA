package com.pressia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.lifecycle.LifecycleEventObserver
import com.pressia.data.PressRepository
import com.pressia.data.readCapped
import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.ThumbStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Contenu RSS d'origine d'un article (jamais le HTML extrait par Readability, pour éviter
 * les allers-retours dégradés). */
fun rssHtml(a: ArticleEntity?): String? = a?.summary?.takeIf { it.isNotBlank() } ?: a?.content

/**
 * Version « flux » d'un article, hors extraction Readability : le HTML livré par le flux ou le
 * compte quand il est déjà lisible en entier — un flux « complet » en donne plus que son extrait
 * RSS — sinon l'extrait. C'est la version affichée à l'ouverture, et celle que retrouve
 * « Revenir à l'extrait RSS » : une extraction ne doit jamais se faire passer pour elle.
 */
fun feedHtml(a: ArticleEntity?): String? {
    val c = a?.content
    return if (a != null && !a.fullFetched && com.pressia.data.readability.Readability.isUsableFull(c)) c
    else rssHtml(a)
}

/**
 * Écran article : extrait RSS, double-tap et balayages configurables (défaut : Readability
 * et navigation), partage système.
 *
 * Les images suivent les mêmes gestes que l'article : un simple tap ouvre la visionneuse, un
 * double-tap déclenche l'action réglée pour le double-tap (voir `ImageTap`) — l'image applique
 * donc elle-même [doubleTap], qui est déjà la valeur courante au recomposé.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArticleScreen(
    vm: PressVM,
    articleId: String,
    onBack: () -> Unit,
    onMenu: () -> Unit,
    /**
     * Balayage horizontal vers un voisin : l'appelant retient l'article affiché (état de
     * l'entrée de navigation), pour qu'on le retrouve en revenant sur cet écran.
     */
    onShowArticle: (String) -> Unit,
    /** Ouvre la recherche sur le périmètre de l'article (action « Recherche », Réglages). */
    onSearch: (Scope) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<ArticleEntity?>(null) }
    var feedName by remember { mutableStateOf<String?>(null) }
    var feedSub by remember { mutableStateOf<com.pressia.data.local.SubscriptionEntity?>(null) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var html by remember { mutableStateOf<String?>(null) }
    var fullMode by remember { mutableStateOf(false) }
    var loadingFull by remember { mutableStateOf(false) }
    // Message d'échec de l'extraction (« rester en mode flux RSS ») ; null quand tout va bien.
    var fullIssue by remember { mutableStateOf<String?>(null) }
    val fontSize by vm.fontSize.collectAsState()
    val titleSp by vm.articleTitle.collectAsState()
    // Icônes configurées en Réglages, dans l'ordre choisi (le menu reste toujours en dernier).
    val topActions by vm.articleActions.collectAsState()
    // Gestes configurés en Réglages (double-tap, balayages gauche/droite).
    val doubleTap by vm.doubleTapAction.collectAsState()
    val swipeLeft by vm.articleSwipeLeft.collectAsState()
    val swipeRight by vm.articleSwipeRight.collectAsState()
    val swipeUp by vm.articleSwipeUp.collectAsState()
    val swipeDown by vm.articleSwipeDown.collectAsState()
    // Un doigt posé sur un lecteur (barre d'avancée, pincer, plein écran) lui appartient : le
    // balayage d'article ne doit pas le confisquer, sinon tirer la barre de la vidéo change
    // d'article au lieu d'avancer dans la vidéo.
    val inPlayer = remember { mutableStateOf(false) }
    // Barre de sélection du texte : celle du système (copier, tout sélectionner) complétée par
    // « Rechercher sur Internet » et « Traduire ».
    val textToolbar = remember { ArticleTextToolbar() }

    // Voisins du balayage horizontal (ordre du plus récent au plus ancien) : la liste de l'écran
    // qui a ouvert l'article — celle des favoris quand on vient des Favoris, sinon celle du flux
    // principal. Sans elle, un favori absent du périmètre du flux n'avait aucun voisin, et le
    // geste revenait en arrière au lieu de passer au favori suivant ou précédent.
    // Fixée pour la durée de l'écran et non par article : le balayage change d'article sans
    // changer d'écran, et la liste peut se reconstruire en fond (synchro, filtre).
    // Rattrapée tant qu'elle est vide : à la reprise après mort du processus, `vm.scanIds`
    // arrive après la première composition — sans quoi plus aucun balayage de navigation.
    // Voisins fournis par l'écran appelant (Favoris, Recherche, Doublons) : cette liste est la
    // sienne, on ne la remplace pas en cours de route (`null` = liste du périmètre courant).
    val ownNeighbours = remember { vm.takeArticleNeighbours() }
    var ids by remember { mutableStateOf(ownNeighbours ?: vm.scanIds) }
    LaunchedEffect(vm.scanIds) {
        if (ids.isEmpty()) ids = vm.scanIds
    }
    val idx = ids.indexOf(articleId)
    val prevId = if (idx > 0) ids[idx - 1] else null
    val nextId = if (idx >= 0 && idx < ids.lastIndex) ids[idx + 1] else null
    // Les voisins du périmètre s'arrêtent à KEEP_MAX : en approchant du bout, on demande la suite
    // (KEEP_MAX de plus), sinon le balayage annonçait « pas d'article plus ancien » alors que la
    // liste en a d'autres. Rien à demander pour une liste fournie par l'écran appelant.
    var scanEnded by remember { mutableStateOf(false) }
    LaunchedEffect(articleId, ids) {
        if (ownNeighbours != null || scanEnded) return@LaunchedEffect
        val here = ids.indexOf(articleId)
        if (here < 0 || here < ids.lastIndex - 1) return@LaunchedEffect
        val more = vm.moreScanIds(ids.size)
        if (more.size > ids.size) ids = more else scanEnded = true
    }
    var offsetX by remember(articleId) { mutableFloatStateOf(0f) }
    // Course du doigt réellement parcourue, dans les deux sens et même quand le geste n'a rien à
    // faire (voisin absent) : c'est elle qui déclenche le message « pas d'article plus ancien ».
    var dragX by remember(articleId) { mutableFloatStateOf(0f) }
    var dragging by remember(articleId) { mutableStateOf(false) }
    // Course verticale du balayage haut/bas, comptée seulement quand l'article est déjà au bord
    // (bas pour un geste vers le haut, haut pour un geste vers le bas).
    var dragY by remember(articleId) { mutableFloatStateOf(0f) }
    // Le défilement repart du haut à chaque article : sans clé, le voisin ouvert par balayage
    // s'affichait à la position de l'article quitté.
    val scrollState = remember(articleId) { ScrollState(0) }
    // Hauteur visible de l'article, en pixels : la barre de défilement en déduit la longueur du
    // curseur (part visible du contenu). Mesurée après la marge, donc c'est bien la fenêtre de
    // défilement. Zéro tant que la mise en page n'a pas eu lieu : aucune barre dessinée.
    var viewportPx by remember { mutableIntStateOf(0) }

    /**
     * Extraction de l'article complet (voir `ensureFull`). En ouverture automatique (mode
     * épinglé), pas de toast de succès : un toast par article ouvert serait du bruit — seul
     * l'échec reste visible (carte + message).
     *
     * En cas d'échec l'écran **reste sur la version du flux** ([feedHtml]) : le message explique
     * pourquoi, et l'extrait RSS n'est jamais remplacé par un contenu tronqué.
     */
    suspend fun loadFull(quiet: Boolean = false) {
        val a = article ?: return
        loadingFull = true
        try {
            val res = vm.repo.ensureFull(a.id)
            // La base a pu changer (extraction enregistrée, cache vérolé purgé) : c'est elle qui
            // dit quelle version du texte s'affiche.
            val fresh = vm.repo.article(a.id) ?: a
            article = fresh
            fullIssue = when (res.outcome) {
                PressRepository.FullOutcome.OK -> null
                PressRepository.FullOutcome.BLOCKED ->
                    "Extraction impossible sur ce site (protection anti-robot, abonnement ou page JavaScript). " +
                        "L'article reste affiché dans sa version du flux RSS."
                PressRepository.FullOutcome.FAILED ->
                    "Extraction impossible (site injoignable ou page illisible). " +
                        "L'article reste affiché dans sa version du flux RSS."
            }
            if (res.outcome == PressRepository.FullOutcome.OK && !res.html.isNullOrBlank()) {
                html = res.html
                fullMode = true
            } else {
                html = feedHtml(fresh)
                fullMode = false
                // L'épingle Readability vaut pour tous les articles : un site qui refuse
                // l'extraction la relâche, sinon elle resterait posée sans effet.
                if (quiet) vm.readabilityOn = false
            }
            if (!quiet) toast(ctx, fullIssue ?: "Article complet affiché")
        } finally { loadingFull = false }
    }

    LaunchedEffect(articleId) {
        val a = vm.repo.article(articleId)
        article = a
        // La copie Readability mise en cache n'est affichée que si l'article est encore en mode
        // complet (`fullFetched`) ; sinon c'est la version du flux (voir `feedHtml`) : couper le
        // mode vaut pour les réouvertures, pas seulement pour l'instant de l'appui.
        // Cache vérolé (anti-robot/paywall) : jamais affiché, repli sur la version du flux.
        val extracted = if (a != null && a.fullFetched && com.pressia.data.readability.Readability.isUsableFull(a.content)) a.content else null
        html = extracted ?: feedHtml(a)
        fullMode = extracted != null
        // Le message d'échec vaut pour l'article où il a été produit : changer d'article l'efface.
        fullIssue = null
        if (a != null) {
            feedName = vm.repo.feedTitle(a.feedId)
            feedSub = vm.repo.subscription(a.feedId)
            if (a.unread) {
                vm.setRead(a.id, false)
                article = a.copy(unread = false)
            }
        }
        // Mode épinglé par l'icône Readability : chaque article ouvert passe en complet.
        if (vm.readabilityOn) loadFull(quiet = true)
    }
    DisposableEffect(articleId) {
        // Chronomètre par article : il repart à chaque balayage vers un autre article.
        // (Un `remember` non clé gardait l'heure du premier article et cumulait les durées.)
        val start = System.currentTimeMillis()
        onDispose {
            val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
            val cur = article
            if (cur != null) vm.logReading(cur.id, cur.feedId, secs)
        }
    }

    /**
     * Icône Readability : épingle le mode complet pour tous les articles ouverts
     * (voir `readabilityOn`), ou le coupe manuellement (article courant + session).
     * Double-tap et pastille du bas passent par ici aussi : une seule bascule, une seule
     * icône, partout pareil.
     * Coupure dès que le mode est actif *d'une façon ou d'une autre* (article en complet
     * ou épingle posée) : sinon, sur un site bloquant l'extraction, l'épingle restait
     * posée sans aucun moyen de la retirer (article jamais en complet, icône éteinte).
     */
    suspend fun toggleReadability() {
        val a = article ?: return
        if (fullMode || vm.readabilityOn) {
            // Version du flux : le HTML du flux quand il est déjà lisible en entier, sinon
            // l'extrait RSS (voir `feedHtml`) — sans ça, un article du flux déjà complet
            // revenait tronqué au petit extrait.
            val back = feedHtml(a)
            html = back
            fullMode = false
            fullIssue = null
            vm.readabilityOn = false
            // La coupe est persistée : l'article rouvert affiche la version du flux, pas la copie
            // Readability restée en base (l'épingle de session, elle, suit `readabilityOn`).
            if (back != null) vm.repo.revertFullContent(a.id, back)
            toast(ctx, "Extrait RSS affiché")
        } else {
            vm.readabilityOn = true
            loadFull()
        }
    }

    /** Actions de la barre et des gestes, en un seul endroit : icônes, double-tap, balayages. */
    fun doStar() {
        article?.let {
            // Même étoile effective que dans les listes : « Read later » du compte (Feedly,
            // Inoreader), étoile locale ailleurs.
            val on = vm.starState(it)
            vm.setStar(it.id, !on)
            article = if (vm.isSavedInAccount(it)) it.copy(laterSaved = !on)
            else it.copy(starred = !on)
            toast(ctx, if (!on) "Ajouté aux favoris" else "Retiré des favoris")
        }
    }
    fun doRead() {
        article?.let {
            vm.setRead(it.id, !it.unread); article = article?.copy(unread = !it.unread)
            toast(ctx, if (!it.unread) "Marqué comme non lu" else "Marqué comme lu")
        }
    }
    fun doShare() {
        article?.let { shareArticle(ctx, it) }
    }
    fun doInstapaper() {
        article?.let { vm.sendOrAskInstapaper(it) { toast(ctx, it) } }
    }
    /** Geste configurable (double-tap, balayage non navigant) : la navigation reste à l'appelant. */
    fun fireArticleAction(act: String) {
        when (act) {
            "readability" -> scope.launch { toggleReadability() }
            "star" -> doStar()
            "read" -> doRead()
            "share" -> doShare()
            "instapaper" -> doInstapaper()
            "search" -> article?.let {
                onSearch(Scope(feedId = it.feedId, label = prettyFeedTitle(feedName ?: it.feedTitle)))
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                // Barre de synchro tout en haut de l'écran, au-dessus du titre.
                if (vm.syncing) SyncProgress()
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            IconButton({ onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "retour") }
                            FeedAvatar(feedSub, 28)
                        }
                    },
                    actions = {
                        topActions.forEach { id ->
                            when (id) {
                                "search" -> IconButton({ fireArticleAction("search") }) {
                                    Icon(Icons.Filled.Search, "rechercher")
                                }
                                "instapaper" -> IconButton({ doInstapaper() }) {
                                    Icon(Icons.Filled.BookmarkAdd, "envoyer vers Instapaper")
                                }
                                "star" -> IconButton({ doStar() }) {
                                    // Favori dans la couleur dominante de l'application.
                                    Icon(
                                        if (article?.let { vm.starState(it) } == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        "favori", tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                "read" -> IconButton({ doRead() }) {
                                    Icon(
                                        // Non lu = plein, lu = vide. Pastille franche (plus sombre que le
                                        // rond des listes) : c'est un bouton d'action, pas un indicateur.
                                        if (article?.unread == true) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked,
                                        if (article?.unread == true) "marquer lu" else "marquer non lu",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                "share" -> IconButton({ doShare() }) { Icon(Icons.Filled.Share, "partager") }
                                // Épinglé ou affiché en complet : icône pleine (teinte dominante,
                                // comme le favori actif) ; sinon icône vide. Les deux états
                                // comptent : sur un site bloquant, l'épingle est active alors que
                                // l'article reste en extrait — l'icône pleine le montre et permet
                                // de la retirer d'un second appui (voir `toggleReadability`).
                                "readability" -> IconButton({ scope.launch { toggleReadability() } }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ChromeReaderMode, "article complet",
                                        tint = if (fullMode || vm.readabilityOn) MaterialTheme.colorScheme.primary
                                        else LocalContentColor.current
                                    )
                                }
                            }
                        }
                        IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") }
                    }
                )
            }
        }
    ) { pad ->
        // Balayage horizontal configurable : navigation par défaut (gauche → plus ancien,
        // droite → plus récent), sinon l'action choisie en Réglages. Le contenu suit le doigt,
        // puis l'action part au relâchement (seuil 120 px, sinon retour). « Aucun » fige le
        // sens, comme l'absence de voisin avant.
        val leftNav = swipeLeft == "older"
        val rightNav = swipeRight == "newer"
        Box(
            Modifier.padding(pad).fillMaxSize()
                .pointerInput(prevId, nextId, swipeLeft, swipeRight) {
                    if (prevId != null || nextId != null || swipeLeft != "none" || swipeRight != "none") {
                    detectArticleSwipe(
                        // Un doigt posé sur un lecteur ne déclenche pas le balayage : sans cette
                        // exception, le moindre déplacement horizontal lui envoyait un
                        // `pointercancel` et la barre d'avancée de la vidéo ne répondait plus.
                        onPlayer = { inPlayer.value },
                        onStart = { dragging = true; dragX = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            // Course brute du doigt : sert au relâchement, même quand le contenu ne
                            // suit pas (voir `dragX`).
                            dragX = (dragX + amount).coerceIn(-600f, 600f)
                            val go = if (amount < 0) (!leftNav && swipeLeft != "none") || (leftNav && nextId != null)
                            else (!rightNav && swipeRight != "none") || (rightNav && prevId != null)
                            // Le contenu ne suit le doigt que si le geste aboutit à quelque chose.
                            offsetX = if (go) dragX else 0f
                        },
                        onEnd = {
                            dragging = false
                            when {
                                dragX <= -120f && leftNav && nextId != null -> onShowArticle(nextId)
                                dragX >= 120f && rightNav && prevId != null -> onShowArticle(prevId)
                                // Sens « navigation » sans voisin : on revient à la liste (retour)
                                // et on garde le toast informatif.
                                dragX <= -120f && leftNav -> { toast(ctx, "Pas d'article plus ancien"); onBack() }
                                dragX >= 120f && rightNav -> { toast(ctx, "Pas d'article plus récent"); onBack() }
                                dragX <= -120f && !leftNav && swipeLeft != "none" -> fireArticleAction(swipeLeft)
                                dragX >= 120f && !rightNav && swipeRight != "none" -> fireArticleAction(swipeRight)
                            }
                            offsetX = 0f
                            dragX = 0f
                        },
                        onCancel = { dragging = false; offsetX = 0f; dragX = 0f }
                    )
                    }
                }
        ) {
        // Texte sélectionnable (appui long, puis poignées ou « Tout sélectionner ») : la
        // sélection vit dans la colonne défilante, donc dans le texte lui-même, et la barre de
        // défilement comme les badges de balayage restent en dehors.
        CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
        SelectionContainer(selectionAnchor(textToolbar)) {
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                // Taille de la fenêtre de défilement, relevée là où la colonne est mesurée
                // (après la marge de page, avant `verticalScroll` qui, lui, voit le contenu
                // entier) : c'est l'échelle de la barre de défilement.
                .onSizeChanged { viewportPx = it.height }
                .verticalScroll(scrollState)
                // Balayage haut/bas : il ne prend le geste que là où l'article ne peut plus
                // défiler (bas atteint vers le haut, haut atteint vers le bas) ; partout ailleurs
                // le défilement reste maître.
                .pointerInput(prevId, nextId, swipeUp, swipeDown) {
                    val upNav = swipeUp == "older"
                    val downNav = swipeDown == "newer"
                    detectArticleVerticalSwipe(
                        onPlayer = { inPlayer.value },
                        atTop = { scrollState.value <= 0 },
                        atBottom = { scrollState.value >= scrollState.maxValue },
                        onStart = { dragY = 0f },
                        onDrag = { dy -> dragY = (dragY + dy).coerceIn(-600f, 600f) },
                        onEnd = {
                            when {
                                dragY <= -120f && upNav && nextId != null -> onShowArticle(nextId)
                                dragY >= 120f && downNav && prevId != null -> onShowArticle(prevId)
                                // Sens « navigation » sans voisin : on revient à la liste (comme
                                // le balayage horizontal), le message disant pourquoi.
                                dragY <= -120f && upNav -> { toast(ctx, "Pas d'article plus ancien"); onBack() }
                                dragY >= 120f && downNav -> { toast(ctx, "Pas d'article plus récent"); onBack() }
                                dragY <= -120f && !upNav && swipeUp != "none" -> fireArticleAction(swipeUp)
                                dragY >= 120f && !downNav && swipeDown != "none" -> fireArticleAction(swipeDown)
                            }
                            dragY = 0f
                        },
                        onCancel = { dragY = 0f }
                    )
                }
                .graphicsLayer { translationX = offsetX }
                .pointerInput(doubleTap) {
                    detectTapGestures(onDoubleTap = { fireArticleAction(doubleTap) })
                }
        ) {
            // Le titre ouvre l'article d'origine dans le navigateur externe.
            Text(
                com.pressia.data.remote.cleanTitle(article?.title).ifBlank { article?.title ?: "" },
                fontSize = titleSp.sp,
                lineHeight = (titleSp + 4).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.clickable(
                    enabled = !article?.url.isNullOrBlank(),
                    onClick = { article?.url?.let { openUrl(ctx, it) } }
                )
            )
            // Source et date sous le titre (et plus au-dessus) : source à gauche, date à droite.
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                // Nom du flux et, quand la précision apprend quelque chose, son compte : même
                // police, accrochés par « - » (voir `showFeedAccount`). Le compte (la source :
                // Feedly, Inoreader, Local) en gris foncé — le nom du flux reste en couleur
                // primaire, sinon deux informations différentes se lisaient pareil.
                val accountLabel = if (com.pressia.data.showFeedAccount(vm.accounts, vm.localFeeds))
                    com.pressia.data.feedAccountLabel(feedSub?.provider, vm.accounts) else null
                val feedPart = feedName?.takeIf { it.isNotBlank() }?.let { prettyFeedTitle(it) }
                if (feedPart != null || accountLabel != null) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        if (feedPart != null) {
                            Text(feedPart, style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f, fill = false), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (accountLabel != null) {
                            Text(
                                (if (feedPart != null) " - " else "") + accountLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else Spacer(Modifier.weight(1f))
                Text(
                    article?.let { fmtDate(it.published) } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (!article?.author.isNullOrBlank()) {
                Text(article?.author ?: "", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (loadingFull) LinearProgressIndicator(Modifier.fillMaxWidth())
            fullIssue?.let { msg ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "$msg Ouvrez l'article dans le navigateur pour le lire en entier.",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            val blocks = remember(html, article?.url) { articleBlocks(html.orEmpty(), article?.url.orEmpty()) }
            // Image principale du flux (mise en cache hors-ligne), masquée si l'extrait RSS affiche
            // déjà cette image (doublon) ou en mode complet (images de la page extraite).
            //
            // Les images passent par [thumbModel] : quand l'URL a déjà été téléchargée en miniature
            // à la synchronisation — c'est le cas de `imageUrl`, et de la première image du corps
            // quand le flux n'a pas d'`enclosure` — le fichier local est affiché au lieu d'être
            // retéléchargé. La copie locale est au gabarit des bannières (carré de la largeur
            // d'écran) : le gain est un aller-retour réseau en moins à chaque ouverture, la
            // contrepartie une image haute agrandie au pincement.
            val heroUrl = article?.imageUrl?.takeIf { it.startsWith("http") }
            val heroDuplicate = heroUrl != null && blocks.any { it is ABlock.I && isSameImage(it.url, heroUrl) }
            if (heroUrl != null && !fullMode && !heroDuplicate) {
                ArticleImage(heroUrl, onOpen = { viewerUrl = heroUrl },
                    onDoubleTap = { fireArticleAction(doubleTap) })
            }
            blocks.forEach { b ->
                when (b) {
                    // Les liens du paragraphe sont dans le texte (annotations de lien) : la phrase
                    // reste d'un seul tenant, le lien ne passe plus à la ligne.
                    is ABlock.T -> ArticleTextBlock(b, MaterialTheme.colorScheme.primary, fontSize)
                    is ABlock.Table -> ArticleTable(b, fontSize)
                    is ABlock.I -> ArticleImage(b.url, onOpen = { viewerUrl = b.url },
                        onDoubleTap = { fireArticleAction(doubleTap) })
                    is ABlock.L -> Text(
                        text = b.text,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 6).sp,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        ),
                        modifier = Modifier.padding(bottom = 8.dp)
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { openUrl(ctx, b.url) },
                                onDoubleClick = { fireArticleAction(doubleTap) }
                            )
                    )
                    is ABlock.V -> VideoPlayer(
                        url = b.url,
                        poster = b.poster,
                        embed = b.embed,
                        modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                            .playerGesture(inPlayer)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            article?.url?.takeIf { it.isNotBlank() }?.let { url ->
                // Bouton centré : c'est l'action de sortie de l'écran, elle doit sauter aux yeux.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton({ openUrl(ctx, url) }) { Text("Ouvrir dans le navigateur") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.height(12.dp))
            if (fullMode || vm.readabilityOn) AssistChip({ scope.launch { toggleReadability() } }, { Text("Revenir à l'extrait RSS") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null) })
            else AssistChip({ scope.launch { toggleReadability() } }, { Text("Readability : article complet") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ChromeReaderMode, null) })
            Spacer(Modifier.height(16.dp))
            // Le bandeau applicatif (fin de synchro…) recouvre le bas de l'écran : quand il
            // est visible, un espace supplémentaire garde la pastille atteignable au scroll
            // (même convention que les boutons haut/bas : hauteur du visuel + 20 dp).
            val bannerLift = LocalBannerLift.current
            if (bannerLift > 0.dp) Spacer(Modifier.height(bannerLift + 20.dp))
        }
        }  // fin de SelectionContainer
        }
        // Barre de défilement : au bord droit, dans la marge de 16 dp que le texte laisse libre.
        // Elle vit hors de la colonne défilante pour deux raisons — ne pas partir avec le
        // contenu quand le balayage horizontal translate la page, et ne pas suivre le
        // défilement lui-même (elle le résume). Pendant un balayage, l'icône du geste occupe ce
        // bord : la barre s'efface pour ne pas se superposer à elle.
        if (!dragging) {
            ArticleScrollBar(
                scrollState,
                viewportPx = viewportPx,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 16.dp, bottom = 16.dp, end = 5.dp)
                    .width(3.dp)
            )
        }
        // Icône du geste pendant le balayage : flèche si le sens est la navigation (voisin
        // visé), sinon l'icône de l'action réglée dans ce sens — mêmes visuels que la bande
        // de balayage des listes. Rien n'est annoncé si le sens ne fait rien (voisin absent
        // ou « Aucun ») : un chevron « article suivant » alors que le relâchement déclenche
        // une action serait trompeur.
        if (dragging) {
            val starred = article?.let { vm.starState(it) } == true
            val unread = article?.unread == true
            val leftBadge: Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when {
                offsetX > -40f -> null
                leftNav -> if (nextId != null) Icons.Filled.ChevronRight to "article suivant" else null
                else -> gestureActionIcon(swipeLeft, starred, unread)
            }
            val rightBadge: Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when {
                offsetX < 40f -> null
                rightNav -> if (prevId != null) Icons.Filled.ChevronLeft to "article précédent" else null
                else -> gestureActionIcon(swipeRight, starred, unread)
            }
            leftBadge?.let { (icon, desc) ->
                Icon(
                    icon, desc,
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            rightBadge?.let { (icon, desc) ->
                Icon(
                    icon, desc,
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        }
        // En dernier : le menu de sélection passe devant tout le reste de l'écran.
        SelectionActions(textToolbar, ctx)
    }
    viewerUrl?.let { ImageViewer(it) { viewerUrl = null } }
}

/**
 * Icône et libellé d'une action de geste d'article non navigante, pendant le balayage :
 * mêmes visuels que la bande découverte des balayages de liste (voir `ArticleRow`), pour la
 * même action. `null` pour « Aucun » (ou une valeur inconnue) : rien à annoncer.
 */
private fun gestureActionIcon(act: String, starred: Boolean, unread: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when (act) {
    "readability" -> Icons.AutoMirrored.Filled.ChromeReaderMode to "Readability"
    "read" -> (if (unread) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked) to
        (if (unread) "marquer lu" else "marquer non lu")
    "share" -> Icons.Filled.Share to "partager"
    "instapaper" -> Icons.Filled.BookmarkAdd to "envoyer vers Instapaper"
    "star" -> (if (starred) Icons.Filled.Star else Icons.Filled.StarBorder) to
        (if (starred) "Retirer des favoris" else "Ajouter aux favoris")
    else -> null
}

/**
 * Barre de sélection de l'application : les commandes du système (copier, coller, couper, tout
 * sélectionner) et deux actions propres à la lecture — chercher le texte sur Internet, le
 * traduire. Partagée par l'écran article et les pages de texte (À propos, journal), pour que la
 * sélection s'y comporte pareil.
 *
 * Compose ne livre pas le texte sélectionné à la barre : les deux actions passent donc par la
 * copie (comme « Copier »), puis relisent le presse-papiers. La sélection est copiée au passage.
 */
internal class ArticleTextToolbar : TextToolbar {
    /** Sélection en cours : rectangle (coordonnées de fenêtre) et commandes disponibles. */
    var menu by mutableStateOf<Menu?>(null)
        private set
    /** Point touché qui a créé la sélection (coordonnées de fenêtre). `showMenu` ne donne que le
     *  rectangle de la sélection, décalé de ~65 dp vers le haut dans Compose 1.7 (mesuré) : s'y
     *  accrocher posait le menu plusieurs lignes au-dessus du mot touché. On se sert du doigt, qui
     *  est sur le mot. */
    var anchor by mutableStateOf<Offset?>(null)
    /** Horodatage du dernier `showMenu` : un `hide` immédiat vient d'un autre champ, pas de
     *  l'utilisateur (voir `hide`). */
    private var shownAt = 0L
    /** Sélection que l'utilisateur vient de fermer : Compose la rallume aussitôt après notre
     *  `hide` (la sélection, elle, est toujours active) — on la reconnaît un court instant pour
     *  ne pas la rouvrir. Passé ce délai, une nouvelle sélection au même endroit reste possible. */
    private var dismissedRect: Rect? = null
    private var dismissedAt = 0L

    class Menu(
        val rect: Rect,
        val onCopy: (() -> Unit)?,
        val onSelectAll: (() -> Unit)?,
        val onPaste: (() -> Unit)?,
        val onCut: (() -> Unit)?
    )

    override val status: TextToolbarStatus
        get() = if (menu == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // Le texte des articles est en lecture seule (copier, tout sélectionner) ; un champ
        // éditable (recherche de l'À propos) fournit aussi coller et couper, qui doivent rester
        // disponibles là où on peut écrire.
        if (rect == dismissedRect &&
            android.os.SystemClock.uptimeMillis() - dismissedAt < 800
        ) return
        dismissedRect = null
        shownAt = android.os.SystemClock.uptimeMillis()
        menu = Menu(rect, onCopyRequested, onSelectAllRequested, onPasteRequested, onCutRequested)
    }

    override fun hide() {
        // Un champ éditable de la page (recherche de l'À propos) appelle hide() juste après le
        // showMenu d'un texte voisin — le menu disparaissait aussitôt. Un masquage demandé par
        // l'utilisateur (appui à côté, Retour, action) arrive bien plus tard.
        if (android.os.SystemClock.uptimeMillis() - shownAt < 250) return
        dismissedRect = menu?.rect
        dismissedAt = android.os.SystemClock.uptimeMillis()
        menu = null
    }
}

/** Actions proposées sur le texte sélectionné, posées **au-dessus** de la sélection : sous elle
 *  vivent les poignées, et le menu les couvrait. Dessiné dans la fenêtre de l'écran (et non en
 *  `Popup`) : ici, un appui à côté le ferme **et** Retour aussi — le `Popup` focusable ne
 *  recevait ni l'un ni l'autre (constaté sur l'émulateur). */
@Composable
internal fun SelectionActions(toolbar: ArticleTextToolbar, ctx: android.content.Context) {
    val menu = toolbar.menu ?: return
    androidx.activity.compose.BackHandler { toolbar.hide() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Origine de la zone dans la fenêtre : `menu.rect` est en coordonnées de fenêtre.
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // Hauteur mesurée (le nombre d'entrées varie selon le champ) : la première composition
    // utilise l'estimation, puis la mesure réelle replace le menu.
    var menuH by remember { mutableStateOf(with(density) { MENU_EST_H.roundToPx() }) }
    val menuW = with(density) { 240.dp.toPx() }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            origin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
            area = it.size
        }
    ) {
        // Voile transparent : tout appui hors du menu le referme.
        Box(
            Modifier.matchParentSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { toolbar.hide() }
        )
        val x = (menu.rect.left - origin.x).roundToInt()
            .coerceIn(0, (area.width - menuW.toInt()).coerceAtLeast(0))
        // Au-dessus quand il y a la place, sinon dessous. Le doigt est **dans** la ligne touchée :
        // un écart plus court que la hauteur d'une ligne posait le menu sur le texte sélectionné
        // (constaté). Et l'ancrage est le doigt, pas `menu.rect` : Compose 1.7 y donne un
        // rectangle décalé de ~65 dp vers le haut (mesuré), d'où un menu plusieurs lignes trop
        // haut. Même écart dans les deux sens : la distance ne dépend pas de la position à l'écran.
        val gap = with(density) { MENU_GAP.roundToPx() }
        val from = ((toolbar.anchor?.y ?: menu.rect.top) - origin.y).roundToInt()
        val y = (from - menuH - gap).takeIf { it >= 0 }
            ?: (from + gap).coerceAtMost((area.height - menuH).coerceAtLeast(0))
        Surface(
            Modifier.offset { IntOffset(x, y) }
                .onSizeChanged { menuH = it.height },
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp
        ) {
            Column(Modifier.padding(vertical = 2.dp).widthIn(max = 240.dp)) {
                SelectionAction("Copier") { menu.onCopy?.invoke(); toolbar.hide() }
                SelectionAction("Tout sélectionner") { menu.onSelectAll?.invoke(); toolbar.hide() }
                // Champ éditable (recherche de l'À propos) : coller et couper restent utiles.
                menu.onPaste?.let { paste ->
                    SelectionAction("Coller") { paste(); toolbar.hide() }
                }
                menu.onCut?.let { cut ->
                    SelectionAction("Couper") { cut(); toolbar.hide() }
                }
                SelectionAction("Rechercher sur Internet") {
                    selectionText(ctx, menu.onCopy)?.let {
                        openUrl(ctx, "https://www.google.com/search?q=" + android.net.Uri.encode(it))
                    }
                    toolbar.hide()
                }
                SelectionAction("Traduire") {
                    selectionText(ctx, menu.onCopy)?.let {
                        openUrl(
                            ctx,
                            "https://translate.google.com/?sl=auto&tl=fr&text=" + android.net.Uri.encode(it)
                        )
                    }
                    toolbar.hide()
                }
            }
        }
    }
}

/** Hauteur estimée du menu de sélection avant sa mesure (4 lignes + marges) : sert à le poser
 *  au-dessus de la sélection du premier coup. */
private val MENU_EST_H = 150.dp

/** Écart entre le doigt et le menu de sélection (tient compte des poignées, sous le texte, et
 *  dépasse la hauteur d'une ligne pour ne jamais couvrir la ligne touchée). */
private val MENU_GAP = 40.dp

/**
 * Zones de texte sélectionnables avec les actions de l'application (Copier, Tout sélectionner,
 * Rechercher sur Internet, Traduire) : le même menu que l'écran article, pour les pages qui ne
 * sont pas des articles (À propos, journal des modifications).
 */
@Composable
fun SelectableArea(content: @Composable () -> Unit) {
    val toolbar = remember { ArticleTextToolbar() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalTextToolbar provides toolbar
        ) {
            SelectionContainer(selectionAnchor(toolbar)) { content() }
        }
        SelectionActions(toolbar, ctx)
    }
}

/** Mémorise le doigt qui touche le texte, pour poser le menu juste à côté (voir
 *  [ArticleTextToolbar.anchor]). Simple observateur (`Initial`, rien n'est consommé) : la
 *  sélection de Compose, elle, reçoit l'évènement intact. */
@Composable
private fun selectionAnchor(toolbar: ArticleTextToolbar): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    return Modifier
        .onGloballyPositioned { origin = it.localToWindow(Offset.Zero) }
        .pointerInput(toolbar) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                        toolbar.anchor = e.changes.first().position + origin
                    }
                }
            }
        }
}

@Composable
private fun SelectionAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium
    )
}

/** Texte sélectionné : la copie demandée à Compose, relue dans le presse-papiers. */
private fun selectionText(ctx: android.content.Context, copy: (() -> Unit)?): String? {
    copy?.invoke()
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return null
    return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
        ?.takeIf { it.isNotEmpty() }
}

/**
 * Barre de défilement verticale de l'écran article (une `Column` n'en dessine pas).
 *
 * Tout est calculé dans la phase de dessin : un défilement ne redessine que la barre, la page
 * n'est pas recomposée (`ScrollState.value` est lu dans `Canvas`).
 *
 * - `viewportPx` est la hauteur visible de l'article, `state.maxValue` le débordement. Le contenu
 *   complet vaut donc `viewport + max`, ce qui donne la longueur du curseur (part visible) et sa
 *   position (avancement). Rien ne dépasse l'écran : aucune barre.
 * - Curseur de 36 dp minimum : sur un très long article, la proportion seule le réduirait à un
 *   trait invisible. Même gabarit que la barre du journal (`ChangelogScrollBar`), en plus fin
 *   (3 dp) pour ne pas peser sur la lecture : le texte garde sa marge de 16 dp.
 */
@Composable
private fun ArticleScrollBar(state: ScrollState, viewportPx: Int, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(modifier) {
        val max = state.maxValue
        if (max <= 0 || viewportPx <= 0) return@Canvas
        val content = viewportPx + max
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = CornerRadius(radius, radius)
        )
        val thumbHeight = (size.height * viewportPx / content)
            .coerceIn(36.dp.toPx().coerceAtMost(size.height), size.height)
        val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
        val progress = (state.value.toFloat() / max).coerceIn(0f, 1f)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, maxTop * progress),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

/**
 * Une image de l'article : appui pour l'ouvrir en plein écran, appui long pour son menu (copier
 * son adresse, la partager, la chercher sur Internet).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ArticleImage(url: String, onOpen: () -> Unit, onDoubleTap: () -> Unit) {
    val ctx = LocalContext.current
    // Une URL différente (balayage d'article) referme un menu resté ouvert.
    var menu by remember(url) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        coil.compose.AsyncImage(thumbModel(ctx, url), null,
            Modifier.fillMaxWidth().combinedClickable(
                onClick = onOpen,
                onLongClick = { menu = true },
                onDoubleClick = onDoubleTap
            ),
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
        ImageActionsMenu(url, menu) { menu = false }
    }
}

/**
 * Menu d'appui long d'une image, dans l'article comme en plein écran. Il porte sur l'image
 * elle-même : la copier, la partager, la chercher sur Internet (Google Lens, qui part de son
 * adresse — la recherche, elle, n'a pas d'autre entrée).
 */
@Composable
private fun ImageActionsMenu(url: String, expanded: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Copier l'image") },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
            onClick = {
                onDismiss()
                scope.launch {
                    toast(ctx, if (copyImage(ctx, url)) "Image copiée" else "Image non récupérée")
                }
            }
        )
        DropdownMenuItem(
            text = { Text("Partager") },
            leadingIcon = { Icon(Icons.Filled.Share, null) },
            onClick = {
                onDismiss()
                scope.launch {
                    if (!shareImage(ctx, url)) toast(ctx, "Image non récupérée")
                }
            }
        )
        DropdownMenuItem(
            text = { Text("Rechercher sur Internet") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            onClick = {
                openUrl(ctx, "https://lens.google.com/uploadbyurl?url=" + android.net.Uri.encode(url))
                onDismiss()
            }
        )
    }
}

/** Copie l'image elle-même dans le presse-papiers (voir [shareUri]). */
private suspend fun copyImage(ctx: android.content.Context, url: String): Boolean {
    val uri = shareUri(ctx, url) ?: return false
    val clip = android.content.ClipData(
        android.content.ClipDescription("Image", arrayOf("image/*")),
        android.content.ClipData.Item(uri)
    )
    return ctx.getSystemService(android.content.ClipboardManager::class.java)
        ?.setPrimaryClip(clip) != null
}

/** Partage l'image elle-même : le fichier part en `EXTRA_STREAM`, lisible par l'application
 *  choisie (d'où l'autorisation de lecture jointe à l'intent). */
private suspend fun shareImage(ctx: android.content.Context, url: String): Boolean {
    val uri = shareUri(ctx, url) ?: return false
    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(i, "Partager l'image"))
    return true
}

/**
 * L'image dans le cache de partage, rendue sous forme d'URI `FileProvider` : la copie locale quand
 * elle existe (miniature du flux, ou image du corps déjà chargée), sinon les octets téléchargés
 * sous plafond. Depuis Android 10 un `file://` est refusé au presse-papiers comme au partage.
 */
private suspend fun shareUri(ctx: android.content.Context, url: String): android.net.Uri? =
    withContext(Dispatchers.IO) {
        val local = ThumbStore.cachedFile(ctx, url)
        val bytes = if (local != null) runCatching { local.readBytes() }.getOrNull()
        else runCatching {
            com.pressia.data.readability.Readability.http
                .newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.byteStream()
                        ?.let { readCapped(it, ThumbStore.MAX_SOURCE_BYTES) }
                }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return@withContext null
        val file = java.io.File(java.io.File(ctx.cacheDir, "partage").apply { mkdirs() },
            "image." + imageExtension(bytes))
        if (runCatching { file.writeBytes(bytes) }.isFailure) return@withContext null
        runCatching {
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        }.getOrNull()
    }

/** Extension du fichier copié, d'après les premiers octets (la description posée sur le
 *  presse-papiers annonce, elle, le type `image` en général). */
private fun imageExtension(b: ByteArray): String = when {
    b.size > 8 && b[0] == 0x89.toByte() -> "png"
    b.size > 8 && b[0] == 'G'.code.toByte() -> "gif"
    b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "webp"
    else -> "jpg"
}

/**
 * Gestes de la visionneuse, en un seul détecteur : un appui simple (`onTap`), un appui maintenu
 * (`onLongPress`), un double appui (`onDoubleTap`) et un double appui **glissé**, qui pilote le
 * zoom tant que le doigt n'est pas relevé (`onDragStart` puis `onDrag` avec l'écart vertical en px).
 *
 * Un seul détecteur, et non `detectTapGestures` à côté : les deux guettaient le second appui
 * chacun de leur côté, et celui de la fermeture expirait parfois le premier — le double appui
 * glissé fermait la visionneuse au lieu de zoomer.
 */
private suspend fun PointerInputScope.detectImageGestures(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // Doigt posé sans glisser jusqu'au délai d'appui long.
        if (awaitLongPressOrCancellation(down.id) != null) {
            if (currentEvent.changes.count { it.pressed } == 1) {
                onLongPress()
                waitForUpOrCancellation()
                return@awaitEachGesture
            }
        }
        // Relevé sans glissement : un appui simple, sauf si un second appui le suit.
        if (currentEvent.changes.none { it.pressed }) {
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                awaitFirstDown(requireUnconsumed = false)
            }
            if (second == null) {
                onTap()
                return@awaitEachGesture
            }
            var moved = false
            onDragStart()
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == second.id } ?: break
                if (!change.pressed) break
                val dy = change.position.y - second.position.y
                if (!moved && kotlin.math.abs(dy) > viewConfiguration.touchSlop) moved = true
                if (moved) {
                    onDrag(dy)
                    change.consume()
                }
            }
            if (!moved) onDoubleTap()
        }
    }
}

/** Visionneuse plein écran : tap pour fermer, pincement pour zoomer, double appui pour zoomer puis
 *  dézoomer (et, double appui maintenu, zoom au glissement), appui long pour le menu. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var menu by remember { mutableStateOf(false) }
    // Zoom de départ du double appui glissé, posé au premier mouvement.
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil.compose.AsyncImage(
                // [thumbModel] plutôt que l'URL brute : une image déjà téléchargée (miniature
                // locale à la synchronisation, ou simple présence dans le cache disque Coil pour
                // les images du corps) s'ouvre en grand sans repartir sur le réseau. Le pincement
                // zoome la copie locale, au gabarit de l'écran.
                thumbModel(ctx, url), null,
                Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .transformable(
                        rememberTransformableState { zoom, pan, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            // Cette couche grossit son contenu : les coordonnées du geste y sont
                            // divisées par le zoom, d'où le `* scale` (sinon le déplacement est
                            // lent dès qu'on a zoomé).
                            if (scale > 1f) offset += pan * scale
                            else offset = androidx.compose.ui.geometry.Offset.Zero
                        }
                    )
                    // Après [transformable] : dans le passage montant, il reçoit le geste en
                    // premier et peut le consommer avant que le pincement n'en fasse un déplacement.
                    .pointerInput(Unit) {
                        detectImageGestures(
                            onTap = onClose,
                            onLongPress = { menu = true },
                            onDoubleTap = {
                                val target = if (scale > 1.05f) 1f else 2.5f
                                scope.launch {
                                    animate(scale, target, animationSpec = tween(200)) { v, _ -> scale = v }
                                    if (target == 1f) offset = androidx.compose.ui.geometry.Offset.Zero
                                }
                            },
                            onDragStart = { dragFrom = null },
                            onDrag = { dy ->
                                val base = dragFrom ?: scale.also { dragFrom = it }
                                // Glisser vers le haut agrandit, vers le bas réduit.
                                scale = (base * (1f - dy / 500f)).coerceIn(1f, 5f)
                                if (scale <= 1f) offset = androidx.compose.ui.geometry.Offset.Zero
                            }
                        )
                    },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            ImageActionsMenu(url, menu) { menu = false }
        }
    }
}

fun stripHtml(html: String): String {
    return try { org.jsoup.Jsoup.parse(html).text().replace(Regex(" +"), " ").trim() } catch (_: Exception) { html }
}

/** Blocs de contenu ordonnés (texte / images / liens / vidéos / tableaux) pour un rendu fidèle. */
sealed interface ABlock {
    /** Genre d'un bloc de texte : c'est lui qui donne sa taille et sa place dans l'article. */
    enum class Kind { Body, Title1, Title2, Title3, Bullet, Quote, Caption }

    /**
     * Texte d'un bloc. Les liens qu'il contient sont **dans le flux** du texte : [links] donne
     * leurs plages (index dans [text]) et leur cible, au lieu d'un bloc à part qui ferait passer
     * le lien à la ligne au milieu de la phrase. [spans] porte la mise en forme en ligne (gras,
     * italique), qui ne coupe pas davantage le texte.
     */
    data class T(
        val text: String,
        val links: List<Link> = emptyList(),
        val spans: List<Span> = emptyList(),
        val kind: Kind = Kind.Body
    ) : ABlock
    data class I(val url: String) : ABlock
    /** Lien isolé, hors de tout paragraphe : bloc à part entière, faute de texte où le couler. */
    data class L(val text: String, val url: String) : ABlock
    /** Vidéo embarquée (balise <video>, ou <iframe> d'un hôte de vidéo). */
    data class V(val url: String, val poster: String?, val embed: Boolean = false) : ABlock
    /** Tableau : [head] est la ligne d'en-tête (`th`), vide quand le tableau n'en a pas. */
    data class Table(val head: List<String> = emptyList(), val rows: List<List<String>>) : ABlock

    /** Plage cliquable d'un texte : [start] inclus, [end] exclu, et l'URL qu'elle ouvre. */
    data class Link(val start: Int, val end: Int, val url: String)

    /** Plage de mise en forme (gras, italique, surlignage, soulignement, barré) d'un texte. */
    data class Span(
        val start: Int,
        val end: Int,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val highlight: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false
    )
}

/** Tags qui font un bloc de texte : chacun est coulé d'un seul tenant, liens et styles compris. */
private val ARTICLE_BLOCK_TAGS =
    setOf("p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "figcaption")

/** Sélecteur des blocs de contenu : les conteneurs de texte, plus les médias et les tableaux. */
private val ARTICLE_SELECTOR =
    (ARTICLE_BLOCK_TAGS + listOf("img", "video", "iframe", "table")).joinToString(", ")

/**
 * Texte souligné ? Balises `u` et `ins`, ou style en ligne `text-decoration: underline` (les flux
 * emploient les deux ; Readability retire les styles, pas les balises). La détection remonte à
 * tous les descendants de l'élément.
 */
private fun isUnderlined(el: org.jsoup.nodes.Element): Boolean =
    el.tagName() in setOf("u", "ins") || hasTextDecoration(el, "underline")

/** Texte barré ? Balises `s`, `del` et `strike`, ou style en ligne `line-through`. */
private fun isStruck(el: org.jsoup.nodes.Element): Boolean =
    el.tagName() in setOf("s", "del", "strike") || hasTextDecoration(el, "line-through")

/** Le style en ligne de l'élément demande-t-il ce trait (`underline`, `line-through`) ? */
private fun hasTextDecoration(el: org.jsoup.nodes.Element, value: String): Boolean =
    Regex("text-decoration[^;]*$value", RegexOption.IGNORE_CASE).containsMatchIn(el.attr("style"))

/**
 * Mise en forme en ligne en cours de parcours d'un conteneur : chaque morceau de texte est
 * enregistré avec elle, ce qui donne des plages qui ne se chevauchent pas.
 */
private data class Inline(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val mark: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false
) {
    /** Y a-t-il quelque chose à rendre ? (sinon aucune plage n'est enregistrée) */
    val any get() = bold || italic || mark || underline || strike
}

/** Genre de bloc d'un conteneur de texte (voir [ARTICLE_BLOCK_TAGS]). */
private fun blockKind(tag: String): ABlock.Kind = when (tag) {
    "h1" -> ABlock.Kind.Title1
    "h2" -> ABlock.Kind.Title2
    "h3", "h4", "h5", "h6" -> ABlock.Kind.Title3
    "li" -> ABlock.Kind.Bullet
    "blockquote" -> ABlock.Kind.Quote
    "figcaption" -> ABlock.Kind.Caption
    else -> ABlock.Kind.Body
}

/**
 * Texte d'un bloc avec ses liens : chaque plage ([ABlock.Link]) devient une **annotation de
 * lien** — couleur dominante et soulignée, comme les autres liens de l'application — que Compose
 * ouvre au tap par le gestionnaire d'URI du système (le même `ACTION_VIEW` que [openUrl]). Le
 * texte reste d'un seul tenant : c'est ce qui garde le lien dans la phrase au lieu de le faire
 * passer à la ligne. Construit une fois par texte ; la couleur du thème en fait partie.
 */
@Composable
private fun blockText(b: ABlock.T, linkColor: Color, markColor: Color): AnnotatedString =
    remember(b, linkColor, markColor) {
    buildAnnotatedString {
        val text = b.text
        var at = 0
        b.links.sortedBy { it.start }.forEach { l ->
            val start = l.start.coerceIn(at, text.length)
            val end = l.end.coerceIn(start, text.length)
            if (start > at) append(text.substring(at, start))
            if (end > start) {
                withLink(
                    LinkAnnotation.Url(
                        url = l.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        )
                    )
                ) { append(text.substring(start, end)) }
            }
            at = end
        }
        if (at < text.length) append(text.substring(at))
        // Mise en forme en ligne (gras, italique) : des annotations sur le texte déjà construit,
        // pour ne pas découper la phrase.
        b.spans.forEach { s ->
            val start = s.start.coerceIn(0, text.length)
            val end = s.end.coerceIn(start, text.length)
            if (end > start) addStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    background = if (s.highlight) markColor else Color.Unspecified,
                    textDecoration = listOfNotNull(
                        if (s.underline) TextDecoration.Underline else null,
                        if (s.strike) TextDecoration.LineThrough else null
                    ).takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) }
                ),
                start,
                end
            )
        }
    }
}

/**
 * Rendu d'un bloc de texte : le genre ([ABlock.Kind]) donne taille, graisse et place — un titre
 * n'est plus un paragraphe, une puce porte son point, une légende se centre en petit.
 */
@Composable
private fun ArticleTextBlock(b: ABlock.T, linkColor: Color, size: Int) {
    val base = MaterialTheme.typography.bodyLarge
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (fs, lh, style) = when (b.kind) {
        ABlock.Kind.Title1 -> Triple(size + 10, size + 16, base.copy(fontWeight = FontWeight.Bold))
        ABlock.Kind.Title2 -> Triple(size + 5, size + 11, base.copy(fontWeight = FontWeight.Bold))
        ABlock.Kind.Title3 -> Triple(size + 2, size + 8, base.copy(fontWeight = FontWeight.Bold))
        ABlock.Kind.Caption -> Triple(size - 2, size + 3, base.copy(color = muted))
        ABlock.Kind.Quote -> Triple(size, size + 6, base.copy(fontStyle = FontStyle.Italic))
        else -> Triple(size, size + 6, base)
    }
    val text = blockText(b, linkColor, MaterialTheme.colorScheme.primaryContainer)
    when (b.kind) {
        ABlock.Kind.Bullet -> Row(Modifier.padding(bottom = 8.dp).fillMaxWidth()) {
            Text("•", fontSize = fs.sp, style = style, modifier = Modifier.padding(end = 8.dp))
            Text(text, fontSize = fs.sp, lineHeight = lh.sp, style = style, modifier = Modifier.weight(1f))
        }
        ABlock.Kind.Caption -> Text(
            text, fontSize = fs.sp, lineHeight = lh.sp, style = style,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
        )
        else -> Text(
            text, fontSize = fs.sp, lineHeight = lh.sp, style = style,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/**
 * Tableau du corps : en-tête sur fond plus marqué, lignes alternées sur un fond plus clair,
 * filets fins, colonnes de largeur égale. Les fonds viennent de `onSurface` en transparence :
 * ils suivent donc le thème et la couleur de fond choisie (où `surfaceVariant` vaut le fond).
 */
@Composable
private fun ArticleTable(t: ABlock.Table, size: Int) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val headBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val rowBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, line, RoundedCornerShape(4.dp))
    ) {
        if (t.head.isNotEmpty()) {
            ArticleTableRow(t.head, header = true, size = size, tint = headBg)
            HorizontalDivider(color = line)
        }
        t.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = line)
            ArticleTableRow(row, header = false, size = size, tint = if (i % 2 == 1) rowBg else null)
        }
    }
}

@Composable
private fun ArticleTableRow(cells: List<String>, header: Boolean, size: Int, tint: Color?) {
    Row(
        Modifier.fillMaxWidth().then(if (tint != null) Modifier.background(tint) else Modifier)
    ) {
        cells.forEach { c ->
            Text(
                c,
                fontSize = (size - 1).sp,
                lineHeight = (size + 3).sp,
                fontWeight = if (header) FontWeight.Bold else null,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

fun articleBlocks(html: String, baseUrl: String): List<ABlock> {
    return try {
        val doc = org.jsoup.Jsoup.parse(html, baseUrl)
        // Certains flux livrent le texte nu, hors de toute balise de bloc (« …texte…<br><img …> »,
        // cas des Numériques) : le sélecteur ne trouverait que l'image et l'article s'afficherait
        // sans son corps. Ces nœuds texte sont enveloppés dans un paragraphe pour suivre le même
        // chemin que les autres, à leur place dans le document.
        doc.body()?.childNodes()?.filterIsInstance<org.jsoup.nodes.TextNode>()
            ?.filter { it.text().isNotBlank() }?.toList()
            ?.forEach { it.replaceWith(org.jsoup.nodes.Element("p").text(it.text())) }
        val out = mutableListOf<ABlock>()
        val seen = mutableSetOf<String>()
        fun abs(u: String): String? {
            var x = u.trim()
            if (x.startsWith("//")) x = "https:$x"
            if (!x.startsWith("http")) return null
            if (!seen.add(x)) return null
            return x
        }
        // Détecte une URL YouTube embed et en extrait l'identifiant de vidéo pour une miniature
        // d'affiche (poster) si aucune n'est fournie.
        fun youtubePoster(url: String): String? = youtubePosterUrl(url)
        // Espaces (retours et tabulations compris) ramenés à un seul : le texte d'un flux arrive
        // indenté, et deux espaces de suite se verraient au milieu d'une ligne.
        val ws = Regex("\\s+")
        // L'URL d'un lien, absolue et exploitable dans un navigateur. Elle n'entre pas dans `seen`
        // (contrairement aux médias) : le même lien revient légitimement dans le texte, et le
        // dédoublonner amputerait la phrase.
        fun linkUrl(u: String): String? {
            var x = u.trim()
            if (x.startsWith("//")) x = "https:$x"
            return x.takeIf { it.startsWith("http") }
        }
        // Un conteneur de texte (p, li, h…, blockquote) est coulé **d'un seul tenant** : le texte
        // et ses liens forment un unique bloc, un lien n'étant qu'une plage cliquable — un bloc à
        // part le ferait passer à la ligne au milieu d'une phrase. Les médias rencontrés (img,
        // video, iframe) coupent le texte : ce qui précède est publié, le média prend sa place, la
        // suite reprend. La mise en forme en ligne (strong, em, span…) ne coupe rien.
        fun processFlow(root: org.jsoup.nodes.Element, kind: ABlock.Kind = blockKind(root.tagName())) {
            val sb = StringBuilder()
            val links = mutableListOf<ABlock.Link>()
            val spans = mutableListOf<ABlock.Span>()
            // Publie le texte accumulé. Les plages sont recalculées après rognage : celles
            // enregistrées valent pour la chaîne brute, espaces de tête compris.
            fun flush(k: ABlock.Kind) {
                val raw = sb.toString()
                val start = raw.indexOfFirst { !it.isWhitespace() }
                if (start < 0) { sb.clear(); links.clear(); spans.clear(); return }
                val text = raw.trim()
                val kept = links.mapNotNull { l ->
                    val s = (l.start - start).coerceIn(0, text.length)
                    val e = (l.end - start).coerceIn(0, text.length)
                    if (e > s) ABlock.Link(s, e, l.url) else null
                }
                val styled = spans.mapNotNull { sp ->
                    val s = (sp.start - start).coerceIn(0, text.length)
                    val e = (sp.end - start).coerceIn(0, text.length)
                    if (e > s) sp.copy(start = s, end = e) else null
                }
                out += ABlock.T(text, kept, styled, k)
                sb.clear(); links.clear(); spans.clear()
            }
            // Texte d'un élément en ligne, espaces compris : `wholeText` (et non `text`, que jsoup
            // rogne des deux côtés) permet de recoller le texte de part et d'autre d'un lien sans
            // souder les mots — « Voir notre article pour la suite. » garde ses espaces.
            fun inlineText(el: org.jsoup.nodes.Element): String {
                val raw = StringBuilder()
                el.childNodes().forEach { n ->
                    when {
                        n is org.jsoup.nodes.TextNode -> raw.append(n.wholeText)
                        n is org.jsoup.nodes.Element -> raw.append(inlineText(n))
                    }
                }
                return ws.replace(raw, " ")
            }
            /**
             * Ajoute un morceau de texte et rend la plage qu'il occupe, blancs de bord exclus.
             * Un seul blanc au raccord : les morceaux voisins en portent chacun un (« Voir » +
             * « notre article » + « pour la suite. » ne doit pas donner de double espace).
             */
            fun appendText(s: String): Pair<Int, Int> {
                var t = ws.replace(s, " ")
                if (t.isNotEmpty() && t.first().isWhitespace() && (sb.isEmpty() || sb.last().isWhitespace())) {
                    t = t.trimStart()
                }
                val at = sb.length
                sb.append(t)
                var lo = at
                var hi = sb.length
                while (lo < hi && sb[lo].isWhitespace()) lo++
                while (hi > lo && sb[hi - 1].isWhitespace()) hi--
                return lo to hi
            }
            /**
             * Parcourt un conteneur. La mise en forme ([style]) vient des éléments en ligne
             * rencontrés au-dessus (strong, em, mark, u, s…) et des styles en ligne : chaque
             * morceau de texte est enregistré avec le style en cours, ce qui donne des plages qui
             * ne se chevauchent pas.
             */
            fun walk(el: org.jsoup.nodes.Element, kind: ABlock.Kind, style: Inline) {
                // Le style porté par l'élément lui-même s'ajoute à celui hérité du parent.
                val st = style.copy(
                    mark = style.mark || el.tagName() == "mark",
                    underline = style.underline || isUnderlined(el),
                    strike = style.strike || isStruck(el)
                )
                el.childNodes().forEach { node ->
                    when {
                        node is org.jsoup.nodes.TextNode -> {
                            val (lo, hi) = appendText(node.wholeText)
                            if (hi > lo && st.any) spans += ABlock.Span(lo, hi, st.bold, st.italic, st.mark, st.underline, st.strike)
                        }
                        node is org.jsoup.nodes.Element -> when (node.tagName()) {
                            "br" -> sb.append('\n')
                            "a" -> {
                                val t = inlineText(node)
                                if (t.isNotBlank()) {
                                    val (lo, hi) = appendText(t)
                                    if (hi > lo) {
                                        if (st.any) spans += ABlock.Span(lo, hi, st.bold, st.italic, st.mark, st.underline, st.strike)
                                        linkUrl(node.absUrl("href").ifBlank { node.attr("href") })
                                            ?.let { links += ABlock.Link(lo, hi, it) }
                                    }
                                }
                            }
                            "img" -> {
                                flush(kind)
                                abs(node.absUrl("src").ifBlank { node.attr("src") })?.let { out += ABlock.I(it) }
                            }
                            "video" -> {
                                flush(kind)
                                val poster = node.attr("poster").takeIf { it.isNotBlank() }
                                abs(node.absUrl("src").ifBlank { node.attr("src") })?.let { out += ABlock.V(it, poster) }
                            }
                            "iframe" -> {
                                flush(kind)
                                abs(node.absUrl("src").ifBlank { node.attr("src") })?.let {
                                    if (isVideoEmbed(it)) out += ABlock.V(it, youtubePoster(it), embed = true)
                                    else out += embedLink(node, it)
                                }
                            }
                            // Sous-bloc (un <p> dans un <li>, deux <p> dans une citation) : chacun
                            // reste un bloc de texte à part, sans être recollé au précédent. Il
                            // garde le genre du parent (une puce reste une puce) sauf si la balise
                            // impose le sien (un <h3> dans une citation est un titre).
                            in ARTICLE_BLOCK_TAGS -> {
                                flush(kind)
                                walk(node, blockKind(node.tagName()).takeIf { it != ABlock.Kind.Body } ?: kind, st)
                                flush(kind)
                            }
                            "strong", "b" -> walk(node, kind, st.copy(bold = true))
                            "em", "i", "cite" -> walk(node, kind, st.copy(italic = true))
                            else -> walk(node, kind, st)
                        }
                    }
                }
            }
            walk(root, kind, Inline())
            flush(kind)
        }
        // Médias et liens isolés (hors conteneur de texte) : leur propre bloc.
        fun processBlock(el: org.jsoup.nodes.Element) {
            when (el.tagName()) {
                "img" -> {
                    val u = el.absUrl("src").ifBlank { el.attr("src") }
                    abs(u)?.let { out += ABlock.I(it) }
                }
                "a" -> {
                    val t = el.text().trim()
                    val href = el.absUrl("href").ifBlank { el.attr("href") }
                    if (t.length >= 2) linkUrl(href)?.let { out += ABlock.L(t, it) }
                }
                "video" -> {
                    // Affiche : attribut `poster` uniquement (une <source> est le média, pas une image).
                    val poster = el.attr("poster").takeIf { it.isNotBlank() }
                    val src = el.absUrl("src").ifBlank { el.attr("src") }
                    val u = abs(src)
                    if (u != null) out += ABlock.V(u, poster)
                }
                "iframe" -> {
                    val src = el.absUrl("src").ifBlank { el.attr("src") }
                    abs(src)?.let {
                        // Une iframe n'est pas forcément une vidéo : MiniMachines y met une carte
                        // vers un autre de ses articles (wp-embedded-content). Seuls les hôtes de
                        // vidéo ont droit au bloc lecteur ; les autres deviennent un lien, sinon
                        // l'article montrait un lecteur noir pour une page qui n'en est pas une.
                        if (isVideoEmbed(it)) out += ABlock.V(it, youtubePoster(it), embed = true)
                        else out += embedLink(el, it)
                    }
                }
                // Tableau : une ligne par <tr>, l'en-tête étant la première quand elle porte des
                // <th>. Les cellules sont lues en texte (une cellule peut contenir un paragraphe).
                "table" -> {
                    val trs = el.select("tr")
                    val head = trs.firstOrNull()?.takeIf { it.select("th").isNotEmpty() }
                    val rows = trs.map { tr -> tr.select("th, td").map { c -> c.text().trim() } }
                        .filter { row -> row.any { it.isNotBlank() } }
                    if (rows.isNotEmpty()) {
                        out += if (head != null) ABlock.Table(rows.first(), rows.drop(1))
                        else ABlock.Table(rows = rows)
                    }
                }
                // Conteneur de texte : coulé d'un seul tenant, liens compris.
                else -> processFlow(el)
            }
        }
        // Un conteneur de texte imbriqué dans un autre (un <p> dans un <li>) est déjà pris par son
        // parent : le sélectionner en plus dupliquerait son texte. Un tableau en fait autant de
        // ses cellules.
        fun isNestedBlock(el: org.jsoup.nodes.Element): Boolean =
            el.parents().any { it.tagName() in ARTICLE_BLOCK_TAGS || it.tagName() == "table" }
        doc.body()?.select(ARTICLE_SELECTOR)?.forEach { el ->
            if (el.tagName() !in ARTICLE_BLOCK_TAGS || !isNestedBlock(el)) processBlock(el)
        }
        // Les <a>, <img>, <video>, <iframe> isolés au niveau du corps (hors conteneur de bloc)
        // sont aussi pris en compte.
        doc.body()?.children()?.filterIsInstance<org.jsoup.nodes.Element>()?.forEach { el ->
            when (el.tagName()) {
                "a", "img", "video", "iframe" -> processBlock(el)
            }
        }
        if (out.isEmpty()) listOf(ABlock.T(stripHtml(html))) else out
    } catch (_: Exception) { listOf(ABlock.T(stripHtml(html))) }
}

/**
 * Une iframe porte-t-elle une vidéo ?
 *
 * Seuls YouTube (toutes ses écritures, reconnues par [youtubeVideoId]) et les lecteurs vidéo
 * connus ont droit au bloc lecteur. Tout le reste — carte d'un autre article, formulaire,
 * commentaires — est une page intégrée, rendue comme un simple lien : l'article affichait sinon
 * un lecteur noir pour une iframe qui n'est pas une vidéo.
 */
fun isVideoEmbed(url: String): Boolean {
    if (youtubeVideoId(url) != null) return true
    val host = runCatching { java.net.URI(url.trim()) }.getOrNull()?.host
        ?.removePrefix("www.").orEmpty()
    return listOf("vimeo.com", "dailymotion.com", "twitch.tv").any { host == it || host.endsWith(".$it") }
}

/** Lien d'une iframe qui n'est pas une vidéo : son titre, à défaut la page visée. */
private fun embedLink(el: org.jsoup.nodes.Element, url: String): ABlock.L {
    val title = el.attr("title").trim().takeIf { it.isNotEmpty() }
    return ABlock.L(title ?: (runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url), url)
}

/**
 * Identifiant d'une vidéo YouTube, quelle que soit la forme de l'URL.
 *
 * Les flux emploient toutes les écritures : `youtube.com/embed/<id>` (iframe),
 * `youtu.be/<id>`, `youtube.com/watch?v=<id>`, `youtube.com/shorts/<id>`,
 * `youtube.com/v/<id>`. L'identifiant suffit à reconstruire l'intégration et l'affiche.
 */
fun youtubeVideoId(url: String): String? {
    val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
    val host = (u.host ?: "").removePrefix("www.").removePrefix("m.").removePrefix("music.")
    val path = u.path.orEmpty()
    val id = when {
        host == "youtu.be" -> path.trimStart('/').substringBefore('/')
        host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
            val p = path
            val fromPath = p.substringAfter("/embed/").takeIf { it.length != p.length }
                ?: p.substringAfter("/shorts/").takeIf { it.length != p.length }
                ?: p.substringAfter("/v/").takeIf { it.length != p.length }
            fromPath?.substringBefore('?')?.substringBefore('/')
                ?: u.query?.substringAfter("v=")?.substringBefore('&')
        }
        else -> null
    }
    // Un identifiant YouTube fait 11 caractères ; on écarte les reliquats (URL de chaîne, etc.).
    return id?.takeIf { it.length == 11 }
}

/**
 * URL de la page **d'intégration** YouTube : celle du mode « interface YouTube ».
 *
 * C'est le document que le site destine à être embarqué — pas d'en-tête, pas de commentaires, un
 * lecteur qui occupe la vue —, et ses contrôles (titre, son, sous-titres, réglages, ⤢, barre
 * d'avancée, temps) sont de **vrais éléments du document**, donc utilisables.
 *
 * La page `m.youtube.com/watch` ne convient pas à ce mode : présentée avec un User-Agent de
 * navigateur, elle se met en pause ~0,5 s après chaque `play()` — le lecteur se croit non visible
 * (`onVideoProgress` → `visibility.Xj()` → `pauseVideo`, pile relevée dans le player), et cela
 * même sans feuille de style de l'application et avec `backgroundable` forcé. La page
 * d'intégration, elle, joue normalement (vérifié en direct).
 */
fun youtubeEmbedUrl(videoId: String): String =
    "https://www.youtube.com/embed/$videoId"

/**
 * En-tête `Referer` du chargement.
 *
 * YouTube refuse l'intégration quand la requête ne porte pas de référent (« Erreur 152 » pour
 * un référent absent, « Erreur 153 » pour un référent invalide). Or un `loadUrl` **sans en-tête**
 * n'envoie aucun Referer (requête de premier niveau, sans page référente), et
 * `loadDataWithBaseURL` donne une **origine** mais pas de référent — la balise
 * `<meta name="referrer">` ne peut pas en inventer un. D'où `loadUrl(url, headers)` avec un
 * Referer explicite.
 */
fun youtubeReferer(): String =
    // YouTube refuse `www.youtube.com` comme référent d'une intégration (« Erreur 152 » : une page
    // YouTube ne peut pas intégrer une page YouTube), alors qu'il l'accepte depuis `m.youtube.com`
    // — vérifié en direct dans la WebView.
    "https://m.youtube.com/"

/**
 * User-Agent présenté à YouTube quand on veut **son** interface.
 *
 * Avec l'User-Agent de la WebView — que Chromium marque `wv`, comme tous les navigateurs
 * embarqués — YouTube livre une page réduite (202 nœuds, aucun `ytp-chrome-bottom`, aucun
 * bouton) : le lecteur, sans commandes, à charge pour l'application hôte de les poser. Avec un
 * User-Agent de navigateur, c'est la vraie page mobile (1493 nœuds, 28 boutons, roue, barre) —
 * mesuré sur le même appareil, la même URL. C'est ce que choisit le réglage « Interface
 * YouTube ».
 */
fun youtubeBrowserUserAgent(): String =
    "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/128.0.0.0 Mobile Safari/537.36"

/**
 * Script du mode « interface YouTube » : démarre la lecture demandée par l'appui sur l'affiche.
 *
 * La page d'intégration n'a pas d'autoplay : elle attend un geste dans la page. Ici, la lecture est
 * décidée par l'appui sur l'affiche — un geste de l'utilisateur —, et
 * `mediaPlaybackRequiresUserGesture = false` autorise la page à démarrer sans attendre un second
 * appui. On rétablit aussi le son, que YouTube coupe par défaut quand il n'a pas vu de geste. Le
 * lecteur n'existe qu'après le rendu progressif de la page : on réessaie tant qu'il ne joue pas.
 */
internal fun youtubeSiteScript(): String = """
(function () {
  var tries = 0;
  function start() {
    var pl = document.getElementById('movie_player');
    var v = document.querySelector('video');
    if (!v && !pl) { if (tries++ < 60) setTimeout(start, 500); return; }
    try { if (pl && pl.unMute) pl.unMute(); } catch (e) {}
    try { if (pl && pl.playVideo) pl.playVideo(); } catch (e) {}
    if (v) {
      v.muted = false;
      try { v.volume = 1; } catch (e) {}
      if (v.paused) {
        try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
      }
    }
    if ((!v || v.paused) && tries++ < 60) setTimeout(start, 500);
  }
  window.pressiaSite = start;
  // Plein écran : la WebView est déplacée par l'application (voir `PlayerFullscreen`), ce qui met
  // la page en pause. On retient l'état de lecture avant le déplacement, et `pressiaResume` la
  // relance une fois la vue en place.
  window.pressiaWant = false;
  window.pressiaToggleFull = function () {
    var v = document.querySelector('video');
    window.pressiaWant = !!(v && !v.paused && !v.ended);
    if (window.PressIAHost && window.PressIAHost.fullscreen) window.PressIAHost.fullscreen();
  };
  window.pressiaResume = function () {
    var v = document.querySelector('video');
    if (!v || !window.pressiaWant || v.ended) return;
    if (v.paused) start();
  };
  // YouTube a mesuré la page avant notre feuille de style : il se croit minuscule et reste en
  // « tiny mode » (aucun contrôle rendu). Un `resize` lui fait reprendre sa mise en page — un
  // seul ne suffit pas toujours, le lecteur étant monté progressivement.
  [0, 300, 1000, 2500, 5000].forEach(function (ms) {
    setTimeout(function () { window.dispatchEvent(new Event('resize')); }, ms);
  });
  // Le bouton ⤢ du site demande le plein écran au navigateur, refusé dans une WebView
  // (« Permissions check failed », constaté : il ne se passait rien). On le détourne vers
  // l'application, qui pose la vue en plein écran — le même chemin que le bouton de la barre.
  if (!window.pressiaSiteHooked) {
    window.pressiaSiteHooked = true;
    document.addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('.fullscreen-icon') : null;
      if (!btn) return;
      e.preventDefault();
      e.stopPropagation();
      try {
        if (window.pressiaToggleFull) window.pressiaToggleFull();
        else if (window.PressIAHost && window.PressIAHost.fullscreen) {
          window.PressIAHost.fullscreen();
        }
      } catch (err) {}
    }, true);
  }
  start();
})()
""".trimIndent()

/**
 * Habillage du mode « interface YouTube » : le lecteur prend la place du bloc 16/9.
 *
 * La page chargée est la page d'intégration ([youtubeEmbedUrl]) : elle ne contient que le lecteur,
 * ses contrôles sont les siens, et rien n'est à masquer. On lui impose seulement la géométrie du
 * bloc, le fond noir et l'absence de gestes de défilement (un glissement horizontal est un appui
 * sur la barre d'avancée, pas un défilement).
 *
 * La géométrie n'est pas facultative : laissé à la hauteur de son conteneur, le lecteur peut se
 * croire minuscule et passer en « tiny mode » — il **ne rend alors aucun contrôle** (`ytp-tiny-mode`
 * dans le DOM, ni `.ytp-chrome-bottom` ni `#player-control-overlay` rempli : constaté sur la page
 * `watch`, une vidéo muette qu'on ne peut ni mettre en pause ni avancer). À la taille du bloc, ses
 * contrôles mobiles s'affichent et se placent d'eux-mêmes en bas de l'image. Changer la taille par
 * CSS ne suffit pas à le faire sortir du « tiny mode » : un `resize` est dispatché ensuite (voir
 * [youtubeSiteScript]).
 */
internal fun youtubeSiteCss(): String = listOf(
    "html,body{margin:0!important;padding:0!important;overflow:hidden!important;" +
        "background:#000!important;touch-action:none!important}",
    "#player-container-id,#player,#movie_player,.html5-video-player{" +
        "position:fixed!important;top:0!important;left:0!important;" +
        "width:100vw!important;height:56.25vw!important;margin:0!important;padding:0!important}",
    "video.video-stream{position:absolute!important;top:0!important;left:0!important;" +
        "width:100vw!important;height:56.25vw!important;object-fit:contain!important}",
    // Plein écran : la WebView est déplacée dans une vue plein écran (voir `PlayerFullscreen`) et
    // la page reçoit la classe `pressia-full`. Le lecteur couvre alors tout l'écran ; ses
    // contrôles, posés sur la fenêtre, suivent d'eux-mêmes.
    "html.pressia-full #player-container-id,html.pressia-full #player," +
        "html.pressia-full #movie_player,html.pressia-full .html5-video-player{" +
        "width:100vw!important;height:100vh!important}",
    "html.pressia-full video.video-stream{" +
        "width:100vw!important;height:100vh!important;object-fit:contain!important}",
).joinToString("")

/**
 * Script qui pose la feuille de style du lecteur ([youtubeSiteCss]) dans la page chargée.
 *
 * Rejoué plusieurs fois : YouTube remplace une partie du document après coup (rendu progressif
 * des composants), et une feuille de style posée trop tôt se retrouve détachée.
 */
internal fun youtubePlayerScript(css: String): String =
    "(function(){" +
        "var css=" + jsSingleQuoted(css) + ";" +
        "var apply=function(){" +
        "var s=document.getElementById('pressia-yt');" +
        "if(!s){s=document.createElement('style');s.id='pressia-yt';" +
        "(document.head||document.documentElement).appendChild(s);}" +
        "s.textContent=css;};" +
        "apply();setTimeout(apply,700);setTimeout(apply,2200);setTimeout(apply,5000);" +
        "})()"

/** Chaîne Kotlin -> littéral JavaScript entre apostrophes. */
private fun jsSingleQuoted(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

/** Image d'affiche d'une vidéo YouTube (hqdefault), ou null si l'URL n'en est pas une. */
fun youtubePosterUrl(url: String): String? =
    youtubeVideoId(url)?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }

fun openUrl(ctx: android.content.Context, url: String) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        ctx.startActivity(i)
    } catch (_: Exception) {}
}

/**
 * Ouvre [url] dans un onglet de navigateur intégré (Chrome Custom Tab).
 *
 * L'onglet partage la session du navigateur — l'utilisateur reste connecté à son compte sans que
 * PressIA ait à gérer le mot de passe — tout en gardant l'application visible derrière, avec sa
 * barre d'outils et le bouton de retour. Le mode « chrome / top » laisse Chrome gérer les onglets :
 * sans barre de couleur, le chargement paraît plus lent alors qu'il ne l'est pas.
 *
 * Repli sur [openUrl] si aucun navigateur ne sait présenter d'onglet personnalisé : certaines
 *ROM minimalistes n'en proposent pas.
 */
fun openCustomTab(ctx: android.content.Context, url: String, color: Int? = null) {
    val parsed = runCatching { android.net.Uri.parse(url) }.getOrNull()
    if (parsed == null) { openUrl(ctx, url); return }
    try {
        val builder = androidx.browser.customtabs.CustomTabsIntent.Builder()
        if (color != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            builder.setDefaultColorSchemeParams(
                androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(color).build()
            )
        }
        builder.setShowTitle(true)
        // On laisse CustomTabs choisir le navigateur : askpackage impose l'APK du navigateur
        // alors que l'utilisateur peut en avoir plusieurs (Chrome, Brave, Firefox…).
        // `intent` à null demande le service de résolution standard.
        builder.build().launchUrl(ctx, parsed)
    } catch (_: Exception) {
        openUrl(ctx, url)
    }
}

/**
 * Lecteur vidéo embarqué dans un article (balise <video> ou <iframe> YouTube/Vimeo…).
 *
 * Deux cas :
 *  - **fichier média** (balise <video>) : miniature (poster) cliquable, puis un
 *    [android.widget.VideoView] piloté par le cycle de vie Compose — pause sur ON_STOP/ON_DESTROY,
 *    arrêt complet à la sortie de la composition. La vidéo ne démarre jamais en autoplay
 *    (économie de données, respect du lecteur) ;
 *  - **page d'intégration** (balise <iframe>) : ce n'est pas un média, le lecteur intégré ne
 *    pourrait pas la lire. Pour **YouTube**, la vidéo est rejouée **dans l'application**, via une
 *    petite WebView qui charge la page d'intégration ([youtubeEmbedUrl]) ; pour les autres
 *    services (Vimeo, Dailymotion…), la miniature s'affiche et le tap ouvre la vidéo dans le
 *    navigateur externe.
 *
 * **Un seul pattern pour les deux** : l'affiche occupe le bloc, et c'est son tap qui crée le
 * lecteur ([android.widget.VideoView], ou [YoutubePlayer] — donc la WebView). Une vidéo jamais
 * lancée ne compose rien et ne charge rien ; `playing` est le seul état, il n'y en a pas un par
 * lecteur.
 */
@Composable
fun VideoPlayer(
    url: String,
    poster: String?,
    embed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var videoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
    // **Rien ne se lit ni ne se charge avant un appui** : l'affiche occupe le bloc, et c'est elle
    // qui crée le lecteur — `VideoView` pour un fichier média, `YoutubePlayer` (donc la WebView et
    // la page d'intégration) pour une vidéo YouTube. Un seul état pour les deux cas.
    val playing = remember { mutableStateOf(false) }

    // Intégration YouTube : lue dans l'application, via la WebView d'intégration.
    val youtubeId = remember(url) { if (embed) youtubeVideoId(url) else null }
    val watchUrl = remember(youtubeId) { youtubeId?.let { "https://www.youtube.com/watch?v=$it" } }

    // Titre de la vidéo, sous le bloc : demandé à l'ouverture de l'article (oEmbed, mis en
    // cache), pas à la lecture — il sert d'indication avant tout appui et l'ouvre dans YouTube.
    var title by remember(youtubeId) { mutableStateOf(youtubeId?.let { youtubeTitles[it] }) }
    LaunchedEffect(youtubeId) {
        if (youtubeId != null && title == null) title = youtubeTitle(youtubeId)
    }

    // Affiche : le tap lance la lecture dans l'application (YouTube, fichier média) ou ouvre la
    // vidéo dans le navigateur (autre service d'intégration, qui n'a pas de lecteur ici).
    val onTap: () -> Unit = if (youtubeId == null && embed) {
        { openUrl(context, url) }
    } else {
        { playing.value = true }
    }

    Column(modifier = modifier) {
        if (youtubeId != null && playing.value) {
            // Lecteur YouTube : composé **seulement** à l'appui — une intégration que personne ne
            // regarde n'est ni composée ni chargée.
            YoutubePlayer(videoId = youtubeId, title = title, poster = poster)
        } else if (!playing.value) {
            // Affiche (poster) : tap pour lancer la lecture ou ouvrir la vidéo.
            VideoPoster(
                poster = poster,
                label = if (youtubeId != null) "Lire la vidéo"
                else if (embed) "Ouvrir la vidéo" else "Lancer la vidéo",
                onTap = onTap,
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(16f / 9f)
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    // La lecture démarre dans `onPrepared`, jamais juste après `setVideoURI` :
                    // avant la préparation, `start()` ne fait rien (ni son ni image) et la
                    // lecture ne revient jamais.
                    android.widget.VideoView(ctx).apply {
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            start()
                        }
                        setVideoURI(android.net.Uri.parse(url))
                        videoView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
            )
            // Cycle de vie : pause quand l'écran est quitté, arrêt complet à la destruction.
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_STOP,
                        androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> videoView?.pause()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    videoView?.stopPlayback()
                    videoView = null
                }
            }
        }
        // Le titre ouvre la vidéo dans YouTube ; sous le lecteur une fois lancé (il y partage la
        // ligne avec le bouton plein écran, voir [YoutubePlayer]).
        if (watchUrl != null && !playing.value) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                YoutubeTitleRow(title, watchUrl, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Balayage horizontal de l'écran article, avec une exception : le geste qui commence sur un
 * lecteur appartient au lecteur.
 *
 * `detectHorizontalDragGestures` consomme le geste dès que la course horizontale dépasse le
 * seuil : tirer la barre d'avancée d'une vidéo faisait alors passer à l'article suivant, et le
 * lecteur ne voyait jamais la fin de son geste. Ici, un geste né sur un lecteur est suivi
 * jusqu'au relâchement sans rien consommer ; les autres sont traités comme avant (seuil
 * horizontal, la course verticale reste au défilement).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectArticleSwipe(
    onPlayer: () -> Boolean,
    onStart: () -> Unit,
    onDrag: (androidx.compose.ui.input.pointer.PointerInputChange, Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (onPlayer()) {
            // Doigt sur un lecteur : on laisse filer le flux tel quel.
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        var totalX = 0f
        var totalY = 0f
        var started = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dx = change.positionChange().x
            if (!started) {
                totalX += dx
                totalY += change.positionChange().y
                when {
                    // Course verticale : c'est le défilement de l'article, on rend la main.
                    kotlin.math.abs(totalY) > slop && kotlin.math.abs(totalY) > kotlin.math.abs(totalX) -> break
                    kotlin.math.abs(totalX) > slop -> {
                        started = true
                        change.consume()
                        onStart()
                        onDrag(change, totalX)
                        // La course d'amorçage est déjà comptée : ne pas la rejouer image par image.
                        totalX = 0f
                    }
                }
            } else {
                change.consume()
                onDrag(change, dx)
            }
        }
        if (started) onEnd() else onCancel()
    }
}

/**
 * Balayage vertical de l'écran article : il ne prend le geste que là où l'article ne peut pas
 * défiler — vers le haut quand le bas est atteint, vers le bas quand le haut l'est. Ailleurs le
 * geste reste au défilement, et un doigt posé sur un lecteur appartient au lecteur (même exception
 * que le balayage horizontal).
 *
 * Posé **après** `verticalScroll` dans la chaîne de modificateurs : dans le passage montant, il
 * voit l'évènement avant le défilement et peut donc lui prendre la course au bord de l'article.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectArticleVerticalSwipe(
    onPlayer: () -> Boolean,
    atTop: () -> Boolean,
    atBottom: () -> Boolean,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (onPlayer()) {
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            return@awaitEachGesture
        }
        val slop = viewConfiguration.touchSlop
        var total = 0f
        var started = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val dy = change.positionChange().y
            if (!started) {
                total += dy
                if (kotlin.math.abs(total) > slop) {
                    // Vers le haut : seulement au bas de l'article ; vers le bas : seulement en
                    // haut. Sinon le défilement garde le geste (rien n'est consommé).
                    if (!(if (total < 0) atBottom() else atTop())) break
                    started = true
                    change.consume()
                    onStart()
                    onDrag(total)
                    total = 0f
                }
            } else {
                change.consume()
                onDrag(dy)
            }
        }
        if (started) onEnd() else onCancel()
    }
}

/**
 * Signale « un doigt est posé sur le lecteur » pendant toute la durée du geste.
 *
 * Le balayage horizontal de l'écran article enveloppe la colonne entière : sans ce repère,
 * tirer la barre d'avancée de la vidéo faisait passer à l'article suivant (constaté sur
 * l'émulateur) et le lecteur ne voyait jamais la fin de son geste. On observe la passe
 * [PointerEventPass.Initial] : l'événement arrive avant que la WebView ou le `MediaController`
 * ne le consomme, et rien n'est intercepté — le lecteur reste maître du geste.
 */
private fun Modifier.playerGesture(active: MutableState<Boolean>) =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.any { it.pressed }
                if (pressed != active.value) active.value = pressed
            }
        }
    }

/**
 * Affiche d'une vidéo, en attendant un appui.
 *
 * Rien ne doit se lire sans geste : ce bloc montre l'image et le bouton de lecture, et c'est
 * l'appui qui décide de la suite (charger le lecteur, ou ouvrir la vidéo ailleurs).
 */
@Composable
private fun VideoPoster(
    poster: String?,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color.Black)
            .clickable { onTap() },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        if (poster != null) {
            coil.compose.AsyncImage(
                thumbModel(context, poster),
                null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayCircleFilled,
            contentDescription = label,
            tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(56.dp)
        )
    }
}

/** Activité qui héberge un contexte Compose (pour l'orientation et les barres système). */
private fun android.content.Context.activityOrNull(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Pont entre la page du lecteur et l'application : c'est par lui que le bouton plein écran de
 * nos contrôles demande le basculement.
 *
 * L'appeler depuis JavaScript est indispensable : l'API Fullscreen du navigateur est **refusée**
 * dans une WebView (« Permissions check failed », même sur un vrai geste et avec
 * `onShowCustomView` en place). Le plein écran est donc l'affaire de l'application — voir
 * [PlayerFullscreen].
 */
class PlayerJsBridge(private val onFullscreen: () -> Unit) {
    @android.webkit.JavascriptInterface
    fun fullscreen() = onFullscreen()
}

/**
 * Touche Retour pendant que le lecteur YouTube est affiché.
 *
 * Quand la vidéo joue, l'appui n'atteint **aucun** de nos gestionnaires de retour : ni la WebView,
 * ni la vue plein écran, ni le `BackHandler` de l'écran, ni le gestionnaire de l'activité
 * (`onBackPressed` n'est jamais appelé). Mesuré sur l'émulateur : `Activity.dispatchKeyEvent` voit
 * bien la touche, mais quelque chose dans la hiérarchie la consomme avant eux — en plein écran,
 * et aussi après un aller-retour en plein écran ; le même appui, vidéo en pause, passait
 * normalement. L'activité est le seul endroit qui la voie à coup sûr : tant que le lecteur est à
 * l'écran, elle la lui confie ([handler]), et celui-ci rend le comportement habituel — plein écran
 * d'abord, puis le retour normal. Hors du lecteur, `handler` vaut `null` et rien n'est intercepté.
 */
object PlayerBack {
    @Volatile
    var handler: (() -> Unit)? = null
}

/**
 * WebView du lecteur, qui **restitue la touche Retour à l'application**.
 *
 * Chromium consomme l'appui sur « retour » dès qu'un média joue : la touche n'atteint alors plus
 * l'activité, et le gestionnaire de retour de l'écran article ne se déclenche plus. Constaté :
 * vidéo lancée (ou seulement mise en pause — le lecteur reste actif), le retour ne quittait plus
 * l'article, et le décodeur était libéré au passage (`crMediaCodecBridge: Codec released`) au
 * lieu de sortir de l'écran. La touche est donc interceptée **ici**, avant Chromium, et confiée
 * au gestionnaire de retour de l'application : le plein écran se referme d'abord, l'article se
 * quitte ensuite — exactement comme sur les autres écrans.
 *
 * Le clavier logiciel, lui, garde la touche quand il est ouvert (elle le referme sans quitter
 * l'écran), et rien n'est intercepté si la WebView n'a pas de destinataire.
 */
private class PlayerWebView(context: android.content.Context) : android.webkit.WebView(context) {
    /** Gestionnaire de retour de l'application (`OnBackPressedDispatcher`), appelé sur ACTION_UP. */
    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val back = onBack
        if (back != null && event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.action == android.view.KeyEvent.ACTION_UP) back()
            // Les deux appuis sont consommés : sans cela Chromium reçoit la touche qu'il
            // attendait et arrête la lecture au lieu de quitter l'article. Sans destinataire,
            // en revanche, on ne touche à rien (le comportement d'origine reste possible).
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * Plein écran du lecteur : la **WebView elle-même** est déplacée dans une vue posée sur la
 * fenêtre, puis remise à sa place.
 *
 * Pourquoi déplacer la vue et non en créer une autre : recharger la page relancerait la vidéo au
 * début, avec un nouveau tampon. Ici, c'est la même page, au même instant de lecture ; la vue
 * change seulement de parent. Compose ne s'en aperçoit pas — le bloc garde sa place, vide, et
 * reprend la vue en sortant.
 */
private class PlayerFullscreen {
    private var frame: android.widget.FrameLayout? = null
    private var target: android.view.View? = null
    private var holder: android.view.ViewGroup? = null
    private var holderParams: android.view.ViewGroup.LayoutParams? = null
    private var onExit: () -> Unit = {}

    val active: Boolean get() = frame != null

    /** @return vrai si la vue est bien passée en plein écran (voir l'appelant, qui remet sinon
     *  l'état à « réduit » — un état plein écran sans vue plein écran laissait le bouton bloqué). */
    fun enter(
        ctx: android.content.Context,
        root: android.view.ViewGroup?,
        view: android.view.View,
        exit: () -> Unit
    ): Boolean {
        if (active || root == null) return false
        val parent = view.parent as? android.view.ViewGroup ?: return false
        holder = parent
        holderParams = view.layoutParams
        target = view
        onExit = exit
        parent.removeView(view)
        val d = ctx.resources.displayMetrics.density
        val screen = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // Sortie visible : les barres système sont masquées, et la barre du lecteur n'est pas
            // toujours là (mode « interface YouTube », page en chargement). En haut **à gauche** :
            // les contrôles du site occupent le coin droit (son, sous-titres, réglages), et la
            // croix y recouvrait la roue des réglages.
            addView(
                android.widget.TextView(ctx).apply {
                    text = "✕"
                    contentDescription = "quitter le plein écran"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 20f
                    gravity = android.view.Gravity.CENTER
                    setBackgroundColor(0x99000000.toInt())
                    setOnClickListener { onExit() }
                },
                android.widget.FrameLayout.LayoutParams(
                    (44 * d).toInt(), (44 * d).toInt(),
                    android.view.Gravity.TOP or android.view.Gravity.START
                ).apply {
                    topMargin = (10 * d).toInt()
                    leftMargin = (10 * d).toInt()
                }
            )
        }
        root.addView(
            screen,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame = screen
        return true
    }

    /** Revient au bloc de l'article (la vue retrouve son parent Compose). */
    fun exit() {
        if (!active) return
        val screen = frame ?: return
        val view = target
        val parent = holder
        val params = holderParams
        frame = null
        target = null
        holder = null
        holderParams = null
        onExit = {}
        if (view != null) screen.removeView(view)
        (screen.parent as? android.view.ViewGroup)?.removeView(screen)
        if (view != null && parent != null) {
            parent.addView(
                view, params ?: android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /**
     * Sortie sans remise en place : l'écran article se ferme, la WebView va être détruite et ne
     * doit pas rester posée sur la fenêtre.
     */
    fun release() {
        val screen = frame
        frame = null
        holder = null
        holderParams = null
        onExit = {}
        val view = target
        target = null
        if (view != null) screen?.removeView(view)
        (screen?.parent as? android.view.ViewGroup)?.removeView(screen)
    }
}

/**
 * Titres déjà obtenus (oEmbed) : une vidéo vue une fois ne redemande rien.
 *
 * **Borné** comme les autres caches de session : un parcours d'articles vidéo en accumulait un
 * par vidéo rencontrée, pour la vie du processus. Au-delà de [YOUTUBE_TITLES_MAX], les titres les
 * moins récemment demandés partent (LRU par accès) ; un titre oublié se redemande, il n'est pas
 * perdu.
 */
private const val YOUTUBE_TITLES_MAX = 200
private val youtubeTitles: MutableMap<String, String> =
    java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > YOUTUBE_TITLES_MAX
        }
    )

/**
 * Titre d'une vidéo YouTube, par oEmbed — la seule source publique qui ne réclame pas de clé
 * d'API. Silencieux en cas d'échec (hors-ligne, vidéo retirée) : le bloc garde alors un lien
 * générique, et la lecture se comporte comme avant.
 */
private suspend fun youtubeTitle(videoId: String): String? {
    youtubeTitles[videoId]?.let { return it }
    val fetched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val embed = java.net.URLEncoder.encode(
                "https://www.youtube.com/watch?v=$videoId", "UTF-8"
            )
            val req = okhttp3.Request.Builder()
                .url("https://www.youtube.com/oembed?url=$embed&format=json")
                .header("User-Agent", youtubeBrowserUserAgent())
                .build()
            // Client partagé (celui de Readability) : délais globaux, annulation réelle — un
            // HttpURLConnection n'en avait aucun et retenait un fil jusqu'au bout.
            com.pressia.data.readability.Readability.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                org.json.JSONObject(r.body?.string().orEmpty())
                    .optString("title").trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
    if (fetched != null) youtubeTitles[videoId] = fetched
    return fetched
}

/**
 * Titre de la vidéo, sous le lecteur : un appui l'ouvre dans YouTube.
 *
 * Le titre vient d'oEmbed ; tant qu'il n'est pas là (ou s'il est introuvable), la ligne porte un
 * libellé générique — elle reste le chemin vers YouTube, et le bloc ne saute pas de hauteur
 * quand le titre arrive.
 */
@Composable
private fun YoutubeTitleRow(title: String?, watchUrl: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { openUrl(ctx, watchUrl) }
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            "ouvrir dans YouTube",
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            title ?: "Ouvrir la vidéo sur YouTube",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Bouton plein écran, à côté du titre (masqué pendant le plein écran, où la page en affiche un). */
@Composable
private fun FullscreenToggle(on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(30.dp).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (on) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            if (on) "quitter le plein écran" else "plein écran",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Lecteur YouTube **dans l'application** : une WebView charge la page d'**intégration**
 * ([youtubeEmbedUrl]), où joue le lecteur officiel, avec **ses** contrôles (titre, son,
 * sous-titres, réglages, ⤢, barre d'avancée, temps).
 *
 * C'est la seule présentation : la page de lecture `m.youtube.com/watch`, servie à un User-Agent de
 * navigateur, se met en pause ~0,5 s après chaque `play()` (le lecteur se croit non visible,
 * `onVideoProgress` → `visibility.Xj()` → `pauseVideo`) et n'a d'ailleurs, en WebView `wv`, ni image
 * ni commandes utilisables ; en WebView `wv` elle se met en pause aussi toute seule en cours de
 * lecture. L'intégration, elle, joue normalement et ses commandes sont de **vrais éléments du
 * document** — il n'y a donc rien à poser à la place.
 *
 * Points d'attention :
 *  - **le lecteur n'existe qu'à l'appui** : [VideoPlayer] montre l'affiche et ne compose ce
 *    composable (donc la WebView et la page) qu'une fois le bloc touché ; [youtubeSiteScript]
 *    démarre alors la lecture (la page, elle, n'a pas d'autoplay) ;
 *  - JavaScript et le stockage local sont nécessaires au lecteur YouTube ;
 *  - `mediaPlaybackRequiresUserGesture = false` : sans cela, Chromium refuse de démarrer la
 *    lecture d'une vidéo déclenchée par la page ;
 *  - la page est chargée par `loadUrl(url, headers)` avec un **Referer** explicite (voir
 *    [youtubeReferer]) : un chargement sans en-tête n'en envoie aucun ;
 *  - la page est **habillée** par [youtubePlayerScript] avec [youtubeSiteCss] : géométrie du bloc
 *    16/9, fond noir, pas de défilement ;
 *  - la WebView est **détruite** à la sortie (`onRelease`) : une WebView non détruite garde un
 *    contexte, un moteur de rendu et des cookies — une par article s'accumulerait ;
 *  - le zoom et le scroll horizontal sont désactivés : la WebView est dans une colonne
 *    défilable et les gestes de l'écran article (balayage vers l'article précédent/suivant)
 *    doivent rester disponibles ;
 *  - la WebView est une [PlayerWebView], qui **rend la touche Retour à l'application** : Chromium
 *    la consomme dès qu'un média joue, et l'écran ne se quittait alors plus.
 *
 * Le titre ([title], venu de [VideoPlayer] : même ligne qu'avant la lecture) est affiché sous le
 * lecteur et l'ouvre dans YouTube, et le plein écran place réellement le lecteur sur tout l'écran
 * ([PlayerFullscreen]) — l'API Fullscreen de la page, elle, est refusée par la WebView.
 */
@Composable
fun YoutubePlayer(
    videoId: String,
    title: String?,
    poster: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // Gestionnaire de retour de l'application : la touche Retour est rendue à l'application par
    // [PlayerWebView], Chromium la consommant dès qu'un média joue.
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
        ?.onBackPressedDispatcher
    // Racine de la fenêtre : c'est là que se pose la vue plein écran.
    val rootView = androidx.compose.ui.platform.LocalView.current.rootView
        as? android.view.ViewGroup
    // Le lecteur n'est composé qu'une fois l'affiche touchée (voir [VideoPlayer]) : la page se
    // charge donc à ce moment-là, et pas à l'ouverture de l'article.
    var failed by remember(videoId) { mutableStateOf(false) }
    var loaded by remember(videoId) { mutableStateOf(false) }
    var fullscreen by remember(videoId) { mutableStateOf(false) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    val playerUrl = remember(videoId) { youtubeEmbedUrl(videoId) }
    val watchUrl = remember(videoId) { "https://www.youtube.com/watch?v=$videoId" }
    val screen = remember { PlayerFullscreen() }

    // Filet de sécurité : si la page n'a pas fini de charger (réseau coupé, WebView muette), on
    // propose l'ouverture dans le navigateur au lieu d'un écran noir.
    LaunchedEffect(videoId) {
        kotlinx.coroutines.delay(20_000)
        if (!loaded) failed = true
    }

    // Plein écran : la WebView change de parent, l'écran passe en paysage, les barres système
    // s'effacent, et la page reçoit la classe `pressia-full` (voir [youtubeSiteCss]) pour que
    // l'image occupe toute la hauteur au lieu du seul 16/9.
    LaunchedEffect(fullscreen) {
        val act = context.activityOrNull()
        val wv = webView
        if (fullscreen) {
            // L'application est seule à savoir où en est la vue : si le déplacement échoue (vue
            // détachée au moment du geste), l'état ne doit pas rester « plein écran » sans vue
            // plein écran — c'est ce qui laissait le bouton dans le mauvais sens en permanence.
            val entered = wv != null && screen.enter(context, rootView, wv) { fullscreen = false }
            if (!entered) {
                fullscreen = false
            } else {
                wv.evaluateJavascript(
                    "document.documentElement.classList.add('pressia-full');" +
                        "window.pressiaFull=true;",
                    null
                )
            }
            act?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            act?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
        } else {
            if (wv != null) {
                wv.evaluateJavascript(
                    "document.documentElement.classList.remove('pressia-full');" +
                        "window.pressiaFull=false;" +
                        // La page a été mise à la taille de l'écran par la classe : sans cet
                        // événement, YouTube garde sa mise en page plein écran dans le bloc.
                        "window.dispatchEvent(new Event('resize'));",
                    null
                )
            }
            screen.exit()
            act?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            act?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        // Reprise : détacher la WebView coupe le média et YouTube le laisse en pause. On redemande
        // la lecture après coup, plusieurs fois — la page rétablit parfois son état un instant
        // plus tard (changement de segment, reprise du rendu).
        val player = webView
        if (player != null) {
            listOf(150L, 600L, 1500L, 3000L).forEach { wait ->
                kotlinx.coroutines.delay(wait)
                player.evaluateJavascript("if(window.pressiaResume){window.pressiaResume()}", null)
            }
        }
    }

    // Retour : quitter le plein écran d'abord, quitter l'article ensuite. La touche est confiée à
    // l'activité tant que le lecteur est à l'écran (voir [PlayerBack]) : la hiérarchie de vues la
    // consomme quand la vidéo joue, et aucun de nos gestionnaires ne la recevrait.
    DisposableEffect(failed) {
        PlayerBack.handler = if (!failed) {
            {
                if (fullscreen) fullscreen = false
                else backDispatcher?.onBackPressed()
            }
        } else {
            null
        }
        onDispose { PlayerBack.handler = null }
    }

    // La vue plein écran est posée sur la fenêtre, hors de la composition : elle ne part pas
    // toute seule, et l'orientation doit être rendue au système.
    DisposableEffect(Unit) {
        onDispose {
            PlayerBack.handler = null
            screen.release()
            val act = context.activityOrNull()
            act?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            act?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(androidx.compose.ui.graphics.Color.Black)
                .clipToBounds()
        ) {
            when {
                // Repli : l'affiche de la vidéo, un tap l'ouvre dans YouTube (navigateur externe).
                failed -> VideoPoster(
                    poster = poster,
                    label = "Ouvrir la vidéo sur YouTube",
                    onTap = { openUrl(context, watchUrl) },
                    modifier = Modifier.fillMaxSize()
                )
                else -> AndroidView(
                    factory = { ctx ->
                        PlayerWebView(ctx).apply {
                            // Chromium consomme la touche Retour dès qu'un média joue (voir
                            // [PlayerWebView]) : elle est rendue au gestionnaire de l'application.
                            onBack = { backDispatcher?.onBackPressed() }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            // Le lecteur du site (ses commandes) n'est servi qu'à un User-Agent
                            // de navigateur : la WebView le marque `wv` sinon.
                            settings.userAgentString = youtubeBrowserUserAgent()
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            settings.displayZoomControls = false
                            isHorizontalScrollBarEnabled = false
                            isVerticalScrollBarEnabled = false
                            // Fond **transparent**, pas noir : au moment où elle est créée, la
                            // WebView n'a pas encore la taille de son bloc et peint alors tout
                            // l'écran en noir (constaté : une image entièrement noire à l'appui).
                            // Le noir vient de la boîte qui l'entoure, et de la page elle-même
                            // (`background:#000`, voir [youtubeSiteCss]).
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            // Bouton ⤢ du lecteur du site : c'est l'application qui réalise le
                            // plein écran (voir [PlayerJsBridge]).
                            addJavascriptInterface(
                                PlayerJsBridge { scope.launch { fullscreen = !fullscreen } },
                                "PressIAHost"
                            )
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView, url: String) {
                                    view.post {
                                        loaded = true
                                        // La page reçoit son habillage (géométrie du bloc, fond
                                        // noir, gestes) puis son script de démarrage.
                                        view.evaluateJavascript(
                                            youtubePlayerScript(youtubeSiteCss()),
                                            null
                                        )
                                        view.evaluateJavascript(youtubeSiteScript(), null)
                                        if (fullscreen) {
                                            view.evaluateJavascript(
                                                "document.documentElement.classList.add('pressia-full');" +
                                                    "window.pressiaFull=true;",
                                                null
                                            )
                                        }
                                    }
                                }
                            }
                            webView = this
                            // En-tête Referer explicite : un chargement de premier niveau n'en envoie aucun.
                            loadUrl(playerUrl, mapOf("Referer" to youtubeReferer()))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { wv ->
                        // La vue plein écran ne doit pas survivre à la WebView qui la fournit.
                        screen.release()
                        // `about:blank` d'abord : la WebView est dans une colonne défilable, la
                        // stopper avant sa destruction évite qu'elle continue de charger en
                        // arrière-plan.
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.destroy()
                        webView = null
                    }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            YoutubeTitleRow(title, watchUrl, modifier = Modifier.weight(1f))
            if (!failed) FullscreenToggle(fullscreen) {
                val wv = webView
                if (wv == null) {
                    fullscreen = !fullscreen
                } else {
                    // La page retient l'état de lecture avant le déplacement (voir
                    // `pressiaToggleFull`) ; sans page prête, on bascule directement.
                    wv.evaluateJavascript(
                        "(function(){if(window.pressiaToggleFull){window.pressiaToggleFull();}" +
                            "else if(window.PressIAHost){window.PressIAHost.fullscreen();}})()",
                        null
                    )
                }
            }
        }
    }

    // Cycle de vie : la lecture YouTube se met en pause quand l'écran est quitté, et reprend
    // au retour — sans cela, le son continuait après avoir changé d'article.
    DisposableEffect(lifecycleOwner, webView) {
        val wv = webView
        val observer = LifecycleEventObserver { _, event ->
            if (wv == null) return@LifecycleEventObserver
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> wv.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> wv.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
