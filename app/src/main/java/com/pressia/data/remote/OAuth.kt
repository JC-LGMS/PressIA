package com.pressia.data.remote

import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import com.pressia.BuildConfig

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

    fun open(ctx: Context, url: String) {
        CustomTabsIntent.Builder().build().launchUrl(ctx, android.net.Uri.parse(url))
    }

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

    /** Échange code→token Feedly. */
    suspend fun feedlyToken(code: String, clientId: String, secret: String, redirect: String = BuildConfig.FEEDLY_REDIRECT_URI): String? {
        return try {
            val body = okhttp3.FormBody.Builder()
                .add("code", code).add("client_id", clientId).add("client_secret", secret)
                .add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
            val req = okhttp3.Request.Builder().url("https://feedly.com/v3/auth/token").post(body).build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val txt = resp.body?.string().orEmpty()
            Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1)
        } catch (_: Exception) { null }
    }

    /** Échange code→token Inoreader. */
    suspend fun inoreaderToken(code: String, appId: String, appKey: String, redirect: String = BuildConfig.INOREADER_REDIRECT_URI): String? {
        return try {
            val body = okhttp3.FormBody.Builder()
                .add("code", code).add("client_id", appId).add("client_secret", appKey)
                .add("redirect_uri", redirect).add("grant_type", "authorization_code").build()
            val req = okhttp3.Request.Builder().url("https://www.inoreader.com/oauth2/token").post(body).build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val txt = resp.body?.string().orEmpty()
            Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.getOrNull(1)
        } catch (_: Exception) { null }
    }
}
