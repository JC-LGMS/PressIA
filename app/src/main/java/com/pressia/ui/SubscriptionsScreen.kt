package com.pressia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pressia.data.PressRepository
import com.pressia.data.local.SubscriptionEntity

/**
 * Accueil façon Press : l'arborescence des dossiers et des abonnements.
 *
 * Choix retenus :
 *  - **l'état de pliage est partagé avec le drawer** et survit à la fermeture de l'application ;
 *  - **le marquage « tout lu » n'existe plus au niveau d'un flux** : l'icônealignait mal, se
 *    confondait avec celle du dossier et marquait parfois le mauvais périmètre. L'action
 *    reste disponible sur le flux affiché.
 *  - **le nouveau dossier est une icône** dans la barre d'action, pas un bouton pleine largeur
 *    qui décalait toute la liste vers le bas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(vm: PressVM, onOpenStream: () -> Unit, onMenu: () -> Unit) {
    LaunchedEffect(Unit) { vm.refresh() }
    val ctx = LocalContext.current
    val totalUnread = vm.unreads.values.sum()
    val hiddenEmpty by vm.hideEmptySubs.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<PressRepository.Folder?>(null) }   // dossier renommé
    var creating by remember { mutableStateOf(false) }                          // création d'un dossier
    var moving by remember { mutableStateOf<SubscriptionEntity?>(null) }       // déplacement d'un flux
    var toDelete by remember { mutableStateOf<PressRepository.Folder?>(null) }
    var addingTo by remember { mutableStateOf<PressRepository.Folder?>(null) } // ajout d'un flux local

    LaunchedEffect(vm.syncMsg) {
        vm.syncMsg?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); vm.syncMsg = null }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (totalUnread > 0) "Accueil ($totalUnread)" else "Accueil") },
                navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") } },
                actions = {
                    IconButton({ vm.toggleHideEmptySubs() }) {
                        Icon(
                            if (hiddenEmpty) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            if (hiddenEmpty) "afficher les flux sans article non lu"
                            else "masquer les flux sans article non lu"
                        )
                    }
                    IconButton({
                        toast(ctx, "Actualisation des abonnements")
                        vm.sync()
                    }) { Icon(Icons.Filled.Refresh, "actualiser") }
                    IconButton({ creating = true }) { Icon(Icons.Filled.CreateNewFolder, "nouveau dossier") }
                }
            )
            if (vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    ) { pad ->
        val uncategorized = vm.subs.filter { it.categoryId == null }
        val visibleUncategorized = if (hiddenEmpty) uncategorized.filter { (vm.unreads[it.id] ?: 0) > 0 } else uncategorized
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (vm.subs.isEmpty() && !vm.syncing) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Aucun abonnement.")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Ajoutez un flux RSS ou connectez Feedly / Inoreader depuis Comptes.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            // `vm.folders` et `vm.subs` sont déjà ordonnés par la base : c'est cet ordre qui est
            // affiché, et c'est donc lui qui dit si un élément peut monter ou descendre.
            vm.folders.forEachIndexed { fi, f ->
                val subs = vm.subs.filter { it.categoryId in f.ids }
                // Un dossier vide peut être masqué ; un dossier sans flux ne l'est jamais,
                // sinon la liste donnerait l'impression d'une synchronisation incomplète.
                val hideFolder = hiddenEmpty && subs.isNotEmpty() && subs.all { (vm.unreads[it.id] ?: 0) == 0 }
                if (!hideFolder) {
                    item(key = "cat-${f.id}") {
                        FolderRow(
                            label = f.label,
                            unread = subs.sumOf { vm.unreads[it.id] ?: 0 },
                            collapsed = vm.isCollapsed(f.id),
                            canMoveUp = fi > 0,
                            canMoveDown = fi < vm.folders.lastIndex,
                            onOpen = {
                                vm.switchScope(Scope(categoryId = f.id, label = f.label))
                                vm.refresh(); onOpenStream()
                            },
                            onToggle = { vm.toggleFolder(f.id) },
                            onRename = { editing = f },
                            onAddFeed = { addingTo = f },
                            onMoveUp = { vm.moveFolder(f, -1) },
                            onMoveDown = { vm.moveFolder(f, 1) },
                            onDelete = { toDelete = f }
                        )
                    }
                    if (!vm.isCollapsed(f.id)) {
                        // Le flux remonte au fond du dossier s'il en quitte un autre : sa place dans
                        // la liste est celle de sa nouvelle liste, pas de celle d'origine.
                        val shown = (if (hiddenEmpty) subs.filter { (vm.unreads[it.id] ?: 0) > 0 } else subs)
                            .sortedBy { it.sortOrder }
                        items(shown, key = { "s-${it.id}" }) { s ->
                            val i = shown.indexOfFirst { it.id == s.id }
                            SubRow(
                                vm, s, Modifier.padding(start = 20.dp), onOpenStream,
                                onMove = { moving = s },
                                onMoveUp = { vm.moveFeedOrder(s.id, -1, s.categoryId) },
                                onMoveDown = { vm.moveFeedOrder(s.id, 1, s.categoryId) },
                                canMoveUp = i > 0,
                                canMoveDown = i < shown.lastIndex
                            )
                        }
                    }
                }
            }
            if (visibleUncategorized.isNotEmpty()) {
                item(key = "cat-none") {
                    Text(
                        "Flux sans dossier", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                items(visibleUncategorized, key = { "s-${it.id}" }) { s ->
                    val i = visibleUncategorized.indexOfFirst { it.id == s.id }
                    SubRow(
                        vm, s, Modifier, onOpenStream,
                        onMove = { moving = s },
                        onMoveUp = { vm.moveFeedOrder(s.id, -1, null) },
                        onMoveDown = { vm.moveFeedOrder(s.id, 1, null) },
                        canMoveUp = i > 0,
                        canMoveDown = i < visibleUncategorized.lastIndex
                    )
                }
            }
        }
    }

    if (creating) {
        FolderNameDialog(
            title = "Nouveau dossier",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                vm.createFolder(name) {
                    toast(ctx, if (it == null) "Dossier non créé" else "Dossier « ${it.label} » créé")
                }
            }
        )
    }
    editing?.let { f ->
        FolderNameDialog(
            title = "Renommer le dossier",
            initial = f.label,
            onDismiss = { editing = null },
            onConfirm = { name ->
                editing = null
                vm.renameFolder(f, name)
                toast(ctx, "Dossier renommé")
            }
        )
    }
    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${f.label} » ?") },
            text = {
                Text(
                    if (f.merged)
                        "Ce dossier regroupe les dossiers de plusieurs comptes : il ne peut être supprimé que depuis chaque compte. Les flux restent abonnés."
                    else
                        "Les flux qu'il contient ne sont pas supprimés : ils repassent dans « Flux sans dossier »."
                )
            },
            confirmButton = {
                TextButton({
                    toDelete = null
                    if (f.merged && f.providers.size > 1) toast(ctx, "Dossier partagé : supprimez-le depuis chaque compte")
                    else {
                        vm.deleteFolder(f); toast(ctx, "Dossier supprimé")
                    }
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton({ toDelete = null }) { Text("Annuler") } }
        )
    }
    addingTo?.let { f ->
        AddFeedDialog(
            title = "Ajouter un flux dans « ${f.label} »",
            onDismiss = { addingTo = null },
            onConfirm = { url ->
                addingTo = null
                vm.addLocalFeed(url, f.id) { ok ->
                    toast(ctx, if (ok) "Flux ajouté" else "Flux non ajouté")
                }
            }
        )
    }
    moving?.let { s ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Déplacer « ${prettyFeedTitle(s.title)} »") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = {
                            Text(
                                "Sans dossier",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable(enabled = s.categoryId != null) {
                            moving = null
                            vm.moveFeed(s.id, null)
                            toast(ctx, "Déplacé hors de tout dossier")
                        }
                    )
                    vm.folders.forEach { f ->
                        val current = s.categoryId != null && s.categoryId in f.ids
                        ListItem(
                            modifier = Modifier.clickable(enabled = !current) {
                                moving = null
                                vm.moveFeed(s.id, f)
                                toast(ctx, "Déplacé dans « ${f.label} »")
                            },
                            headlineContent = { Text(f.label + if (current) " (déjà ici)" else "") },
                            leadingContent = { Icon(Icons.Filled.Folder, null) }
                        )
                    }
                }
            },
            confirmButton = { TextButton({ moving = null }) { Text("Annuler") } }
        )
    }
}

/**
 * Ligne de dossier : libellé, compteur, flèche de pliage toujours visible, menu d'actions.
 * Un appui sur la ligne ouvre le flux ; la flèche replie, le menu gère.
 */
@Composable
private fun FolderRow(
    label: String,
    unread: Int,
    collapsed: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onAddFeed: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onToggle, Modifier.size(34.dp)) {
            Icon(
                if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                if (collapsed) "déplier" else "replier", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            if (collapsed) Icons.Filled.Folder else Icons.Filled.FolderOpen, null,
            Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp)
        )
        if (unread > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(if (unread > 999) "999+" else "$unread")
            }
        }
        Box {
            var open by remember { mutableStateOf(false) }
            IconButton({ open = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "actions du dossier", Modifier.size(18.dp)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("Renommer") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { open = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Ajouter un flux local") },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { open = false; onAddFeed() }
                )
                // Les deux actions de position sont désactivées en bout de liste : un dossier déjà
                // en premier ne peut pas remonter, et l'indiquer évite de chercher pourquoi rien
                // ne se passe.
                DropdownMenuItem(
                    text = { Text("Monter") },
                    enabled = canMoveUp,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                    onClick = { open = false; onMoveUp() }
                )
                DropdownMenuItem(
                    text = { Text("Descendre") },
                    enabled = canMoveDown,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                    onClick = { open = false; onMoveDown() }
                )
                DropdownMenuItem(
                    text = { Text("Supprimer le dossier") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { open = false; onDelete() }
                )
            }
        }
    }
}

/** Saisie du nom d'un dossier (création ou renommage). */
@Composable
private fun FolderNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                name, { name = it }, label = { Text("Nom du dossier") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton({ onConfirm(name) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } }
    )
}

/** Saisie de l'URL d'un flux RSS local. */
@Composable
private fun AddFeedDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                url, { url = it }, label = { Text("URL du flux RSS/Atom") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    autoCorrect = false
                )
            )
        },
        confirmButton = { TextButton({ onConfirm(url.trim()) }, enabled = url.isNotBlank()) { Text("Ajouter") } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } }
    )
}

@Composable
private fun SubRow(
    vm: PressVM,
    sub: SubscriptionEntity,
    mod: Modifier,
    onOpenStream: () -> Unit,
    onMove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    val pretty = prettyFeedTitle(sub.title)
    var menu by remember { mutableStateOf(false) }
    Row(
        mod.fillMaxWidth().clickable {
            vm.switchScope(Scope(feedId = sub.id, label = pretty))
            vm.refresh(); onOpenStream()
        }.padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FeedAvatar(sub, 26)
        Spacer(Modifier.width(10.dp))
        Text(
            pretty, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val n = vm.unreads[sub.id] ?: 0
        if (n > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(if (n > 999) "999+" else "$n")
            }
        }
        Box {
            IconButton({ menu = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "actions du flux", Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Déplacer vers un dossier") },
                    leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                    onClick = { menu = false; onMove() }
                )
                DropdownMenuItem(
                    text = { Text("Monter") },
                    enabled = canMoveUp,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                    onClick = { menu = false; onMoveUp() }
                )
                DropdownMenuItem(
                    text = { Text("Descendre") },
                    enabled = canMoveDown,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                    onClick = { menu = false; onMoveDown() }
                )
            }
        }
    }
}
