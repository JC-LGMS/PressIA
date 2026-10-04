package com.pressia.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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

/** Page de connexion directe, utilisée comme repli si l'URL d'authentification n'aboutit pas. */
const val FEEDLY_SIGNIN_URL = "https://cloud.feedly.com/signin"

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
 * Clés de `localStorage` dans lesquelles la page Feedly range la session. La valeur est un objet
 * JSON du type `{"plan":…,"provider":…,"feedlyId":…,"feedlyToken":…}` : c'est le champ
 * `feedlyToken` qui est le jeton accepté par l'API v3.
 */
private val FEEDLY_STORAGE_KEYS = listOf("feedly.session", "feedly.token", "token", "accessToken")

/** Champs JSON pouvant porter le jeton, essayés dans l'ordre. */
private val FEEDLY_TOKEN_FIELDS = listOf("feedlyToken", "accessToken", "access_token", "token")

/**
 * Un jeton Feedly est une longue chaîne ; `feedly.authed=1` ne l'est pas.
 * On écarte ces drapeaux et les en-têtes de cookies collés ensemble pour ne jamais
 * tenter une connexion avec une valeur vide ou tronquée.
 */
private fun plausibleToken(value: String?): String? {
    val v = value?.trim()?.trim('"')?.trim()
    return v?.takeIf { it.length >= 40 && !it.contains(';') && !it.contains(' ') }
}

/** Extrait le champ `feedlyToken` de la valeur JSON stockée par la page Feedly. */
private fun tokenFromSessionJson(value: String?): String? {
    val v = value?.trim()?.takeIf { it.startsWith("{") } ?: return null
    FEEDLY_TOKEN_FIELDS.forEach { field ->
        val m = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(v) ?: return@forEach
        plausibleToken(m.groupValues[1])?.let { return it }
    }
    return null
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
 * Jeton de session Feedly dans le `localStorage` de la page : c'est là que Feedly le dépose
 * après une authentification réussie. `evaluateJavascript` renvoie une valeur JSON, d'où le
 * décodage de la chaîne ; l'évaluation échoue si la page n'est pas encore chargée.
 */
private suspend fun WebView.storageSession(): String? = suspendCoroutine { cont ->
    // Chaque clé est renvoyée séparément : une seule valeur suffit et l'ordre n'importe pas.
    val script = FEEDLY_STORAGE_KEYS.joinToString(
        prefix = "(function(){try{return [", postfix = "].filter(Boolean).join(',');}catch(e){return '';}})()"
    ) { k -> "localStorage.getItem('$k')" }
    runCatching {
        evaluateJavascript(script) { value -> cont.resume(storageFrom(value)) }
    }.onFailure { cont.resume(null) }
}

/** Isole le jeton dans la valeur renvoyée : champ `feedlyToken` du JSON, ou chaîne brute. */
private fun storageFrom(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null" || text == "\"\"" || text == "\"\"") return null
    // evaluateJavascript renvoie une chaîne JSON : on retire guillemets et échappements.
    val unquoted = text.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
    tokenFromSessionJson(unquoted)?.let { return it }
    return plausibleToken(unquoted)
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
 * la page officielle cloud.feedly.com/signin s'ouvre dans une WebView, puis le jeton de session
 * (`localStorage`) est récupéré automatiquement, vérifié via l'API Feedly, enregistré, et
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

    /** Session Feedly visible dans la WebView : localStorage d'abord (seule source réellement utile). */
    suspend fun findSession(): String? = webView?.storageSession() ?: nativeSession() ?: webView?.documentCookie()

    /**
     * Vérifie le jeton, enregistre le compte, puis lance une vraie synchronisation. Sans ce
     * dernier passage le compte restait connecté mais vide : on restait sur une page Feedly et
     * aucun article n'arrivait jamais.
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
        vm.connectAccount("feedly", token, label)
        status = "Connexion réussie — récupération de vos flux…"
        val msg = vm.syncNow()
        vm.loadAccount()
        busy = false
        done = true
        onDone("Connecté à Feedly ✓ — $msg")
        return true
    }

    /**
     * Surveille la session : la page reste affichée après la connexion, mais dès que le cookie
     * existe on prend le relais, on valide et on revient sur les comptes.
     */
    LaunchedEffect(Unit) {
        forgetFeedlySession()
        var lastToken = ""
        var lastTry = 0L
        while (!done) {
            delay(1200)
            if (busy) continue
            val token = if (manualToken.isNotBlank()) manualToken.trim() else findSession() ?: continue
            // Même jeton refusé : on réessaie au plus toutes les 5 s (le réseau peut revenir).
            val now = System.currentTimeMillis()
            if (token == lastToken && now - lastTry < 5000) continue
            lastToken = token; lastTry = now
            tryConnect(token)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connexion Feedly") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "retour") } },
                actions = {
                    IconButton({ webView?.loadUrl(FEEDLY_AUTH_URL) }) { Icon(Icons.Filled.Refresh, "recharger") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FeedlySignInView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                url = FEEDLY_AUTH_URL,
                onView = { webView = it },
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
                if (!showTokenField) {
                    TextButton({ webView?.loadUrl(FEEDLY_SIGNIN_URL) }) {
                        Text("Page de connexion simple")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FeedlySignInView(modifier: Modifier, url: String, onView: (WebView) -> Unit, onLoaded: () -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        onLoaded()
                        // La connexion par cookie passe parfois par une redirection : on relit le bac à cookies.
                        CookieManager.getInstance().flush()
                    }
                }
                loadUrl(url)
                onView(this)
            }
        }
    )
}
