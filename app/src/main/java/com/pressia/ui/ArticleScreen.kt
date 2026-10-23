package com.pressia.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleEventObserver
import com.pressia.data.local.ArticleEntity
import kotlinx.coroutines.launch

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
fun ArticleScreen(vm: PressVM, articleId: String, onBack: () -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var article by remember { mutableStateOf<ArticleEntity?>(null) }
    var feedName by remember { mutableStateOf<String?>(null) }
    var feedSub by remember { mutableStateOf<com.pressia.data.local.SubscriptionEntity?>(null) }
    var viewerUrl by remember { mutableStateOf<String?>(null) }
    var html by remember { mutableStateOf<String?>(null) }
    var fullMode by remember { mutableStateOf(false) }
    var loadingFull by remember { mutableStateOf(false) }
    var blockedNotice by remember { mutableStateOf(false) }
    val fontSize by vm.fontSize.collectAsState()
    val titleSp by vm.articleTitle.collectAsState()
    // Icônes configurées en Réglages, dans l'ordre choisi (le menu reste toujours en dernier).
    val topActions by vm.articleActions.collectAsState()
    // Gestes configurés en Réglages (double-tap, balayages gauche/droite).
    val doubleTap by vm.doubleTapAction.collectAsState()
    val swipeLeft by vm.articleSwipeLeft.collectAsState()
    val swipeRight by vm.articleSwipeRight.collectAsState()

    // Voisins dans la liste courante (ordre du plus récent au plus ancien) : le balayage
    // horizontal navigue vers eux. Figés à l'ouverture : la liste peut se reconstruire en
    // fond (synchro, filtre), le swipe de l'article en cours ne doit pas mourir avec elle.
    // Hors liste (recherche, favoris), pas de voisin. Rattrapés tant qu'ils sont vides : à la
    // reprise après mort du processus, `vm.articles` arrive après la première composition —
    // sans ce rattrapage, plus aucun balayage de navigation pour cet article.
    var ids by remember(articleId) { mutableStateOf(vm.articles.map { it.id }) }
    LaunchedEffect(articleId, vm.articles) {
        if (ids.isEmpty()) ids = vm.articles.map { it.id }
    }
    val idx = ids.indexOf(articleId)
    val prevId = if (idx > 0) ids[idx - 1] else null
    val nextId = if (idx >= 0 && idx < ids.lastIndex) ids[idx + 1] else null
    var offsetX by remember(articleId) { mutableFloatStateOf(0f) }
    // Course du doigt réellement parcourue, dans les deux sens et même quand le geste n'a rien à
    // faire (voisin absent) : c'est elle qui déclenche le message « pas d'article plus ancien ».
    var dragX by remember(articleId) { mutableFloatStateOf(0f) }
    var dragging by remember(articleId) { mutableStateOf(false) }
    // Le défilement repart du haut à chaque article : sans clé, le voisin ouvert par balayage
    // s'affichait à la position de l'article quitté.
    val scrollState = remember(articleId) { ScrollState(0) }

    /**
     * Extraction de l'article complet (voir `ensureFull`). En ouverture automatique (mode
     * épinglé), pas de toast de succès : un toast par article ouvert serait du bruit — seul
     * l'échec reste visible (pastille + message).
     */
    suspend fun loadFull(quiet: Boolean = false) {
        val a = article ?: return
        loadingFull = true
        try {
            val res = vm.repo.ensureFull(a.id)
            blockedNotice = res.blocked
            if (!res.html.isNullOrBlank()) { html = res.html; fullMode = !res.blocked }
            if (!quiet) {
                toast(
                    ctx,
                    when {
                        res.blocked -> "Extraction bloquée par le site — extrait RSS conservé"
                        fullMode -> "Article complet affiché"
                        else -> "Contenu complet indisponible"
                    }
                )
            }
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
            blockedNotice = false
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
            // Même étoile effective que dans les listes : « Read later » pour
            // Feedly (pas de favori local), étoile locale ailleurs.
            val on = vm.starState(it)
            vm.setStar(it.id, !on)
            article = if (vm.isFeedlyArticle(it)) it.copy(laterSaved = !on)
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
                            IconButton({ onBack() }) { Icon(Icons.Filled.ArrowBack, "retour") }
                            FeedAvatar(feedSub, 28)
                        }
                    },
                    actions = {
                        topActions.forEach { id ->
                            when (id) {
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
                                        Icons.Filled.ChromeReaderMode, "article complet",
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
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; dragX = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            // Course brute du doigt : sert au relâchement, même quand le contenu ne
                            // suit pas (voir `dragX`).
                            dragX = (dragX + amount).coerceIn(-600f, 600f)
                            val go = if (amount < 0) (!leftNav && swipeLeft != "none") || (leftNav && nextId != null)
                            else (!rightNav && swipeRight != "none") || (rightNav && prevId != null)
                            // Le contenu ne suit le doigt que si le geste aboutit à quelque chose.
                            offsetX = if (go) dragX else 0f
                        },
                        onDragEnd = {
                            dragging = false
                            when {
                                dragX <= -120f && leftNav && nextId != null -> vm.selectedArticleId = nextId
                                dragX >= 120f && rightNav && prevId != null -> vm.selectedArticleId = prevId
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
                        onDragCancel = { dragging = false; offsetX = 0f; dragX = 0f }
                    )
                    }
                }
        ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)
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
                if (!feedName.isNullOrBlank()) {
                    Text(prettyFeedTitle(feedName), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
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
            if (blockedNotice) {
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "Extraction impossible sur ce site (protection anti-robot, abonnement ou page JavaScript). " +
                            "L'extrait RSS est conservé — ouvrez l'article dans le navigateur pour le lire en entier.",
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
                coil.compose.AsyncImage(thumbModel(ctx, heroUrl), null,
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .combinedClickable(
                            onClick = { viewerUrl = heroUrl },
                            onDoubleClick = { fireArticleAction(doubleTap) }
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
            }
            blocks.forEach { b ->
                when (b) {
                    is ABlock.T -> Text(
                        text = b.text,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 6).sp,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    is ABlock.I -> coil.compose.AsyncImage(thumbModel(ctx, b.url), null,
                        Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .combinedClickable(
                                onClick = { viewerUrl = b.url },
                                onDoubleClick = { fireArticleAction(doubleTap) }
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
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
                    is ABlock.V -> VideoPlayer(url = b.url, poster = b.poster, modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth())
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
            if (fullMode || vm.readabilityOn) AssistChip({ scope.launch { toggleReadability() } }, { Text("Revenir à l'extrait RSS") }, leadingIcon = { Icon(Icons.Filled.ChromeReaderMode, null) })
            else AssistChip({ scope.launch { toggleReadability() } }, { Text("Readability : article complet") }, leadingIcon = { Icon(Icons.Filled.ChromeReaderMode, null) })
            Spacer(Modifier.height(16.dp))
            // Le bandeau applicatif (fin de synchro…) recouvre le bas de l'écran : quand il
            // est visible, un espace supplémentaire garde la pastille atteignable au scroll
            // (même convention que les boutons haut/bas : hauteur du visuel + 20 dp).
            val bannerLift = LocalBannerLift.current
            if (bannerLift > 0.dp) Spacer(Modifier.height(bannerLift + 20.dp))
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
    }
    viewerUrl?.let { ImageViewer(it) { viewerUrl = null } }
}

/**
 * Icône et libellé d'une action de geste d'article non navigante, pendant le balayage :
 * mêmes visuels que la bande découverte des balayages de liste (voir `ArticleRow`), pour la
 * même action. `null` pour « Aucun » (ou une valeur inconnue) : rien à annoncer.
 */
private fun gestureActionIcon(act: String, starred: Boolean, unread: Boolean): Pair<androidx.compose.ui.graphics.vector.ImageVector, String>? = when (act) {
    "readability" -> Icons.Filled.ChromeReaderMode to "Readability"
    "read" -> (if (unread) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked) to
        (if (unread) "marquer lu" else "marquer non lu")
    "share" -> Icons.Filled.Share to "partager"
    "instapaper" -> Icons.Filled.BookmarkAdd to "envoyer vers Instapaper"
    "star" -> (if (starred) Icons.Filled.Star else Icons.Filled.StarBorder) to
        (if (starred) "Retirer des favoris" else "Ajouter aux favoris")
    else -> null
}

/** Visionneuse plein écran : tap pour fermer, pincement pour zoomer. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClose() })
                }
        ) {
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
                            if (scale > 1f) offset += pan else offset = androidx.compose.ui.geometry.Offset.Zero
                        }
                    ),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }
}

fun stripHtml(html: String): String {
    return try { org.jsoup.Jsoup.parse(html).text().replace(Regex(" +"), " ").trim() } catch (_: Exception) { html }
}

/** Blocs de contenu ordonnés (texte / images / liens / vidéos) pour un rendu fidèle. */
sealed interface ABlock {
    data class T(val text: String) : ABlock
    data class I(val url: String) : ABlock
    /** Lien hypertexte : le texte à afficher et l'URL cible (ouvre le navigateur externe). */
    data class L(val text: String, val url: String) : ABlock
    /** Vidéo embarquée (balise <video> ou <iframe> YouTube/Vimeo…). */
    data class V(val url: String, val poster: String?) : ABlock
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
        fun youtubePoster(url: String): String? {
            val id = url.substringAfter("youtube.com/embed/").takeIf { it.length != url.length }
                ?: url.substringAfter("youtu.be/").takeIf { it.length != url.length }
                ?: url.substringAfter("youtube.com/v/").takeIf { it.length != url.length }
            return id?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }
        }
        // Les éléments de bloc (p, li, h…) sont traités dans l'ordre du document. Leurs enfants
        // texte, liens (<a>) et images (<img>) produisent les blocs correspondants, préservant
        // l'ordre d'apparition. Les vidéos (<video>, <iframe>) créent un bloc ABlock.V.
        fun processBlock(el: org.jsoup.nodes.Element) {
            when (el.tagName()) {
                "img" -> {
                    val u = el.absUrl("src").ifBlank { el.attr("src") }
                    abs(u)?.let { out += ABlock.I(it) }
                }
                "a" -> {
                    val t = el.text().trim()
                    val href = el.absUrl("href").ifBlank { el.attr("href") }
                    if (t.length >= 2) abs(href)?.let { out += ABlock.L(t, it) }
                }
                "video" -> {
                    // Poster : attribut poster, ou première <source>, ou null.
                    val poster = el.attr("poster").takeIf { it.isNotBlank() }
                        ?: el.select("source").firstOrNull()?.let { it.absUrl("src").takeIf { it.isNotBlank() } }
                        ?: el.select("source").firstOrNull()?.let { it.attr("src").takeIf { it.isNotBlank() } }
                    val src = el.absUrl("src").ifBlank { el.attr("src") }
                    val u = abs(src)
                    if (u != null) out += ABlock.V(u, poster)
                    else poster?.let { out += ABlock.V(it, null) } // vidéo sans src exploitable : poster seul
                }
                "iframe" -> {
                    val src = el.absUrl("src").ifBlank { el.attr("src") }
                    abs(src)?.let {
                        // YouTube embed : on construit une miniature d'affiche pour le bloc vidéo.
                        out += ABlock.V(it, youtubePoster(it))
                    }
                }
                else -> {
                    el.childNodes().forEach { node ->
                        when (node) {
                            is org.jsoup.nodes.TextNode -> {
                                val t = node.text().trim()
                                if (t.length >= 2) out += ABlock.T(t)
                            }
                            is org.jsoup.nodes.Element -> {
                                when (node.tagName()) {
                                    "a" -> {
                                        val t = node.text().trim()
                                        val href = node.absUrl("href").ifBlank { node.attr("href") }
                                        if (t.length >= 2) abs(href)?.let { out += ABlock.L(t, it) }
                                    }
                                    "img" -> {
                                        val u = node.absUrl("src").ifBlank { node.attr("src") }
                                        abs(u)?.let { out += ABlock.I(it) }
                                    }
                                    "video" -> {
                                        val poster = node.attr("poster").takeIf { it.isNotBlank() }
                                        val src = node.absUrl("src").ifBlank { node.attr("src") }
                                        abs(src)?.let { out += ABlock.V(it, poster) }
                                    }
                                    "iframe" -> {
                                        val src = node.absUrl("src").ifBlank { node.attr("src") }
                                        abs(src)?.let { out += ABlock.V(it, youtubePoster(it)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        doc.body()?.select("p, li, h1, h2, h3, pre, blockquote, img, video, iframe")?.forEach { processBlock(it) }
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
 * Affiche une miniature (poster) cliquable avant lecture, puis un [android.widget.VideoView]
 * piloté par le cycle de vie Compose : pause sur ON_STOP/ON_DESTROY, arrêt complet à la
 * sortie de la composition. Si le flux fournit une miniature (poster), elle est affichée en
 * attendant que l'utilisateur lance la lecture — la vidéo ne démarre jamais en autoplay
 * (économie de données, respect du lecteur).
 */
@Composable
fun VideoPlayer(url: String, poster: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val videoView = remember { android.widget.VideoView(context) }
    val playing = remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (!playing.value && poster != null) {
            // Miniature d'affiche : tap pour lancer la lecture.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .clickable {
                        videoView.setVideoURI(android.net.Uri.parse(url))
                        videoView.start()
                        playing.value = true
                    }
            ) {
                coil.compose.AsyncImage(
                    thumbModel(context, poster),
                    null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Icon(
                    imageVector = Icons.Filled.PlayCircleFilled,
                    contentDescription = "Lancer la vidéo",
                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(56.dp)
                        .align(androidx.compose.ui.Alignment.Center)
                )
            }
        } else {
            AndroidView(
                factory = { videoView },
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(16f / 9f)
                    .background(androidx.compose.ui.graphics.Color.Black)
            ) { view: android.widget.VideoView ->
                if (!playing.value) {
                    view.setVideoURI(android.net.Uri.parse(url))
                    playing.value = true
                }
            }
            // Cycle de vie : pause quand l'écran est quitté, arrêt complet à la destruction.
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_STOP,
                        androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> videoView.pause()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    videoView.stopPlayback()
                }
            }
        }
    }
}
