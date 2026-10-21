package com.pressia.data.remote

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Instapaper sans clé d'application ni OAuth : exactement comme l'extension Chrome officielle
 * (v3.1.2, `js/readlater.js` + `bookmarklet.js`), qui n'utilise que la session web du compte —
 * cookies de `instapaper.com` — et des endpoints internes :
 *
 *  - `POST /bookmarklet/post_v6` (`gr=1&u=&t=&can_url=&s=&tzd=&i=&b=`) : enregistre l'URL, renvoie
 *    en texte un JSON `{bookmark_id, folders: [{id, name}], all_tags, bookmark_tags, …}` ; 403 = session expirée.
 *  - `GET /move/{id}/to/{folder}` : range le marque-page dans un dossier.
 *  - `POST /data/bookmarks/actions` (`{actions: [{bookmark_id, action: "tags",
 *    add_tags: [{name}], remove_tags: []}]}`, comme le dialogue de tags du site) : pose les tags,
 *    créés s'ils n'existent pas.
 *  - `GET /data/user_session` : `{user: {folders: [{id, title, …}], tags: [{id, name, …}], formKey…}}`
 *    (charge utile du site actuel, vue dans son paquet `appCollectionManagement`) ; 401 = déconnecté.
 *  - `POST /data/folders/create` (JSON `{title, sync_to_mobile, public}` + en-tête `X-Form-Key`,
 *    clé lue dans le cookie `form_key` ou `user_session`) : crée un dossier.
 *  - `POST /user/login` (`username`, `password`, `_xsrf` frais lu sur la page) : ouvre la session
 *    avec les identifiants déjà stockés pour l'API simple — aucune saisie supplémentaire.
 *
 * Les cookies vivent dans le [CookieManager] de la WebView (persistés par le système) : aucune
 * clé ni jeton à stocker. Parsers et corps de requêtes volontairement purs (JVM) pour les tests.
 */
object InstapaperWeb {
    private const val BASE = "https://www.instapaper.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val noRedirect = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Folder(val id: Long, val title: String)
    data class Tag(val id: Long, val name: String, val count: Int = 0, val slug: String = "")
    data class Session(val folders: List<Folder>, val tags: List<Tag>, val formKey: String?)
    data class Saved(val bookmarkId: Long, val folders: List<Folder>, val tags: List<Tag>)

    sealed interface SendResult {
        data object Ok : SendResult
        /** Session expirée : reconnecter (login auto si les identifiants simples sont là). */
        data object Expired : SendResult
        data object Refused : SendResult
        data object ServerError : SendResult
        data object NetworkError : SendResult
    }

    // ----- Pur JVM : en-têtes, cookies, corps -----

    /** En-tête `Cookie` courant pour instapaper.com, lu dans la WebView (vide si jamais connecté). */
    fun cookieHeader(): String =
        try {
            CookieManager.getInstance()?.getCookie(BASE).orEmpty()
        } catch (_: Exception) { "" }

    /** Persiste les cookies de session reçus (`Set-Cookie`) dans la WebView. */
    fun storeCookies(url: String, headers: okhttp3.Headers) {
        try {
            val cm = CookieManager.getInstance() ?: return
            headers.values("Set-Cookie").forEach { cm.setCookie(url, it) }
            cm.flush()
        } catch (_: Exception) { /* session web indisponible, les appels échoueront en Expired */ }
    }

    /** Clé anti-CSRF : cookie `form_key` en priorité, sinon champ de `user_session` (camel ou snake). */
    fun formKey(cookieHeader: String, sessionKey: String?): String? {
        cookieHeader.split(";").forEach {
            val kv = it.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "form_key" && kv[1].isNotBlank()) return kv[1]
        }
        return sessionKey?.takeIf { it.isNotBlank() }
    }

    /** Jeton `_xsrf` du formulaire de connexion (page `GET /user/login`). */
    fun extractXsrf(loginHtml: String): String? {
        val m = Regex("""name="_xsrf"\s+value="([^"]+)"""").find(loginHtml)
        return m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * Corps du `POST /bookmarklet/post_v6` en mode « lien » (`gr=1`, comme le clic droit
     * « Save to Instapaper ») : URL + titre, sans contenu de page.
     */
    fun saveBody(url: String, title: String): String =
        "gr=1&u=${enc(url)}&t=${enc(title.take(250))}&can_url=&s=&tzd=&i=&b="

    /** `(String, Charset)` exige l'API 33 : la variante `(String, String)` vaut dès l'API 1. */
    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** Parse `GET /data/user_session` → dossiers + tags + clé, ou null si déconnecté / illisible. */
    fun parseSession(body: String): Session? {
        return try {
            val root = JSONObject(body)
            if (root.isNull("user")) return null
        val u = root.getJSONObject("user")
        val folders = mutableListOf<Folder>()
        u.optJSONArray("folders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id")
                val title = o.optString("title").ifBlank { o.optString("display_title") }.ifBlank { "(sans nom)" }
                if (id != 0L) folders += Folder(id, title)
            }
        }
        val tags = mutableListOf<Tag>()
        u.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id")
                val name = o.optString("name")
                if (id != 0L && name.isNotBlank()) tags += Tag(id, name, o.optInt("count"), o.optString("slug"))
            }
        }
        val key = u.optString("formKey").ifBlank { u.optString("form_key") }.ifBlank { null }
        Session(folders, tags.sortedByDescending { it.count }, key)
        } catch (_: Exception) { null }
    }

    /** Parse la réponse texte de `post_v6` → id + dossiers + tags proposés, ou null si illisible. */
    fun parseSaved(body: String): Saved? {
        return try {
            val o = JSONObject(body)
            val id = o.optLong("bookmark_id")
            if (id == 0L) return null
        val folders = mutableListOf<Folder>()
        o.optJSONArray("folders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val fid = f.optLong("id")
                val title = f.optString("name").ifBlank { f.optString("title") }.ifBlank { "(sans nom)" }
                if (fid != 0L) folders += Folder(fid, title)
            }
        }
        val tags = mutableListOf<Tag>()
        o.optJSONArray("all_tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val tid = t.optLong("id")
                val name = t.optString("name")
                if (tid != 0L && name.isNotBlank()) tags += Tag(tid, name, t.optInt("count"), t.optString("slug"))
            }
        }
        Saved(id, folders, tags.sortedByDescending { it.count })
        } catch (_: Exception) { null }
    }

    // ----- Réseau : session web (cookies WebView) -----

    private fun authed(path: String): Request.Builder = Request.Builder()
        .url(BASE + path)
        .header("Cookie", cookieHeader())
        .header("X-Requested-With", "XMLHttpRequest")

    /**
     * Session active ? (dossiers + tags + clé anti-CSRF, ou null si expirée). Ne lève jamais :
     * une panne réseau donne aussi null — à l'appelant de distinguer si besoin ([fetchSession]).
     */
    suspend fun session(): Session? = try {
        fetchSession()
    } catch (_: Exception) { null }

    /** Même chose en laissant passer les pannes réseau (pour ne pas les confondre avec un refus). */
    suspend fun fetchSession(): Session? = withContext(Dispatchers.IO) {
        // `Rt()` du site préfixe tous ces appels par `/data`.
        val req = authed("/data/user_session").get().build()
        client.newCall(req).execute().use {
            if (it.code != 200) return@withContext null
            parseSession(it.body?.string().orEmpty())
        }
    }

    /**
     * Ouvre la session web avec les identifiants du compte simple : `_xsrf` frais depuis la page
     * de connexion, puis `POST /user/login`. Renvoie faux si le serveur refuse (mauvais
     * identifiants), et lève en cas de panne réseau.
     */
    suspend fun login(user: String, pass: String): Boolean = withContext(Dispatchers.IO) {
            // Jeton frais + cookies de session anonyme.
            val pageReq = Request.Builder().url("$BASE/user/login").get().build()
            val page = noRedirect.newCall(pageReq).execute().use {
                storeCookies("$BASE/", it.headers)
                it.body?.string().orEmpty()
            }
            val xsrf = extractXsrf(page) ?: return@withContext false
            val body = FormBody.Builder()
                .add("_xsrf", xsrf)
                .add("username", user)
                .add("password", pass)
                .add("keep_logged_in", "yes")
                .add("hello2", "yup")
                .add("u", "").add("t", "").add("s", "").add("a", "")
                .build()
            val loginReq = Request.Builder()
                .url("$BASE/user/login")
                .header("Cookie", cookieHeader())
                .post(body)
                .build()
            noRedirect.newCall(loginReq).execute().use { storeCookies("$BASE/", it.headers) }
            fetchSession() != null
    }

    /** Enregistre l'URL (mode lien), ou [SendResult.Expired] si la session est morte. */
    suspend fun save(url: String, title: String): Pair<SendResult, Saved?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/bookmarklet/post_v6")
                .header("Cookie", cookieHeader())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(saveBody(url, title).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            client.newCall(req).execute().use {
                when (it.code) {
                    200 -> {
                        val saved = parseSaved(it.body?.string().orEmpty())
                        if (saved == null) Pair(SendResult.Refused, null) else Pair(SendResult.Ok, saved)
                    }
                    403 -> Pair(SendResult.Expired, null)
                    400 -> Pair(SendResult.Refused, null)
                    in 500..599 -> Pair(SendResult.ServerError, null)
                    else -> Pair(SendResult.ServerError, null)
                }
            }
        } catch (_: Exception) { Pair(SendResult.NetworkError, null) }
    }

    /** Range le marque-page dans un dossier (`GET /move/{id}/to/{folder}`, comme l'extension). */
    suspend fun move(bookmarkId: Long, folderId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = authed("/move/$bookmarkId/to/$folderId").get().build()
            client.newCall(req).execute().use { it.code in 200..399 }
        } catch (_: Exception) { false }
    }

    /**
     * Pose les tags via l'action du site actuel (`POST /data/bookmarks/actions`, comme le
     * dialogue de tags du site : `add_tags` = objets `{name}`, créés si besoin).
     */
    suspend fun addTags(bookmarkId: Long, names: List<String>, formKey: String?): Boolean {
        if (names.isEmpty() || formKey.isNullOrBlank()) return names.isEmpty()
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject()
                    .put("actions", JSONArray().put(
                        JSONObject()
                            .put("bookmark_id", bookmarkId)
                            .put("action", "tags")
                            .put("add_tags", JSONArray(names.map { JSONObject().put("name", it) }))
                            .put("remove_tags", JSONArray())
                    ))
                    .toString()
                val req = Request.Builder()
                    .url("$BASE/data/bookmarks/actions")
                    .header("Cookie", cookieHeader())
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-Form-Key", formKey)
                    .post(json.toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { it.code in 200..299 }
            } catch (_: Exception) { false }
        }
    }

    /** Crée un dossier (`POST /data/folders/create` + `X-Form-Key`), ou null si refusé. */
    suspend fun createFolder(title: String, formKey: String?): Folder? = withContext(Dispatchers.IO) {
        if (formKey.isNullOrBlank() || title.isBlank()) return@withContext null
        try {
            val json = JSONObject()
                .put("title", title.trim())
                .put("sync_to_mobile", true)
                .put("public", false)
                .toString()
            val req = Request.Builder()
                .url("$BASE/data/folders/create")
                .header("Cookie", cookieHeader())
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Form-Key", formKey)
                .post(json.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use {
                if (it.code !in 200..299) return@withContext null
                try {
                    val o = JSONObject(it.body?.string().orEmpty())
                    val f = if (o.has("folder")) o.getJSONObject("folder") else o
                    Folder(f.optLong("id"), f.optString("title").ifBlank { "(sans nom)" })
                        .takeIf { fd -> fd.id != 0L }
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { null }
    }

    /** Déconnecte côté web : jette les cookies Instapaper de la WebView. */
    fun logout() {
        try {
            val cm = CookieManager.getInstance() ?: return
            val before = cookieHeader()
            // Seuls les cookies du domaine nous appartiennent : on les expire un par un.
            before.split(";").mapNotNull {
                val n = it.substringBefore("=").trim()
                n.takeIf { s -> s.isNotBlank() }?.let { name -> "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; domain=.instapaper.com" }
            }.forEach { cm.setCookie(BASE, it) }
            cm.flush()
        } catch (_: Exception) { /* tant pis, la session mourra côté serveur */ }
    }
}
