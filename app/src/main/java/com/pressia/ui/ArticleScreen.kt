package com.pressia.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pressia.data.local.ArticleEntity
import kotlinx.coroutines.launch

/** Écran article : extrait RSS, double-tap → article complet (Readability), partage système. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val justify by vm.justify.collectAsState()
    val titleSp by vm.articleTitle.collectAsState()
    // Icônes configurées en Réglages, dans l'ordre choisi (le menu reste toujours en dernier).
    val topActions by vm.articleActions.collectAsState()

    // Voisins dans la liste courante (ordre du plus récent au plus ancien) : le balayage
    // horizontal navigue vers eux. Hors liste (recherche, favoris), pas de voisin.
    val ids = remember(vm.articles) { vm.articles.map { it.id } }
    val idx = ids.indexOf(articleId)
    val prevId = if (idx > 0) ids[idx - 1] else null
    val nextId = if (idx >= 0 && idx < ids.lastIndex) ids[idx + 1] else null
    var offsetX by remember(articleId) { mutableFloatStateOf(0f) }
    var dragging by remember(articleId) { mutableStateOf(false) }
    // Le défilement repart du haut à chaque article : sans clé, le voisin ouvert par balayage
    // s'affichait à la position de l'article quitté.
    val scrollState = remember(articleId) { ScrollState(0) }

    /** Contenu RSS d'origine (jamais le HTML complet, pour éviter les allers-retours dégradés). */
    fun rssHtml(a: ArticleEntity?): String? = a?.summary?.takeIf { it.isNotBlank() } ?: a?.content

    LaunchedEffect(articleId) {
        val a = vm.repo.article(articleId)
        article = a
        // N'affiche jamais un cache vérolé (anti-robot/paywall) : repli sur l'extrait RSS.
        val usable = com.pressia.data.readability.Readability.isUsableFull(a?.content)
        html = if (usable) a?.content else rssHtml(a)
        fullMode = (a?.fullFetched == true) && usable
        if (a != null) {
            feedName = vm.repo.feedTitle(a.feedId)
            feedSub = vm.repo.subscription(a.feedId)
            if (a.unread) {
                vm.setRead(a.id, false)
                article = a.copy(unread = false)
            }
        }
    }
    val start = remember { System.currentTimeMillis() }
    DisposableEffect(articleId) {
        onDispose {
            val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
            val cur = article
            if (cur != null) vm.logReading(cur.id, cur.feedId, secs)
        }
    }

    suspend fun loadFull() {
        val a = article ?: return
        loadingFull = true
        try {
            val res = vm.repo.ensureFull(a.id)
            blockedNotice = res.blocked
            if (!res.html.isNullOrBlank()) { html = res.html; fullMode = !res.blocked }
            toast(
                ctx,
                when {
                    res.blocked -> "Extraction bloquée par le site — extrait RSS conservé"
                    fullMode -> "Article complet affiché"
                    else -> "Contenu complet indisponible"
                }
            )
        } finally { loadingFull = false }
    }

    /** Double-tap : bascule entre la version complète et l'extrait RSS. */
    suspend fun toggleMode() {
        val a = article ?: return
        if (fullMode) {
            html = rssHtml(a)
            fullMode = false
            blockedNotice = false
            toast(ctx, "Extrait RSS affiché")
        } else {
            loadFull()
        }
    }

    Scaffold(
        topBar = {
            Column {
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
                                "instapaper" -> IconButton({ article?.let { vm.sendOrAskInstapaper(it) { toast(ctx, it) } } }) {
                                    Icon(Icons.Filled.BookmarkAdd, "envoyer vers Instapaper")
                                }
                                "star" -> IconButton({ article?.let {
                                    // Même étoile effective que dans les listes : « Read later » pour
                                    // Feedly (pas de favori local), étoile locale ailleurs.
                                    val on = vm.starState(it)
                                    vm.setStar(it.id, !on)
                                    article = if (vm.isFeedlyArticle(it)) it.copy(laterSaved = !on)
                                    else it.copy(starred = !on)
                                    toast(ctx, if (!on) "Ajouté aux favoris" else "Retiré des favoris")
                                } }) {
                                    // Favori dans la couleur dominante de l'application.
                                    Icon(
                                        if (article?.let { vm.starState(it) } == true) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        "favori", tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                "read" -> IconButton({ article?.let {
                                    vm.setRead(it.id, !it.unread); article = article?.copy(unread = !it.unread)
                                    toast(ctx, if (!it.unread) "Marqué comme non lu" else "Marqué comme lu")
                                } }) {
                                    Icon(
                                        // Non lu = plein, lu = vide. Pastille franche (plus sombre que le
                                        // rond des listes) : c'est un bouton d'action, pas un indicateur.
                                        if (article?.unread == true) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked,
                                        if (article?.unread == true) "marquer lu" else "marquer non lu",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                "share" -> IconButton({ article?.let { shareArticle(ctx, it) } }) { Icon(Icons.Filled.Share, "partager") }
                            }
                        }
                        IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") }
                    }
                )
                if (vm.syncing) SyncProgress()
            }
        }
    ) { pad ->
        // Balayage horizontal : le contenu suit le doigt, puis ouvre le voisin au relâchement.
        // Gauche → article suivant (plus ancien), droite → précédent (plus récent).
        Box(
            Modifier.padding(pad).fillMaxSize()
                .pointerInput(prevId, nextId) {
                    if (prevId != null || nextId != null) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val hasNeighbor = (amount < 0 && nextId != null) || (amount > 0 && prevId != null)
                            if (hasNeighbor) {
                                val lo = if (prevId == null) -600f else -600f
                                val hi = if (nextId == null) 600f else 600f
                                offsetX = (offsetX + amount).coerceIn(lo, hi)
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            when {
                                offsetX <= -120f && nextId != null -> vm.selectedArticleId = nextId
                                offsetX >= 120f && prevId != null -> vm.selectedArticleId = prevId
                                else -> offsetX = 0f
                            }
                        },
                        onDragCancel = { dragging = false; offsetX = 0f }
                    )
                    }
                }
        ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState)
                .graphicsLayer { translationX = offsetX }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scope.launch { toggleMode() } })
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
            val heroUrl = article?.imageUrl?.takeIf { it.startsWith("http") }
            val heroDuplicate = heroUrl != null && blocks.any { it is ABlock.I && isSameImage(it.url, heroUrl) }
            if (heroUrl != null && !fullMode && !heroDuplicate) {
                coil.compose.AsyncImage(heroUrl, null,
                    Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .clickable { viewerUrl = heroUrl },
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
            }
            blocks.forEach { b ->
                when (b) {
                    is ABlock.T -> Text(
                        text = b.text,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize + 6).sp,
                        style = if (justify) MaterialTheme.typography.bodyLarge.copy() else MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    is ABlock.I -> coil.compose.AsyncImage(b.url, null,
                        Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            .clickable { viewerUrl = b.url },
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
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
            if (fullMode) AssistChip({ scope.launch { toggleMode() } }, { Text("Revenir à l'extrait RSS") }, leadingIcon = { Icon(Icons.Filled.Article, null) })
            else AssistChip({ scope.launch { loadFull() } }, { Text("Double-tap : article complet") }, leadingIcon = { Icon(Icons.Filled.AutoStories, null) })
            Spacer(Modifier.height(16.dp))
        }
        // Flèches de direction pendant le geste : confirment le voisin visé.
        if (dragging && offsetX < -40f && nextId != null) {
            Icon(
                Icons.Filled.ChevronRight, "article suivant",
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (dragging && offsetX > 40f && prevId != null) {
            Icon(
                Icons.Filled.ChevronLeft, "article précédent",
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        }
    }
    viewerUrl?.let { ImageViewer(it) { viewerUrl = null } }
}

/** Visionneuse plein écran : tap pour fermer, pincement pour zoomer. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(url: String, onClose: () -> Unit) {
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
                url, null,
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

/** Blocs de contenu ordonnés (texte / images) pour un rendu fidèle avec images inline. */
sealed interface ABlock {
    data class T(val text: String) : ABlock
    data class I(val url: String) : ABlock
}

fun articleBlocks(html: String, baseUrl: String): List<ABlock> {
    return try {
        val doc = org.jsoup.Jsoup.parse(html, baseUrl)
        val out = mutableListOf<ABlock>()
        val seen = mutableSetOf<String>()
        fun abs(u: String): String? {
            var x = u.trim()
            if (x.startsWith("//")) x = "https:$x"
            if (!x.startsWith("http")) return null
            if (!seen.add(x)) return null
            return x
        }
        doc.body()?.select("p, li, h1, h2, h3, pre, blockquote, img")?.forEach { el ->
            if (el.tagName() == "img") {
                val u = el.absUrl("src").ifBlank { el.attr("src") }
                abs(u)?.let { out += ABlock.I(it) }
            } else {
                val t = el.text().trim()
                if (t.length >= 2) out += ABlock.T(t)
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
