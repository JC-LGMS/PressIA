package com.pressia.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.SubscriptionEntity
import java.text.DateFormat
import java.util.Date

/**
 * Clic court uniquement : l'appui long est consommé sans rien déclencher.
 * Un simple [androidx.compose.foundation.clickable] déclencherait quand même l'ouverture
 * de l'article au relâchement après un appui long.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.longPressInert(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = {})

/** Carte article — 4 vues : liste / carte / magazine / compacte (favicon, petits caractères). */
@Composable
fun ArticleRow(
    a: ArticleEntity,
    mode: String,
    trailing: String,
    titleSp: Int,
    dateSp: Int,
    feeds: Map<String, SubscriptionEntity>,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleStar: () -> Unit
) {
    val ctx = LocalContext.current
    val alpha = if (a.unread) 1f else 0.55f
    val src = remember(a.feedId, feeds) { prettyFeedTitle(feeds[a.feedId]?.title ?: feeds[a.feedId]?.id) }
    /**
     * Où vit le rond lu/non-lu : à gauche dans la vue magazine, et dans la vue liste seulement
     * quand l'article n'a pas d'image (la place de l'image). Partout ailleurs il est à droite.
     *
     * Cette position commande aussi celle de l'étoile du swipe : elle doit apparaître exactement
     * là où le rond se trouvait, sinon le geste déplaçait l'information d'un bout à l'autre de la
     * ligne. Elle évite au passage le double rond de la vue liste sur un article sans image.
     */
    val dotAtStart = when (mode) {
        "magazine" -> true
        "compact", "card" -> false
        else -> a.imageUrl.isNullOrBlank()
    }
    // Pendant un glissement, le rond lu/non-lu laisse la place à l'étoile du geste (dessinée
    // derrière la ligne) : une seule étoile, jamais deux.
    val trail: @Composable (Boolean) -> Unit = { swiping ->
        if (swiping) Unit else when (trailing) {
            "none" -> Unit
            "read" -> if (dotAtStart) Unit else Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                IconButton(onToggleRead, Modifier.size(30.dp)) {
                    Icon(
                        // Non lu = plein, lu = vide.
                        if (a.unread) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked,
                        if (a.unread) "marquer lu" else "marquer non lu",
                        tint = readDotTint(),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            "share" -> if (dotAtStart) Unit else IconButton({ shareArticle(ctx, a) }) { Icon(Icons.Filled.Share, "partager") }
            else -> Unit
        }
    }
    val content: @Composable (Boolean, Boolean) -> Unit = { swiping, flipped ->
    when (mode) {
        "compact" -> Row(
            Modifier.fillMaxWidth().longPressInert(onOpen)
                .padding(horizontal = 12.dp, vertical = 4.dp).alpha(alpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                // Favicon et titre dans un même Row centré : le favicon se cale ainsi sur les
                // lignes du titre seul, et non sur le centre de toute la ligne (titre + méta).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable { onToggleRead() }) { FeedAvatar(feeds[a.feedId]) }
                    Spacer(Modifier.width(10.dp))
                    Text(a.title, fontSize = titleSp.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // La méta est décalée du retrait du favicon pour rester alignée sur le titre.
                MetaLine(src, a.published, dateSp, a.starred && (!swiping || !flipped),
                    modifier = Modifier.padding(start = 38.dp))
            }
            trail(swiping)
        }
        "magazine" -> Card(Modifier.fillMaxWidth().padding(8.dp).longPressInert(onOpen)) {
            Column {
                if (!a.imageUrl.isNullOrBlank()) AsyncImage(a.imageUrl, null, Modifier.fillMaxWidth().height(180.dp).alpha(alpha), contentScale = ContentScale.Crop)
                Text(a.title, Modifier.padding(12.dp), fontSize = titleSp.sp,
                    fontWeight = if (a.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    trail(swiping); Spacer(Modifier.width(8.dp))
                    MetaLine(src, a.published, dateSp, a.starred && (!swiping || !flipped))
                }
            }
        }
        "card" -> Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).longPressInert(onOpen)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!a.imageUrl.isNullOrBlank()) AsyncImage(a.imageUrl, null, Modifier.size(72.dp).alpha(alpha), contentScale = ContentScale.Crop)
                else FeedAvatar(feeds[a.feedId], 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, fontSize = titleSp.sp,
                        fontWeight = if (a.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(alpha))
                    MetaLine(src, a.published, dateSp, a.starred && (!swiping || !flipped))
                }
                trail(swiping)
            }
        }
        else -> ListItem(
            modifier = Modifier.longPressInert(onOpen).alpha(alpha).padding(vertical = 2.dp),
            headlineContent = { Text(a.title, fontSize = titleSp.sp,
                fontWeight = FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent = { MetaLine(src, a.published, dateSp, a.starred && (!swiping || !flipped)) },
            leadingContent = {
                if (!a.imageUrl.isNullOrBlank()) Box(Modifier.clickable { onToggleRead() }) { AsyncImage(a.imageUrl, null, Modifier.size(56.dp), contentScale = ContentScale.Crop) }
                else Icon(
                    if (a.unread) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked, null,
                    tint = readDotTint(),
                    modifier = Modifier.size(14.dp).clickable(onClick = onToggleRead)
                )
            },
            trailingContent = { trail(swiping) }
        )
    }
    }
    SwipeToStar(a, onToggleStar, dotAtStart, content)
}

/**
 * Balayage d'un article vers la gauche : bascule le favori.
 *
 * Le rond lu/non-lu disparaît dès le début du glissement et laisse place à l'étoile qui reflète
 * l'état favori actuel ; dès que le seuil est franchi l'étoile bascule, et le rond revient au
 * relâchement. L'état du geste est stable (clé sur l'id seul) pour que deux balayages
 * d'affilée fonctionnent.
 *
 * [starAtStart] place l'étoile du côté du rond lu/non-lu, qui n'est pas toujours le même selon la
 * vue : l'étoile apparaît ainsi exactement là où l'information a disparu.
 */
@Composable
private fun SwipeToStar(
    a: ArticleEntity,
    onToggleStar: () -> Unit,
    starAtStart: Boolean,
    content: @Composable (Boolean, Boolean) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val toggle by rememberUpdatedState(onToggleStar)
    var dx by remember(a.id) { mutableFloatStateOf(0f) }
    var swiping by remember(a.id) { mutableStateOf(false) }
    var fired by remember(a.id) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().pointerInput(a.id) {
            detectHorizontalDragGestures(
                onDragStart = { swiping = true },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dx = (dx + amount).coerceIn(-160f, 0f)
                    // Bascule dès le franchissement du seuil (et non au relâchement), une seule
                    // fois par geste : le drapeau est remis si l'utilisateur revient en arrière.
                    if (!fired && dx <= -72f) { fired = true; toggle() }
                    else if (fired && dx > -72f) fired = false
                },
                onDragEnd = { dx = 0f; swiping = false; fired = false },
                onDragCancel = { dx = 0f; swiping = false; fired = false }
            )
        }
    ) {
        if (dx < -8f) {
            Row(
                Modifier.matchParentSize().background(primary.copy(alpha = 0.18f)),
                horizontalArrangement = if (starAtStart) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (a.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (a.starred) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = primary,
                    modifier = Modifier
                        .padding(start = if (starAtStart) 22.dp else 0.dp, end = if (starAtStart) 0.dp else 28.dp)
                        .size(18.dp)
                )
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = dx }) { content(swiping, fired) }
    }
}

/**
 * Ligne de méta d'un article : source à gauche (suivie d'une petite étoile si l'article est
 * favori), heure seule à droite.
 *
 * L'heure est ancrée au bord droit en plaçant le bloc source et son étoile dans un enfant qui
 * occupe toute la largeur restante. Les deux se partageant la largeur, une source courte laissait
 * l'heure dériver vers le centre.
 */
@Composable
private fun MetaLine(
    source: String,
    published: Long,
    dateSp: Int,
    starred: Boolean,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                source, fontSize = dateSp.sp, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (starred) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Filled.Star, "favori", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((dateSp + 2).dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(fmtTime(published), fontSize = dateSp.sp, color = color, maxLines = 1)
    }
}

/**
 * Couleur du rond lu / non lu.
 *
 * Le rond doit signaler l'état sans jamais dominer la ligne : on part du contour du thème et on
 * l'éclaircit franchement. En thème sombre, le contour seul était quasi invisible ; en thème
 * clair, il tires vers le gris plombe et paraissait « sale ».
 */
@Composable
fun readDotTint(): Color {
    val cs = MaterialTheme.colorScheme
    return if (cs.background.luminance() < 0.5f) lerp(cs.outline, cs.onSurface, 0.55f) else cs.outlineVariant
}

/** Favicon de l'abonnement : icône fournie (Feedly/Inoreader), sinon favicon du site via son host, sinon initiale. */
fun feedFaviconUrl(sub: SubscriptionEntity?): String? {
    if (sub == null) return null
    if (!sub.iconUrl.isNullOrBlank()) return sub.iconUrl
    val host = listOf(sub.siteUrl, sub.url, sub.id).firstNotNullOfOrNull { raw ->
        runCatching {
            java.net.URI(raw.trim()).host?.removePrefix("www.")?.takeIf { it.contains(".") }
        }.getOrNull()
    } ?: return null
    // Service de favicons (mise en cache hors-ligne par Coil après le 1er chargement)
    return "https://www.google.com/s2/favicons?domain=$host&sz=128"
}

/** Favicon de l'abonnement, sinon pastille avec l'initiale (100 % hors-ligne). */
@Composable
fun FeedAvatar(sub: SubscriptionEntity?, sizeDp: Int = 28) {
    FeedAvatarContent(prettyFeedTitle(sub?.title), feedFaviconUrl(sub), sizeDp)
}

@Composable
private fun FeedAvatarContent(feedTitle: String?, iconUrl: String?, sizeDp: Int = 28) {
    val s = sizeDp.dp
    if (!iconUrl.isNullOrBlank()) {
        // En thème sombre, les favicons clairs (fond blanc) agressent l'œil : on les assombrit.
        val night = MaterialTheme.colorScheme.background.luminance() < 0.5f
        AsyncImage(
            iconUrl, feedTitle,
            Modifier.size(s).clip(CircleShape),
            contentScale = ContentScale.Crop,
            colorFilter = if (night) ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.78f, 0.78f, 0.78f, 1f) }) else null
        )
    } else {
        val letter = prettyFeedTitle(feedTitle).firstOrNull()?.uppercase() ?: "•"
        val bg = MaterialTheme.colorScheme.primaryContainer
        Box(Modifier.size(s).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Text(letter, fontSize = (sizeDp / 2).sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

fun fmtDate(ts: Long): String = try {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))
} catch (_: Exception) { "" }

/** Heure seule (« 14:32 ») : le jour est porté par l'en-tête de groupe du flux. */
fun fmtTime(ts: Long): String = try {
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
} catch (_: Exception) { "" }

/** Date seule (« 5/10/26 »), sans l'heure. */
fun fmtDay(ts: Long): String = try {
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
} catch (_: Exception) { "" }

/** Clé de jour local (AAAA-MM-JJ) : sert à regrouper les articles par journée. */
fun dayKey(ts: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ts
    return "%04d-%02d-%02d".format(
        c.get(java.util.Calendar.YEAR),
        c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH)
    )
}

/** Libellé lisible d'une clé de jour : « Aujourd'hui », « Hier », sinon la date complète. */
fun dayLabel(key: String, now: Long = System.currentTimeMillis()): String = when (key) {
    dayKey(now) -> "Aujourd'hui"
    dayKey(now - 86400_000L) -> "Hier"
    else -> try {
        val p = key.split("-").map { it.toInt() }
        val c = java.util.Calendar.getInstance()
        c.clear(); c.set(p[0], p[1] - 1, p[2])
        DateFormat.getDateInstance(DateFormat.FULL).format(c.time)
    } catch (_: Exception) { key }
}

/** Deux URL d'image désignent-elles la même image (CDN, paramètres de requête) ? */
fun isSameImage(a: String, b: String): Boolean {
    fun norm(u: String): String {
        var x = u.trim().substringBefore("?")
        if (x.startsWith("//")) x = "https:$x"
        x = x.substringAfter("://").removePrefix("www.")
        return x.substringAfterLast('/').lowercase()
    }
    val na = norm(a); val nb = norm(b)
    return na.isNotBlank() && na == nb
}

/** Élément de liste groupé par jour (en-tête collant façon Press). */
sealed interface StreamItem {
    data class Day(val key: String, val label: String) : StreamItem
    data class Art(val article: ArticleEntity) : StreamItem
}

/** Articles (triés du plus récent au plus ancien) + en-têtes de jour intercalés. */
fun groupByDay(articles: List<ArticleEntity>, now: Long = System.currentTimeMillis()): List<StreamItem> {
    val out = ArrayList<StreamItem>(articles.size + 8)
    var last: String? = null
    articles.forEach { a ->
        val k = dayKey(a.published)
        if (k != last) { out += StreamItem.Day(k, dayLabel(k, now)); last = k }
        out += StreamItem.Art(a)
    }
    return out
}

/** Nom d'origine lisible : « https://www.lemonde.fr/rss/une.xml » → « lemonde.fr ». */
fun prettyFeedTitle(title: String?): String {
    if (title.isNullOrBlank()) return ""
    val t = title.trim()
    return try {
        val host = java.net.URI(t).host
        if (host != null) host.removePrefix("www.") else t
    } catch (_: Exception) { t }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleList(articles: List<ArticleEntity>, mode: String, onOpen: (ArticleEntity) -> Unit, vm: PressVM) {
    val trailing by vm.trailingAction.collectAsState()
    val titleSp by vm.titleFont.collectAsState()
    val dateSp by vm.dateFont.collectAsState()
    val feeds = remember(vm.subs) { vm.subs.associateBy { it.id } }
    val row: @Composable (ArticleEntity) -> Unit = { a ->
        ArticleRow(a, mode, trailing, titleSp, dateSp, feeds,
            { onOpen(a) }, { vm.setRead(a.id, !a.unread) }, { vm.setStar(a.id, !a.starred) })
    }
    if (mode == "magazine") {
        LazyVerticalGrid(GridCells.Adaptive(280.dp), contentPadding = PaddingValues(4.dp)) {
            items(articles, key = { it.id }) { a -> row(a) }
        }
    } else {
        val now = remember(articles) { System.currentTimeMillis() }
        val grouped = remember(articles, now) { groupByDay(articles, now) }
        LazyColumn {
            grouped.forEach { si ->
                when (si) {
                    is StreamItem.Day -> stickyHeader(key = "day-${si.key}") { DayHeader(si.label) }
                    is StreamItem.Art -> item(key = "art-${si.article.id}") { row(si.article) }
                }
            }
        }
    }
}

/** Séparateur de jour collant en tête de groupe. */
@Composable
private fun DayHeader(label: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

/** Partage système d'un article (comme Press). */
fun shareArticle(ctx: android.content.Context, a: ArticleEntity) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, a.title)
        putExtra(Intent.EXTRA_TEXT, "${a.title}\n${a.url}")
    }
    ctx.startActivity(Intent.createChooser(i, "Partager l'article"))
}

/** Petit toast système. */
fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}
