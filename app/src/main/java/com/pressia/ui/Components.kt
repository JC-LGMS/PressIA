package com.pressia.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.SubscriptionEntity
import com.pressia.data.local.ThumbStore
import com.pressia.data.remote.cleanTitle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

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
    tapFaviconRead: Boolean,
    titleSp: Int,
    dateSp: Int,
    feeds: Map<String, SubscriptionEntity>,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleStar: () -> Unit,
    showSource: Boolean = true,
    onInstapaper: () -> Unit = {},
    swipe: String = "star",
    swipeRight: String = "read"
) {
    val ctx = LocalContext.current
    // Lus bien estompés, mais encore lisibles.
    val alpha = if (a.unread) 1f else 0.48f
    val src = remember(a.feedId, feeds) { prettyFeedTitle(feeds[a.feedId]?.title ?: feeds[a.feedId]?.id) }
    // Titres déjà en base avec des entités HTML (« &nbsp; », …) : nettoyés à l'affichage.
    val title = remember(a.title) { cleanTitle(a.title).ifBlank { "(sans titre)" } }
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
    /**
     * Effet d'un appui sur le favicon du flux ou sur la vignette de l'article, identique dans les
     * quatre vues. L'option « Favicon/miniature marque lu/non lu » éteinte ne doit laisser ni zone
     * morte ni écart avec le reste de la ligne : l'appui ouvre alors l'article, comme le titre.
     * [longPressInert] et non un simple [clickable], pour qu'un appui long n'ouvre rien, comme sur
     * le reste de la ligne.
     */
    val leadTap: () -> Unit = if (tapFaviconRead) ({ onToggleRead() }) else ({ onOpen() })
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
            "instapaper" -> if (dotAtStart) Unit else IconButton(onInstapaper) { Icon(Icons.Filled.BookmarkAdd, "envoyer vers Instapaper") }
            "star" -> if (dotAtStart) Unit else IconButton(onToggleStar) {
                Icon(
                    if (a.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                    if (a.starred) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            else -> Unit
        }
    }
    /**
     * Gouttière tenue par l'action de fin. Elle sert à deux choses : la méta doit s'arrêter au bord
     * du bouton (et non en dessous), et l'étoile de swipe doit se superposer exactement à lui —
     * d'où une marge de (moitié de gouttière - 9 dp), les 9 dp étant le rayon de l'icône de 18 dp.
     */
    val trailW = when (trailing) { "read" -> 30.dp; "share", "instapaper", "star" -> 48.dp; else -> 0.dp }
    // L'étoile est centrée sur la largeur totale de la ligne, bord droit à l'écran : sa marge doit
    // donc valoir le padding de la ligne + la moitié de gouttière - le rayon de l'icône (9 dp).
    // Sinon elle dérive d'autant vers l'extérieur que le padding.
    val starEnd = if (mode == "compact" && trailW > 0.dp) 12.dp + trailW / 2 - 9.dp else 28.dp
    val density = LocalDensity.current
    // Hauteur du bloc de méta, mesurée au rendu : elle permet de remonter l'étoile de swipe sur le
    // bloc titre, puisque la ligne compacte est plus haute que le titre.
    var metaH by remember(a.id) { mutableStateOf(0.dp) }
    val content: @Composable (Boolean, Boolean) -> Unit = { swiping, flipped ->
        // L'étoile de la méta ne s'efface pendant le geste que si le geste pilote les favoris,
        // dans un sens ou dans l'autre.
        val metaStar = a.starred && ((swipe != "star" && swipeRight != "star") || !swiping || !flipped)
    when (mode) {
        "compact" -> Column(
            Modifier.fillMaxWidth().longPressInert(onOpen)
                .padding(horizontal = 12.dp, vertical = 6.dp).alpha(alpha)
        ) {
            // Favicon, titre et action de fin partagent le même Row centré : tout se cale sur les
            // lignes du titre seul, et non sur le centre de la ligne entière (titre + méta).
            Row(verticalAlignment = Alignment.CenterVertically) {
                // La zone cliquable garde 28 dp même quand le favicon rétrécit.
                Box(
                    Modifier.size(28.dp).longPressInert(leadTap),
                    contentAlignment = Alignment.Center
                ) { FeedAvatar(feeds[a.feedId], 24) }
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = titleSp.sp, lineHeight = (titleSp * 1.2f).sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                trail(swiping)
            }
            // La méta est calée sur le titre : retrait du favicon à gauche, bord du bouton à
            // droite. Le onSizeChanged est après le padding, donc la hauteur relevée inclut l'écart.
            MetaLine(src, a.published, dateSp, metaStar,
                modifier = Modifier.padding(start = 36.dp, end = trailW, top = 3.dp)
                    .onSizeChanged { metaH = with(density) { it.height.toDp() } },
                showSource = showSource)
        }
        "magazine" -> Card(Modifier.fillMaxWidth().padding(8.dp).longPressInert(onOpen)) {
            Column {
                // La bannière est la vignette de l'article : elle répond au même appui que le
                // favicon des autres vues.
                if (!a.imageUrl.isNullOrBlank()) AsyncImage(
                    thumbModel(ctx, a.imageUrl), null,
                    Modifier.fillMaxWidth().height(180.dp).alpha(alpha).longPressInert(leadTap),
                    contentScale = ContentScale.Crop
                )
                Text(title, Modifier.padding(12.dp), fontSize = titleSp.sp,
                    fontWeight = if (a.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    trail(swiping); Spacer(Modifier.width(8.dp))
                    MetaLine(src, a.published, dateSp, metaStar, showSource = showSource)
                }
            }
        }
        "card" -> Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).longPressInert(onOpen)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Même appui que dans les autres vues, sur la vignette ou sur le favicon selon ce qu'il y a.
                if (!a.imageUrl.isNullOrBlank()) AsyncImage(
                    thumbModel(ctx, a.imageUrl), null,
                    Modifier.size(72.dp).alpha(alpha).longPressInert(leadTap),
                    contentScale = ContentScale.Crop
                )
                // Le Box maintient la zone cliquable à 48 dp même quand le favicon rétrécit.
                else Box(
                    Modifier.size(48.dp).longPressInert(leadTap),
                    contentAlignment = Alignment.Center
                ) { FeedAvatar(feeds[a.feedId], 48) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = titleSp.sp,
                        fontWeight = if (a.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(alpha))
                    MetaLine(src, a.published, dateSp, metaStar, showSource = showSource)
                }
                trail(swiping)
            }
        }
        else -> ListItem(
            modifier = Modifier.longPressInert(onOpen).alpha(alpha).padding(vertical = 2.dp),
            headlineContent = { Text(title, fontSize = titleSp.sp,
                fontWeight = FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            supportingContent = { MetaLine(src, a.published, dateSp, metaStar, showSource = showSource) },
            leadingContent = {
                if (!a.imageUrl.isNullOrBlank()) Box(Modifier.longPressInert(leadTap)) { AsyncImage(thumbModel(ctx, a.imageUrl), null, Modifier.size(56.dp), contentScale = ContentScale.Crop) }
                // Le rond n'est pas un favicon mais l'indicateur d'état : il reste donc un bouton
                // lu/non-lu quelle que soit l'option, comme celui en bout de ligne.
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
    SwipeAction(a, swipe, swipeRight, onToggleStar, onToggleRead, { shareArticle(ctx, a) }, onInstapaper, dotAtStart, starEnd, metaH, content)
}

/**
 * Balayage d'un article vers la gauche ou vers la droite : déclenche l'action choisie en
 * Réglages pour chaque sens (favori, lu/non-lu, partage, Instapaper) — ou rien si « Aucun ».
 *
 * Même fonctionnement des deux côtés : le bouton de fin disparaît dès le début du
 * glissement et laisse place à l'icône de l'action du sens en cours, qui reflète l'état
 * actuel ; dès que le seuil est franchi (144 px, course max 320 px) l'action bascule (une
 * seule fois par geste et par sens), et le bouton revient au relâchement. L'état du geste est stable (clé sur l'id
 * seul) pour que deux balayages d'affilée fonctionnent.
 *
 * [iconAtStart] place l'icône du côté du rond lu/non-lu, qui n'est pas toujours le même selon
 * la vue : l'icône apparaît ainsi exactement là où l'information a disparu. [iconEnd] est la
 * marge qui la superpose au bouton de fin, et [iconBottom] la hauteur à retrancher en bas pour
 * la remonter sur le seul bloc titre des vues où la ligne est plus haute que le titre.
 */
@Composable
private fun SwipeAction(
    a: ArticleEntity,
    action: String,
    actionRight: String,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit,
    onShare: () -> Unit,
    onInstapaper: () -> Unit,
    iconAtStart: Boolean,
    iconEnd: Dp,
    iconBottom: Dp,
    content: @Composable (Boolean, Boolean) -> Unit
) {
    if (action == "none" && actionRight == "none") {
        content(false, false)
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    fun fireOf(act: String): () -> Unit = when (act) {
        "read" -> onToggleRead
        "share" -> onShare
        "instapaper" -> onInstapaper
        else -> onToggleStar
    }
    val toggleLeft by rememberUpdatedState(fireOf(action))
    val toggleRight by rememberUpdatedState(fireOf(actionRight))
    var dx by remember(a.id, action, actionRight) { mutableFloatStateOf(0f) }
    var swiping by remember(a.id, action, actionRight) { mutableStateOf(false) }
    // Sens du geste qui a basculé : -1 gauche, +1 droite, 0 aucun (remis si on revient en arrière).
    var fired by remember(a.id, action, actionRight) { mutableIntStateOf(0) }
    val lo = if (action == "none") 0f else -320f
    val hi = if (actionRight == "none") 0f else 320f
    Box(
        Modifier.fillMaxWidth().pointerInput(a.id, action, actionRight) {
            detectHorizontalDragGestures(
                onDragStart = { swiping = true },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    dx = (dx + amount).coerceIn(lo, hi)
                    // Bascule dès le franchissement du seuil (et non au relâchement), une seule
                    // fois par geste et par sens : le drapeau est remis si l'utilisateur revient
                    // en arrière, ce qui autorise l'autre sens dans le même geste.
                    if (fired == 0) {
                        if (dx <= -144f) { fired = -1; toggleLeft() }
                        else if (dx >= 144f) { fired = 1; toggleRight() }
                    } else if (fired == -1 && dx > -144f) fired = 0
                    else if (fired == 1 && dx < 144f) fired = 0
                },
                onDragEnd = { dx = 0f; swiping = false; fired = 0 },
                onDragCancel = { dx = 0f; swiping = false; fired = 0 }
            )
        }
    ) {
        if (dx < -8f || dx > 8f) {
            val goingRight = dx > 0f
            val act = if (goingRight) actionRight else action
            // L'icône vit sur la bande découverte par le geste ; vers la gauche on garde le
            // placement historique (côté du rond, sinon bout de ligne), vers la droite son
            // miroir : la bande découverte est à gauche dans tous les cas.
            val atStart = goingRight || iconAtStart
            Row(
                Modifier.matchParentSize().padding(bottom = iconBottom).background(primary.copy(alpha = 0.18f)),
                horizontalArrangement = if (atStart) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (icon, desc) = when (act) {
                    "read" -> (if (a.unread) Icons.Filled.Circle else Icons.Filled.RadioButtonUnchecked) to
                        (if (a.unread) "marquer lu" else "marquer non lu")
                    "share" -> Icons.Filled.Share to "partager"
                    "instapaper" -> Icons.Filled.BookmarkAdd to "envoyer vers Instapaper"
                    else -> (if (a.starred) Icons.Filled.Star else Icons.Filled.StarBorder) to
                        (if (a.starred) "Retirer des favoris" else "Ajouter aux favoris")
                }
                Icon(
                    icon, desc,
                    tint = primary,
                    modifier = Modifier
                        .padding(
                            start = if (atStart) (if (iconAtStart) 22.dp else iconEnd) else 0.dp,
                            end = if (atStart) 0.dp else iconEnd
                        )
                        .size(18.dp)
                )
            }
        }
        // La méta ne perd son étoile que si le geste a basculé les favoris, dans un sens ou l'autre.
        val starFlipped = (fired == -1 && action == "star") || (fired == 1 && actionRight == "star")
        Box(Modifier.fillMaxWidth().graphicsLayer { translationX = dx }) { content(swiping, starFlipped) }
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
    modifier: Modifier = Modifier,
    showSource: Boolean = true
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    // Interligne resserré : la méta tient dans une seule hauteur de ligne, sans le blanc que le
    // style du thème laisserait sous le texte.
    val lh = (dateSp * 1.15f).sp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (showSource) {
                Text(
                    source, fontSize = dateSp.sp, lineHeight = lh, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (starred) {
                if (showSource) Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Filled.Star, "favori", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((dateSp + 2).dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(fmtTime(published), fontSize = dateSp.sp, lineHeight = lh, color = color, maxLines = 1)
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

/**
 * Modèle d'image pour une vignette de liste : le fichier local pré-téléchargé par la
 * synchronisation quand il existe, sinon l'URL (chargée paresseusement par Coil au scroll).
 * Sans `remember` : le fichier peut apparaître après une synchro pendant que la liste est
 * affichée, et un simple `stat` ne coûte rien à la composition.
 */
fun thumbModel(ctx: Context, url: String?): Any? {
    if (url.isNullOrBlank()) return null
    return ThumbStore.cachedFile(ctx, url) ?: url
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

/**
 * Icône du drawer pour chaque menu, réaffichée à gauche du titre de la vue.
 * « Tous les articles » et les dossiers n'en ont pas (titre seul) ; un flux précis garde
 * uniquement son favicon ([FeedAvatar], géré par chaque écran).
 */
fun drawerIconFor(route: String): ImageVector? = when (route) {
    "home" -> Icons.Filled.Home
    "starred" -> Icons.Filled.Star
    "dedup" -> Icons.Filled.ContentCopy
    "search" -> Icons.Filled.Search
    "stats" -> Icons.Filled.BarChart
    "settings" -> Icons.Filled.Settings
    "login" -> Icons.Filled.AccountCircle
    "about" -> Icons.Filled.Info
    else -> null
}

/** Titre de vue : icône du menu à gauche, libellé elliptique. */
@Composable
fun ViewTitle(icon: ImageVector?, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Deux petits boutons en surimpression, en bas au centre : haut / bas de liste. */@Composable
fun ScrollJumpOverlay(onTop: () -> Unit, onBottom: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(shadowElevation = 4.dp, shape = CircleShape) {
            Row {
                IconButton(onTop, Modifier.size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowUp, "remonter en haut")
                }
                IconButton(onBottom, Modifier.size(40.dp)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "descendre en bas")
                }
            }
        }
    }
}

/**
 * Fin curseur de position verticale, au bord droit : le haut du curseur dit où l'on est
 * dans la liste, sa hauteur dit quelle part est visible. Masqué quand tout tient à l'écran.
 */
@Composable
private fun PositionTrack(topFrac: Float, thumbFrac: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.width(4.dp).padding(vertical = 8.dp)) {
        val trackH = maxHeight
        Box(
            Modifier.fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
        )
        Box(
            Modifier.fillMaxWidth()
                .offset(y = trackH * topFrac)
                .fillMaxHeight(thumbFrac)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** Curseur de position pour une colonne défilante : masqué quand tout tient à l'écran. */
@Composable
fun ColumnPositionIndicator(state: ScrollState, modifier: Modifier = Modifier) {
    val max = state.maxValue
    if (max <= 0) return
    // Un seul BoxWithConstraints, comme les listes : un BoxWithConstraints imbriqué avec un
    // fillMaxSize prenait tout l'écran au lieu de 4 dp.
    BoxWithConstraints(modifier.width(4.dp).padding(vertical = 8.dp)) {
        val viewportPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val contentPx = viewportPx + max
        val thumb = (viewportPx / contentPx).coerceIn(0.06f, 1f)
        val top = (state.value / contentPx).coerceIn(0f, 1f - thumb)
        val trackH = maxHeight
        Box(
            Modifier.fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
        )
        Box(
            Modifier.fillMaxWidth()
                .offset(y = trackH * top)
                .fillMaxHeight(thumb)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** Curseur de position pour une [LazyColumn] : suit l'index et le décalage du 1er visible. */
@Composable
fun LazyListPositionIndicator(state: LazyListState, modifier: Modifier = Modifier) {
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (total <= visible || total <= 0 || visible <= 0) return
    val firstSize = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
    val frac = (state.firstVisibleItemScrollOffset.toFloat() / firstSize).coerceIn(0f, 1f)
    val thumb = (visible.toFloat() / total).coerceIn(0.06f, 1f)
    val top = ((state.firstVisibleItemIndex + frac) / total).coerceIn(0f, 1f - thumb)
    PositionTrack(top, thumb, modifier)
}

/** Curseur de position pour une grille magazine. */
@Composable
fun LazyGridPositionIndicator(state: LazyGridState, modifier: Modifier = Modifier) {
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (total <= visible || total <= 0 || visible <= 0) return
    val firstH = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height?.coerceAtLeast(1) ?: 1
    val frac = (state.firstVisibleItemScrollOffset.toFloat() / firstH).coerceIn(0f, 1f)
    val thumb = (visible.toFloat() / total).coerceIn(0.06f, 1f)
    val top = ((state.firstVisibleItemIndex + frac) / total).coerceIn(0f, 1f - thumb)
    PositionTrack(top, thumb, modifier)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleList(
    articles: List<ArticleEntity>,
    mode: String,
    onOpen: (ArticleEntity) -> Unit,
    vm: PressVM,
    showSource: Boolean = true
) {
    val trailing by vm.trailingAction.collectAsState()
    val swipe by vm.swipeAction.collectAsState()
    val swipeRight by vm.swipeRightAction.collectAsState()
    val tapFaviconRead by vm.tapFaviconRead.collectAsState()
    val titleSp by vm.titleFont.collectAsState()
    val dateSp by vm.dateFont.collectAsState()
    val feeds = remember(vm.subs) { vm.subs.associateBy { it.id } }
    val ctx = LocalContext.current
    val sendScope = rememberCoroutineScope()
    val row: @Composable (ArticleEntity) -> Unit = { a ->
        // Étoile effective : un article Feedly affiche son « Read later » (jamais de favori
        // local), basculé par le même geste que l'étoile des autres flux.
        val star = vm.starState(a)
        ArticleRow(a.copy(starred = star), mode, trailing, tapFaviconRead, titleSp, dateSp, feeds,
            { onOpen(a) }, { vm.setRead(a.id, !a.unread) }, { vm.setStar(a.id, !star) },
            showSource = showSource,
            onInstapaper = { sendScope.launch { vm.sendOrAskInstapaper(a) { toast(ctx, it) } } },
            swipe = swipe, swipeRight = swipeRight)
    }
    if (mode == "magazine") {
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                GridCells.Adaptive(280.dp), contentPadding = PaddingValues(4.dp),
                state = gridState
            ) {
                items(articles, key = { it.id }) { a -> row(a) }
            }
            if (articles.size > 3) {
                ScrollJumpOverlay(
                    onTop = { scope.launch { gridState.animateScrollToItem(0) } },
                    onBottom = {
                        scope.launch {
                            val last = (gridState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            gridState.animateScrollToItem(last)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
            LazyGridPositionIndicator(
                gridState,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp)
            )
        }
    } else {
        val now = remember(articles) { System.currentTimeMillis() }
        val grouped = remember(articles, now) { groupByDay(articles, now) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                // Les boutons haut/bas flottent en bas au centre : en vues liste et compacte, un
                // espace sous le dernier article l'empêche d'être masqué par eux.
                contentPadding = PaddingValues(
                    bottom = if (mode == "list" || mode == "compact") 72.dp else 0.dp
                )
            ) {
                grouped.forEachIndexed { i, si ->
                    when (si) {
                        is StreamItem.Day -> {
                            // Respiration entre le dernier article et le jour suivant. L'écart est un
                            // élément de la liste et non un padding de l'en-tête : celui-ci est collant,
                            // un padding haut laisserait un trou au-dessus de lui quand il se colle.
                            if (i > 0) item(key = "gap-${si.key}") { Spacer(Modifier.height(8.dp)) }
                            stickyHeader(key = "day-${si.key}") { DayHeader(si.label) }
                        }
                        is StreamItem.Art -> item(key = "art-${si.article.id}") { row(si.article) }
                    }
                }
            }
            if (articles.size > 3) {
                ScrollJumpOverlay(
                    onTop = { scope.launch { listState.animateScrollToItem(0) } },
                    onBottom = {
                        scope.launch {
                            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(last)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
            LazyListPositionIndicator(
                listState,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp)
            )
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
    val t = cleanTitle(a.title).ifBlank { "(sans titre)" }
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, t)
        putExtra(Intent.EXTRA_TEXT, "$t\n${a.url}")
    }
    ctx.startActivity(Intent.createChooser(i, "Partager l'article"))
}

/** Petit toast système. */
fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * Échelle d'animation système (0 = animations coupées : option développeur, accessibilité ou
 * économie d'énergie). Les animations Compose se figent alors (une rotation à 360° retombe à
 * 0°) : l'indicateur de synchronisation doit avoir un repli qui n'en dépend pas.
 */
private fun animatorScale(ctx: Context): Float = runCatching {
    android.provider.Settings.Global.getFloat(
        ctx.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    )
}.getOrDefault(1f)

/**
 * Bouton de synchronisation : l'icône pivote tant qu'une synchronisation a lieu — manuelle,
 * de démarrage ou périodique (Worker, suivie via [PressVM.workerSyncing]) — pour les écrans
 * Accueil / flux / favoris qui partagent le même état. Animations coupées : pulsation manuelle
 * (`delay`, insensible à l'échelle) au lieu de la rotation.
 */
@Composable
fun SyncButton(vm: PressVM, description: String = "actualiser", onClick: () -> Unit = { vm.sync() }) {
    val bgSync by vm.workerSyncing.collectAsState()
    IconButton(onClick) {
        if (vm.syncing || bgSync) {
            val ctx = LocalContext.current
            if (remember { animatorScale(ctx) } > 0f) {
                val angle by rememberInfiniteTransition(label = "sync").animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                    label = "spin"
                )
                Icon(Icons.Filled.Refresh, description, Modifier.graphicsLayer { rotationZ = angle })
            } else {
                // Pas d'API d'animation : on tourne par pas (recomposition pilotée par `delay`,
                // insensible à l'échelle), même vitesse qu'en mode animé (~1 tour/s).
                var angle by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(40)
                        angle = (angle + 15f) % 360f
                    }
                }
                Icon(Icons.Filled.Refresh, description, Modifier.graphicsLayer { rotationZ = angle })
            }
        } else {
            Icon(Icons.Filled.Refresh, description)
        }
    }
}

/**
 * Barre de chargement de synchronisation, un peu plus haute que le trait fin par défaut.
 * Animations coupées : balayage maison (la version M3 démarre en retrait du bord et avance
 * par à-coups) — un segment qui part du bord gauche et grandit en continu, sans API d'animation.
 */
@Composable
fun SyncProgress() {
    val ctx = LocalContext.current
    if (remember { animatorScale(ctx) } > 0f) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp))
    } else {
        var f by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(60)
                f = if (f >= 1f) 0f else f + 0.04f
            }
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(f)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** Nom de version affiché (drawer, écran À propos) : suit le `versionName` du build. */
fun appVersionName(ctx: Context): String? = runCatching {
    val pm = ctx.packageManager
    val pkg = ctx.packageName
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName
    } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).versionName
    }
}.getOrNull()
