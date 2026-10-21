package com.pressia.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pressia.BuildConfig
import com.pressia.data.CloudAccount
import com.pressia.data.isLocalProvider
import com.pressia.data.local.Prefs
import com.pressia.data.local.SubscriptionEntity
import com.pressia.data.remote.OAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Écran Comptes.
 *
 * Organisation retenue : trois sections — **agrégateur de flux RSS** (Feedly, Inoreader,
 * liste des comptes connectés), **flux RSS locaux**, puis **services tiers** (Instapaper).
 * C'est l'ordre qui répond à la question posée le plus souvent — « j'ai bien connecté ce
 * compte ? » — sans avoir à faire défiler l'écran.
 *
 * Plusieurs comptes peuvent être enregistrés, y compris plusieurs comptes du même service : chacun
 * garde ses dossiers et ses abonnements. Un seul est « actif » : c'est lui qui sert de référence
 * aux opérations courantes et à la première synchronisation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    vm: PressVM,
    onOAuth: (String) -> Unit,
    onFeedlySignIn: () -> Unit = {},
    onInoreaderSignIn: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var service by remember { mutableStateOf<String?>(null) }   // service en cours de connexion
    var inoreaderId by remember { mutableStateOf("") }
    var inoreaderKey by remember { mutableStateOf("") }
    var rssUrl by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var instaUser by remember { mutableStateOf("") }
    var instaPass by remember { mutableStateOf("") }
    var instaBusy by remember { mutableStateOf(false) }
    val instaConnected by vm.instapaperUser.collectAsState()
    val instaMode by vm.instapaperMode.collectAsState()
    var localFeeds by remember { mutableStateOf<List<SubscriptionEntity>>(emptyList()) }
    var removing by remember { mutableStateOf<CloudAccount?>(null) }
    var removingFeed by remember { mutableStateOf<SubscriptionEntity?>(null) }

    val accounts = vm.accounts
    val activeId by vm.activeAccountId.collectAsState()
    // Comptes à la session expirée : avertis avec réinvite (boutons de connexion existants).
    val expired by vm.expiredAccounts.collectAsState()
    // [AccountInfo.provider] porte l'identifiant du compte actif : c'est le repli quand la
    // préférence n'a pas encore été lue, pour que la ligne du compte actif soit cochée d'emblée.
    val active = activeId ?: vm.account?.provider

    LaunchedEffect(Unit) {
        val d = vm.repo.prefs.data.first()
        // Identifiants de l'application Inoreader de l'utilisateur (s'il en a enregistré une).
        // Vide par défaut : l'identifiant par défaut, celui de l'extension officielle, sert à
        // l'« authentification applicative » mais n'a pas notre URL de redirection déclarée —
        // le flux OAuth exigerait une application à lui.
        inoreaderId = d[Prefs.INOREADER_APP_ID]?.takeIf { it.isNotBlank() }.orEmpty()
        inoreaderKey = d[Prefs.INOREADER_APP_KEY]?.takeIf { it.isNotBlank() }.orEmpty()
        localFeeds = vm.repo.localFeeds()
        vm.loadAccount()
    }
    // Les flux locaux sont affichés sous leur propre section : ils ne dépendent d'aucun compte.
    LaunchedEffect(vm.localFeeds) { localFeeds = vm.localFeeds }

    val loginScroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(loginScroll).fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Agrégateur de flux RSS", style = MaterialTheme.typography.titleMedium)

        // ---- Feedly ------------------------------------------------------------------------
        OutlinedButton(
            {
                service = "feedly"; msg = ""
                onFeedlySignIn()
            },
            Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.RssFeed, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Se connecter avec Feedly")
        }
        Text(
            "La page de connexion Feedly s'ouvre dans l'application, avec vos options habituelles " +
                "(e-mail, Google, Apple). Dès que vous êtes connecté, PressIA récupère votre session et " +
                "ajoute le compte. Le niveau gratuit suffit, pas besoin de Feedly Pro.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- Inoreader ---------------------------------------------------------------------
        // Connexion « par session » : la page Inoreader s'ouvre dans l'application et le
        // cookie de session est récupéré automatiquement (même principe que Feedly).
        // C'est le chemin le plus simple : ni mot de passe saisi dans PressIA, ni
        // application Inoreader à enregistrer au préalable.
        OutlinedButton(
            { msg = ""; onInoreaderSignIn() },
            Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.RssFeed, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Se connecter avec Inoreader")
        }
        Text(
            "La page Inoreader s'ouvre dans l'application : connectez-vous avec vos identifiants " +
                "habituels. Dès que vous êtes connecté, PressIA récupère votre session, synchronise " +
                "vos flux et revient automatiquement. Aucun mot de passe n'est conservé par l'application.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Connexion OAuth : chemin « avancé », qui exige une application Inoreader
        // enregistrée par le développeur, avec `pressia://oauth/inoreader` comme URL de
        // redirection. Les champs démarrent **vides** : l'identifiant par défaut (celui de
        // l'extension officielle, qui sert à l'authentification applicative) n'a pas notre
        // URL de redirection déclarée — Inoreader refuserait le retour.
        OutlinedButton(
            { service = if (service == "inoreader") null else "inoreader"; msg = "" },
            Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.RssFeed, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (service == "inoreader") "Annuler la connexion OAuth Inoreader" else "Connexion OAuth Inoreader (application enregistrée)")
        }
        if (service == "inoreader") {
            OutlinedTextField(
                inoreaderId, { inoreaderId = it }, label = { Text("Inoreader App ID") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                inoreaderKey, { inoreaderKey = it }, label = { Text("Inoreader App Key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Button({
                scope.launch {
                    vm.repo.prefs.set(Prefs.INOREADER_APP_ID, inoreaderId)
                    vm.repo.prefs.set(Prefs.INOREADER_APP_KEY, inoreaderKey)
                    // Les identifiants saisis sont publiés aussitôt : l'API Inoreader les
                    // exige en en-tête sur chaque appel.
                    vm.repo.refreshInoreaderAppAuth()
                    onOAuth("inoreader")
                }
            }, Modifier.fillMaxWidth(), enabled = inoreaderId.isNotBlank()) { Text("Valider et connecter") }
            Text(
                "Nécessite une application créée sur inoreader.com/developers, avec l'URL de " +
                    "redirection « pressia://oauth/inoreader ». La page Inoreader s'ouvre ensuite " +
                    "dans l'application.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---- comptes connectés : regroupés avec l'agrégateur ---------------------------------
        if (accounts.isNotEmpty()) {
            HorizontalDivider()
            Text("Vos comptes", style = MaterialTheme.typography.titleMedium)
            accounts.forEach { acc ->
                AccountRow(
                    account = acc,
                    feeds = vm.subs.count { it.provider == acc.id },
                    active = acc.id == active,
                    expired = acc.id in expired,
                    onSelect = { vm.useAccount(acc); toast(ctx, "Compte ${acc.label} actif") },
                    onRemove = { removing = acc }
                )
            }
            Text(
                "Le compte actif est celui qui est synchronisé en premier. Les autres restent " +
                    "connectés et conservent leurs propres dossiers et abonnements.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Feed RSS local seul : aucun compte connecté.
        if (accounts.isEmpty()) {
            HorizontalDivider()
            vm.account?.let { info ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.RssFeed, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isLocalProvider(info.provider)) "Flux RSS locaux uniquement" else info.label,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "${info.feeds} flux", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- flux RSS saisis à la main ------------------------------------------------------
        HorizontalDivider()
        Text("Flux RSS locaux", style = MaterialTheme.typography.titleMedium)
        Text(
            "Saisis à la main, sans compte : ils restent dans PressIA quoi que vous fassiez d'autre, " +
                "et peuvent être rangés dans vos propres dossiers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            rssUrl, { rssUrl = it }, label = { Text("URL du flux RSS/Atom") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                autoCorrect = false
            )
        )
        Button(
            {
                val url = rssUrl.trim()
                scope.launch {
                    vm.addLocalFeed(url) { ok ->
                        msg = if (ok) "Flux ajouté et synchronisé." else "Flux non ajouté"
                        if (ok) rssUrl = ""
                    }
                }
            },
            Modifier.fillMaxWidth(),
            // Désactivé pendant l'enregistrement + la première synchronisation (voir
            // `PressVM.localFeedAdding`) : le bouton se réactive tout seul à la fin.
            enabled = rssUrl.isNotBlank() && !vm.localFeedAdding
        ) {
            if (vm.localFeedAdding) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Ajout en cours…")
            } else {
                Text("Ajouter ce flux")
            }
        }

        if (localFeeds.isNotEmpty()) {
            localFeeds.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FeedAvatar(f, 24)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        prettyFeedTitle(f.title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton({ removingFeed = f }) {
                        Icon(Icons.Filled.Delete, "désabonner", Modifier.size(18.dp))
                    }
                }
            }
        }

        // (comptes connectés : voir « Agrégateur de flux RSS », plus haut)

        // ---- services tiers -------------------------------------------------------------------
        HorizontalDivider()
        Text("Services annexes", style = MaterialTheme.typography.titleMedium)
        // ---- Instapaper --------------------------------------------------------------------
        Text("Instapaper", style = MaterialTheme.typography.titleSmall)
        Text(
            "Pour envoyer un article vers Instapaper, avec ou sans choix de tags et dossiers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (instaConnected.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Instapaper · $instaConnected", modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                TextButton({
                    vm.disconnectInstapaper()
                    instaUser = ""; instaPass = ""
                    toast(ctx, "Instapaper déconnecté")
                }) { Text("Déconnecter", color = MaterialTheme.colorScheme.error) }
            }
            if ("instapaper" in expired) {
                Text(
                    "Identifiants refusés — vérifiez le mot de passe (ou reconnectez le compte)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            OutlinedTextField(
                instaUser, { instaUser = it }, label = { Text("Identifiant Instapaper") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                    autoCorrect = false
                )
            )
            OutlinedTextField(
                instaPass, { instaPass = it }, label = { Text("Mot de passe") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    autoCorrect = false
                )
            )
            Button(
                {
                    instaBusy = true
                    vm.connectInstapaper(instaUser, instaPass) {
                        msg = it
                        toast(ctx, it)
                        if (it == "Compte Instapaper connecté") instaPass = ""
                        instaBusy = false
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = instaUser.isNotBlank() && instaPass.isNotEmpty() && !instaBusy
            ) { Text(if (instaBusy) "Vérification…" else "Connecter Instapaper") }
        }
        // Les options d'envoi n'apparaissent qu'une fois le compte connecté : avant, seuls
        // l'identifiant, le mot de passe et le bouton de connexion sont visibles.
        if (instaConnected.isNotBlank()) {
        // ---- Mode d'envoi : direct, ou dossier + tags choisis à chaque fois (session web) ----
        Text("Envoi vers Instapaper", style = MaterialTheme.typography.titleSmall)
        InstaModeRow(instaMode == "direct", "Envoyer directement",
            "Sans dossier ni tag.",
            { vm.setInstapaperMode("direct") })
        InstaModeRow(instaMode == "choose", "Choisir le dossier et les tags à chaque envoi",
            "Demande la destination avant chaque envoi.",
            { vm.setInstapaperMode("choose") })
        }

        if (vm.authMsg?.isNotBlank() == true) {
            Text(vm.authMsg!!, color = MaterialTheme.colorScheme.primary)
        }
        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.primary)
        }
        ColumnPositionIndicator(loginScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
    }

    removingFeed?.let { f ->
        AlertDialog(
            onDismissRequest = { removingFeed = null },
            title = { Text("Désabonner ce flux ?") },
            text = {
                Text(
                    "« ${prettyFeedTitle(f.title)} » et ses articles seront supprimés de l'appareil. " +
                        "Les statistiques de lecture déjà comptées sont conservées."
                )
            },
            confirmButton = {
                TextButton({
                    val id = f.id
                    removingFeed = null
                    vm.deleteLocalFeed(id)
                    toast(ctx, "Flux désabonné")
                }) { Text("Désabonner", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ removingFeed = null }) { Text("Annuler") } }
        )
    }
    removing?.let { acc ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Déconnecter ${acc.label} ?") },
            text = {
                Text(
                    "Ce compte ne sera plus synchronisé : ses articles et leurs miniatures " +
                        "seront supprimés de l'appareil. Le compte et ses dossiers sont retirés " +
                        "de la liste ; vos flux RSS locaux ne sont pas touchés."
                )
            },
            confirmButton = {
                TextButton({
                    removing = null
                    vm.disconnectAccount(acc)
                    toast(ctx, "Compte déconnecté")
                }) { Text("Déconnecter", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Annuler") } }
        )
    }
}

/** Ligne d'un compte connecté : coché si actif, avec son nombre de flux et sa suppression. */
@Composable
private fun AccountRow(
    account: CloudAccount,
    feeds: Int,
    active: Boolean,
    expired: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !active, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(active, onClick = { if (!active) onSelect() })
        Column(Modifier.weight(1f)) {
            Text(
                if (account.name.isNotBlank()) "${account.label} · ${account.name}" else account.label,
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "$feeds flux${if (active) " · compte actif" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Session expirée : l'utilisateur sait pourquoi rien n'arrive et que faire —
            // les boutons de connexion plus haut servent à reconnecter.
            if (expired) {
                Text(
                    "Session expirée — reconnectez-vous",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        IconButton(onRemove) { Icon(Icons.Filled.Delete, "déconnecter", Modifier.size(18.dp)) }
    }
}

/**
 * Choix du mode d'envoi Instapaper (bouton radio + aide) dans l'écran Comptes.
 */
@Composable
private fun InstaModeRow(selected: Boolean, title: String, help: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Traite un retour OAuth reçu par deep link (`pressia://oauth/feedly?code=…`), c'est-à-dire quand
 * le système relance l'application sur l'URL de redirection.
 *
 * Le compte est enregistré dans le registre multi-comptes — et pas seulement dans les anciennes
 * préférences — sinon il disparaissait au lancement suivant.
 */
suspend fun handleOAuthDeepLink(vm: PressVM, uri: android.net.Uri): String {
    val service = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: uri.getQueryParameter("provider")
        ?: "feedly"
    val redirect = if (service == "feedly") BuildConfig.FEEDLY_REDIRECT_URI else BuildConfig.INOREADER_REDIRECT_URI
    val res = OAuth.parseRedirect(uri.toString(), "pressia://oauth")
    if (res == null) return "Retour OAuth illisible"
    // Le `state` émis au départ doit revenir à l'identique : sans ce contrôle, un lien
    // `pressia://oauth` forgé par une autre application pouvait connecter un compte tiers.
    if (!OAuth.checkState(res.state)) return "Retour OAuth refusé : état de sécurité absent ou différent"
    res.error?.let { return "Connexion refusée : $it" }
    val code = res.code?.takeIf { it.isNotBlank() } ?: return "Retour OAuth sans code d'autorisation"

    val d = vm.repo.prefs.data.first()
    val token = if (service == "feedly") {
        val id = d[Prefs.FEEDLY_CLIENT_ID]?.takeIf { it.isNotBlank() } ?: BuildConfig.FEEDLY_CLIENT_ID
        val secret = d[Prefs.FEEDLY_CLIENT_SECRET]?.takeIf { it.isNotBlank() } ?: BuildConfig.FEEDLY_CLIENT_SECRET
        OAuth.feedlyToken(code, id, secret, redirect)
    } else {
        val id = d[Prefs.INOREADER_APP_ID]?.takeIf { it.isNotBlank() } ?: BuildConfig.INOREADER_APP_ID
        val key = d[Prefs.INOREADER_APP_KEY]?.takeIf { it.isNotBlank() } ?: BuildConfig.INOREADER_APP_KEY
        OAuth.inoreaderToken(code, id, key, redirect)
    }
    if (token.isNullOrBlank()) return "Échange code→jeton refusé : vérifiez l'identifiant et l'URL de redirection"
    vm.connectAccount(service, token, "")
    return "Compte ${if (service == "feedly") "Feedly" else "Inoreader"} connecté"
}
