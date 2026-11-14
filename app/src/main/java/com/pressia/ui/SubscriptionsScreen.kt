package com.pressia.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pressia.data.PressRepository
import com.pressia.data.local.SubscriptionEntity
import kotlinx.coroutines.launch

/**
 * Accueil filtré (« masquer les flux sans article non lu ») et tout est lu : la liste est
 * vide sans que ce soit une erreur — on l'explique au lieu d'une page blanche.
 */
fun allFeedsRead(subs: List<SubscriptionEntity>, unreads: Map<String, Int>): Boolean =
    subs.isNotEmpty() && subs.all { (unreads[it.id] ?: 0) == 0 }

/**
 * Accueil façon Press : l'arborescence des dossiers et des abonnements.
 *
 * Choix retenus :
 *  - **l'état de pliage est partagé avec le drawer** et survit à la fermeture de l'application ;
 *  - **le marquage « tout lu » n'existe pas en icône au niveau d'un flux** : l'icône s'alignait mal,
 *    se confondait avec celle du dossier et marquait parfois le mauvais périmètre. L'action reste
 *    disponible là où elle ne prête pas à confusion : sur le flux affiché (barre d'actions), et
 *    depuis l'**appui long** sur une ligne — flux ou dossier — qui ouvre le menu de la ligne, où
 *    « Tout marquer comme lu » porte le périmètre exact de la ligne touchée ([PressVM.markAllReadFor]).
 *  - **le nouveau dossier est une icône** dans la barre d'action, pas un bouton pleine largeur
 *    qui décalait toute la liste vers le bas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    vm: PressVM,
    onOpenStream: () -> Unit,
    onMenu: () -> Unit,
    // Balayage vers la droite : remonte d'un niveau. Renseigné quand « Tous les articles » est
    // la page d'accueil — l'Accueil est alors une étape entre la racine et les dossiers/flux
    // (voir MainActivity.goHome).
    onSwipeBack: (() -> Unit)? = null,
    /** Ouvre la recherche sur le dossier ou le flux touché (menu d'appui long). */
    onSearch: (Scope) -> Unit = {}
) {
    LaunchedEffect(Unit) { vm.refresh() }
    val ctx = LocalContext.current
    val totalUnread = vm.unreads.values.sum()
    val hiddenEmpty by vm.hideEmptySubs.collectAsState()
    // 100 % = l'ancien 110 % : bases relevées d'un dixième (dossier 17,6 sp, flux 16,5 sp).
    val homeScale by vm.homeScale.collectAsState()
    // 100 % = l'ancien 90 %, redéfini comme référence : si votre curseur était à 90,
    // remettez-le à 100 pour retrouver exactement le même rendu.
    val folderSp = (17.6f * 0.9f * homeScale / 100f).coerceIn(12f, 25f)
    val feedSp = (16.5f * 0.9f * homeScale / 100f).coerceIn(11f, 23f)
    var editing by remember { mutableStateOf<PressRepository.Folder?>(null) }   // dossier renommé
    var creating by remember { mutableStateOf(false) }                          // création d'un dossier
    var moving by remember { mutableStateOf<SubscriptionEntity?>(null) }       // déplacement d'un flux
    var toDelete by remember { mutableStateOf<PressRepository.Folder?>(null) }
    var addingTo by remember { mutableStateOf<PressRepository.Folder?>(null) } // ajout d'un flux local
    // Précision du compte : inutile quand il n'y a qu'un compte, ou que des flux locaux
    // (voir `showFeedAccount`) — on ne l'affiche alors nulle part.
    val showAccount = com.pressia.data.showFeedAccount(vm.accounts, vm.localFeeds)

    Scaffold(
        topBar = {
            // Column explicite : sans elle, SyncProgress et TopAppBar se superposent dans le
            // slot topBar et le titre opaque couvre la barre.
            Column {
                // Barre de synchro tout en haut de l'écran, au-dessus du titre.
                if (vm.syncing) SyncProgress()
                TopAppBar(
                    title = {
                        ViewTitle(
                            drawerIconFor("home"),
                            if (totalUnread > 0) "Accueil ($totalUnread)" else "Accueil"
                        )
                    },
                    navigationIcon = { IconButton(onMenu) { Icon(Icons.Filled.Menu, "menu") } },
                    actions = {
                        IconButton({ vm.toggleHideEmptySubs() }) {
                            Icon(
                                if (hiddenEmpty) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                if (hiddenEmpty) "afficher les flux sans article non lu"
                                else "masquer les flux sans article non lu"
                            )
                        }
                        SyncButton(vm, "actualiser") {
                            toast(ctx, "Actualisation des abonnements")
                            vm.sync()
                        }
                        IconButton({ creating = true }) { Icon(Icons.Filled.CreateNewFolder, "nouveau dossier") }
                    }
                )
            }
        }
    ) { pad ->
        val uncategorized = vm.subs.filter { it.categoryId == null }
        val visibleUncategorized = if (hiddenEmpty) uncategorized.filter { (vm.unreads[it.id] ?: 0) > 0 } else uncategorized
        // Tout est lu avec le filtre « sans non-lus » : aucun flux à montrer — le dire
        // plutôt que laisser une page blanche (distinct de « aucun abonnement »).
        val nothingUnread = hiddenEmpty && allFeedsRead(vm.subs, vm.unreads)
        val listState = rememberLazyListState()
        val jumpScope = rememberCoroutineScope()
        // Même tirage que les vues articles : le rond ne reflète que le geste puis s'efface
        // au relâchement, la synchro gardant barre et bouton.
        var gestureRefresh by remember { mutableStateOf(false) }
        // Même balayage de retour que les listes de dossier/flux : pastille « Accueil » puis
        // remontée d'un niveau au relâchement (ici, vers « Tous les articles »).
        val swipe = rememberBackSwipeState()
        val backSwipe = if (onSwipeBack == null) Modifier else Modifier.backSwipe(swipe, onSwipeBack)
        Box(Modifier.padding(pad).fillMaxSize().then(backSwipe)) {
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = gestureRefresh,
                onRefresh = {
                    jumpScope.launch {
                        gestureRefresh = true
                        // Par vm.sync() (suivi, annulable) plutôt qu'un lancement direct :
                        // une déconnexion arrête aussi les synchronisations au geste.
                        vm.sync()
                        kotlinx.coroutines.delay(100)
                        gestureRefresh = false
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer { translationX = swipe.drag }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Les boutons haut/bas flottent en bas au centre : sans cette marge, le
                    // dernier flux restait dessous et ne pouvait pas être vu en entier.
                    contentPadding = PaddingValues(bottom = 72.dp)
                ) {
                    if (vm.subs.isEmpty() && vm.loaded && !vm.syncing) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Aucun abonnement.")
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Ajoutez un flux RSS ou connectez Feedly depuis Comptes.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    if (nothingUnread && vm.loaded && !vm.syncing) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Aucun flux avec des articles non lus.")
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
                                    labelSp = folderSp,
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
                                    onDelete = { toDelete = f },
                                    // Périmètre du dossier entier : tous ses flux, y compris ceux
                                    // masqués par le filtre « sans non-lus » (ils n'ont rien à
                                    // marquer, mais autant ne pas dépendre de l'affichage).
                                    // Lambda : le menu (popup) relit le compteur à l'instant de
                                    // l'affichage (voir le KDoc de FolderRow).
                                    unread = { subs.sumOf { vm.unreads[it.id] ?: 0 } },
                                    onMarkAllRead = { vm.markAllReadFor(subs.map { it.id }) },
                                    account = if (showAccount)
                                        f.providers.map { com.pressia.data.feedAccountLabel(it, vm.accounts) }
                                            .distinct().joinToString(" · ")
                                    else null,
                                    onSearch = { onSearch(Scope(categoryId = f.id, label = f.label)) }
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
                                        vm, s, Modifier.padding(start = 20.dp), feedSp, onOpenStream,
                                        onMove = { moving = s },
                                        onMoveUp = { vm.moveFeedOrder(s.id, -1, s.categoryId) },
                                        onMoveDown = { vm.moveFeedOrder(s.id, 1, s.categoryId) },
                                        canMoveUp = i > 0,
                                        canMoveDown = i < shown.lastIndex,
                                        onMarkAllRead = { vm.markAllReadFor(listOf(s.id)) },
                                        account = if (showAccount) com.pressia.data.feedAccountLabel(s.provider, vm.accounts) else null,
                                        onSearch = { onSearch(Scope(feedId = s.id, label = prettyFeedTitle(s.title))) }
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
                                vm, s, Modifier, feedSp, onOpenStream,
                                onMove = { moving = s },
                                onMoveUp = { vm.moveFeedOrder(s.id, -1, null) },
                                onMoveDown = { vm.moveFeedOrder(s.id, 1, null) },
                                canMoveUp = i > 0,
                                canMoveDown = i < visibleUncategorized.lastIndex,
                                onMarkAllRead = { vm.markAllReadFor(listOf(s.id)) },
                                account = if (showAccount) com.pressia.data.feedAccountLabel(s.provider, vm.accounts) else null,
                                onSearch = { onSearch(Scope(feedId = s.id, label = prettyFeedTitle(s.title))) }
                            )
                        }
                    }
                }
            }
        if (vm.subs.size + vm.folders.size > 3) {
            ScrollJumpOverlay(
                onTop = { jumpScope.launch { listState.animateToTop() } },
                onBottom = {
                    jumpScope.launch {
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
        if (onSwipeBack != null && swipe.drag > 0f) {
            SwipeBackHint(
                swipe.progress, Icons.Filled.Home, "retour à Tous les articles",
                Modifier.align(Alignment.CenterStart)
            )
        }
        }
    }

    if (creating) {
        // Le compte qui recevra le dossier : le seul connecté quand il n'y a que lui, sinon
        // « Local » puis chaque compte (voir `folderOwners`).
        val owners = com.pressia.data.folderOwners(vm.accounts, vm.localFeeds)
            .map { it to com.pressia.data.feedAccountLabel(it, vm.accounts) }
        FolderNameDialog(
            title = "Nouveau dossier",
            initial = "",
            choices = owners,
            onDismiss = { creating = false },
            onConfirm = { name, provider ->
                creating = false
                vm.createFolder(name, provider) { created ->
                    toast(
                        ctx,
                        when {
                            created == null -> "Dossier non créé"
                            com.pressia.data.isLocalProvider(provider) ||
                                !com.pressia.data.isLocalProvider(created.providers.first()) ->
                                "Dossier « ${created.label} » créé"
                            // Inoreader supprime une étiquette vide ; Feedly ne peut rien créer
                            // sans flux. Le dossier reste local jusqu'au premier flux rangé dedans.
                            else -> "Dossier « ${created.label} » local : il rejoindra " +
                                com.pressia.data.serviceLabel(com.pressia.data.providerBase(provider)) +
                                " au premier flux qui y sera rangé"
                        }
                    )
                }
            }
        )
    }
    editing?.let { f ->
        FolderNameDialog(
            title = "Renommer le dossier",
            initial = f.label,
            onDismiss = { editing = null },
            onConfirm = { name, _ ->
                editing = null
                vm.renameFolder(f, name) { ok ->
                    toast(ctx, if (ok) "Dossier renommé" else "Renommage refusé par le compte")
                }
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
                        "Les flux qu'il contient ne sont pas supprimés : ils repassent dans « Flux sans dossier ». S'il appartient à un compte, il y est supprimé aussi."
                )
            },
            confirmButton = {
                TextButton({
                    toDelete = null
                    if (f.merged && f.providers.size > 1) toast(ctx, "Dossier partagé : supprimez-le depuis chaque compte")
                    else vm.deleteFolder(f) { ok ->
                        toast(ctx, if (ok) "Dossier supprimé" else "Suppression refusée par le compte")
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
                vm.addLocalFeed(url, f.id) { res ->
                    toast(ctx, res.message)
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
                    // Lignes compactes plutôt que des ListItem : à deux lignes (libellé + compte),
                    // les ListItem laissaient beaucoup de vide entre les dossiers.
                    MoveTargetRow(
                        label = "Sans dossier",
                        icon = false,
                        enabled = s.categoryId != null
                    ) {
                        moving = null
                        vm.moveFeed(s.id, null)
                        toast(ctx, "Déplacé hors de tout dossier")
                    }
                    vm.folders.forEach { f ->
                        val current = s.categoryId != null && s.categoryId in f.ids
                        MoveTargetRow(
                            label = f.label,
                            // Le compte du dossier, sous son nom : deux « News » de comptes
                            // différents ne se distinguent pas autrement.
                            account = if (showAccount) f.providers
                                .map { com.pressia.data.feedAccountLabel(it, vm.accounts) }
                                .distinct().joinToString(" · ") else null,
                            hint = if (current) "déjà ici" else null,
                            enabled = !current
                        ) {
                            moving = null
                            vm.moveFeed(s.id, f)
                            toast(ctx, "Déplacé dans « ${f.label} »")
                        }
                    }
                }
            },
            confirmButton = { TextButton({ moving = null }) { Text("Annuler") } }
        )
    }
}

/**
 * Ligne de dossier : libellé, compteur, flèche de pliage toujours visible, menu d'actions.
 * Un appui ouvre le flux, un appui long ouvre le menu (qui porte aussi « Tout marquer comme
 * lu » sur le dossier entier), la flèche replie, le menu gère.
 *
 * [unread] est une **lambda** et non un nombre : le menu est un popup, dont le contenu ne se
 * recompose pas sur les changements d'état lus hors de lui (constaté : l'entrée « Tout marquer
 * comme lu » restait grisée alors que le dossier avait des non-lus). Lire la lambda **dans** le
 * lambda du menu la soumet à la recomposition du popup ; le badge, lui, vit dans l'arbre
 * principal et peut l'appeler directement.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    label: String,
    labelSp: Float,
    unread: () -> Int,
    collapsed: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onAddFeed: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onMarkAllRead: () -> Unit,
    /** Compte du dossier, quand la précision est utile (null = masquée). */
    account: String? = null,
    /** Ouvre la recherche sur ce dossier. */
    onSearch: () -> Unit = {}
) {
    // Menu unique, ouvert par l'icône de droite comme par un appui long sur la ligne.
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { open = true })
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
            label, fontSize = labelSp.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 2.dp)
        )
        if (unread() > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(if (unread() > 999) "999+" else "${unread()}")
            }
        }
        Box {
            IconButton({ open = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "actions du dossier", Modifier.size(18.dp)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (account != null) {
                    Text(
                        account,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                DropdownMenuItem(
                    text = { Text("Recherche") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    onClick = { open = false; onSearch() }
                )
                // Marquage groupé du dossier : la ligne d'appui long l'ouvre aussi. Sans non-lus,
                // l'entrée est désactivée — le geste ne doit pas donner l'impression d'agir à vide.
                // `unread()` est relu **ici** : un popup ne se recompose pas sur les valeurs lues
                // hors de lui (voir le KDoc de FolderRow).
                DropdownMenuItem(
                    text = { Text("Tout marquer comme lu") },
                    enabled = unread() > 0,
                    leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                    onClick = { open = false; onMarkAllRead() }
                )
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

/**
 * Saisie du nom d'un dossier (création ou renommage).
 *
 * [choices] = comptes entre lesquels choisir le propriétaire du dossier (`id` du compte, libellé) ;
 * le premier est coché. Rien à choisir quand il n'y en a qu'un : le dossier part chez lui.
 */
@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    choices: List<Pair<String, String>> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var owner by remember { mutableStateOf(choices.firstOrNull()?.first ?: com.pressia.data.LOCAL_PROVIDER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it }, label = { Text("Nom du dossier") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (choices.size > 1) {
                    Text(
                        "Créer dans",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    choices.forEach { (id, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = owner == id, role = Role.RadioButton) { owner = id },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = owner == id, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(name, owner) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } }
    )
}

/**
 * Ligne de la modale de déplacement : libellé, compte du dossier en dessous (petit), icône.
 *
 * Compacte exprès : deux lignes de ListItem laissaient un grand vide entre chaque dossier.
 */
@Composable
private fun MoveTargetRow(
    label: String,
    account: String? = null,
    hint: String? = null,
    icon: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon) Icon(Icons.Filled.Folder, null, Modifier.size(20.dp)) else Spacer(Modifier.width(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label + if (hint != null) " ($hint)" else "")
            if (account != null) {
                Text(
                    account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
                    autoCorrectEnabled = false
                )
            )
        },
        confirmButton = { TextButton({ onConfirm(url.trim()) }, enabled = url.isNotBlank()) { Text("Ajouter") } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubRow(
    vm: PressVM,
    sub: SubscriptionEntity,
    mod: Modifier,
    feedSp: Float,
    onOpenStream: () -> Unit,
    onMove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMarkAllRead: () -> Unit,
    /** Compte du flux, quand la précision est utile (null = masquée). */
    account: String? = null,
    /** Ouvre la recherche sur ce flux. */
    onSearch: () -> Unit = {}
) {
    val pretty = prettyFeedTitle(sub.title)
    val n = vm.unreads[sub.id] ?: 0
    // Menu unique, ouvert par l'icône de droite comme par un appui long sur la ligne.
    var menu by remember { mutableStateOf(false) }
    Row(
        mod.fillMaxWidth().combinedClickable(
            onClick = {
                vm.switchScope(Scope(feedId = sub.id, label = pretty))
                vm.refresh(); onOpenStream()
            },
            onLongClick = { menu = true }
        ).padding(start = 6.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FeedAvatar(sub, 26)
        Spacer(Modifier.width(10.dp))
        Text(
            pretty, fontSize = feedSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (n > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(if (n > 999) "999+" else "$n")
            }
        }
        Box {
            IconButton({ menu = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "actions du flux", Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (account != null) {
                    Text(
                        account,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                DropdownMenuItem(
                    text = { Text("Recherche") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    onClick = { menu = false; onSearch() }
                )
                // Marquage groupé du flux : la ligne d'appui long l'ouvre aussi (voir FolderRow).
                // `vm.unreads` est relu **ici** : un popup ne se recompose pas sur les valeurs lues
                // hors de lui — l'entrée restait grisée alors que le flux avait des non-lus.
                DropdownMenuItem(
                    text = { Text("Tout marquer comme lu") },
                    enabled = (vm.unreads[sub.id] ?: 0) > 0,
                    leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                    onClick = { menu = false; onMarkAllRead() }
                )
                DropdownMenuItem(
                    text = { Text("Déplacer vers un dossier") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
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
