package com.pressia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Un changement livré : sa date, son titre et, quand le commit en portait un, son détail. */
data class ChangelogItem(val date: String, val title: String, val detail: String? = null)

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
        "1.0.0",
        listOf(
            ChangelogItem(
                "2026-10-25",
                "Journal des modifications",
                "Page accessible en touchant le numéro de version et la date de build (tiroir, " +
                    "À propos). Le numéro de version avance automatiquement à chaque modification " +
                    "de l'application : plus besoin d'y penser."
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
        // Une ligne par changement : la liste reste paresseuse, l'historique fait plus de
        // 200 entrées et tout composer d'un coup serait inutilement coûteux.
        LazyColumn(
            Modifier.fillMaxSize(),
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
    }
}

/** Une ligne du journal : date à gauche, titre puis détail du commit à droite. */
@Composable
private fun ChangelogRow(item: ChangelogItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            item.date,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp).padding(top = 2.dp)
        )
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
