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
import com.pressia.data.remote.inoreaderSessionCandidates
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
 * Inoreader à enregistrer au préalable. Le cookie porte l'identifiant de session, que
 * l'API accepte dans l'en-tête `Authorization` — d'où la vérification par un vrai
 * appel API avant d'enregistrer le compte.
 *
 * La connexion OAuth reste disponible par ailleurs (écran Comptes) : les deux chemins
 * coexistent, celui-ci étant le plus simple.
 */
const val INOREADER_LOGIN_URL = "https://www.inoreader.com/"

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
            ?.split(";")?.map { it.trim()?.substringBefore('=') }
            ?.filter { it in INOREADER_SESSION_COOKIES }.orEmpty()
        present.forEach { name ->
            runCatching { cm.setCookie(host, "$name=; Max-Age=0; Path=/") }
        }
    }
    runCatching { cm.flush() }
}

/**
 * En-tête `Cookie` de la session Inoreader, lu dans le bac natif de la WebView.
 *
 * Le `flush` précède la lecture : les cookies posés par la page ne sont persistés
 * qu'après, et sans lui la détection peut arriver avant l'écriture (échec au premier
 * essai).
 */
private fun nativeCookieHeader(): String? {
    val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
    runCatching { cm.flush() }
    val header = INOREADER_COOKIE_HOSTS.asSequence()
        .mapNotNull { host -> runCatching { cm.getCookie(host) }.getOrNull() }
        .firstOrNull { it.isNotBlank() }
    if (header == null) {
        android.util.Log.w("PressIA", "Inoreader cookie: aucun cookie trouvé sur les ${INOREADER_COOKIE_HOSTS.size} hôtes")
    } else {
        android.util.Log.i("PressIA", "Inoreader cookie: trouve ${header.take(60)}… sur ${INOREADER_COOKIE_HOSTS.first { runCatching { cm.getCookie(it) }.getOrNull() == header }}")
    }
    return header
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

    /** Valeurs candidates tirées du cookie de session, dans l'ordre de préférence. */
    fun sessionCandidates(): List<String> =
        inoreaderSessionCandidates(nativeCookieHeader())

    /**
     * Vérifie le jeton, enregistre le compte, puis revient sur la liste des comptes.
     *
     * [tokens] : valeurs candidates tirées du cookie, dans l'ordre de préférence. Le
     * dépôt les essaie une à une et renvoie la première acceptée — c'est lui qui
     * connaît les schémas d'authentification du service.
     *
     * Ordre impératif : retour d'abord, synchronisation ensuite. La synchronisation part
     * dans le scope du modèle (qui survit à la navigation) via [PressVM.connectAccount] —
     * lancée ici avant le retour, elle tournerait dans le scope de cet écran, annulé dès
     * sa sortie.
     */
    suspend fun tryConnect(tokens: List<String>): Boolean {
        if (busy || done) return false
        val candidates = tokens.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return false
        busy = true
        status = "Vérification de la session Inoreader…"
        // Les identifiants d'application (`AppId` / `AppKey`) sont exigés par l'API sur
        // chaque appel : on les publie avant de vérifier, sinon la session serait
        // refusée à tort (403).
        val hasAppAuth = vm.repo.refreshInoreaderAppAuth()
        val session = vm.repo.verifyInoreaderWebSession(candidates)
        if (session == null) {
            android.util.Log.w("PressIA", "Inoreader verifyWebSession: null — ${candidates.size} candidat(s) essayé(s) — AppAuth=${if (hasAppAuth) "OK" else "MANQUANT"}")
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
     * Surveille la session : la page reste affichée après la connexion, mais dès que le
     * cookie de session existe on prend le relais, on valide et on revient sur les comptes.
     *
     * Si la page a validé les identifiants (retour sur `inoreader.com`) sans qu'aucun
     * cookie ne soit lisible en 20 s (protection anti-robot, cookies bloqués…), on le dit
     * explicitement au lieu de tourner en rond en silence.
     */
    LaunchedEffect(Unit) {
        forgetInoreaderSession()
        var lastCandidates: List<String> = emptyList()
        var lastTry = 0L
        var landedAt = 0L
        while (!done) {
            delay(1200)
            if (busy) continue
            val candidates = if (manualToken.isNotBlank()) listOf(manualToken.trim()) else sessionCandidates()
            if (candidates.isEmpty()) {
                // Pas de cookie lisible. Deux cas : l'utilisateur n'est pas encore
                // connecté (formulaire de connexion affiché — rien à signaler), ou la
                // session est installée mais illisible (cookies bloqués, protection
                // anti-robot) : le formulaire a disparu, c'est le moment de le dire.
                val waiting = webView?.loginFormVisible() ?: true
                if (!stuckNotified && !waiting) {
                    if (landedAt == 0L) landedAt = System.currentTimeMillis()
                    if (System.currentTimeMillis() - landedAt > 20000) {
                        stuckNotified = true
                        showTokenField = true
                        status = "PressIA ne voit aucun cookie de session Inoreader (connexion non " +
                            "terminée, protection anti-robot ou cookies bloqués). Touchez " +
                            "« Réessayer », ou collez un jeton ci-dessous."
                    }
                } else if (waiting) {
                    landedAt = 0L
                }
                continue
            }
            landedAt = 0L
            // Même jeu de candidats refusé : on réessaie au plus toutes les 5 s (le réseau
            // peut revenir).
            val now = System.currentTimeMillis()
            if (candidates == lastCandidates && now - lastTry < 5000) continue
            lastCandidates = candidates; lastTry = now
            if (tryConnect(candidates)) break
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
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                        label = { Text("Jeton de session Inoreader") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Button({ scope.launch { tryConnect(listOf(manualToken.trim())) } }, enabled = manualToken.isNotBlank()) {
                        Text("Utiliser ce jeton")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ scope.launch { tryConnect(sessionCandidates()) } }, enabled = !busy) {
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
