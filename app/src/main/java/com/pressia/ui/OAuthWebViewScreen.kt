package com.pressia.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pressia.BuildConfig
import com.pressia.data.local.Prefs
import com.pressia.data.remote.OAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Authentification 100 % in-app (comme le Press d'origine et son FeedlyOAuthActivity) :
 * WebView intégrée → interception du retour `pressia://oauth/…` → échange code→token → compte enregistré.
 * Aucun navigateur externe nécessaire.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OAuthWebViewScreen(vm: PressVM, provider: String, onBack: () -> Unit, onDone: () -> Unit = onBack) {
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun authUrl(): String? {
        if (clientId.isBlank() || redirect.isBlank()) return null
        return if (provider == "feedly") OAuth.feedlyAuthUrl(clientId, redirect)
        else OAuth.inoreaderAuthUrl(clientId, redirect)
    }

    LaunchedEffect(provider) {
        val d = vm.repo.prefs.data.first()
        if (provider == "feedly") {
            clientId = d[Prefs.FEEDLY_CLIENT_ID]?.takeIf { it.isNotBlank() } ?: BuildConfig.FEEDLY_CLIENT_ID
            clientSecret = d[Prefs.FEEDLY_CLIENT_SECRET]?.takeIf { it.isNotBlank() } ?: BuildConfig.FEEDLY_CLIENT_SECRET
            redirect = BuildConfig.FEEDLY_REDIRECT_URI
        } else {
            clientId = d[Prefs.INOREADER_APP_ID]?.takeIf { it.isNotBlank() } ?: BuildConfig.INOREADER_APP_ID
            clientSecret = d[Prefs.INOREADER_APP_KEY]?.takeIf { it.isNotBlank() } ?: BuildConfig.INOREADER_APP_KEY
            redirect = BuildConfig.INOREADER_REDIRECT_URI
        }
        loaded = true
        // Page d'authentification chargée automatiquement : rien à saisir au préalable.
        if (authUrl() != null) started = true
        else error = "Identifiant d'application manquant (FEEDLY_CLIENT_ID / INOREADER_APP_ID dans local.properties)."
    }

    fun finishWith(msg: String) {
        vm.authMsg = msg
        onDone()
    }

    fun onCode(code: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                vm.repo.prefs.set(
                    if (provider == "feedly") Prefs.FEEDLY_CLIENT_ID else Prefs.INOREADER_APP_ID, clientId
                )
                vm.repo.prefs.set(
                    if (provider == "feedly") Prefs.FEEDLY_CLIENT_SECRET else Prefs.INOREADER_APP_KEY, clientSecret
                )
                vm.repo.prefs.set(
                    if (provider == "feedly") Prefs.FEEDLY_CLIENT_ID else Prefs.INOREADER_APP_ID, clientId
                )
                vm.repo.prefs.set(
                    if (provider == "feedly") Prefs.FEEDLY_CLIENT_SECRET else Prefs.INOREADER_APP_KEY, clientSecret
                )
                val tok = if (provider == "feedly") OAuth.feedlyToken(code, clientId, clientSecret, redirect)
                else OAuth.inoreaderToken(code, clientId, clientSecret, redirect)
                if (!tok.isNullOrBlank()) {
                    // Passage par le registre de comptes : écrire seulement TOKEN/PROVIDER
                    // ferait perdre le compte dès que Prefs.ACCOUNTS existe.
                    vm.connectAccount(provider, tok, "")
                    vm.syncNow()
                    finishWith(if (provider == "feedly") "Connecté à Feedly ✓" else "Connecté à Inoreader ✓")
                } else {
                    error = "Échange code→jeton refusé. Vérifiez Client ID/Secret et l'URL de redirection déclarée ($redirect)."
                    busy = false
                }
            } catch (e: Exception) {
                error = "Erreur réseau : ${e.message}"
                busy = false
            }
        }
    }

    fun handleUrl(url: String): Boolean {
        val res = OAuth.parseRedirect(url, redirect.ifBlank { "pressia://oauth" }) ?: return false
        if (!res.code.isNullOrBlank()) onCode(res.code)
        else {
            error = when (res.error) {
                null -> "Retour OAuth sans code d'autorisation."
                "access_denied", "access_denied " -> "Connexion refusée par l'utilisateur."
                else -> "OAuth : ${res.error}"
            }
        }
        return true
    }

    Scaffold(
        topBar = {
            Column {
                // Barre de synchro tout en haut de l'écran, au-dessus du titre.
                if (vm.syncing) SyncProgress()
                TopAppBar(
                    title = { Text(if (provider == "feedly") "Connexion Feedly" else "Connexion Inoreader") },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "retour") } },
                    actions = {
                        IconButton({ webView?.reload() }) { Icon(Icons.Filled.Refresh, "recharger") }
                    }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!loaded) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            if (error != null) {
                Card(
                    Modifier.fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) { Text(error!!, Modifier.padding(12.dp)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (started && authUrl() != null) {
                WebLoginView(
                    url = authUrl()!!,
                    redirectBase = redirect,
                    onRedirect = { handleUrl(it) },
                    onView = { webView = it }
                )
            } else if (!started) {
                Text(
                    "Impossible de charger la page de connexion : renseignez l'identifiant d'application.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLoginView(url: String, redirectBase: String, onRedirect: (String) -> Boolean, onView: (WebView) -> Unit) {
    var firstLoad by remember(url) { mutableStateOf(true) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url.toString()
                        return onRedirect(u)
                    }
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        return onRedirect(url)
                    }
                }
                onView(this)
            }
        },
        update = { wv ->
            if (firstLoad) {
                firstLoad = false
                // Ne recharge pas si la WebView a déjà intercepté le retour OAuth (url custom scheme)
                val current = wv.url
                if (current == null || (!current.startsWith(redirectBase) && current != url)) {
                    wv.loadUrl(url)
                }
            }
        }
    )
}
