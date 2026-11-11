package com.pressia.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * FreshRSS : agrégateur **auto-hébergé**, qui expose l'API Google Reader sous
 * `<serveur>/api/greader.php/` — les mêmes endpoints que Inoreader (`reader/api/0/…`), d'où le
 * fournisseur qui en dérive.
 *
 * Deux différences expliquent ce module :
 * - le serveur est celui de l'utilisateur, saisi à la connexion : le jeton du compte porte donc
 *   l'adresse du serveur à côté du jeton d'authentification (`freshrss|<serveur>|<auth>`),
 *   plutôt que d'ajouter une colonne au registre des comptes pour une seule valeur ;
 * - la connexion se fait par **identifiant et mot de passe** (`accounts/ClientLogin`), route qui
 *   rend un `Auth=…` à envoyer ensuite en `Authorization: GoogleLogin auth=…`.
 *
 * Pur (sauf [FreshRss.login]) : testé en JVM.
 */

/** Préfixe du jeton stocké d'un compte FreshRSS. */
const val FRESHRSS_PREFIX = "freshrss|"

/** Ce que porte le jeton d'un compte FreshRSS : l'adresse de son serveur et son jeton d'accès. */
data class FreshRssAuth(val baseUrl: String, val token: String)

/**
 * Adresse d'un serveur FreshRSS ramenée à sa racine (`https://freshrss.exemple.org`).
 *
 * Le schéma est ajouté quand il manque (on tape « freshrss.exemple.org »), les barres de fin
 * sont retirées, et une adresse complète (`…/api/greader.php`, celle qu'on copie parfois des
 * réglages) est ramenée à la racine : l'API ajoute ce chemin elle-même, le doubler donnait un
 * 404. Rend "" quand l'adresse n'est pas exploitable (protocole autre que http/https).
 */
fun freshRssBaseUrl(raw: String): String {
    var t = raw.trim()
    if (t.isEmpty()) return ""
    if (!t.contains("://")) t = "https://$t"
    val lower = t.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return ""
    val scheme = if (lower.startsWith("http://")) "http://" else "https://"
    // Seul l'hôte et son éventuel chemin comptent : une adresse complète
    // (`…/api/greader.php`, celle qu'on copie parfois des réglages) est ramenée à la racine,
    // l'API ajoutant ce chemin elle-même — le doubler donnait un 404.
    val host = t.substring(scheme.length)
        .substringBefore('?').substringBefore('#')
        .trimEnd('/').removeSuffix("/api/greader.php").trimEnd('/')
    return if (host.isBlank()) "" else scheme + host
}

/** Jeton stocké d'un compte FreshRSS, à partir du serveur et du jeton d'authentification. */
fun freshRssToken(baseUrl: String, auth: String): String =
    FRESHRSS_PREFIX + freshRssBaseUrl(baseUrl) + "|" + auth.trim()

/**
 * Relecture du jeton stocké ; null quand la forme ne s'y prête pas (jeton d'un autre service,
 * compte d'avant FreshRSS, morceau manquant).
 */
fun freshrssAuth(stored: String?): FreshRssAuth? {
    val s = stored?.trim().orEmpty()
    if (!s.startsWith(FRESHRSS_PREFIX)) return null
    val rest = s.removePrefix(FRESHRSS_PREFIX)
    val cut = rest.indexOf('|')
    if (cut <= 0 || cut == rest.length - 1) return null
    val base = freshRssBaseUrl(rest.substring(0, cut))
    val token = rest.substring(cut + 1).trim()
    if (base.isEmpty() || token.isEmpty()) return null
    return FreshRssAuth(base, token)
}

/** Issue d'une connexion FreshRSS : jeton à stocker dans le compte, et libellé à afficher. */
data class FreshRssConnect(val token: String, val label: String)

object FreshRss {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Connexion d'un serveur FreshRSS par identifiant et mot de passe.
     *
     * `accounts/ClientLogin` rend l'`Auth=…` de l'API Google Reader (le mot de passe peut être
     * celui du compte ou le « mot de passe d'API » des réglages FreshRSS, qui existe pour ça).
     * La connexion n'est retenue qu'après un **vrai appel** `user-info` : un serveur qui répond
     * au formulaire sans accepter le jeton (API désactivée, compte restreint) laisserait sinon
     * un compte inutilisable dans l'application.
     */
    suspend fun login(server: String, user: String, password: String): FreshRssConnect? =
        withContext(Dispatchers.IO) {
            val base = freshRssBaseUrl(server)
            val name = user.trim()
            if (base.isEmpty() || name.isEmpty() || password.isEmpty()) return@withContext null
            val auth = clientLogin(base, name, password) ?: return@withContext null
            val url = "$base/api/greader.php/"
            val label = runCatching {
                val u = InoreaderService.create(url, appAuth = false)
                    .userInfo(mapOf("Authorization" to inoreaderAuthHeader(auth)))
                listOfNotNull(
                    u.userName?.takeIf { it.isNotBlank() },
                    u.userEmail?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { name }
            }.getOrNull() ?: return@withContext null
            FreshRssConnect(token = freshRssToken(base, auth), label = label)
        }

    /** `accounts/ClientLogin` : rend le jeton `Auth=…` de la réponse texte, ou null si refusé. */
    private fun clientLogin(base: String, user: String, password: String): String? {
        val form = FormBody.Builder().add("Email", user).add("Passwd", password).build()
        val request = Request.Builder().url("$base/api/greader.php/accounts/ClientLogin").post(form).build()
        return try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                res.body?.string()?.lineSequence()
                    ?.firstOrNull { it.startsWith("Auth=") }
                    ?.removePrefix("Auth=")?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
