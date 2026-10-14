package com.pressia.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pressia.data.local.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * URL d'authentification officielle de Feedly, celle qu'emploient les applications mobiles :
 * page de connexion.feedly.com adossée à un `client_id=feedly`, puis retour sur l'application.
 * Si ce n'est pas la version installée d'APK qui gère le retour, l'app web prend le relais sur
 * la même page et le cookie de session est récupéré comme ci-dessous.
 */
const val FEEDLY_AUTH_URL =
    "https://feedly.com/v3/auth/auth?response_type=code&client_id=feedly" +
        "&redirect_uri=https%3A%2F%2Ffeedly.com%2Fi%2Flogin" +
        "&scope=https%3A%2F%2Fcloud.feedly.com%2Fsubscriptions"

/** Retour d'authentification : c'est ici que Feedly renvoie `?code=…` (ou `?error=…`). */
const val FEEDLY_REDIRECT_LOGIN = "https://feedly.com/i/login"

/** Hôtes où chercher le cookie de session Feedly après connexion. */
private val FEEDLY_COOKIE_HOSTS = listOf(
    "https://feedly.com", "https://cloud.feedly.com", "https://www.feedly.com"
)

/**
 * Cookies de session Feedly. Le nom a changé au fil du temps (`feedly.authed` sur les comptes
 * récents, `feedly.session` sur les plus anciens) ; ils ne servent plus que de filet de sécurité,
 * le jeton réellement exploitable étant dans `localStorage`.
 */
private val FEEDLY_SESSION_COOKIES = listOf("feedly.authed", "feedly.session")

/**
 * Un jeton Feedly est une longue chaîne ; `feedly.authed=1` ne l'est pas.
 * On écarte ces drapeaux et les en-têtes de cookies collés ensemble pour ne jamais
 * tenter une connexion avec une valeur vide ou tronquée.
 */
private fun plausibleToken(value: String?): String? {
    val v = value?.trim()?.trim('"')?.trim()
    return v?.takeIf { it.length >= 40 && !it.contains(';') && !it.contains(' ') }
}

/** Extrait le jeton de session Feedly d'un en-tête de cookies, quel que soit son ordre. */
private fun sessionFrom(cookieHeader: String?): String? = cookieHeader
    ?.split(";")
    ?.map { it.trim() }
    ?.firstOrNull { c -> FEEDLY_SESSION_COOKIES.any { c.startsWith("$it=") } }
    ?.substringAfter('=')
    ?.let { plausibleToken(it) }

/** Cookie de session Feedly dans le bac natif de la WebView, sur tous les hôtes possibles. */
private fun nativeSession(): String? {
    val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
    // Les cookies posés par la page ne sont persistés qu'après flush : sans lui la lecture peut
    // arriver avant l'écriture et la détection échoue au premier essai.
    runCatching { cm.flush() }
    return FEEDLY_COOKIE_HOSTS.asSequence().mapNotNull { host ->
        runCatching { cm.getCookie(host) }.getOrNull()
    }.mapNotNull { sessionFrom(it) }.firstOrNull { it.isNotBlank() }
}

/**
 * Jeton de session Feedly dans le stockage web de la page : c'est là que Feedly le dépose
 * après une authentification réussie.
 *
 * On ratisse **toutes** les clés (`localStorage` puis `sessionStorage`) au lieu de quelques
 * noms en dur : Feedly renomme ses clés au fil des refontes, seule la forme du jeton est
 * stable (longue chaîne dans un champ `*token*` d'un JSON, ou chaîne brute). `evaluateJavascript`
 * renvoie une valeur JSON, d'où le déguillemetage ; l'évaluation échoue si la page n'est pas
 * encore chargée.
 */
private suspend fun WebView.storageSession(): String? = suspendCoroutine { cont ->
    // La recherche a lieu côté page : on ne remonte que le jeton, jamais tout le stockage.
    val script = "(function(){" +
        "function hunt(s){try{for(var i=0;i<s.length;i++){" +
        "var v=s.getItem(s.key(i))||'';" +
        "var m=v.match(/\"(?:feedlyToken|accessToken|access_token|token)\"\\s*:\\s*\"([^\"]{40,200})\"/);" +
        "if(m)return m[1];" +
        "var t=v.trim().replace(/^\"|\"$/g,'');" +
        "if(/^[A-Za-z0-9\\-_]{40,}$/.test(t))return t;" +
        "}}catch(e){}return '';}" +
        "try{var t=hunt(localStorage);if(t)return t;" +
        "try{return hunt(sessionStorage);}catch(e){return '';}}" +
        "catch(e){return '';}})()"
    runCatching {
        evaluateJavascript(script) { value -> cont.resume(plausibleToken(value)) }
    }.onFailure { cont.resume(null) }
}

/** `document.cookie` : filet de sécurité quand le bac natif n'expose pas encore le cookie. */
private suspend fun WebView.documentCookie(): String? = suspendCoroutine { cont ->
    runCatching {
        evaluateJavascript("document.cookie") { value ->
            cont.resume(sessionFrom(value?.trim()?.trim('"')))
        }
    }.onFailure { cont.resume(null) }
}

/**
 * Oublie une éventuelle session Feedly périmée, pour ne pas la prendre pour une nouvelle.
 * Le bac natif suffit : le `localStorage` est vidé avec les données du site par la WebView.
 */
private fun forgetFeedlySession() {
    val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
    FEEDLY_COOKIE_HOSTS.forEach { host ->
        val present = runCatching { cm.getCookie(host) }.getOrNull()
            ?.split(";")?.map { it.trim()?.substringBefore('=') }
            ?.filter { it in FEEDLY_SESSION_COOKIES }.orEmpty()
        present.forEach { name ->
            runCatching { cm.setCookie(host, "$name=; Max-Age=0; Path=/") }
        }
    }
    runCatching { cm.flush() }
}

/**
 * Connexion Feedly « classique », entièrement dans l'application :
 * la page d'authentification officielle s'ouvre dans une WebView, puis le jeton de session
 * (stockage web) est récupéré automatiquement, vérifié via l'API Feedly, enregistré, et
 * l'utilisateur revient sur la liste des comptes **avec ses flux réellement synchronisés**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedlySignInScreen(vm: PressVM, onBack: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Chargement de la page Feedly…") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showTokenField by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    /** Dernière URL chargée par la WebView (suit les redirections post-connexion). */
    var lastUrl by remember { mutableStateOf("") }
    /** Message de blocage déjà affiché : on ne le répète pas à chaque tour de sonde. */
    var stuckNotified by remember { mutableStateOf(false) }
    /**
     * PKCE (RFC 7636) : le vérificateur reste dans l'app, seul le défi part dans l'URL.
     * Au retour, c'est nous qui échangeons le code contre le jeton : la page
     * `feedly.com/i/login` ne sait pas le faire pour un code qu'elle n'a pas initié
     * (`token_exchange_error` sinon).
     */
    val verifier = remember { com.pressia.data.remote.OAuth.pkceVerifier() }
    val authUrl = remember(verifier) {
        FEEDLY_AUTH_URL + "&code_challenge=" +
            com.pressia.data.remote.OAuth.pkceChallenge(verifier) + "&code_challenge_method=S256"
    }

    /** Session Feedly visible dans la WebView : stockage web d'abord, cookies en filet. */
    suspend fun findSession(): String? = webView?.storageSession() ?: nativeSession() ?: webView?.documentCookie()

    /**
     * Vérifie le jeton, enregistre le compte, puis revient à l'accueil avant de lancer
     * une vraie synchronisation. Sans ce dernier passage le compte restait connecté mais
     * vide : on restait sur une page Feedly et aucun article n'arrivait jamais.
     *
     * Ordre impératif : retour d'abord, synchro ensuite. La synchro part dans le scope du
     * modèle (qui survit à la navigation) via [PressVM.sync] — lancée ici avant le retour,
     * elle tournerait dans le scope de cet écran, annulé dès sa sortie.
     */
    suspend fun tryConnect(token: String): Boolean {
        if (busy || done || token.isBlank()) return false
        busy = true
        status = "Vérification de la session Feedly…"
        val label = vm.repo.verifyFeedlySession(token)
        if (label == null) {
            busy = false
            status = "Session Feedly non valide. Terminez la connexion sur la page puis « Réessayer »."
            return false
        }
        // Le compte est ajouté au registre multi-comptes et rendu actif : écrire seulement
        // TOKEN/PROVIDER le ferait disparaître de la liste des comptes.
        // La première synchro puis l'historique partent dans connectAccount ; on revient
        // à l'accueil aussitôt, sans attendre (le scope du modèle survit à la navigation).
        vm.connectAccount("feedly", token, label)
        status = "Connexion réussie — récupération de vos flux…"
        vm.loadAccount()
        busy = false
        done = true
        onDone("Connecté à Feedly ✓")
        return true
    }

    /**
     * Échange un code d'autorisation contre le jeton, puis connecte le compte.
     * Un code déjà consommé ou périmé ne donne rien : l'utilisateur n'a qu'à se
     * reconnecter sur la page pour en obtenir un frais (même vérificateur).
     */
    suspend fun connectWithCode(code: String): Boolean {
        if (busy || done || code.isBlank()) return false
        status = "Échange du code Feedly contre un jeton…"
        val token = com.pressia.data.remote.OAuth.feedlyTokenPkce(code, verifier, FEEDLY_REDIRECT_LOGIN)
        if (token.isNullOrBlank()) {
            status = "Feedly a refusé l'échange du code. Reconnectez-vous sur la page pour un code frais, puis cela devrait passer."
            return false
        }
        return tryConnect(token)
    }

    /**
     * Surveille la session : la page reste affichée après la connexion, mais dès que le jeton
     * existe on prend le relais, on valide et on revient sur les comptes.
     *
     * Si la page a validé les identifiants (atterrissage sur `feedly.com/i/`) sans qu'aucune
     * session n'apparaisse dans la WebView sous 20 s (protection anti-robot ou cookies bloqués),
     * on le dit explicitement au lieu de tourner en rond en silence.
     */
    LaunchedEffect(Unit) {
        forgetFeedlySession()
        var lastToken = ""
        var lastTry = 0L
        var landedAt = 0L
        while (!done) {
            delay(1200)
            if (busy) continue
            val token = if (manualToken.isNotBlank()) manualToken.trim() else findSession()
            if (token == null) {
                if (!stuckNotified && lastUrl.contains("feedly.com/i/")) {
                    if (landedAt == 0L) landedAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - landedAt > 20000) {
                        stuckNotified = true
                        showTokenField = true
                        status = "Feedly vous a renvoyé ici connecté, mais PressIA ne voit aucune " +
                            "session (protection anti-robot ou cookies bloqués). Touchez « Réessayer », " +
                            "ou collez un jeton ci-dessous."
                    }
                } else if (!lastUrl.contains("feedly.com/i/")) {
                    landedAt = 0L
                }
                continue
            }
            landedAt = 0L
            // Même jeton refusé : on réessaie au plus toutes les 5 s (le réseau peut revenir).
            val now = System.currentTimeMillis()
            if (token == lastToken && now - lastTry < 5000) continue
            lastToken = token; lastTry = now
            tryConnect(token)
        }
    }

    Scaffold(
        topBar = {
            Column {
                // Barre de synchro tout en haut de l'écran, au-dessus du titre.
                if (vm.syncing) SyncProgress()
                TopAppBar(
                    title = { Text("Connexion Feedly") },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "retour") } },
                    actions = {
                        IconButton({ webView?.loadUrl(authUrl) }) { Icon(Icons.Filled.Refresh, "recharger") }
                    }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FeedlySignInView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                url = authUrl,
                onView = { webView = it },
                onUrl = { lastUrl = it },
                onCode = { code -> scope.launch { connectWithCode(code) } },
                onAuthError = { err ->
                    status = "Connexion Feedly refusée : $err. Reconnectez-vous sur la page."
                },
                onLoaded = {
                    if (status == "Chargement de la page Feedly…") status = "Connectez-vous sur la page Feedly ci-dessous."
                }
            )
            Column(
                Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Renseignez vos identifiants habituels Feedly, ou utilisez le bouton Google / Apple " +
                        "proposé par la page. Une fois connecté, PressIA récupère la session, " +
                        "synchronise vos flux et revient automatiquement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showTokenField) {
                    OutlinedTextField(
                        manualToken, { manualToken = it },
                        label = { Text("Jeton de session Feedly") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Button({ scope.launch { tryConnect(manualToken.trim()) } }, enabled = manualToken.isNotBlank()) {
                        Text("Utiliser ce jeton")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ scope.launch { tryConnect(findSession().orEmpty()) } }, enabled = !busy) {
                        Text("Réessayer")
                    }
                    TextButton({ showTokenField = !showTokenField }) {
                        Text(if (showTokenField) "Masquer la saisie manuelle" else "Connexion bloquée ? Saisir un jeton")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FeedlySignInView(
    modifier: Modifier,
    url: String,
    onView: (WebView) -> Unit,
    onUrl: (String) -> Unit,
    onCode: (String) -> Unit,
    onAuthError: (String) -> Unit,
    onLoaded: () -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply webview@{
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    // La connexion traverse plusieurs hôtes Feedly : sans cela les cookies de
                    // session posés hors de la page courante sont jetés et la session ne
                    // s'installe jamais dans la WebView.
                    setAcceptThirdPartyCookies(this@webview, true)
                }
                webViewClient = object : WebViewClient() {
                    /**
                     * Intercepte le retour d'authentification : on échange le code nous-mêmes
                     * (PKCE) au lieu de laisser la page `/i/login` échouer dessus. Tout le reste
                     * charge normalement.
                     */
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        android.util.Log.i("PressIA", "Feedly nav : ${target.replace(Regex("([?&]code=)[^&]*"), "$1<...>")}")
                        if (!target.startsWith(FEEDLY_REDIRECT_LOGIN)) return false
                        val code = request.url.getQueryParameter("code")
                        if (!code.isNullOrBlank()) {
                            onCode(code)
                            return true
                        }
                        request.url.getQueryParameter("error")?.let { onAuthError(it) }
                        return false
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        android.util.Log.i("PressIA", "Feedly page : ${url.replace(Regex("([?&]code=)[^&]*"), "$1<...>")}")
                        onUrl(url)
                        onLoaded()
                        // La connexion par cookie passe parfois par une redirection : on relit le bac à cookies.
                        CookieManager.getInstance().flush()
                    }
                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceResponse
                    ) {
                        if (request.isForMainFrame) {
                            android.util.Log.w("PressIA", "Feedly HTTP ${error.statusCode} ${request.url}")
                        }
                    }
                }
                loadUrl(url)
                onView(this)
            }
        }
    )
}
