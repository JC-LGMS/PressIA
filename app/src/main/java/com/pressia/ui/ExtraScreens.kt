package com.pressia.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pressia.data.FeedSort
import com.pressia.data.Sections
import com.pressia.data.StatsPeriod
import com.pressia.data.dayLabelShort
import com.pressia.data.exportJson
import com.pressia.data.fmtDuration
import com.pressia.data.importJson
import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.Prefs
import com.pressia.data.sortFeeds
import com.pressia.ui.theme.toHex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Recherche avec portée explicite : la portée est affichée en permanence et élargissable à la
 * demande (Tous les articles / un dossier / un flux), indépendante de la portée du flux principal.
 */
@Composable
fun SearchScreen(vm: PressVM, onOpen: (ArticleEntity) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ArticleEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showScope by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Portée de recherche : par défaut celle du flux affiché, élargissable sans y toucher.
    var searchScope by remember { mutableStateOf(vm.scope) }
    // La portée suit le flux affiché : en revenant à l'accueil (ou en changeant de flux), une
    // portée devenue obsolète est réalignée et les résultats périmés sont vidés. Un choix manuel
    // dans cet écran n'y touche pas : seul switchScope (la navigation) fait bouger vm.scope.
    LaunchedEffect(vm.scope) { searchScope = vm.scope; results = emptyList() }

    Column(Modifier.padding(16.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Rechercher…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true)
        Spacer(Modifier.height(8.dp))

        // Portée courante, visible et modifiable.
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { showScope = !showScope }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (searchScope.feedId != null) Icons.Filled.RssFeed else Icons.Filled.FolderOpen,
                null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Portée : ${searchScope.label}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Touchez pour élargir la recherche",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (showScope) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                "élargir la portée", tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showScope) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                ScopeChoiceRow("Tous les articles", searchScope.feedId == null && searchScope.categoryId == null) {
                    searchScope = Scope(label = "Tous les articles"); results = emptyList()
                }
                vm.categories.forEach { c ->
                    ScopeChoiceRow(c.label, searchScope.categoryId == c.id) {
                        searchScope = Scope(categoryId = c.id, label = c.label); results = emptyList()
                    }
                }
                vm.subs.forEach { s ->
                    ScopeChoiceRow(prettyFeedTitle(s.title), searchScope.feedId == s.id) {
                        searchScope = Scope(feedId = s.id, label = prettyFeedTitle(s.title)); results = emptyList()
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button({
            scope.launch {
                loading = true
                try { results = vm.repo.search(q, searchScope.feedId, searchScope.categoryId) }
                finally { loading = false }
            }
        }, enabled = q.length >= 2) { Text("Chercher") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        Text("${results.size} résultat(s) · ${searchScope.label}")
        ArticleList(results, "list", onOpen, vm, showSource = searchScope.feedId == null)
    }
}

/** Une ligne du sélecteur de portée de recherche. */
@Composable
private fun ScopeChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            null, Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Déduplication.
 *
 * Deux corrections par rapport à la version précédente :
 *  - **aucun scan au démarrage**. L'analyse se lance sur le bouton, sinon l'ouverture de l'écran
 *    déclenchait une requête lourde en arrière-plan et l'écran paraissait vide sans raison ;
 *  - **un seul bouton d'action**, « Tout marquer lu sauf le + récent ». Chaque groupe gardait son
 *    bouton, ce qui multipliait des actions identiques sur une longue liste.
 */
@Composable
fun DedupScreen(vm: PressVM, onOpen: (ArticleEntity) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val feeds = remember(vm.subs) { vm.subs.associateBy { it.id } }
    val totalDup = vm.dups.sumOf { it.size - 1 }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                {
                    scope.launch {
                        vm.loadDups()
                        toast(ctx, "Recherche de doublons terminée")
                    }
                },
                enabled = !vm.syncing
            ) { Text(if (vm.dupScanned) "Re-scanner" else "Rechercher les doublons") }
            Spacer(Modifier.width(12.dp))
            Text(
                if (!vm.dupScanned) "analyse à lancer"
                else if (totalDup > 0) "$totalDup doublon${if (totalDup > 1) "s" else ""}"
                else "aucun doublon",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        if (totalDup > 0) {
            OutlinedButton(
                {
                    scope.launch {
                        vm.markAllDupsRead()
                        toast(ctx, "$totalDup doublon(s) marqué(s) lus")
                    }
                },
                enabled = !vm.syncing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Tout marquer lu sauf le + récent") }
            Spacer(Modifier.height(4.dp))
        }
        vm.dups.forEach { group ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${group.size} articles pour le même sujet",
                        style = MaterialTheme.typography.titleSmall
                    )
                    group.forEach { a ->
                        val newest = a.id == group.firstOrNull()?.id
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).clickable { onOpen(a) }) {
                                Text(
                                    com.pressia.data.remote.cleanTitle(a.title).take(60), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (newest) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    "${prettyFeedTitle(feeds[a.feedId]?.title ?: feeds[a.feedId]?.id)} · " +
                                        "${fmtDay(a.published)} · ${fmtTime(a.published)}" +
                                        if (newest) " · le plus récent" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!newest) {
                                IconButton({
                                    // setRead(id, unread) : false = marquer comme lu.
                                    scope.launch { vm.setRead(a.id, false) }
                                }) {
                                    Icon(
                                        Icons.Filled.RadioButtonUnchecked, "marquer lu",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (vm.dupScanned && vm.dups.isEmpty()) {
            Text("Aucun doublon détecté.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Stats de lecture : périodes (jour / 7 j / 30 j / tout / année), temps par flux trié, ouvertures par jour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: PressVM) {
    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    var year by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(FeedSort.TIME_DESC) }
    LaunchedEffect(period, year) { vm.loadStats(period, year) }
    val s = vm.stats
    val years = vm.statsYears
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.padding(16.dp).verticalScroll(scroll).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPeriod.entries.forEach { p ->
                FilterChip(period == p && year == 0, { period = p; year = 0 }, { Text(p.label) })
            }
        }
        if (years.size > 1) {
            Text("Par année", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.take(6).forEach { y ->
                    FilterChip(year == y, { year = if (year == y) 0 else y }, { Text("$y") })
                }
            }
        }
        if (s == null) { CircularProgressIndicator(); return@Column }
        val rangeLabel = when {
            year > 0 -> "année $year"
            period == StatsPeriod.ALL -> "depuis le début"
            else -> period.label.lowercase()
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(fmtDuration(s.totalSeconds), style = MaterialTheme.typography.displaySmall)
                Text("temps de lecture cumulé ($rangeLabel)")
                Text(
                    "Moyenne : ${fmtDuration(s.avgSeconds)} par article ouvert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${s.totalOpens}", "articles ouverts", Modifier.weight(1f))
            StatCard("${s.readWithoutOpening}", "passés en lu sans ouvrir", Modifier.weight(1f))
        }
        if (s.perCategory.isNotEmpty()) {
            Text("Temps par dossier", style = MaterialTheme.typography.titleMedium)
            val maxCat = s.perCategory.maxOfOrNull { it.second } ?: 1
            s.perCategory.forEach { (label, secs) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtDuration(secs))
                }
                LinearProgressIndicator(
                    progress = { (secs.toFloat() / maxCat).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (s.perFeed.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Temps par flux", style = MaterialTheme.typography.titleMedium)
            }
            // Un seul bouton par grandeur : recliquer inverse le sens de tri et la flèche.
            val sortByTime = sort == FeedSort.TIME_DESC || sort == FeedSort.TIME_ASC
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sortByTime,
                    onClick = { sort = if (sort == FeedSort.TIME_DESC) FeedSort.TIME_ASC else FeedSort.TIME_DESC },
                    label = { Text(if (sort == FeedSort.TIME_ASC) FeedSort.TIME_ASC.label else FeedSort.TIME_DESC.label) }
                )
                FilterChip(
                    selected = !sortByTime,
                    onClick = { sort = if (sort == FeedSort.OPENS_DESC) FeedSort.OPENS_ASC else FeedSort.OPENS_DESC },
                    label = { Text(if (sort == FeedSort.OPENS_ASC) FeedSort.OPENS_ASC.label else FeedSort.OPENS_DESC.label) }
                )
            }
            val sorted = sortFeeds(s.perFeed, sort)
            val maxFeed = sorted.maxOfOrNull { it.second.first }?.coerceAtLeast(1) ?: 1
            sorted.forEach { (label, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(prettyFeedTitle(label).take(40), modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${fmtDuration(v.first)} · ${v.second} ouv.")
                }
                LinearProgressIndicator(
                    progress = { (v.first.toFloat() / maxFeed).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        val totalEx = s.readabilityOk + s.readabilityKo
        if (totalEx > 0) {
            Text("Extraction des articles (Readability)", style = MaterialTheme.typography.titleMedium)
            Text(
                "${s.readabilityOk} ${if (s.readabilityOk > 1) "réussites" else "réussite"} · " +
                    "${s.readabilityKo} ${if (s.readabilityKo > 1) "blocages" else "blocage"} " +
                    "(paywall, anti-robot, cookies, page JavaScript) " +
                    "— ${s.readabilityOk * 100 / totalEx} % du temps",
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Détail journalier utile sur les courtes périodes seulement : inutile (et trompeur)
        // sur « depuis le début » ou une année entière.
        val showPerDay = year == 0 && (period == StatsPeriod.DAY || period == StatsPeriod.WEEK || period == StatsPeriod.MONTH)
        if (s.perDay.isNotEmpty() && showPerDay) {
            Text("Ouvertures par jour", style = MaterialTheme.typography.titleMedium)
            val max = s.perDay.maxOfOrNull { it.second } ?: 1
            s.perDay.takeLast(31).forEach { (day, c) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dayLabelShort(day), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(76.dp), maxLines = 1
                    )
                    LinearProgressIndicator(
                        progress = { c.toFloat() / max },
                        modifier = Modifier.weight(1f).height(10.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("$c", fontSize = 12.sp, modifier = Modifier.width(28.dp), maxLines = 1)
                }
            }
        }
        }
        ColumnPositionIndicator(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
    }
}

@Composable
private fun StatCard(value: String, label: String, mod: Modifier) {
    Card(mod) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Liste déroulante de choix d'action (raccourci, glissement) : affiche le libellé courant,
 * ouvre la liste au toucher. Remplace les rangées de pastilles, illisibles dès qu'il y a
 * plus de quatre choix.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionDropDown(
    options: List<Pair<String, String>>,
    selected: String,
    label: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (id, l) ->
                DropdownMenuItem(
                    text = { Text(l) },
                    onClick = { expanded = false; onSelect(id) }
                )
            }
        }
    }
}

/** Réglages façon Press + sync. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: PressVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    suspend fun <T> get(k: androidx.datastore.preferences.core.Preferences.Key<T>, d: T): T =
        runCatching { vm.repo.prefs.data.first()[k] ?: d }.getOrDefault(d)

    var night by remember { mutableStateOf("dark") }
    var accent by remember { mutableStateOf("red") }
    var bg by remember { mutableStateOf("default") }
    var home by remember { mutableStateOf("subs") }
    var viewMode by remember { mutableStateOf("list") }
    var trailing by remember { mutableStateOf("read") }
    var swipe by remember { mutableStateOf("star") }
    var swipeRight by remember { mutableStateOf("read") }
    var articleActions by remember { mutableStateOf(parseArticleActions(ARTICLE_ACTIONS_DEFAULT)) }
    var fontSize by remember { mutableStateOf(16) }
    var articleTitle by remember { mutableStateOf(20) }
    var titleFont by remember { mutableStateOf(16) }
    var dateFont by remember { mutableStateOf(12) }
    var justify by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(true) }
    var maxItems by remember { mutableStateOf(1000) }
    var imagesOffline by remember { mutableStateOf(true) }
    var interval by remember { mutableStateOf(Prefs.SYNC_INTERVAL_DEFAULT) }
    var openMode by remember { mutableStateOf("internal") }
    var mergeFolders by remember { mutableStateOf(true) }
    var drawerScale by remember { mutableStateOf(100) }
    var homeScale by remember { mutableStateOf(100) }
    var syncOnLaunch by remember { mutableStateOf(true) }
    var showAccentPicker by remember { mutableStateOf(false) }
    var showBgPicker by remember { mutableStateOf(false) }
    var cacheInfo by remember { mutableStateOf("calcul…") }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showArticleActions by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf("") }
    var exportSel by remember { mutableStateOf(Sections.ALL) }
    var exportTokens by remember { mutableStateOf(false) }
    var importSel by remember { mutableStateOf(Sections.ALL) }
    var importReplace by remember { mutableStateOf(false) }

    suspend fun refreshCacheInfo() {
        cacheInfo = vm.repo.cacheInfo()
    }

    /**
     * (Re)charge l'état local de l'écran depuis les réglages enregistrés. Indispensable après un
     * import : sans cela les valeurs importées ne s'affichent pas, et la première modification
     * utilisateur réécrirait par-dessus l'import avec les anciennes valeurs.
     */
    suspend fun reloadFromPrefs() {
        night = get(Prefs.NIGHT_THEME, "dark"); accent = get(Prefs.ACCENT, "red"); bg = get(Prefs.BG, "default"); home = get(Prefs.HOME_SCREEN, "subs")
        viewMode = get(Prefs.VIEW_MODE, "list"); trailing = get(Prefs.TRAILING_ACTION, "read")
        swipe = get(Prefs.SWIPE_ACTION, "star")
        swipeRight = get(Prefs.SWIPE_RIGHT_ACTION, "read")
        articleActions = parseArticleActions(get(Prefs.ARTICLE_ACTIONS, ARTICLE_ACTIONS_DEFAULT))
        fontSize = get(Prefs.FONT_SIZE, 16); articleTitle = get(Prefs.ARTICLE_TITLE_SIZE, 20)
        titleFont = get(Prefs.TITLE_FONT_SIZE, 16)
        dateFont = get(Prefs.DATE_FONT_SIZE, 12); justify = get(Prefs.JUSTIFY, false)
        wifiOnly = get(Prefs.WIFI_ONLY, true); maxItems = get(Prefs.MAX_ITEMS, 1000)
        imagesOffline = get(Prefs.IMAGES_OFFLINE, true); interval = get(Prefs.SYNC_INTERVAL_MIN, Prefs.SYNC_INTERVAL_DEFAULT)
        openMode = get(Prefs.OPEN_MODE, "internal"); mergeFolders = get(Prefs.MERGE_FOLDERS, true)
        drawerScale = get(Prefs.DRAWER_TEXT_SCALE, 100); homeScale = get(Prefs.HOME_TEXT_SCALE, 100)
        syncOnLaunch = get(Prefs.SYNC_ON_LAUNCH, true)
        refreshCacheInfo()
    }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val json = vm.repo.exportJson(exportSel, exportTokens)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                backupMsg = "Sauvegarde écrite (${json.length / 1024} Ko)."
                toast(ctx, "Sauvegarde exportée")
            } catch (e: Exception) {
                backupMsg = "Export impossible : ${e.message}"
            }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val json = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw Exception("fichier illisible")
                val r = vm.repo.importJson(json, importSel, importReplace)
                // Les réglages importés doivent s'appliquer immédiatement à l'écran ouvert…
                reloadFromPrefs()
                backupMsg = "Import terminé : ${r.prefs} option(s), ${r.accounts} compte(s), " +
                    "${r.stats} statistique(s), ${r.read} favori(s)."
                vm.refresh(); vm.loadAccount()
                toast(ctx, "Sauvegarde importée")
            } catch (e: Exception) {
                backupMsg = "Import impossible : ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { reloadFromPrefs() }
    // Progression de l'actualisation images (toasts démarré/étapes/terminé) : le travail vit
    // dans le modèle et survit à la navigation, l'écran ne fait que consommer.
    LaunchedEffect(vm.favToast) {
        vm.favToast?.let { toast(ctx, it); vm.favToast = null }
    }
    // Taille du cache à jour quand l'actualisation se termine (aussi sous nos yeux que de retour).
    LaunchedEffect(vm.refreshingFavicons) {
        if (!vm.refreshingFavicons) refreshCacheInfo()
    }
    fun clearCache() = scope.launch {
        vm.repo.clearCache()
        coil.Coil.imageLoader(ctx).diskCache?.clear()
        refreshCacheInfo()
        vm.refresh()
    }
    fun save() = scope.launch {
        vm.repo.prefs.set(Prefs.NIGHT_THEME, night); vm.repo.prefs.set(Prefs.ACCENT, accent); vm.repo.prefs.set(Prefs.BG, bg)
        vm.repo.prefs.set(Prefs.HOME_SCREEN, home)
        vm.repo.prefs.set(Prefs.VIEW_MODE, viewMode); vm.repo.prefs.set(Prefs.TRAILING_ACTION, trailing)
        vm.repo.prefs.set(Prefs.SWIPE_ACTION, swipe)
        vm.repo.prefs.set(Prefs.SWIPE_RIGHT_ACTION, swipeRight)
        vm.repo.prefs.set(Prefs.ARTICLE_ACTIONS, articleActions.joinToString(","))
        vm.repo.prefs.set(Prefs.FONT_SIZE, fontSize); vm.repo.prefs.set(Prefs.ARTICLE_TITLE_SIZE, articleTitle)
        vm.repo.prefs.set(Prefs.TITLE_FONT_SIZE, titleFont)
        vm.repo.prefs.set(Prefs.DATE_FONT_SIZE, dateFont); vm.repo.prefs.set(Prefs.JUSTIFY, justify)
        vm.repo.prefs.set(Prefs.WIFI_ONLY, wifiOnly); vm.repo.prefs.set(Prefs.MAX_ITEMS, maxItems)
        vm.repo.prefs.set(Prefs.IMAGES_OFFLINE, imagesOffline); vm.repo.prefs.set(Prefs.SYNC_INTERVAL_MIN, interval)
        vm.repo.prefs.set(Prefs.OPEN_MODE, openMode); vm.repo.prefs.set(Prefs.MERGE_FOLDERS, mergeFolders)
        vm.repo.prefs.set(Prefs.DRAWER_TEXT_SCALE, drawerScale)
        vm.repo.prefs.set(Prefs.HOME_TEXT_SCALE, homeScale)
        vm.repo.prefs.set(Prefs.SYNC_ON_LAUNCH, syncOnLaunch)
        com.pressia.data.sync.SyncWorker.schedule(ctx, interval, wifiOnly)
        vm.refresh()
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.padding(16.dp).verticalScroll(scroll).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Affichage", style = MaterialTheme.typography.titleMedium)
        Text("Thème de nuit (bouton Nuit du menu)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Sombre", "amoled" to "AMOLED").forEach { (id, l) ->
                FilterChip(night == id, { night = id; save() }, { Text(l) })
            }
        }
        Text(
            "Le menu n'offre que trois apparences : auto, jour et nuit. En mode nuit, c'est le thème " +
                "choisi ici qui s'applique — AMOLED force un fond noir, plus sombre que le thème Sombre.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Couleur dominante")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.pressia.ui.theme.ACCENTS.forEach { (id, c) ->
                val sel = accent == id
                Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(c)
                        .border(
                            width = if (sel) 3.dp else 1.dp,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .clickable { accent = id; save() }
                )
            }
            val customAccent = accent.startsWith("#")
            Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (customAccent) com.pressia.ui.theme.parseHexColor(accent) ?: MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (customAccent) 3.dp else 1.dp,
                        color = if (customAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable { showAccentPicker = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, "couleur personnalisée")
            }
        }
        Text("Couleur de fond")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.pressia.ui.theme.BACKGROUNDS.forEach { choice ->
                val sel = bg == choice.id
                val raw = com.pressia.ui.theme.resolveBackground(choice.id, dark = false)
                val swatch = raw ?: MaterialTheme.colorScheme.surfaceVariant
                Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(swatch)
                        .border(
                            width = if (sel) 3.dp else 1.dp,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .clickable { bg = choice.id; save() },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    if (raw == null) Text("A", style = MaterialTheme.typography.labelLarge)
                }
            }
            val customBg = bg.startsWith("#")
            Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (customBg) com.pressia.ui.theme.parseHexColor(bg) ?: MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = if (customBg) 3.dp else 1.dp,
                        color = if (customBg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable { showBgPicker = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Add, "couleur personnalisée")
            }
        }
        HorizontalDivider()
        Text("Page de démarrage")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("subs" to "Accueil", "stream" to "Tous les articles").forEach { (id, l) ->
                FilterChip(home == id, { home = id; save() }, { Text(l) })
            }
        }
        Text("Ouverture des articles")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("internal" to "Dans PressIA", "custom" to "Onglet\npersonnalisé", "external" to "Navigateur").forEach { (id, l) ->
                FilterChip(openMode == id, { openMode = id; save() }, { Text(l) })
            }
        }
        Text(
            when (openMode) {
                "external" -> "Toucher un article l'ouvre directement dans le navigateur. La vue interne (extrait hors-ligne) reste accessible en revenant sur l'article."
                "custom" -> "L'article s'ouvre dans un onglet de navigateur intégré, avec votre compte et vos historique : presque comme le navigateur, mais sans quitter l'application. La vue interne reste accessible en revenant sur l'article."
                else -> "Toucher un article l'ouvre dans PressIA, avec l'extrait hors-ligne et l'article complet au double-tap."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Dossiers des comptes")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mergeFolders, { mergeFolders = true; save() }, { Text("Fusionner") })
            FilterChip(!mergeFolders, { mergeFolders = false; save() }, { Text("Séparer") })
        }
        Text(
            if (mergeFolders)
                "Un seul dossier par nom commun à tous vos comptes, comme dans Feedly."
            else
                "Un dossier par compte, même si deux comptes ont un dossier du même nom : les vôtres ne peuvent plus se confondre avec ceux d'un autre compte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Vue des articles")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("compact" to "Compacte", "list" to "Liste", "card" to "Carte", "magazine" to "Magazine").forEach { (id, l) ->
                FilterChip(viewMode == id, { viewMode = id; save() }, { Text(l) })
            }
        }
        Text("Bouton de raccourci à droite de chaque article")
        ActionDropDown(
            options = listOf("star" to "Favoris", "read" to "Lu/non-lu", "share" to "Partager", "instapaper" to "Instapaper", "none" to "Aucun"),
            selected = trailing,
            label = "Bouton de raccourci",
            onSelect = { trailing = it; save() }
        )
        Text(
            "Les favoris se gèrent aussi en glissant l'article vers la gauche ou vers la droite, selon les deux réglages suivants.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Glissement d'un article vers la gauche")
        ActionDropDown(
            options = listOf("star" to "Favoris", "read" to "Lu/non-lu", "share" to "Partager", "instapaper" to "Instapaper", "none" to "Aucun"),
            selected = swipe,
            label = "Action du glissement",
            onSelect = { swipe = it; save() }
        )
        Text("Glissement d'un article vers la droite")
        ActionDropDown(
            options = listOf("star" to "Favoris", "read" to "Lu/non-lu", "share" to "Partager", "instapaper" to "Instapaper", "none" to "Aucun"),
            selected = swipeRight,
            label = "Action du glissement",
            onSelect = { swipeRight = it; save() }
        )
        Row(
            Modifier.fillMaxWidth().clickable { showArticleActions = true },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Icônes d'action de l'écran article")
                Text(
                    if (articleActions.isEmpty()) "Aucune (menu seul)"
                    else articleActions.joinToString(" · ") { articleActionLabel(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, "modifier")
        }
        if (showArticleActions) {
            ArticleActionsDialog(
                selected = articleActions,
                onDismiss = { showArticleActions = false },
                onChange = { articleActions = it; save() }
            )
        }
        val tapFav by vm.tapFaviconRead.collectAsState()
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Favicon/miniature marque lu/non lu"); Spacer(Modifier.weight(1f)); Switch(tapFav, { scope.launch { vm.repo.prefs.set(com.pressia.data.local.Prefs.TAP_FAVICON_READ, it) } })
        }
        Text(
            "Toucher le favicon du flux ou la vignette de l'article le marque lu ou non lu sans l'ouvrir. " +
                "Désactivé, le changement d'état passe par l'ouverture de l'article, ou par le bouton de " +
                "raccourci ci-dessus s'il est réglé sur « Lu/non-lu ».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Text("Police des titres de liste : $titleFont sp")
        Slider(titleFont.toFloat(), { titleFont = it.toInt(); save() }, valueRange = 8f..24f, steps = 15)
        Text("Police des dates et heures : $dateFont sp")
        Slider(dateFont.toFloat(), { dateFont = it.toInt(); save() }, valueRange = 8f..16f, steps = 7)
        Text("Police du titre des articles : $articleTitle sp")
        Slider(articleTitle.toFloat(), { articleTitle = it.toInt(); save() }, valueRange = 8f..28f, steps = 19)
        Text("Police du texte des articles : $fontSize sp")
        Slider(fontSize.toFloat(), { fontSize = it.toInt(); save() }, valueRange = 8f..24f, steps = 15)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // Taille du texte du menu : sur un drawer très rempli, la taille par défaut ne laisse pas
        // la place de tenir le nom des flux. Le réglage est en pourcentage, donc 100 % ne change rien.
        Text("Taille du texte du menu : $drawerScale %")
        Slider(drawerScale.toFloat(), { drawerScale = it.toInt(); save() }, valueRange = 70f..140f, steps = 13)
        Text(
            "Concerne les noms de flux et de dossiers du menu latéral. 100 % correspond à la taille actuelle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Taille du texte de l'accueil : $homeScale %")
        Slider(homeScale.toFloat(), { homeScale = it.toInt(); save() }, valueRange = 70f..140f, steps = 13)
        Text(
            "Concerne les noms de flux et de dossiers de l'écran Accueil. 100 % correspond à la taille actuelle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        Text("Hors-ligne & synchronisation", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Synchroniser au lancement"); Spacer(Modifier.weight(1f))
            Switch(syncOnLaunch, { syncOnLaunch = it; save() })
        }
        Text(
            "Toutes les synchronisations récupèrent la même chose : les nouveaux articles de tous " +
                "vos comptes ainsi que le tableau « Read later ». Au démarrage, mais pas si une " +
                "synchronisation s'est bien finie il y a moins longtemps que la « Fréquence de " +
                "synchronisation » ci-dessous (10 minutes par défaut) : les données sont déjà là. " +
                "Option coupée : rien n'est récupéré au démarrage. " +
                "Le bouton d'actualisation, lui, marche toujours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Un seul réglage de volume : le nombre d'articles conservés. La fréquence règle la planification en arrière-plan, et la fenêtre pendant laquelle le lancement ne resynchronise pas.",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Wi-Fi uniquement"); Spacer(Modifier.weight(1f)); Switch(wifiOnly, { wifiOnly = it; save() })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Images hors-ligne"); Spacer(Modifier.weight(1f)); Switch(imagesOffline, { imagesOffline = it; save() })
        }
        Text("Articles conservés par flux : $maxItems (hors-ligne)")
        Slider(maxItems.toFloat(), { maxItems = it.toInt(); save() }, valueRange = 100f..10000f, steps = 99)
        Text(
            if (interval <= 0) "Fréquence de synchronisation : manuelle"
            else "Fréquence de synchronisation : toutes les $interval min"
        )
        // 73 crans de 10 min sur 0..720 : le défaut de 10 min est sélectionnable au cran près.
        Slider(interval.toFloat(), { interval = it.toInt(); save() }, valueRange = 0f..720f, steps = 71)
        Text("Cache utilisé : $cacheInfo")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scope.launch { clearCache(); toast(ctx, "Cache vidé") } }) { Text("Vider le cache") }
            FilledTonalButton(
                { vm.refreshFavicons() },
                enabled = !vm.refreshingFavicons,
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                if (vm.refreshingFavicons) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (vm.refreshingFavicons) "Actualisation…" else "Actualiser favicons",
                    textAlign = TextAlign.Center
                )
            }
        }
        HorizontalDivider()
        Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
        Text(
            "Exporter ou importer en un seul fichier : les options, les statistiques, les comptes " +
                "et les favoris de vos flux RSS locaux. Vous choisissez ce que vous voulez inclure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ showExportDialog = true }) { Text("Exporter…") }
            OutlinedButton({ showImportDialog = true }) { Text("Importer…") }
        }
        if (backupMsg.isNotBlank()) Text(backupMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        ColumnPositionIndicator(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
    }
    if (showAccentPicker) {
        ColorPickerDialog("Couleur dominante", accent, { showAccentPicker = false }) {
            accent = it; showAccentPicker = false; save()
        }
    }
    if (showBgPicker) {
        ColorPickerDialog("Couleur de fond", bg, { showBgPicker = false }) {
            bg = it; showBgPicker = false; save()
        }
    }
    if (showExportDialog) {
        SectionPickerDialog(
            title = "Exporter dans un fichier",
            onDismiss = { showExportDialog = false },
            onConfirm = { sel, tokens, _ ->
                showExportDialog = false
                exportSel = sel; exportTokens = tokens
                exportLauncher.launch("pressia-sauvegarde-${stampNow()}.json")
            }
        )
    }
    if (showImportDialog) {
        SectionPickerDialog(
            title = "Importer depuis un fichier",
            extraCheck = "Remplacer les statistiques existantes",
            onDismiss = { showImportDialog = false },
            onConfirm = { sel, _, replace ->
                showImportDialog = false
                importSel = sel; importReplace = replace
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        )
    }
}

/** Modale de choix des icônes d'action de l'écran article : visibilité + ordre. */
@Composable
private fun ArticleActionsDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onChange: (List<String>) -> Unit
) {
    fun move(id: String, delta: Int) {
        val cur = selected.toMutableList()
        val from = cur.indexOf(id)
        val to = (from + delta).coerceIn(0, cur.lastIndex)
        if (from != to) {
            cur.add(to, cur.removeAt(from))
            onChange(cur)
        }
    }
    // Les décochées suivent dans l'ordre canonique : les cocher les ajoute en fin de liste,
    // d'où on les remonte avec les flèches.
    val orderedIds = selected + (ARTICLE_ACTION_IDS - selected.toSet())
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Icônes d'action", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Cochez celles à afficher, dans l'ordre voulu avec les flèches. Le menu reste toujours en dernier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                orderedIds.forEach { id ->
                    val enabled = id in selected
                    val pos = selected.indexOf(id)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(enabled, {
                            onChange(if (enabled) selected - id else selected + id)
                        })
                        Text(articleActionLabel(id), Modifier.weight(1f))
                        IconButton({ move(id, -1) }, enabled = enabled && pos > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, "monter")
                        }
                        IconButton({ move(id, 1) }, enabled = enabled && pos < selected.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, "descendre")
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Fermer") }
                }
            }
        }
    }
}

/** Horodatage court pour le nom du fichier de sauvegarde. */
private fun stampNow(): String {
    val c = java.util.Calendar.getInstance()
    return "%04d%02d%02d-%02dh%02d".format(
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
        c.get(java.util.Calendar.MINUTE)
    )
}

/** Sélecteur des sections à inclure dans la sauvegarde / la restauration. */
@Composable
private fun SectionPickerDialog(
    title: String,
    extraCheck: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (sections: List<String>, includeTokens: Boolean, extra: Boolean) -> Unit
) {
    val labels = mapOf(
        Sections.PREFS to "Options (thème, couleurs, polices, vues…)",
        Sections.STATS to "Statistiques de lecture et d'extraction",
        Sections.ACCOUNTS to "Comptes et flux (abonnements, dossiers)",
        Sections.READ to "Favoris (étoiles) et lus des flux locaux"
    )
    var sel by remember { mutableStateOf(Sections.ALL) }
    var tokens by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Sections.ALL.forEach { id ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            sel = if (id in sel) sel - id else sel + id
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(id in sel, { sel = if (id in sel) sel - id else sel + id })
                        Text(labels[id] ?: id, Modifier.padding(start = 4.dp))
                    }
                }
                if (extraCheck != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { extra = !extra },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(extra, { extra = !extra })
                        Text(extraCheck, Modifier.padding(start = 4.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { tokens = !tokens },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(tokens, { tokens = !tokens })
                    Text("Inclure les jetons de connexion", Modifier.padding(start = 4.dp))
                }
                Text(
                    "Les jetons de connexion sont des mots de passe : ne partagez le fichier qu'à une personne de confiance.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Annuler") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        { onConfirm(sel.ifEmpty { Sections.ALL }, tokens, extra) },
                        enabled = sel.isNotEmpty()
                    ) { Text(if (extraCheck != null) "Importer" else "Exporter") }
                }
            }
        }
    }
}

/** Sélecteur de couleur libre (teinte / saturation / luminosité + aperçu hexadécimal). */
@Composable
fun ColorPickerDialog(title: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = remember(current) {
        com.pressia.ui.theme.parseHexColor(current)
            ?: com.pressia.ui.theme.accentSeed(current)
    }
    var hsv by remember(current) { mutableStateOf(com.pressia.ui.theme.colorToHsv(start)) }
    val color = androidx.compose.ui.graphics.Color.hsv(hsv[0], hsv[1], hsv[2])
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(color.toHex(), style = MaterialTheme.typography.titleMedium)
                }
                Text("Teinte : ${hsv[0].toInt()}°")
                Slider(hsv[0], { hsv = floatArrayOf(it, hsv[1], hsv[2]) }, valueRange = 0f..360f)
                Text("Saturation : ${(hsv[1] * 100).toInt()} %")
                Slider(hsv[1], { hsv = floatArrayOf(hsv[0], it, hsv[2]) }, valueRange = 0f..1f)
                Text("Luminosité : ${(hsv[2] * 100).toInt()} %")
                Slider(hsv[2], { hsv = floatArrayOf(hsv[0], hsv[1], it) }, valueRange = 0f..1f)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onDismiss) { Text("Annuler") }
                    Spacer(Modifier.width(8.dp))
                    Button({ onPick(color.toHex()) }) { Text("Valider") }
                }
            }
        }
    }
}

/** Écran « À propos » : nom, version, description courte et bouton vers le code source. */
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(com.pressia.R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.height(12.dp))
        Text("PressIA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!version.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "Version $version", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Lecteur RSS Android développé grâce à OpenCode et Muse Spark 1.3 free par " +
                "Jean-Cyril Poiraud (inspiré par Press) : Feedly ou flux locaux, " +
                "100 % Jetpack Compose, lecture hors-ligne et article complet via Readability.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton({ openUrl(ctx, "https://github.com/JC-LGMS/PressIA-JCP") }) {
            Text("Code source")
        }
    }
}
