package com.pressia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
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
    // Un doigt posé sur un lecteur (barre d'avancée, pincer, plein écran) lui appartient : le
    // balayage d'article ne doit pas le confisquer, sinon tirer la barre de la vidéo change
    // d'article au lieu d'avancer dans la vidéo.
    val inPlayer = remember { mutableStateOf(false) }

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
    // Hauteur visible de l'article, en pixels : la barre de défilement en déduit la longueur du
    // curseur (part visible du contenu). Mesurée après la marge, donc c'est bien la fenêtre de
    // défilement. Zéro tant que la mise en page n'a pas eu lieu : aucune barre dessinée.
    var viewportPx by remember { mutableIntStateOf(0) }

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
                        onCancel = { dragging = false; offsetX = 0f; dragX = 0f }
                    )
                    }
                }
        ) {
        // Texte sélectionnable (appui long, puis poignées ou « Tout sélectionner ») : la
        // sélection vit dans la colonne défilante, donc dans le texte lui-même, et la barre de
        // défilement comme les badges de balayage restent en dehors.
        SelectionContainer {
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                // Taille de la fenêtre de défilement, relevée là où la colonne est mesurée
                // (après la marge de page, avant `verticalScroll` qui, lui, voit le contenu
                // entier) : c'est l'échelle de la barre de défilement.
                .onSizeChanged { viewportPx = it.height }
                .verticalScroll(scrollState)
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
                        // Les liens du paragraphe sont dans le texte (annotations de lien) : la
                        // phrase reste d'un seul tenant, le lien ne passe plus à la ligne.
                        text = blockText(b, MaterialTheme.colorScheme.primary),
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
            if (fullMode || vm.readabilityOn) AssistChip({ scope.launch { toggleReadability() } }, { Text("Revenir à l'extrait RSS") }, leadingIcon = { Icon(Icons.Filled.ChromeReaderMode, null) })
            else AssistChip({ scope.launch { toggleReadability() } }, { Text("Readability : article complet") }, leadingIcon = { Icon(Icons.Filled.ChromeReaderMode, null) })
            Spacer(Modifier.height(16.dp))
            // Le bandeau applicatif (fin de synchro…) recouvre le bas de l'écran : quand il
            // est visible, un espace supplémentaire garde la pastille atteignable au scroll
            // (même convention que les boutons haut/bas : hauteur du visuel + 20 dp).
            val bannerLift = LocalBannerLift.current
            if (bannerLift > 0.dp) Spacer(Modifier.height(bannerLift + 20.dp))
        }
        }  // fin de SelectionContainer
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
    /**
     * Texte d'un bloc. Les liens qu'il contient sont **dans le flux** du texte : [links] donne
     * leurs plages (index dans [text]) et leur cible, au lieu d'un bloc à part qui ferait passer
     * le lien à la ligne au milieu de la phrase.
     */
    data class T(val text: String, val links: List<Link> = emptyList()) : ABlock
    data class I(val url: String) : ABlock
    /** Lien isolé, hors de tout paragraphe : bloc à part entière, faute de texte où le couler. */
    data class L(val text: String, val url: String) : ABlock
    /** Vidéo embarquée (balise <video> ou <iframe> YouTube/Vimeo…). */
    data class V(val url: String, val poster: String?, val embed: Boolean = false) : ABlock

    /** Plage cliquable d'un texte : [start] inclus, [end] exclu, et l'URL qu'elle ouvre. */
    data class Link(val start: Int, val end: Int, val url: String)
}

/** Tags qui font un bloc de texte : chacun est coulé d'un seul tenant, liens compris. */
private val ARTICLE_BLOCK_TAGS = setOf("p", "li", "h1", "h2", "h3", "pre", "blockquote")

/**
 * Texte d'un bloc avec ses liens : chaque plage ([ABlock.Link]) devient une **annotation de
 * lien** — couleur dominante et soulignée, comme les autres liens de l'application — que Compose
 * ouvre au tap par le gestionnaire d'URI du système (le même `ACTION_VIEW` que [openUrl]). Le
 * texte reste d'un seul tenant : c'est ce qui garde le lien dans la phrase au lieu de le faire
 * passer à la ligne. Construit une fois par texte ; la couleur du thème en fait partie.
 */
@Composable
private fun blockText(b: ABlock.T, linkColor: Color): AnnotatedString = remember(b, linkColor) {
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
        fun processFlow(root: org.jsoup.nodes.Element) {
            val sb = StringBuilder()
            val links = mutableListOf<ABlock.Link>()
            // Publie le texte accumulé. Les plages sont recalculées après rognage : celles
            // enregistrées valent pour la chaîne brute, espaces de tête compris.
            fun flush() {
                val raw = sb.toString()
                val start = raw.indexOfFirst { !it.isWhitespace() }
                if (start < 0) { sb.clear(); links.clear(); return }
                val text = raw.trim()
                val kept = links.mapNotNull { l ->
                    val s = (l.start - start).coerceIn(0, text.length)
                    val e = (l.end - start).coerceIn(0, text.length)
                    if (e > s) ABlock.Link(s, e, l.url) else null
                }
                out += ABlock.T(text, kept)
                sb.clear(); links.clear()
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
            fun walk(el: org.jsoup.nodes.Element) {
                el.childNodes().forEach { node ->
                    when {
                        node is org.jsoup.nodes.TextNode -> appendText(node.wholeText)
                        node is org.jsoup.nodes.Element -> when (node.tagName()) {
                            "br" -> sb.append('\n')
                            "a" -> {
                                val t = inlineText(node)
                                if (t.isNotBlank()) {
                                    val (lo, hi) = appendText(t)
                                    if (hi > lo) {
                                        linkUrl(node.absUrl("href").ifBlank { node.attr("href") })
                                            ?.let { links += ABlock.Link(lo, hi, it) }
                                    }
                                }
                            }
                            "img" -> {
                                flush()
                                abs(node.absUrl("src").ifBlank { node.attr("src") })?.let { out += ABlock.I(it) }
                            }
                            "video" -> {
                                flush()
                                val poster = node.attr("poster").takeIf { it.isNotBlank() }
                                abs(node.absUrl("src").ifBlank { node.attr("src") })?.let { out += ABlock.V(it, poster) }
                            }
                            "iframe" -> {
                                flush()
                                abs(node.absUrl("src").ifBlank { node.attr("src") })?.let { out += ABlock.V(it, youtubePoster(it), embed = true) }
                            }
                            // Sous-bloc (un <p> dans un <li>, deux <p> dans une citation) : chacun
                            // reste un bloc de texte à part, sans être recollé au précédent.
                            in ARTICLE_BLOCK_TAGS -> { flush(); walk(node); flush() }
                            else -> walk(node)
                        }
                    }
                }
            }
            walk(root)
            flush()
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
                        // YouTube embed : on construit une miniature d'affiche pour le bloc vidéo.
                        // C'est une PAGE web, pas un fichier média : le lecteur intégré ne peut pas
                        // la lire — le tap ouvre donc la vidéo dans le navigateur.
                        out += ABlock.V(it, youtubePoster(it), embed = true)
                    }
                }
                // Conteneur de texte : coulé d'un seul tenant, liens compris.
                else -> processFlow(el)
            }
        }
        // Un conteneur de texte imbriqué dans un autre (un <p> dans un <li>) est déjà pris par son
        // parent : le sélectionner en plus dupliquerait son texte.
        fun isNestedBlock(el: org.jsoup.nodes.Element): Boolean =
            el.parents().any { it.tagName() in ARTICLE_BLOCK_TAGS }
        doc.body()?.select("p, li, h1, h2, h3, pre, blockquote, img, video, iframe")?.forEach { el ->
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
 * URL de lecture YouTube, prête pour une WebView.
 *
 * C'est la page `m.youtube.com/watch` — et **non** la page d'intégration
 * (`youtube.com/embed/…`) : sur l'émulateur, l'intégration affiche le son sans jamais
 * composer l'image (le décodeur reçoit une surface, les images n'atteignent pas l'écran),
 * alors que la page de lecture d'un document de premier niveau rend la vidéo normalement.
 * C'est aussi ce que fait le tap sur le titre de l'intégration, et cela fonctionne.
 */
fun youtubeEmbedUrl(videoId: String): String =
    "https://m.youtube.com/watch?v=$videoId"

/**
 * En-tête `Referer` de la page de lecture YouTube.
 *
 * YouTube refuse l'intégration quand la requête ne porte pas de référent (« Erreur 152 » pour
 * un référent absent, « Erreur 153 » pour un référent invalide). Or un `loadUrl` **sans en-tête**
 * n'envoie aucun Referer (requête de premier niveau, sans page référente), et
 * `loadDataWithBaseURL` donne une **origine** mais pas de référent — la balise
 * `<meta name="referrer">` ne peut pas en inventer un. D'où `loadUrl(url, headers)` avec un
 * Referer explicite, cohérent avec l'hôte demandé.
 */
fun youtubeReferer(): String = "https://m.youtube.com/"

/**
 * Habillage de la page de lecture : on ne garde que le lecteur.
 *
 * La page `m.youtube.com/watch` est un site complet (en-tête, titre, commentaires, vidéos
 * suggérées). Dans le bloc 16/9 de l'article, on masque tout cela et on fixe le lecteur en
 * haut, à la largeur de la vue. Les sélecteurs couvrent les conteneurs connus ; s'ils changent,
 * la page reste utilisable, simplement plus bavarde.
 */
internal fun youtubePlayerCss(): String = listOf(
    "html,body{margin:0!important;padding:0!important;overflow:hidden!important;" +
        "background:#000!important;touch-action:none!important}",
    "ytm-header-renderer,ytm-app-header,ytm-mobile-topbar-renderer,#header,header," +
        "ytm-slim-video-metadata-section-renderer,ytm-watch-metadata," +
        "ytm-comment-section-renderer,.watch-below-the-player{display:none!important}",
    "#player-container-id,.player-container,ytm-player,.html5-video-player{" +
        "position:fixed!important;top:0!important;left:0!important;" +
        "width:100vw!important;height:56.25vw!important;margin:0!important;padding:0!important}" +
        // L'image, elle, est dimensionnée en pixels par le script du site : elle reste petite au
        // centre du bloc une dizaine de secondes, le temps que YouTube recalcule sa mise en page.
        // On la fixe donc au bloc, `object-fit:contain` gardant ses proportions (bandes noires).
        "video.video-stream{position:fixed!important;top:0!important;left:0!important;" +
        "width:100vw!important;height:56.25vw!important;object-fit:contain!important}" +
        // `touch-action:none` : sans cela, Chromium prend le glissement horizontal pour un
        // défilement et envoie un `pointercancel` au bout de quelques pixels, ce qui annule le
        // geste en cours (constaté sur le lecteur : plus aucun `pointermove` après ~15 px).
        ".html5-video-player,.ytp-chrome-bottom,.ytp-progress-bar-container," +
        ".ytp-progress-bar,video{touch-action:none!important}",
).joinToString("")

/**
 * Script injecté après le chargement de la page de lecture (voir [youtubePlayerCss]).
 *
 * Rejoué plusieurs fois : YouTube remplace une partie du document après coup (rendu progressif
 * des composants), et une feuille de style posée trop tôt se retrouve détachée.
 */
internal fun youtubePlayerScript(): String =
    "(function(){" +
        "var css=" + jsSingleQuoted(youtubePlayerCss()) + ";" +
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

/**
 * Contrôles du lecteur, injectés dans la page.
 *
 * Ceux du lecteur mobile YouTube **ne répondent pas** dans une WebView : leurs boutons sont
 * peints sur un canevas (le document ne contient ni `.ytp-progress-bar`, ni `.ytp-chrome-bottom`,
 * ni bouton atteignable) et aucun appui dessus n'a d'effet — ni pause, ni avance, ni la roue des
 * réglages (constaté un par un sur l'émulateur, y compris après `touch-action:none`). Vouloir
 * s'en servir, c'est n'avoir aucun contrôle du tout.
 *
 * On pose donc les nôtres, en éléments réels du document, au-dessus du lecteur :
 *  - une surface transparente sur toute la vidéo : un appui met en pause ou reprend, un double
 *    appui à gauche/droite recule/avance de 10 s. Elle intercepte aussi les appuis destinés au
 *    canevas, si bien que les contrôles morts de YouTube ne s'affichent plus ;
 *  - une barre en bas : lecture/pause, temps écoulé et durée, barre d'avancée (appui et
 *    glissement, `video.currentTime`), son, et roue des réglages ;
 *  - un menu de réglages : vitesse de lecture et lecture en boucle.
 *
 * `touch-action:none` et `preventDefault`/`stopPropagation` sont indispensables : sans eux
 * Chromium prend le glissement horizontal pour un défilement et l'annule (`pointercancel` au bout
 * de ~15 px). Le script est rejoué plusieurs fois, YouTube remplaçant une partie du document.
 */
internal fun youtubeControlsScript(): String = """
(function () {
  if (window.pressiaControls) { window.pressiaControls(); return; }
  var dragging = false;
  var userMuted = false;
  var el = {};
  var speeds = [0.5, 0.75, 1, 1.25, 1.5, 2];
  function video() { return document.querySelector('video'); }
  function fmt(s) {
    s = Math.max(0, Math.floor(s || 0));
    var m = Math.floor(s / 60), r = s % 60;
    return m + ':' + (r < 10 ? '0' : '') + r;
  }
  function paint() {
    var v = video();
    if (!v || !el.fill) return;
    // Le son est rétabli à chaque passe tant que l'utilisateur ne l'a pas coupé lui-même :
    // YouTube coupe le son d'une vidéo qui n'a pas été lancée par un geste dans la page, et le
    // recoupe en cours de lecture (changement de segment, reprise). Ici la lecture vient d'un
    // appui franc sur l'affiche, donc le son est légitime.
    if (!userMuted && (v.muted || v.volume < 0.05)) {
      v.muted = false;
      try { v.volume = 1; } catch (err) {}
    }
    var d = v.duration;
    var p = (isFinite(d) && d > 0) ? Math.min(1, v.currentTime / d) : 0;
    el.fill.style.width = (p * 100) + '%';
    el.dot.style.left = (p * 100) + '%';
    el.time.textContent = fmt(v.currentTime) + ' / ' + fmt(d);
    if (v.paused) { el.play.className = ''; } else { el.play.className = 'on'; }
    el.mute.textContent = userMuted ? '🔇' : '🔊';
  }
  function seek(x) {
    var v = video();
    if (!v || !isFinite(v.duration) || v.duration <= 0) return;
    var r = el.track.getBoundingClientRect();
    var p = Math.min(1, Math.max(0, (x - r.left) / r.width));
    v.currentTime = p * v.duration;
    paint();
  }
  function toggle() {
    var v = video();
    if (!v) return;
    if (v.paused) { v.play(); } else { v.pause(); }
    paint();
  }
  function nudge(d) {
    var v = video();
    if (!v || !isFinite(v.duration)) return;
    v.currentTime = Math.min(v.duration, Math.max(0, v.currentTime + d));
    paint();
  }
  function stop(e) { e.preventDefault(); e.stopPropagation(); }
  function speedItem(label, value) {
    var b = document.createElement('div');
    b.className = 'pressia-chip';
    b.textContent = label;
    b.dataset.value = value;
    b.addEventListener('pointerdown', function (e) { stop(e); pickSpeed(value); }, true);
    el.speedBox.appendChild(b);
  }
  function pickSpeed(value) {
    var v = video();
    if (!v) return;
    v.playbackRate = parseFloat(value);
    var items = el.speedBox.children;
    for (var i = 0; i < items.length; i++) {
      items[i].className = 'pressia-chip' +
        (items[i].dataset.value === value ? ' pressia-on' : '');
    }
    el.menu.style.display = 'none';
  }
  function build() {
    var v = video();
    if (!v) return false;
    if (!document.getElementById('pressia-css')) {
      var st = document.createElement('style');
      st.id = 'pressia-css';
      st.textContent = `
#pressia-shade{position:fixed;left:0;top:0;width:100vw;height:56.25vw;z-index:2147483640;
  background:transparent;touch-action:none}
#pressia-bar{position:fixed;left:0;right:0;bottom:0;height:50px;z-index:2147483641;
  display:flex;flex-direction:column;color:#fff;touch-action:none;-webkit-user-select:none;
  user-select:none;font-family:Roboto,Arial,sans-serif;-webkit-tap-highlight-color:transparent;
  background:linear-gradient(180deg,rgba(0,0,0,0) 0%,rgba(0,0,0,.55) 40%,rgba(0,0,0,.9) 100%)}
#pressia-row{display:flex;align-items:center;height:34px;padding:0 10px;box-sizing:border-box}
#pressia-play{position:relative;width:34px;height:34px;flex:0 0 auto;margin-right:6px}
#pressia-play::before{content:'';position:absolute;left:11px;top:9px;border-left:13px solid #fff;
  border-top:8px solid transparent;border-bottom:8px solid transparent}
#pressia-play.on::before{display:none}
#pressia-play.on::after{content:'';position:absolute;left:10px;top:9px;width:5px;height:16px;
  background:#fff;box-shadow:9px 0 0 #fff}
#pressia-time{font-size:11px;opacity:.92;white-space:nowrap;flex:0 0 auto}
#pressia-gap{flex:1 1 auto}
#pressia-mute{font-size:16px;line-height:34px;width:34px;height:34px;text-align:center;flex:0 0 auto}
#pressia-gear{font-size:16px;line-height:34px;width:34px;height:34px;text-align:center;flex:0 0 auto}
#pressia-line{position:relative;height:16px;margin:0 12px;flex:0 0 auto}
#pressia-track{position:absolute;left:0;right:0;top:6px;height:4px;border-radius:2px;
  background:rgba(255,255,255,.35)}
#pressia-fill{position:absolute;left:0;top:0;height:100%;width:0;background:#f44;border-radius:2px}
#pressia-dot{position:absolute;top:50%;left:0;width:13px;height:13px;margin:-6.5px 0 0 -6.5px;
  border-radius:50%;background:#f44}
#pressia-menu{position:fixed;right:8px;bottom:54px;z-index:2147483642;display:none;
  padding:8px 10px;border-radius:8px;background:rgba(26,26,26,.97);color:#fff;
  font-family:Roboto,Arial,sans-serif;font-size:11px;touch-action:none;
  box-shadow:0 4px 14px rgba(0,0,0,.55)}
#pressia-menu .pressia-head{font-size:9px;letter-spacing:.06em;text-transform:uppercase;
  color:rgba(255,255,255,.55);margin-bottom:3px}
#pressia-speeds{display:flex;gap:4px}
#pressia-speeds .pressia-chip{padding:5px 6px;border-radius:5px;font-size:11px;
  background:rgba(255,255,255,.14)}
#pressia-speeds .pressia-on{background:rgba(244,68,68,.9)}
#pressia-loop{display:flex;justify-content:space-between;margin-top:6px;padding-top:6px;
  border-top:1px solid rgba(255,255,255,.15)}
#pressia-loop.pressia-on{color:#f66}
.ytp-unmute{display:none!important}
`;
      (document.head || document.documentElement).appendChild(st);
    }
    if (!document.getElementById('pressia-bar')) {
      var shade = document.createElement('div');
      shade.id = 'pressia-shade';
      var bar = document.createElement('div');
      bar.id = 'pressia-bar';
      var row = document.createElement('div');
      row.id = 'pressia-row';
      var play = document.createElement('div');
      play.id = 'pressia-play';
      var time = document.createElement('div');
      time.id = 'pressia-time';
      var gap = document.createElement('div');
      gap.id = 'pressia-gap';
      var mute = document.createElement('div');
      mute.id = 'pressia-mute';
      var gear = document.createElement('div');
      gear.id = 'pressia-gear';
      gear.textContent = '⚙';
      var line = document.createElement('div');
      line.id = 'pressia-line';
      var track = document.createElement('div');
      track.id = 'pressia-track';
      var fill = document.createElement('div');
      fill.id = 'pressia-fill';
      var dot = document.createElement('div');
      dot.id = 'pressia-dot';
      track.appendChild(fill);
      track.appendChild(dot);
      line.appendChild(track);
      row.appendChild(play);
      row.appendChild(time);
      row.appendChild(gap);
      row.appendChild(mute);
      row.appendChild(gear);
      bar.appendChild(row);
      bar.appendChild(line);
      var menu = document.createElement('div');
      menu.id = 'pressia-menu';
      var head = document.createElement('div');
      head.className = 'pressia-head';
      head.textContent = 'Vitesse';
      menu.appendChild(head);
      var speedBox = document.createElement('div');
      speedBox.id = 'pressia-speeds';
      menu.appendChild(speedBox);
      var loop = document.createElement('div');
      loop.id = 'pressia-loop';
      loop.textContent = 'Lecture en boucle';
      menu.appendChild(loop);

      el.shade = shade; el.bar = bar; el.play = play; el.time = time; el.mute = mute;
      el.gear = gear; el.line = line; el.track = track; el.fill = fill; el.dot = dot;
      el.menu = menu; el.speedBox = speedBox; el.loop = loop;

      // Appui simple : lecture/pause. Double appui : -10 s à gauche, +10 s à droite.
      var downX = 0, downY = 0, downT = 0, lastTap = 0, tapTimer = null;
      shade.addEventListener('pointerdown', function (e) {
        downX = e.clientX; downY = e.clientY; downT = Date.now(); stop(e);
      }, true);
      shade.addEventListener('pointermove', function (e) { stop(e); }, true);
      shade.addEventListener('pointerup', function (e) {
        stop(e);
        // Un appui à côté du menu le referme, sans toucher à la lecture.
        if (menu.style.display === 'block') { menu.style.display = 'none'; return; }
        if (Math.abs(e.clientX - downX) + Math.abs(e.clientY - downY) > 12) return;
        if (Date.now() - downT > 700) return;
        var now = Date.now();
        if (now - lastTap < 320) {
          clearTimeout(tapTimer);
          lastTap = 0;
          nudge(e.clientX < window.innerWidth / 2 ? -10 : 10);
          return;
        }
        lastTap = now;
        tapTimer = setTimeout(toggle, 320);
      }, true);
      shade.addEventListener('pointercancel', function (e) { stop(e); }, true);

      play.addEventListener('pointerdown', function (e) { stop(e); toggle(); }, true);
      mute.addEventListener('pointerdown', function (e) {
        stop(e);
        var v2 = video();
        if (!v2) return;
        userMuted = !userMuted;
        v2.muted = userMuted;
        paint();
      }, true);
      gear.addEventListener('pointerdown', function (e) {
        stop(e);
        menu.style.display = (menu.style.display === 'block') ? 'none' : 'block';
      }, true);
      loop.addEventListener('pointerdown', function (e) {
        stop(e);
        var v3 = video();
        if (!v3) return;
        v3.loop = !v3.loop;
        loop.className = (v3.loop ? 'pressia-on' : '');
        menu.style.display = 'none';
      }, true);

      track.addEventListener('touchstart', stop, { passive: false });
      track.addEventListener('touchmove', stop, { passive: false });
      track.addEventListener('pointerdown', function (e) {
        dragging = true; seek(e.clientX); stop(e);
      }, true);
      track.addEventListener('pointermove', function (e) {
        if (dragging) seek(e.clientX);
        stop(e);
      }, true);
      track.addEventListener('pointerup', function (e) {
        dragging = false; seek(e.clientX); stop(e);
      }, true);
      track.addEventListener('pointercancel', function (e) { dragging = false; stop(e); }, true);

      for (var i = 0; i < speeds.length; i++) {
        speedItem(speeds[i] + '×', String(speeds[i]));
      }

      document.body.appendChild(shade);
      document.body.appendChild(bar);
      document.body.appendChild(menu);
      v.addEventListener('timeupdate', paint);
      v.addEventListener('play', paint);
      v.addEventListener('pause', paint);
      v.addEventListener('volumechange', paint);
      v.addEventListener('loadedmetadata', paint);
      setInterval(paint, 500);
    }
    paint();
    return true;
  }
  window.pressiaControls = function () { if (!build()) setTimeout(build, 700); };
  window.pressiaControls();
  for (var k = 1; k <= 10; k++) setTimeout(build, k * 900);
})()
"""
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
 *    petite WebView qui charge la page d'intégration (`youtube-nocookie.com`) ; pour les autres
 *    services (Vimeo, Dailymotion…), la miniature s'affiche et le tap ouvre la vidéo dans le
 *    navigateur externe.
 */
@Composable
fun VideoPlayer(url: String, poster: String?, embed: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var videoView by remember { mutableStateOf<android.widget.VideoView?>(null) }
    val playing = remember { mutableStateOf(false) }

    // Intégration YouTube : lue dans l'application, via la WebView d'intégration.
    val youtubeId = remember(url) { if (embed) youtubeVideoId(url) else null }

    // Intégration (iframe) : la vignette ouvre la vidéo dans le navigateur, pas le lecteur.
    val onTap: () -> Unit = if (embed) {
        { openUrl(context, url) }
    } else {
        { playing.value = true }
    }

    Column(modifier = modifier) {
        if (youtubeId != null) {
            // Lecteur YouTube intégré : la WebView charge le lecteur officiel.
            YoutubePlayer(videoId = youtubeId, poster = poster)
        } else if (!playing.value) {
            // Affiche (poster) : tap pour lancer la lecture ou ouvrir la vidéo.
            VideoPoster(
                poster = poster,
                label = if (embed) "Ouvrir la vidéo" else "Lancer la vidéo",
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

/**
 * Lecteur YouTube **dans l'application** : une WebView charge la page de lecture
 * (`m.youtube.com/watch?v=<id>`), qui affiche le lecteur officiel — contrôles, qualité,
 * reprise, plein écran, compte de l'utilisateur.
 *
 * Pourquoi la page de lecture et non l'intégration `embed` : sur l'émulateur, l'intégration
 * affiche le son sans jamais composer l'image (le décodeur reçoit bien une surface, aucune
 * image n'atteint l'écran), alors que la page de lecture — un document de premier niveau —
 * rend la vidéo normalement. C'est aussi le chemin qu'emprunte le tap sur le titre de
 * l'intégration, et celui qui fonctionne.
 *
 * Points d'attention :
 *  - **rien n'est chargé avant un appui** : l'affiche occupe le bloc, et la page de lecture
 *    n'est demandée qu'au tap. La page démarre d'elle-même une fois chargée : sans cette
 *    barrière, ouvrir un article suffisait à lancer la vidéo ;
 *  - JavaScript et le stockage local sont nécessaires au lecteur YouTube ;
 *  - `mediaPlaybackRequiresUserGesture = false` : sans cela, Chromium refuse de démarrer la
 *    lecture d'une vidéo déclenchée par la page ;
 *  - la page est chargée par `loadUrl(url, headers)` avec un **Referer** explicite (voir
 *    [youtubeReferer]) : un chargement sans en-tête n'en envoie aucun ;
 *  - la page du site est **habillée** par [youtubePlayerScript] : en-tête et contenus
 *    annexes masqués, lecteur fixé en haut — le bloc 16/9 ne montre que la vidéo ;
 *  - la WebView est **détruite** à la sortie (`onRelease`) : une WebView non détruite garde un
 *    contexte, un moteur de rendu et des cookies — une par article s'accumulerait ;
 *  - le zoom et le scroll horizontal sont désactivés : la WebView est dans une colonne
 *    défilable et les gestes de l'écran article (balayage vers l'article précédent/suivant)
 *    doivent rester disponibles.
 */
@Composable
fun YoutubePlayer(videoId: String, poster: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Rien ne se lit avant un appui : la page de lecture démarre d'elle-même dès qu'elle est
    // chargée (son et image), et un article qui s'ouvre ne doit pas se mettre à jouer.
    var started by remember(videoId) { mutableStateOf(false) }
    var failed by remember(videoId) { mutableStateOf(false) }
    var loaded by remember(videoId) { mutableStateOf(false) }
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    val playerUrl = remember(videoId) { youtubeEmbedUrl(videoId) }

    // Affiche seule tant que l'utilisateur n'a pas appuyé : aucune WebView, aucun réseau.
    if (!started) {
        VideoPoster(
            poster = poster,
            label = "Lire la vidéo",
            onTap = { started = true },
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )
        return
    }

    // Filet de sécurité : si la page n'a pas fini de charger (réseau coupé, WebView muette), on
    // propose l'ouverture dans le navigateur au lieu d'un écran noir. Le délai ne court qu'à
    // partir du tap — avant, il n'y a rien à charger.
    LaunchedEffect(videoId, started) {
        if (!started) return@LaunchedEffect
        kotlinx.coroutines.delay(20_000)
        if (!loaded) failed = true
    }

    if (failed) {
        // Repli : l'affiche de la vidéo, un tap l'ouvre dans YouTube (navigateur externe).
        VideoPoster(
            poster = poster,
            label = "Ouvrir la vidéo sur YouTube",
            onTap = { openUrl(context, "https://www.youtube.com/watch?v=$videoId") },
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(androidx.compose.ui.graphics.Color.Black)
            .clipToBounds()
    ) {
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    // Fond **transparent**, pas noir : au moment où elle est créée, la WebView
                    // n'a pas encore la taille de son bloc et peint alors tout l'écran en noir
                    // (constaté : une image entièrement noire à l'appui). Le noir vient de la
                    // boîte qui l'entoure, et de la page elle-même (`background:#000`, voir
                    // [youtubePlayerCss]).
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: android.webkit.WebView, url: String) {
                            view.post {
                                loaded = true
                                // La page est un site complet : on n'en garde que le lecteur.
                                view.evaluateJavascript(youtubePlayerScript(), null)
                                view.evaluateJavascript(youtubeControlsScript(), null)
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
                // `about:blank` d'abord : la WebView est dans une colonne défilable, la stopper
                // avant sa destruction évite qu'elle continue de charger en arrière-plan.
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
                webView = null
            }
        )
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
