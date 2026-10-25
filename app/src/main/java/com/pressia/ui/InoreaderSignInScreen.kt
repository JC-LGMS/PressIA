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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pressia.data.remote.INOREADER_COOKIE_HOSTS
import com.pressia.data.remote.INOREADER_SESSION_COOKIES
import com.pressia.data.remote.inoreaderCookieCandidates
import com.pressia.data.remote.inoreaderCookieHasSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Page de connexion Inoreader : c'est le site officiel qui s'ouvre, l'utilisateur s'y
 * connecte, et le **cookie de session** est récupéré automatiquement.
 *
 * Le mécanisme est celui de l'extension Inoreader pour navigateur, transporté à une
 * application native : pas de mot de passe saisi dans PressIA, pas d'application
 * Inoreader à enregistrer au préalable. Une fois la session installée, l'API Inoreader
 * est appelée exactement comme l'extension le fait — les cookies dans un en-tête
 * `Cookie` (avec `AppId`/`AppKey` en paramètre d'URL), sans en-tête `Authorization`.
 *
 * Le cookie porteur est `al` (posé sur `.www.inoreader.com`) : c'est lui que l'API
 * accepte seul. La connexion OAuth reste disponible par ailleurs (écran Comptes) : les
 * deux chemins coexistent, celui-ci étant le plus simple.
 */
const val INOREADER_LOGIN_URL = "https://www.inoreader.com/"

/**
 * URL d'appel API servant de référence au bac à cookies.
 *
 * `CookieManager.getCookie(url)` rend **les cookies que le navigateur enverrait à cette
 * URL** (domaine et chemin compris), y compris les cookies `HttpOnly` (`al`, `ssid`) que
 * `document.cookie` ne montre pas. C'est donc la source fiable : la requête de
 * vérification part exactement avec les mêmes cookies.
 */
private const val INOREADER_API_URL = "https://www.inoreader.com/reader/api/0/user-info"

/**
 * La page affiche-t-elle encore un formulaire de connexion ?
 *
 * C'est ce qui distingue « l'utilisateur n'est pas encore connecté » de « la session est
 * installée mais illisible » (cookies bloqués) : dans le premier cas, le formulaire est là
 * et il n'y a rien à signaler ; dans le second, il a disparu et le cookie ne se voit pas.
 * `evaluateJavascript` renvoie une valeur JSON, d'où la comparaison sur la chaîne.
 */
private suspend fun WebView.loginFormVisible(): Boolean = suspendCoroutine { cont ->
    val script = "(function(){try{return !!document.querySelector('input[type=password]');}catch(e){return false;}})()"
    runCatching {
        evaluateJavascript(script) { value -> cont.resume(value?.trim() == "true") }
    }.onFailure { cont.resume(false) }
}

/**
 * Oublie une éventuelle session Inoreader périmée, pour ne pas la prendre pour une
 * nouvelle : le bac à cookies suffit, le `localStorage` est vidé avec les données du
 * site par la WebView.
 */
private fun forgetInoreaderSession() {
    val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
    INOREADER_COOKIE_HOSTS.forEach { host ->
        val present = runCatching { cm.getCookie(host) }.getOrNull()
            ?.split(";")?.map { it.trim().substringBefore('=') }
            ?.filter { it in INOREADER_SESSION_COOKIES }.orEmpty()
        present.forEach { name ->
            runCatching { cm.setCookie(host, "$name=; Max-Age=0; Path=/") }
        }
    }
    runCatching { cm.flush() }
}

/**
 * Cookies de session que le navigateur enverrait à l'API Inoreader.
 *
 * Lu sur l'URL d'appel : `al` (domaine `.www.inoreader.com`) et les autres cookies de
 * session y sont présents quelle que soit la façon dont la page les a posés. Un en-tête
 * `Cookie` brut est renvoyé, ou null si la page n'a encore posé aucun cookie.
 */
private fun sessionCookieHeader(): String? {
    val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
    runCatching { cm.flush() }
    val header = runCatching { cm.getCookie(INOREADER_API_URL) }.getOrNull()
    android.util.Log.d("PressIA", "Inoreader cookie (API) : ${header?.take(80) ?: "<aucun>"}…")
    return header?.takeIf { it.isNotBlank() }
}

/**
 * Connexion Inoreader « par session », entièrement dans l'application :
 * la page officielle s'ouvre dans une WebView, puis le cookie de session est récupéré,
 * vérifié via l'API Inoreader, enregistré, et l'utilisateur revient sur la liste des
 * comptes **avec ses flux réellement synchronisés**.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InoreaderSignInScreen(vm: PressVM, onBack: () -> Unit, onDone: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Chargement de la page Inoreader…") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showTokenField by remember { mutableStateOf(false) }
    var manualToken by remember { mutableStateOf("") }
    /** Message de blocage déjà affiché : on ne le répète pas à chaque tour de sonde. */
    var stuckNotified by remember { mutableStateOf(false) }

    /**
     * Vérifie la session, enregistre le compte, puis revient sur la liste des comptes.
     *
     * [cookieHeaders] : en-têtes `Cookie` candidats (voir `inoreaderCookieCandidates`).
     * Le dépôt essaie chaque en-tête par un vrai appel API et renvoie la première session
     * acceptée.
     *
     * Ordre impératif : retour d'abord, synchronisation ensuite. La synchronisation part
     * dans le scope du modèle (qui survit à la navigation) via [PressVM.connectAccount] —
     * lancée ici avant le retour, elle tournerait dans le scope de cet écran, annulé dès
     * sa sortie.
     */
    suspend fun tryConnect(cookieHeaders: List<String>): Boolean {
        if (busy || done) return false
        val candidates = cookieHeaders.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return false
        busy = true
        status = "Vérification de la session Inoreader…"
        // Les identifiants d'application (`AppId` / `AppKey`) sont exigés par l'API sur
        // chaque appel : on les publie avant de vérifier, sinon la session serait
        // refusée à tort (403).
        val hasAppAuth = vm.repo.refreshInoreaderAppAuth()
        val session = if (hasAppAuth) vm.repo.verifyInoreaderWebSession(candidates) else null
        if (session == null) {
            busy = false
            status = if (!hasAppAuth) {
                "Session Inoreader refusée : identifiants d'application Inoreader manquants " +
                    "(INOREADER_APP_ID / INOREADER_APP_KEY dans local.properties)."
            } else {
                "Session Inoreader non valide. Terminez la connexion sur la page puis « Réessayer »."
            }
            return false
        }
        // Le compte est ajouté au registre multi-comptes et rendu actif : écrire seulement
        // TOKEN/PROVIDER le ferait disparaître de la liste des comptes.
        vm.connectAccount("inoreader", session.token, session.label)
        status = "Connexion réussie — récupération de vos flux…"
        vm.loadAccount()
        busy = false
        done = true
        onDone("Connecté à Inoreader ✓")
        return true
    }

    /**
     * Surveille la session : dès que le cookie de session existe, on prend le relais, on
     * valide et on revient sur les comptes. La connexion est donc automatique une fois
     * l'utilisateur identifié sur la page ; le bouton « Je suis connecté » reste là pour
     * forcer une vérification (cookie tardif, réseau revenu).
     *
     * Si la page a validé les identifiants (retour sur `inoreader.com`) sans qu'aucun
     * cookie de session n'apparaisse en 20 s (cookies bloqués, protection anti-robot), on
     * le dit explicitement au lieu de tourner en rond en silence.
     */
    LaunchedEffect(Unit) {
        forgetInoreaderSession()
        var landedAt = 0L
        var lastTry = 0L
        while (!done) {
            delay(1200)
            if (busy) continue
            val header = if (manualToken.isNotBlank()) manualToken.trim() else sessionCookieHeader()
            if (!inoreaderCookieHasSession(header)) {
                // Pas encore de cookie de session. Deux cas : l'utilisateur n'est pas
                // encore connecté (formulaire affiché — rien à signaler), ou la session
                // est installée mais illisible : le formulaire a disparu, on le dit.
                val waiting = webView?.loginFormVisible() ?: true
                if (!stuckNotified && !waiting) {
                    if (landedAt == 0L) landedAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - landedAt > 20000) {
                        stuckNotified = true
                        showTokenField = true
                        status = "PressIA ne voit aucun cookie de session Inoreader (connexion non " +
                            "terminée, protection anti-robot ou cookies bloqués). Touchez " +
                            "« Je suis connecté », ou collez un cookie ci-dessous."
                    }
                } else if (waiting) {
                    landedAt = 0L
                }
                continue
            }
            landedAt = 0L
            // Cookie présent : on vérifie, au plus toutes les 4 s (le réseau peut être lent).
            val now = System.currentTimeMillis()
            if (now - lastTry < 4000) continue
            lastTry = now
            tryConnect(inoreaderCookieCandidates(header))
        }
    }

    Scaffold(
        topBar = {
            Column {
                // Barre de synchro tout en haut de l'écran, au-dessus du titre.
                if (vm.syncing) SyncProgress()
                TopAppBar(
                    title = { Text("Connexion Inoreader", fontSize = 19.sp) },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "retour") } },
                    actions = {
                        IconButton({ webView?.loadUrl(INOREADER_LOGIN_URL) }) { Icon(Icons.Filled.Refresh, "recharger") }
                    }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            InoreaderSignInView(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
                url = INOREADER_LOGIN_URL,
                onView = { webView = it },
                onLoaded = {
                    if (status == "Chargement de la page Inoreader…") status = "Connectez-vous sur la page Inoreader ci-dessous."
                }
            )
            Column(
                Modifier.fillMaxWidth().padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Renseignez vos identifiants Inoreader habituels sur la page ci-dessus. " +
                        "Dès que vous êtes connecté, PressIA récupère votre session, synchronise " +
                        "vos flux et revient automatiquement. Aucun mot de passe n'est conservé " +
                        "par l'application.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showTokenField) {
                    OutlinedTextField(
                        manualToken, { manualToken = it },
                        label = { Text("Cookie de session Inoreader") },
                        placeholder = { Text("al=…; ssid=…") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Button({ scope.launch { tryConnect(listOf(manualToken.trim())) } }, enabled = manualToken.isNotBlank()) {
                        Text("Utiliser ce cookie")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        { scope.launch { tryConnect(inoreaderCookieCandidates(sessionCookieHeader())) } },
                        enabled = !busy
                    ) {
                        Text("Réessayer")
                    }
                    Button(
                        { scope.launch { tryConnect(inoreaderCookieCandidates(sessionCookieHeader())) } },
                        enabled = !busy
                    ) {
                        Text("Je suis connecté sur Inoreader")
                    }
                    TextButton({ showTokenField = !showTokenField }) {
                        Text(if (showTokenField) "Masquer la saisie manuelle" else "Connexion bloquée ? Coller un cookie")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InoreaderSignInView(
    modifier: Modifier,
    url: String,
    onView: (WebView) -> Unit,
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
                    // La connexion traverse plusieurs hôtes Inoreader (www, apex) : sans cela
                    // les cookies de session posés hors de la page courante sont jetés et la
                    // session ne s'installe jamais dans la WebView.
                    setAcceptThirdPartyCookies(this@webview, true)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Rien à intercepter : la page de connexion est la page du site.
                        android.util.Log.i("PressIA", "Inoreader nav : ${request.url}")
                        return false
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        android.util.Log.i("PressIA", "Inoreader page : $url")
                        onLoaded()
                        // La connexion par cookie passe parfois par une redirection : on relit
                        // le bac à cookies.
                        CookieManager.getInstance().flush()
                    }
                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceResponse
                    ) {
                        if (request.isForMainFrame) {
                            android.util.Log.w("PressIA", "Inoreader HTTP ${error.statusCode} ${request.url}")
                        }
                    }
                }
                loadUrl(url)
                onView(this)
            }
        },
        onRelease = { wv ->
            // Libère le moteur de rendu natif : sans cela chaque passage par l'écran de
            // connexion laissait une WebView vivante derrière lui.
            wv.loadUrl("about:blank")
            wv.destroy()
        }
    )
}
