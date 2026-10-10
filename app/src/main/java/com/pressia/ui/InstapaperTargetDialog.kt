package com.pressia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pressia.data.remote.InstapaperWeb
import kotlinx.coroutines.launch

/**
 * Dialogue de destination Instapaper (mode « Choisir le dossier et les tags à chaque envoi ») :
 * dossier (Accueil par défaut, existants, ou nouveau créé ici) + tags (existants à cocher et/ou
 * saisie libre séparée par des virgules, créés automatiquement à l'envoi).
 *
 * Affiché depuis l'activité tant que [PressVM.instapaperPickerFor] est non nul, quel que soit
 * l'écran d'origine (liste, swipe ou écran article).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstapaperTargetDialog(vm: PressVM, articleId: String, articleTitle: String, onToast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var folders by remember(articleId) { mutableStateOf<List<InstapaperWeb.Folder>>(emptyList()) }
    var tags by remember(articleId) { mutableStateOf<List<InstapaperWeb.Tag>>(emptyList()) }
    var loaded by remember(articleId) { mutableStateOf(false) }
    var folderId by remember(articleId) { mutableStateOf<Long?>(null) }
    var checkedTags by remember(articleId) { mutableStateOf(setOf<String>()) }
    var freeTags by remember(articleId) { mutableStateOf("") }
    var folderMenu by remember(articleId) { mutableStateOf(false) }
    var creating by remember(articleId) { mutableStateOf(false) }
    var newFolder by remember(articleId) { mutableStateOf("") }
    var busy by remember(articleId) { mutableStateOf(false) }
    var createError by remember(articleId) { mutableStateOf<String?>(null) }

    LaunchedEffect(articleId) {
        folders = vm.instapaperFolders()
        tags = vm.instapaperTags()
        loaded = true
    }

    fun allTags(): List<String> {
        val free = freeTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return (checkedTags + free).distinct()
    }

    fun send() {
        if (busy) return
        busy = true
        scope.launch {
            // L'article a pu changer d'état depuis l'ouverture : on relit le strict nécessaire.
            val a = vm.repo.article(articleId)
            if (a == null) {
                onToast("Article introuvable")
                vm.cancelInstapaperPicker()
                return@launch
            }
            vm.sendToInstapaperTarget(a, folderId, allTags(), onToast)
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = { vm.cancelInstapaperPicker() }) {
        Card {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Envoyer vers Instapaper", style = MaterialTheme.typography.titleLarge)
                Text(
                    articleTitle.ifBlank { "(sans titre)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (!loaded) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    // ---- Dossier ----
                    Text("Dossier", style = MaterialTheme.typography.titleSmall)
                    ExposedDropdownMenuBox(expanded = folderMenu, onExpandedChange = { folderMenu = it }) {
                        OutlinedTextField(
                            value = folders.firstOrNull { it.id == folderId }?.title ?: "Accueil",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(folderMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            singleLine = true
                        )
                        ExposedDropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Accueil") },
                                onClick = { folderId = null; folderMenu = false },
                                trailingIcon = { if (folderId == null) Icon(Icons.Filled.Check, null) }
                            )
                            folders.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.title) },
                                    onClick = { folderId = f.id; folderMenu = false },
                                    trailingIcon = { if (folderId == f.id) Icon(Icons.Filled.Check, null) }
                                )
                            }
                        }
                    }
                    if (!creating) {
                        TextButton({ creating = true; createError = null }) { Text("+ Nouveau dossier…") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                newFolder, { newFolder = it; createError = null },
                                label = { Text("Nom du dossier") },
                                modifier = Modifier.weight(1f), singleLine = true
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton({
                                scope.launch {
                                    val f = vm.createInstapaperFolder(newFolder)
                                    if (f == null) createError = "Création refusée (titre déjà pris ?)"
                                    else {
                                        folders = folders + f
                                        folderId = f.id
                                        newFolder = ""
                                        creating = false
                                    }
                                }
                            }, enabled = newFolder.isNotBlank()) { Icon(Icons.Filled.Add, "créer le dossier") }
                        }
                        if (createError != null) {
                            Text(createError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // ---- Tags ----
                    Text("Tags", style = MaterialTheme.typography.titleSmall)
                    if (tags.isNotEmpty()) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { t ->
                                val on = t.name in checkedTags
                                FilterChip(
                                    selected = on,
                                    onClick = { checkedTags = if (on) checkedTags - t.name else checkedTags + t.name },
                                    label = { Text(t.name) },
                                    leadingIcon = { if (on) Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        freeTags, { freeTags = it },
                        label = { Text("Autres tags, séparés par des virgules") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text(
                        "Les tags inexistants sont créés automatiquement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ vm.cancelInstapaperPicker() }) { Text("Annuler") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { send() }, enabled = loaded && !busy) { Text("Envoyer") }
                }
            }
        }
    }
}
