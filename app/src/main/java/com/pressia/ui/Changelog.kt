package com.pressia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Un changement livré : sa date et son heure, son titre et, quand le commit en portait un, son
 * détail. L'heure ([time], `HH:mm`) est celle du commit : elle s'affiche sous la date et
 * départage deux changements du même jour.
 */
data class ChangelogItem(
    val date: String,
    val title: String,
    val detail: String? = null,
    val time: String? = null
)

/** Une version publiée et ses changements, du plus récent au plus ancien. */
data class ChangelogEntry(val version: String, val items: List<ChangelogItem>)

/**
 * Journal des modifications, du plus récent au plus ancien.
 *
 * **À tenir à jour** (voir AGENTS.md) : à chaque commit qui touche l'application, ajouter un
 * [ChangelogItem] en tête de l'entrée de la version courante, avec le titre et le corps du
 * message de commit. Le numéro de version, lui, avance tout seul au build suivant
 * (`version.properties`) ; c'est donc la version affichée par [ChangelogScreen] qu'il faut
 * reprendre dans l'entrée. L'historique d'avant le journal (208 commits) est dans
 * [CHANGELOG_HISTORY], généré une fois depuis `git log` et à ne plus modifier.
 */
val CHANGELOG: List<ChangelogEntry> = listOf(
    ChangelogEntry(
        "1.0.3",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications : barre de défilement",
                "Le journal affiche une barre de défilement au bord droit de la liste : la " +
                    "longueur du curseur indique la part visible des 200 et quelques changements. " +
                    "Elle n'apparaît que si la liste dépasse l'écran.",
                time = "01:41"
            )
        )
    ),
    ChangelogEntry(
        "1.0.1",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications : heure et minute sous chaque date",
                "Chaque changement du journal affiche désormais l'heure du commit (`HH:mm`) sous " +
                    "sa date, ce qui départage deux changements du même jour. L'historique a été " +
                    "régénéré avec ces horaires.",
                time = "01:34"
            )
        )
    ),
    ChangelogEntry(
        "1.0.0",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications",
                "Page accessible en touchant le numéro de version et la date de build (tiroir, " +
                    "À propos). Le numéro de version avance automatiquement à chaque modification " +
                    "de l'application : plus besoin d'y penser.",
                time = "01:29"
            )
        ) + CHANGELOG_HISTORY
    )
)

/**
 * Écran « Journal des modifications » : version courante (celle du build, avec sa date) puis,
 * version par version, les changements repris des messages de commit.
 */
@Composable
fun ChangelogScreen() {
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }
    val total = remember { CHANGELOG.sumOf { it.items.size } }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Text(
                "Journal des modifications",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (version.isNullOrBlank()) "Version installée inconnue" else "Version installée : $version",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$total changement(s) consigné(s), du plus récent au plus ancien",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Toucher le numéro de version (tiroir ou à propos) rouvre cette page.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()
        // La liste et sa barre de défilement partagent le même espace : la barre se pose au bord
        // droit, sur la marge que le contenu laisse libre (contentPadding de fin), sans masquer
        // le texte.
        Box(Modifier.fillMaxSize()) {
            // Une ligne par changement : la liste reste paresseuse, l'historique fait plus de
            // 200 entrées et tout composer d'un coup serait inutilement coûteux.
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 32.dp)
            ) {
                CHANGELOG.forEach { entry ->
                    item {
                        Text(
                            "Version ${entry.version}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${entry.items.size} changement(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    items(entry.items) { item -> ChangelogRow(item) }
                    item {
                        Spacer(Modifier.height(18.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            // Barre de défilement : posée au bord droit, sur la marge de fin (20 dp) du contenu.
            ChangelogScrollBar(
                listState,
                Modifier.align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(top = 14.dp, bottom = 32.dp, end = 5.dp)
                    .width(6.dp)
            )
        }
    }
}

/**
 * Barre de défilement verticale du journal (un LazyColumn n'en dessine pas).
 *
 * Le dessin lit l'état de la liste dans la phase de dessin : un défilement ne redessine que la
 * barre, la page n'est pas recomposée. La barre n'apparaît que si la liste dépasse l'écran, et
 * la longueur du curseur reflète la part visible du contenu.
 */
@Composable
private fun ChangelogScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Canvas(modifier) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        // Rien à faire défiler : pas de barre.
        if (total <= 0 || visible.isEmpty() || visible.size >= total) return@Canvas
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = CornerRadius(radius, radius)
        )
        // Part visible du contenu, avec un minimum lisible (une ligne très haute ne doit pas
        // réduire le curseur à un trait invisible).
        val thumbHeight = (size.height * visible.size / total)
            .coerceIn(36.dp.toPx().coerceAtMost(size.height), size.height)
        val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
        val first = visible.first()
        // Position : index du premier élément visible, plus la part déjà défilée dedans.
        val inside = if (first.size > 0) {
            (state.firstVisibleItemScrollOffset.toFloat() / first.size).coerceIn(0f, 1f)
        } else 0f
        val maxStart = (total - visible.size).coerceAtLeast(1)
        val progress = ((first.index + inside) / maxStart).coerceIn(0f, 1f)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, maxTop * progress),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius)
        )
    }
}

/** Une ligne du journal : date et heure à gauche, titre puis détail du commit à droite. */
@Composable
private fun ChangelogRow(item: ChangelogItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        // Colonne étroite : la date sur une ligne, l'heure juste en dessous.
        Column(Modifier.width(84.dp).padding(top = 2.dp, end = 6.dp)) {
            Text(
                item.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!item.time.isNullOrBlank()) {
                Text(
                    item.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            if (!item.detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
