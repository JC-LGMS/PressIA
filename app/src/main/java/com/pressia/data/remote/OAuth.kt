package com.pressia.data.remote

import com.pressia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OAuth Feedly (code flow) + Inoreader (OAuth2). Les secrets restent configurables en Réglages. */
object OAuth {
    /**
     * Encodage URL compatible JVM pure (testable sans framework Android).
     *
     * Surcharge `(String, Charset)` volontairement écartée : elle exige l'API 33 alors que
     * l'application démarre à l'API 26 (`NoSuchMethodError` sur Android 8 → 12L).
     */
    fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Jeton anti-CSRF du flux OAuth (`state`) : généré au départ, vérifié au retour.
     * En mémoire seulement : un retour arrivant après la mort du processus ne peut de toute
     * façon pas aboutir, et un état inconnu est refusé.
     */
    @Volatile private var pendingState: String? = null

    fun newState(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** Mémorise un nouvel état pour l'URL d'authentification qui suit. */
    fun beginState(): String = newState().also { pendingState = it }

    /** L'état renvoyé par le fournisseur est-il celui qu'on a émis ? */
    fun checkState(state: String?): Boolean {
        val expected = pendingState ?: return false
        return state != null && state == expected
    }

    fun feedlyAuthUrl(
        clientId: String,
        redirect: String = BuildConfig.FEEDLY_REDIRECT_URI,
        state: String = beginState()
    ): String {
        val scope = enc("https://cloud.feedly.com/subscriptions")
        return "https://feedly.com/v3/auth/auth?response_type=code&client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(redirect)}&scope=$scope&state=${enc(state)}"
    }
    fun inoreaderAuthUrl(
        appId: String,
        redirect: String = BuildConfig.INOREADER_REDIRECT_URI,
        state: String = beginState()
    ): String =
        "https://www.inoreader.com/oauth2/auth?client_id=${enc(appId)}" +
            "&redirect_uri=${enc(redirect)}&response_type=code&scope=read+write&state=${enc(state)}"

    /** Résultat d'un retour OAuth : code d'autorisation, erreur (refus utilisateur…) et `state`. */
    data class RedirectResult(val code: String?, val error: String?, val state: String? = null)

    /** Parse `pressia://oauth/xxx?code=…` ou `…?error=access_denied`. Retourne null si ce n'est pas un retour OAuth. */
    fun parseRedirect(url: String, redirectBase: String): RedirectResult? {
        if (!url.startsWith(redirectBase)) return null
        return try {
            val query = java.net.URI(url).rawQuery.orEmpty()
            val params = query.split("&").mapNotNull {
                val i = it.indexOf("=")
                if (i < 0) null else java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            RedirectResult(
                code = params["code"],
                error = params["error"] ?: params["error_description"],
                state = params["state"]
            )
        } catch (_: Exception) { RedirectResult(null, "URL de retour illisible") }
    }

    /** Échange code→token Feedly (réseau sur IO : jamais sur le thread d'appel). */
    suspend fun feedlyToken(code: String, clientId: String, secret: String, redirect: String = BuildConfig.FEEDLY_REDIRECT_URI): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = okhttp3.FormBody.Builder()
                    .add("code", code).add("client_id", clientId).add("client_secret", secret)
                    .add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
                val req = okhttp3.Request.Builder().url("https://feedly.com/v3/auth/token").post(body).build()
                val resp = okhttp3.OkHttpClient().newCall(req).execute()
                val txt = resp.body?.string().orEmpty()
                Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1)
            } catch (_: Exception) { null }
        }

    /**
     * Jeton OAuth d'un service : le jeton d'accès (cour) et le jeton de renouvellement
     * (long, seule voie pour obtenir un jeton neuf sans refaire la connexion).
     */
    data class Tokens(val access: String, val refresh: String = "")

    /** Échange code→token Inoreader (réseau sur IO : jamais sur le thread d'appel). */
    suspend fun inoreaderToken(code: String, appId: String, appKey: String, redirect: String = BuildConfig.INOREADER_REDIRECT_URI): String? =
        inoreaderTokens(code, appId, appKey, redirect)?.access

    /**
     * Échange code→jetons Inoreader : les **deux** jetons sont récupérés.
     *
     * Ne garder que le jeton d'accès condamnerait le compte à l'expiration (de l'ordre de
     * l'heure chez Inoreader) : sans le jeton de renouvellement, il faudrait refaire toute
     * la procédure de connexion.
     */
    suspend fun inoreaderTokens(
        code: String,
        appId: String,
        appKey: String,
        redirect: String = BuildConfig.INOREADER_REDIRECT_URI
    ): Tokens? = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder()
                .add("code", code).add("client_id", appId).add("client_secret", appKey)
                .add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
            val req = okhttp3.Request.Builder().url("https://www.inoreader.com/oauth2/token").post(body).build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val txt = resp.body?.string().orEmpty()
            parseTokens(txt)
        } catch (_: Exception) { null }
    }

    /**
     * Renouvellement du jeton d'accès Inoreader (`grant_type=refresh_token`).
     *
     * Inoreader peut ne pas renvoyer de jeton de renouvellement : l'ancien est alors
     * conservé (il reste valable).
     */
    suspend fun inoreaderRefresh(
        refreshToken: String,
        appId: String,
        appKey: String
    ): Tokens? = withContext(Dispatchers.IO) {
        try {
            val body = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token").add("refresh_token", refreshToken)
                .add("client_id", appId).add("client_secret", appKey).build()
            val req = okhttp3.Request.Builder().url("https://www.inoreader.com/oauth2/token").post(body).build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val txt = resp.body?.string().orEmpty()
            parseTokens(txt)?.let { if (it.refresh.isBlank()) it.copy(refresh = refreshToken) else it }
        } catch (_: Exception) { null }
    }

    /** Parse une réponse JSON de jetons OAuth (`access_token`, `refresh_token`). */
    internal fun parseTokens(txt: String): Tokens? {
        val access = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1)
        if (access.isNullOrBlank()) return null
        val refresh = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1).orEmpty()
        return Tokens(access, refresh)
    }

    /**
     * Génère un vérificateur PKCE (RFC 7636) : 32 octets aléatoires en base64url (43 caractères).
     * Pur JVM, testable sans Android.
     */
    fun pkceVerifier(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Défi S256 correspondant à un vérificateur PKCE (base64url sans padding). */
    fun pkceChallenge(verifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * Échange code→token Feedly en PKCE (client public, sans secret) : c'est nous qui détenons
     * le vérificateur, donc nous qui échangeons — la page `feedly.com/i/login` ne sait pas le
     * faire pour un code qu'elle n'a pas initié (`token_exchange_error` sinon).
     */
    suspend fun feedlyTokenPkce(code: String, verifier: String, redirect: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = okhttp3.FormBody.Builder()
                    .add("code", code).add("client_id", "feedly")
                    .add("code_verifier", verifier)
                    .add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
                val req = okhttp3.Request.Builder().url("https://feedly.com/v3/auth/token").post(body).build()
                val resp = okhttp3.OkHttpClient().newCall(req).execute()
                val txt = resp.body?.string().orEmpty()
                val token = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1)
                if (token.isNullOrBlank()) {
                    android.util.Log.w("PressIA", "Feedly échange PKCE sans jeton : HTTP ${resp.code} ${txt.take(200)}")
                }
                token?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                android.util.Log.w("PressIA", "Feedly échange PKCE impossible : ${e.javaClass.simpleName}")
                null
            }
        }
}
