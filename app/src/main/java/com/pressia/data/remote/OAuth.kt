package com.pressia.data.remote

import com.pressia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OAuth Feedly (code flow) + Inoreader (OAuth2). Les secrets restent configurables en Réglages. */
object OAuth {
    /** Encodage URL compatible JVM pure (testable sans framework Android). */
    fun enc(s: String): String =
        java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")

    fun feedlyAuthUrl(clientId: String, redirect: String = BuildConfig.FEEDLY_REDIRECT_URI): String {
        val scope = enc("https://cloud.feedly.com/subscriptions")
        return "https://feedly.com/v3/auth/auth?response_type=code&client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(redirect)}&scope=$scope&state=feedly"
    }
    fun inoreaderAuthUrl(appId: String, redirect: String = BuildConfig.INOREADER_REDIRECT_URI): String =
        "https://www.inoreader.com/oauth2/auth?client_id=${enc(appId)}" +
            "&redirect_uri=${enc(redirect)}&response_type=code&scope=read+write&state=inoreader"

    /** Résultat d'un retour OAuth : code d'autorisation ou erreur (refus utilisateur…). */
    data class RedirectResult(val code: String?, val error: String?)

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
                error = params["error"] ?: params["error_description"]
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

    /** Échange code→token Inoreader (réseau sur IO : jamais sur le thread d'appel). */
    suspend fun inoreaderToken(code: String, appId: String, appKey: String, redirect: String = BuildConfig.INOREADER_REDIRECT_URI): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = okhttp3.FormBody.Builder()
                    .add("code", code).add("client_id", appId).add("client_secret", appKey)
                    .add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
                val req = okhttp3.Request.Builder().url("https://www.inoreader.com/oauth2/token").post(body).build()
                val resp = okhttp3.OkHttpClient().newCall(req).execute()
                val txt = resp.body?.string().orEmpty()
                Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1)
            } catch (_: Exception) { null }
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
