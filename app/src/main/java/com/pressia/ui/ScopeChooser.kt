package com.pressia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pressia.data.PressRepository
import com.pressia.data.local.SubscriptionEntity

/**
 * Une ligne de la liste de choix de portée : « tous les articles », un dossier, ou un flux.
 * [feed] non nul marque une ligne de flux ; [folder] une ligne de dossier.
 */
data class ScopeEntry(val scope: Scope, val feed: SubscriptionEntity? = null) {
    val folder: Boolean get() = feed == null && scope.categoryId != null
}

/**
 * Entrées de choix de portée **dans l'ordre de la page Accueil** : les dossiers tels que
 * `vm.folders` les donne (c'est l'ordre choisi par l'utilisateur), chacun suivi de ses flux triés
 * par `sortOrder`, puis les flux sans dossier. Un dossier fusionné (`merged:…`) rassemble les flux
 * de tous ses identifiants réels.
 *
 * Fonction pure : les écrans qui proposent un choix de portée (Recherche, Favoris) l'appellent
 * toutes, pour qu'on retrouve partout les mêmes groupes et le même ordre que sur l'Accueil.
 */
fun scopeEntries(
    folders: List<PressRepository.Folder>,
    subs: List<SubscriptionEntity>
): List<ScopeEntry> {
    val out = ArrayList<ScopeEntry>(folders.size + subs.size)
    folders.forEach { f ->
        out += ScopeEntry(Scope(categoryId = f.id, label = f.label))
        subs.filter { it.categoryId in f.ids }
            .sortedBy { it.sortOrder }
            .forEach { s -> out += ScopeEntry(Scope(feedId = s.id, label = prettyFeedTitle(s.title)), s) }
    }
    // Flux sans dossier : même ordre que dans la section « Flux sans dossier » de l'Accueil.
    subs.filter { s -> folders.none { s.categoryId in it.ids } }
        .sortedBy { it.sortOrder }
        .forEach { s -> out += ScopeEntry(Scope(feedId = s.id, label = prettyFeedTitle(s.title)), s) }
    return out
}

/**
 * Ligne de choix de portée : icône de dossier ou favicon du flux, comme sur la page Accueil — les
 * deux listes de choix de l'application s'y repèrent donc aussi vite que dans la navigation.
 */
@Composable
fun ScopeChoiceRow(entry: ScopeEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            // Les flux sont décalés sous leur dossier, comme sur l'Accueil.
            .padding(start = if (entry.feed != null) 26.dp else 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            entry.feed != null -> FeedAvatar(entry.feed, 20)
            entry.folder -> Icon(
                Icons.Filled.Folder, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Icon(
                Icons.Filled.List, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            entry.scope.label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                Icons.Filled.Check, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Choix de portée complet : « Tous les articles » puis [scopeEntries]. Le défilement et la hauteur
 * maximale restent à la charge de l'appelant (`modifier`), les deux écrans n'ayant pas la même
 * place disponible.
 */
@Composable
fun ScopeChooser(
    vm: PressVM,
    selectedCategoryId: String?,
    selectedFeedId: String?,
    onPick: (Scope) -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String = "Tous les articles"
) {
    val entries = remember(vm.folders, vm.subs) { scopeEntries(vm.folders, vm.subs) }
    Column(modifier) {
        ScopeChoiceRow(
            ScopeEntry(Scope(label = allLabel)),
            selectedCategoryId == null && selectedFeedId == null
        ) { onPick(Scope(label = allLabel)) }
        entries.forEach { e ->
            val selected =
                if (e.feed != null) e.scope.feedId == selectedFeedId else e.scope.categoryId == selectedCategoryId
            ScopeChoiceRow(e, selected) { onPick(e.scope) }
        }
    }
}
