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
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
        // Les traces de lecture ne partent plus avec un flux ou un compte supprimé : c'est donc
        // à l'utilisateur de décider quand les statistiques repartent de zéro. Confirmation
        // obligatoire, la remise à zéro étant irréversible.
        HorizontalDivider(Modifier.padding(top = 8.dp))
        OutlinedButton({ confirmReset = true }, Modifier.fillMaxWidth()) {
            Text("Remettre les statistiques à zéro")
        }
        }
        ColumnPositionIndicator(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Remettre les statistiques à zéro ?") },
            text = {
                Text(
                    "Temps de lecture cumulé, articles ouverts, réussites d'extraction : tout " +
                        "repart de maintenant. Vos articles, leurs états lus/non lus, vos favoris " +
                        "et vos réglages ne sont pas touchés."
                )
            },
            confirmButton = {
                TextButton({
                    confirmReset = false
                    scope.launch {
                        vm.repo.resetStats()
                        vm.loadStats(period, year)
                    }
                }) { Text("Remettre à zéro", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Annuler") } }
        )
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

/** Choix radio d'une source de favicons (même motif que le mode d'envoi Instapaper). */
@Composable
private fun FaviconSourceRow(
    selected: Boolean,
    title: String,
    help: String,
    refreshing: Boolean,
    onSelect: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect, enabled = !refreshing).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = !refreshing)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Séparateur de section des Réglages : filet discret puis titre coloré, pour regrouper les
 * options par usage plutôt que de les aligner en un seul bloc. Le premier titre de l'écran se
 * passe du filet (la barre du haut fait déjà la séparation) — voir [first].
 */
@Composable
private fun SettingsSection(title: String, first: Boolean = false) {
    if (!first) HorizontalDivider(Modifier.padding(top = 4.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
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
    var historyMonths by remember { mutableStateOf(Prefs.HISTORY_MONTHS_DEFAULT) }
    var faviconSource by remember { mutableStateOf(com.pressia.data.FAVICON_SOURCE_AGGREGATOR) }
    var imagesOffline by remember { mutableStateOf(true) }
    var interval by remember { mutableStateOf(Prefs.SYNC_INTERVAL_DEFAULT) }
    var openMode by remember { mutableStateOf("internal") }
    var mergeFolders by remember { mutableStateOf(true) }
    var readabilitySticky by remember { mutableStateOf(false) }
    var doubleTap by remember { mutableStateOf(Prefs.DOUBLE_TAP_DEFAULT) }
    var artSwipeLeft by remember { mutableStateOf(Prefs.ARTICLE_SWIPE_LEFT_DEFAULT) }
    var artSwipeRight by remember { mutableStateOf(Prefs.ARTICLE_SWIPE_RIGHT_DEFAULT) }
    var drawerScale by remember { mutableStateOf(100) }
    var homeScale by remember { mutableStateOf(100) }
    var syncOnLaunch by remember { mutableStateOf(true) }
    var showAccentPicker by remember { mutableStateOf(false) }
    var showBgPicker by remember { mutableStateOf(false) }
    var cacheInfo by remember { mutableStateOf("calcul…") }
    var cacheDetail by remember { mutableStateOf("") }
    var showArticleActions by remember { mutableStateOf(false) }

    suspend fun refreshCacheInfo() {
        cacheInfo = vm.repo.cacheInfo()
        cacheDetail = vm.repo.cacheDetail()
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
        historyMonths = get(Prefs.HISTORY_MONTHS, Prefs.HISTORY_MONTHS_DEFAULT)
        faviconSource = get(Prefs.FAVICON_SOURCE, com.pressia.data.FAVICON_SOURCE_AGGREGATOR)
        imagesOffline = get(Prefs.IMAGES_OFFLINE, true); interval = get(Prefs.SYNC_INTERVAL_MIN, Prefs.SYNC_INTERVAL_DEFAULT)
        openMode = get(Prefs.OPEN_MODE, "internal"); mergeFolders = get(Prefs.MERGE_FOLDERS, true)
        readabilitySticky = get(Prefs.READABILITY_STICKY, false)
        doubleTap = get(Prefs.DOUBLE_TAP_ACTION, Prefs.DOUBLE_TAP_DEFAULT)
        artSwipeLeft = get(Prefs.ARTICLE_SWIPE_LEFT, Prefs.ARTICLE_SWIPE_LEFT_DEFAULT)
        artSwipeRight = get(Prefs.ARTICLE_SWIPE_RIGHT, Prefs.ARTICLE_SWIPE_RIGHT_DEFAULT)
        drawerScale = get(Prefs.DRAWER_TEXT_SCALE, 100); homeScale = get(Prefs.HOME_TEXT_SCALE, 100)
        syncOnLaunch = get(Prefs.SYNC_ON_LAUNCH, true)
        refreshCacheInfo()
    }

    LaunchedEffect(Unit) { reloadFromPrefs() }
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
    /**
     * Change la source des favicons puis les actualise aussitôt (cache vidé +
     * re-téléchargés) : le changement se voit sans passer par le bouton dédié.
     * Écriture synchrone avant l'actualisation pour que celle-ci lise la nouvelle valeur.
     */
    fun pickFaviconSource(v: String) = scope.launch {
        if (vm.refreshingFavicons) return@launch
        faviconSource = v
        vm.repo.prefs.set(Prefs.FAVICON_SOURCE, v)
        vm.refreshFavicons()
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
        vm.repo.prefs.set(Prefs.HISTORY_MONTHS, historyMonths)
        vm.repo.prefs.set(Prefs.FAVICON_SOURCE, faviconSource)
        vm.repo.prefs.set(Prefs.IMAGES_OFFLINE, imagesOffline); vm.repo.prefs.set(Prefs.SYNC_INTERVAL_MIN, interval)
        vm.repo.prefs.set(Prefs.OPEN_MODE, openMode); vm.repo.prefs.set(Prefs.MERGE_FOLDERS, mergeFolders)
        vm.repo.prefs.set(Prefs.READABILITY_STICKY, readabilitySticky)
        vm.repo.prefs.set(Prefs.DOUBLE_TAP_ACTION, doubleTap)
        vm.repo.prefs.set(Prefs.ARTICLE_SWIPE_LEFT, artSwipeLeft)
        vm.repo.prefs.set(Prefs.ARTICLE_SWIPE_RIGHT, artSwipeRight)
        vm.repo.prefs.set(Prefs.DRAWER_TEXT_SCALE, drawerScale)
        vm.repo.prefs.set(Prefs.HOME_TEXT_SCALE, homeScale)
        vm.repo.prefs.set(Prefs.SYNC_ON_LAUNCH, syncOnLaunch)
        vm.refresh()
    }

    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.padding(16.dp).verticalScroll(scroll).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection("Affichage", first = true)
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
        SettingsSection("Accueil")
        Text("Page de démarrage")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("subs" to "Accueil", "stream" to "Tous les articles").forEach { (id, l) ->
                FilterChip(home == id, { home = id; save() }, { Text(l) })
            }
        }
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
        SettingsSection("Lecture des articles")
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
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Readability persistant"); Spacer(Modifier.weight(1f))
            Switch(readabilitySticky, { readabilitySticky = it; save() })
        }
        Text(
            "L'icône « Readability » de l'écran article " +
                "passe tous les articles ouverts en article complet. Coupé (défaut), quitter l'écran " +
                "article désactive ce mode — le balayage d'un article à l'autre le conserve, lui ; " +
                "coché, il reste jusqu'à désactivation manuelle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Double-tap sur un article")
        ActionDropDown(
            options = DOUBLE_TAP_IDS.map { it to articleNavLabel(it) },
            selected = doubleTap,
            label = "Double-tap",
            onSelect = { doubleTap = it; save() }
        )
        Text(
            "Vaut aussi pour les images de l'article : un simple tap en ouvre une en grand, un double-tap " +
                "y déclenche la même action que ci-dessus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Balayage gauche dans un article")
        ActionDropDown(
            options = ARTICLE_SWIPE_IDS.filter { it != "newer" }.map { it to articleNavLabel(it) },
            selected = artSwipeLeft,
            label = "Balayage gauche",
            onSelect = { artSwipeLeft = it; save() }
        )
        Text("Balayage droit dans un article")
        ActionDropDown(
            options = ARTICLE_SWIPE_IDS.filter { it != "older" }.map { it to articleNavLabel(it) },
            selected = artSwipeRight,
            label = "Balayage droit",
            onSelect = { artSwipeRight = it; save() }
        )
        Text(
            "Par défaut, gauche = article plus ancien et droite = article plus récent. " +
                "Toute autre action remplace la navigation dans ce sens (« Aucun » : le geste ne fait rien).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsSection("Listes d'articles")
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
        SettingsSection("Tailles de police")
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
        SettingsSection("Hors-ligne & synchronisation")
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
        Text("Un seul réglage de volume : le nombre d'articles conservés. La fréquence règle la fenêtre pendant laquelle le lancement ne resynchronise pas (pas de synchronisation quand l'application est fermée).",
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
            if (historyMonths <= 0) "Durée d'historique hors favoris : sans limite de temps"
            else "Durée d'historique hors favoris : $historyMonths mois"
        )
        // 0 = illimité, 1..36 mois calendaires (défaut 1, fenêtre Feedly ~31 j) : ne joue
        // qu'à la connexion d'un compte, favoris toujours gardés — le quota par flux reste
        // le garde-fou dans tous les cas.
        Slider(historyMonths.toFloat(), { historyMonths = it.toInt(); save() }, valueRange = 0f..36f, steps = 35)
        Text(
            "1 mois (défaut) : synchronisation rapide, la fenêtre part directement à Feedly. " +
                "Au-delà, la première synchronisation et les rattrapages paginent plus loin et sont plus lents.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (interval <= 0) "Fréquence de synchronisation : manuelle"
            else "Fréquence de synchronisation : toutes les $interval min"
        )
        // 73 crans de 10 min sur 0..720 : le défaut de 10 min est sélectionnable au cran près.
        Slider(interval.toFloat(), { interval = it.toInt(); save() }, valueRange = 0f..720f, steps = 71)
        Text("Cache utilisé : $cacheInfo")
        if (cacheDetail.isNotBlank()) {
            Text(
                "Détail : $cacheDetail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ scope.launch { clearCache(); toast(ctx, "Cache vidé") } }) { Text("Vider le cache") }
        }
        SettingsSection("Favicons")
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
        Text("Favicons : agrégateur ou sources en direct")
        Text(
            "Préchargées seulement quand il y a lieu : première synchronisation, flux arrivé depuis, cache " +
                "vidé, puis une fois par mois. Le bouton ci-dessus les refait tout de suite, dans tous les cas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FaviconSourceRow(
            faviconSource == com.pressia.data.FAVICON_SOURCE_AGGREGATOR,
            "Agrégateur",
            "Icônes via le service Google, sans visiter les sites (défaut).",
            refreshing = vm.refreshingFavicons
        ) { pickFaviconSource(com.pressia.data.FAVICON_SOURCE_AGGREGATOR) }
        FaviconSourceRow(
            faviconSource == com.pressia.data.FAVICON_SOURCE_DIRECT,
            "Sources en direct",
            "Icônes déclarées par les sites eux-mêmes (repli : /favicon.ico).",
            refreshing = vm.refreshingFavicons
        ) { pickFaviconSource(com.pressia.data.FAVICON_SOURCE_DIRECT) }
        // Le bandeau de synchro se pose au-dessus du bas de l'écran, par-dessus le contenu :
        // sans cette réserve, la dernière section (Favicons) restait cachée derrière lui sans
        // qu'on puisse défiler plus bas pour la lire.
        Spacer(Modifier.height(72.dp))
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
}

/** Écran « Sauvegarde/Restauration » : export/import du fichier unique + date de dernière sauvegarde. */
@Composable
fun BackupScreen(vm: PressVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var backupMsg by remember { mutableStateOf("") }
    var exportSel by remember { mutableStateOf(Sections.ALL) }
    var exportTokens by remember { mutableStateOf(false) }
    var importSel by remember { mutableStateOf(Sections.ALL) }
    var importReplace by remember { mutableStateOf(false) }
    // Date de la dernière sauvegarde exportée (0 = aucune) : suit le réglage en direct.
    val lastBackupAt by remember(vm.repo.prefs) {
        vm.repo.prefs.flow(Prefs.LAST_BACKUP_AT, 0L)
    }.collectAsState(initial = 0L)
    val lastBackupMeta by remember(vm.repo.prefs) {
        vm.repo.prefs.flow(Prefs.LAST_BACKUP_META, "")
    }.collectAsState(initial = "")

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val json = vm.repo.exportJson(exportSel, exportTokens)
                // Écriture vérifiée (voir writeBackupFile) : sans elle, un flux de sortie nul ou
                // tronqué laissait annoncer « Sauvegarde effectuée » alors qu'aucun fichier
                // n'existait — le pire des messages, puisqu'il décourage de recommencer.
                val written = writeBackupFile(ctx, uri, json.toByteArray())
                // Restaurer n'est pas sauvegarder : seule une exportation réussie pose la date.
                val now = System.currentTimeMillis()
                vm.repo.prefs.set(Prefs.LAST_BACKUP_AT, now)
                try {
                    val meta = org.json.JSONObject().apply {
                        put("timestamp", now)
                        try {
                            put("appVersionName", com.pressia.BuildConfig.VERSION_NAME)
                            put("appVersionCode", com.pressia.BuildConfig.VERSION_CODE)
                        } catch (_: Exception) {}
                        put("sections", org.json.JSONArray(exportSel))
                        put("tokens", exportTokens)
                        // Une seule date de build : le lecteur accepte encore les anciens
                        // doublons (`apkBuildDate`, `date`), inutile de les réécrire.
                        try {
                            put("buildDate", com.pressia.BuildConfig.BUILD_TIME)
                        } catch (_: Exception) {
                            try {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                put("buildDate", sdf.format(java.util.Date(now)))
                            } catch (_: Exception) {}
                        }
                    }
                    vm.repo.prefs.set(Prefs.LAST_BACKUP_META, meta.toString())
                } catch (_: Exception) {}
                backupMsg = "Sauvegarde créée (${byteSizeText(written)})."
                toast(ctx, "Sauvegarde effectuée")
            } catch (e: Exception) {
                // Le message reste à l'écran : l'échec doit être lisible même si le toast manqué
                // (le message du bas est le compte rendu, le toast n'est que la confirmation).
                backupMsg = "Export impossible : ${e.message ?: e.javaClass.simpleName}"
                toast(ctx, "Sauvegarde non écrite")
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
                backupMsg = "Restauration terminée : ${r.prefs} option(s), ${r.accounts} compte(s), " +
                    "${r.stats} statistique(s), ${r.read} favori(s)."
                // L'écran Réglages se recharge à sa prochaine ouverture (son LaunchedEffect) ;
                // listes et compte affichés suivent ici même, filtre œil y compris.
                vm.refresh(); vm.loadAccount(); vm.refreshUnreadOnly()
                toast(ctx, "Restauration effectuée")
            } catch (e: Exception) {
                backupMsg = "Import impossible : ${e.message}"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Sauvegarder ou restaurer en un seul fichier : les options, les statistiques, les comptes " +
                "et les favoris de vos flux RSS locaux. Vous choisissez ce que vous voulez inclure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (lastBackupAt > 0) {
            val metaInfo = buildString {
                append("Dernière sauvegarde : ${formatBackupAt(lastBackupAt)}")
                if (lastBackupMeta.isNotBlank()) {
                    try {
                        val m = org.json.JSONObject(lastBackupMeta)
                        val vname = m.optString("appVersionName")
                        val buildDate = m.optString("apkBuildDate").takeIf { it.isNotBlank() }
                            ?: m.optString("buildDate").takeIf { it.isNotBlank() }
                            ?: m.optString("date").takeIf { it.isNotBlank() }
                        if (vname.isNotBlank() && buildDate != null) {
                            append("\nVersion PressIA : $vname ($buildDate)")
                        } else if (vname.isNotBlank()) {
                            append("\nVersion PressIA : $vname")
                        } else if (buildDate != null) {
                            append("\nDate de build de l'APK : $buildDate")
                        }
                        val sections = m.optJSONArray("sections")
                        if (sections != null && sections.length() > 0) {
                            val list = mutableListOf<String>()
                            for (i in 0 until sections.length()) {
                                when (sections.getString(i)) {
                                    "prefs" -> list.add("Préférences")
                                    "stats" -> list.add("Statistiques")
                                    "accounts" -> list.add("Comptes/flux")
                                    "read" -> list.add("Favoris/lus")
                                    else -> list.add(sections.getString(i))
                                }
                            }
                            append("\nÉléments sauvegardés : ${list.joinToString(", ")}")
                        }
                        if (m.optBoolean("tokens", false)) {
                            append(" (avec jetons)")
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            Text(metaInfo, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Aucune sauvegarde pour l'instant", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ showExportDialog = true }) { Text("Sauvegarder…") }
            OutlinedButton({ showImportDialog = true }) { Text("Restaurer…") }
        }
        if (backupMsg.isNotBlank()) Text(backupMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (showExportDialog) {
        SectionPickerDialog(
            title = "Sauvegarder dans un fichier",
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
            title = "Restaurer depuis un fichier",
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

/** Date et heure locales courtes (« 14/10/2026 à 14:32 »), même forme que le menu et l'accueil. */
private fun formatBackupAt(ts: Long): String = fmtDateTimeFr(ts)

/** Taille d'un fichier en clair : octets sous 1 Ko (un petit export ne s'arrondit pas à zéro). */
private fun byteSizeText(bytes: Long): String =
    if (bytes < 1024) "$bytes octets" else "${bytes / 1024} Ko"

/**
 * Écrit la sauvegarde dans le document choisi par l'utilisateur et rend la taille réellement
 * présente sur le disque, en levant si rien d'exploitable n'a été écrit.
 *
 * Le document vient du sélecteur « Créer un fichier » : selon le fournisseur,
 * `openOutputStream` peut rendre null (application en échec, document disparu entre le choix et
 * l'écriture). Avale ce cas et l'écran annonçait « Sauvegarde effectuée » sans le moindre fichier
 * — le message le plus trompeur possible, puisqu'il décourage de recommencer (constaté). Relire
 * la taille par le descripteur ferme aussi le cas d'un flux accepté puis tronqué.
 */
private fun writeBackupFile(
    ctx: android.content.Context,
    uri: android.net.Uri,
    bytes: ByteArray
): Long {
    val out = ctx.contentResolver.openOutputStream(uri)
        ?: throw java.io.IOException("fichier impossible à ouvrir en écriture")
    out.use {
        it.write(bytes)
        it.flush()
    }
    // `statSize` vaut -1 quand le fournisseur ne sait pas la donner : ce n'est pas un échec pour
    // autant, le flux a été accepté et refermé sans erreur — on rend alors ce qu'on a écrit.
    val size = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)
    if (size in 0 until bytes.size.toLong()) {
        throw java.io.IOException("écriture incomplète ($size/${bytes.size} octets)")
    }
    return if (size > 0) size else bytes.size.toLong()
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
                    ) { Text(if (extraCheck != null) "Restaurer" else "Sauvegarder") }
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

/** Écran « À propos » : nom, version, description courte, bouton vers le code source,
 * puis résumé cherchable des fonctionnalités et règles de l'application. */
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }
    var query by remember { mutableStateOf("") }
    // Filtrage simple sur le titre et le texte : le résumé reste lisible sans quitter l'écran.
    val shown = remember(query) {
        if (query.isBlank()) ABOUT_TOPICS
        else ABOUT_TOPICS.filter {
            it.title.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Fonctionnalités et règles", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Rechercher…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (query.isBlank()) "${ABOUT_TOPICS.size} sujets"
            else "${shown.size} sujet(s) pour « $query »",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Text(
                "Aucun sujet ne correspond : essayez un autre mot (ex. « favoris », « hors-ligne », « sauvegarde »).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            shown.forEach { topic ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(topic.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            topic.body, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Un sujet du résumé « À propos » : titre court + règle ou fonctionnalité en une phrase. */
private data class AboutTopic(val title: String, val body: String)

/**
 * Ce que PressIA sait faire et les règles qu'elle applique, en langage utilisateur.
 * Chaque entrée reprend un comportement réel et vérifiable de l'application (voir l'écran
 * ou le réglage cité) : en ajouter un nouveau impose de vérifier le comportement d'abord.
 */
private val ABOUT_TOPICS = listOf(
    AboutTopic(
        "Comptes : Feedly, flux locaux",
        "Feedly se connecte sur sa page officielle intégrée (le niveau gratuit suffit), et les flux " +
            "RSS/Atom saisis à la main restent dans PressIA sans compte. Plusieurs comptes coexistent, " +
            "l'actif est synchronisé en premier ; reconnecter le même utilisateur remplace son jeton, sans doublon."
    ),
    AboutTopic(
        "Synchronisation : bouton, lancement, geste",
        "Le bouton d'actualisation, la synchronisation au lancement (désactivable) et le tirer-pour-actualiser " +
            "récupèrent la même chose : nouveaux articles, tableau « Read later » et images des articles " +
            "(miniature déclarée et images du corps, une seule requête par image même partagée). Les favicons, " +
            "eux, ne se téléchargent que lorsqu'ils manquent ou que le mois a passé : première synchronisation, " +
            "flux arrivé depuis, cache vidé, puis une fois par mois (et à la demande avec « Actualiser " +
            "favicons »). Sans compte ni flux, la synchro manuelle le dit au lieu de tourner à vide ; " +
            "hors-ligne, rien ne part. Le bas du menu rappelle la date et l'heure de la dernière " +
            "synchronisation réussie."
    ),
    AboutTopic(
        "Lecture hors-ligne et article complet",
        "Extraits, images et articles complets (extraits via Readability) sont conservés pour lire sans réseau. " +
            "« Images hors-ligne » et « Wi-Fi uniquement » règlent ce qui se télécharge et quand."
    ),
    AboutTopic(
        "Images : grand format sans re-téléchargement",
        "Toucher une image de l'article l'ouvre en plein écran (pincer pour zoomer, tap pour fermer). PressIA y " +
            "affiche la copie déjà téléchargée — la miniature conservée par la synchronisation, ou l'image du " +
            "corps servie par le cache disque : ouvrir une image ne redemande rien au réseau."
    ),
    AboutTopic(
        "Vues : compacte, liste, carte, magazine",
        "Quatre présentations au choix dans les réglages. L'œil ne montre que les non-lus, mémorisé par flux " +
            "et par dossier ; les boutons haut/bas longent la liste, et le bouton bas s'arrête d'abord au " +
            "dernier non-lu simple avant le tout bas (sauf Favoris et Read later)."
    ),
    AboutTopic(
        "Repères d'écran : compteurs et retour",
        "Le titre d'une liste annonce entre parenthèses le nombre d'articles non lus de son périmètre — " +
            "« Tous les articles (12) », un dossier, un flux — comme « Accueil (n) », et rien quand tout est " +
            "lu. Sur la liste d'un dossier ou d'un flux, le balayage vers la droite affiche une icône Accueil " +
            "dès le début du geste et ramène à l'accueil au relâchement ; un frôlement ne fait rien."
    ),
    AboutTopic(
        "Appui long : marquages groupés",
        "Un appui long sur un article propose « marquer lus les plus récents / les plus anciens », l'article " +
            "touché restant tel quel. Sur l'accueil, un appui long sur un flux ou sur un dossier ouvre le menu " +
            "de la ligne, où « Tout marquer comme lu » porte exactement le périmètre touché. Les favoris et le " +
            "Read later ne sont jamais touchés par ces marquages. Chaque marquage groupé (« tout marquer lu » " +
            "inclus) propose un bouton Annuler."
    ),
    AboutTopic(
        "Favoris et Read later : jamais marqués lus",
        "L'étoile PressIA et le tableau « Read later » du compte sont deux choses distinctes (périmètre réglable " +
            "dans les Favoris). Ni « tout marquer lu », ni le menu d'appui long, ni les doublons ne les font passer " +
            "en lu : « tout marquer lu » bascule à l'unité dès qu'un favori ou un Read later doit rester non lu."
    ),
    AboutTopic(
        "Gestes configurables",
        "Balayage vers la gauche ou la droite (favori, lu/non-lu, partage, Instapaper), bouton de fin de ligne, " +
            "icônes de l'écran article : tout se règle. Dans l'article ouvert, double-tap, balayage gauche " +
            "et balayage droit se choisissent aussi (navigation entre articles par défaut) ; sur une image, " +
            "un simple tap l'ouvre en grand et un double-tap déclenche l'action réglée pour le double-tap. " +
            "Toucher le favicon ou la miniature marque lu sans ouvrir (désactivable)."
    ),
    AboutTopic(
        "Ouverture : PressIA, onglet, navigateur",
        "Dans PressIA (extrait hors-ligne, article complet au double-tap — configurable, Readability " +
            "par défaut — ou via l'icône Readability à activer dans les réglages), dans un onglet intégré, " +
            "ou directement dans le navigateur : le mode se choisit dans les réglages. Épinglé, le complet " +
            "s'applique à tous les articles ouverts ; sauf « Readability persistant », le retour à " +
            "l'accueil le coupe."
    ),
    AboutTopic(
        "Conservation : quota par flux et historique",
        "Seul le quota par flux (100 à 10 000 articles, 1 000 par défaut) limite la taille hors-ligne, avec une " +
            "fenêtre d'historique de 0 à 36 mois (0 = sans limite, 1 par défaut, à la connexion d'un compte). " +
            "Les favoris sont toujours gardés."
    ),
    AboutTopic(
        "Recherche et doublons",
        "La recherche demande 2 lettres, dans le flux courant, un dossier ou tous les articles. L'écran doublons " +
            "repère les mêmes sujets repris par plusieurs flux et peut tout marquer lu sauf le plus récent."
    ),
    AboutTopic(
        "Sauvegarde et restauration",
        "Un seul fichier : options, statistiques, abonnements et favoris/lus, au choix ; les jetons ne partent " +
            "que si on les inclut. L'export n'annonce « Sauvegarde effectuée » qu'après avoir vérifié ce qui " +
            "est réellement écrit dans le fichier. Restaurer deux fichiers d'affilée ne perd rien : les favoris " +
            "et les lus de flux locaux dont les articles ne sont pas encore arrivés s'ajoutent à ceux du " +
            "fichier précédent, en attente d'une synchronisation. Seuls les dossiers créés en local sont " +
            "sauvegardés : ceux des comptes sont re-créés par la synchronisation."
    ),
    AboutTopic(
        "Cache, favicons, déconnexion",
        "« Vider le cache » supprime les images téléchargées et les articles complets extraits, mais garde les " +
            "articles (ré-extraits à l'ouverture) ; la synchronisation suivante reprend les images et les " +
            "favicons, redevenus dus. « Actualiser favicons » ne touche qu'aux favicons (source : agrégateur ou " +
            "sites en direct) et repart pour un mois. Le détail du cache " +
            "(« articles », « images », « miniatures ») est dans Réglages. " +
            "Se déconnecter supprime articles, miniatures, cache d'images et file d'attente du compte, et rend " +
            "l'espace libéré ; les statistiques de lecture, elles, sont conservées (remise à zéro depuis Stats)."
    ),
    AboutTopic(
        "Dossiers fusionnés ou séparés",
        "« Fusionner » regroupe les dossiers de même nom de tous les comptes comme dans Feedly ; « Séparer » " +
            "garde un dossier par compte pour ne jamais confondre deux comptes."
    ),
    AboutTopic(
        "Limites réseau et session expirée",
        "Trop de demandes d'affilée (erreur 429) affiche un message d'attente avec le délai demandé, jamais " +
            "confondu avec une session expirée : dans ce cas, l'application demande une reconnexion du compte."
    ),
    AboutTopic(
        "Apparence et confort de lecture",
        "Thème de nuit (sombre, AMOLED), accent, fond, quatre tailles de police, justification, échelles du menu " +
            "et de l'accueil : tout est réglable et exporté avec la sauvegarde."
    )
)
