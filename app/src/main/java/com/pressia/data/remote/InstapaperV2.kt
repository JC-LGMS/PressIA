package com.pressia.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Instapaper API v2 (`https://www.instapaper.com/api/2`, JSON + jeton Bearer OAuth2) : dossiers,
 * tags et enregistrement ciblé. L'envoi direct sans dossier ni tag reste sur l'API simple
 * ([Instapaper]) qui n'a besoin d'aucune clé d'application.
 *
 * Parsers et constructeurs JSON volontairement purs (JVM, sans réseau) pour les tests unitaires.
 */
object InstapaperV2 {
    private const val BASE = "https://www.instapaper.com/api/2"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Folder(val id: Long, val title: String)
    data class Tag(val id: Long, val name: String, val count: Int = 0)

    sealed interface SaveResult {
        data object Ok : SaveResult
        data object BadRequest : SaveResult
        data object Unauthorized : SaveResult
        data object ServerError : SaveResult
        data object NetworkError : SaveResult
    }

    /** Parse `GET /folders` → `{"folders": [{"id", "title", …}]}`. Robuste aux champs absents. */
    fun parseFolders(body: String): List<Folder> = try {
        val arr = JSONObject(body).optJSONArray("folders") ?: JSONArray()
        List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            Folder(o.optLong("id"), o.optString("title").ifBlank { "(sans nom)" })
        }.filter { it.id != 0L }
    } catch (_: Exception) { emptyList() }

    /** Parse un dossier isolé (`POST /folders` renvoie l'objet lui-même). */
    fun parseFolder(body: String): Folder? = try {
        val o = JSONObject(body)
        Folder(o.optLong("id"), o.optString("title").ifBlank { "(sans nom)" }).takeIf { it.id != 0L }
    } catch (_: Exception) { null }

    /** Parse `GET /tags` → `{"tags": [{"id", "name", "count", …}]}`. Robuste aux champs absents. */
    fun parseTags(body: String): List<Tag> = try {
        val arr = JSONObject(body).optJSONArray("tags") ?: JSONArray()
        List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: JSONObject()
            Tag(o.optLong("id"), o.optString("name"), o.optInt("count"))
        }.filter { it.id != 0L && it.name.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    /**
     * Corps de `POST /bookmarks`. Les tags partent en objets `{"name"}` : l'API crée ceux qui
     * n'existent pas encore. Dossier absent ou nul = liste d'accueil (« Home »).
     */
    fun saveBody(url: String, title: String, folderId: Long?, tags: List<String>): String {
        val o = JSONObject()
            .put("url", url)
            .put("title", title.take(250))
        if (folderId != null && folderId != 0L) o.put("folder_id", folderId)
        if (tags.isNotEmpty()) o.put("tags", JSONArray(tags.map { JSONObject().put("name", it) }))
        return o.toString()
    }

    private suspend fun get(token: String, path: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(BASE + path)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { if (it.code == 200) it.body?.string() else null }
        } catch (_: Exception) { null }
    }

    private suspend fun post(token: String, path: String, json: String): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(BASE + path)
                    .header("Authorization", "Bearer $token")
                    .post(json.toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { Pair(it.code, it.body?.string()) }
            } catch (_: Exception) { Pair(-1, null) }
        }

    /** Dossiers du compte (créés sur le site ou via [createFolder]). */
    suspend fun folders(token: String): List<Folder> = get(token, "/folders")?.let(::parseFolders).orEmpty()

    /** Crée un dossier, ou null si refusé (titre déjà pris : erreur 400 côté API). */
    suspend fun createFolder(token: String, title: String): Folder? {
        val (code, body) = post(token, "/folders", JSONObject().put("title", title).toString())
        return if (code in 200..299) body?.let(::parseFolder) else null
    }

    /** Tags du compte, les plus utilisés d'abord. */
    suspend fun tags(token: String): List<Tag> =
        get(token, "/tags")?.let(::parseTags).orEmpty().sortedByDescending { it.count }

    /** Vérifie le jeton sans rien écrire (`GET /me`, 200 = valide). */
    suspend fun checkToken(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/me")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { it.code == 200 }
        } catch (_: Exception) { false }
    }

    /** Enregistre une URL, éventuellement dans un dossier et avec des tags (créés si besoin). */
    suspend fun save(token: String, url: String, title: String, folderId: Long?, tags: List<String>): SaveResult {
        val (code, _) = post(token, "/bookmarks", saveBody(url, title, folderId, tags))
        return when (code) {
            in 200..299 -> SaveResult.Ok
            400 -> SaveResult.BadRequest
            401 -> SaveResult.Unauthorized
            in 500..599 -> SaveResult.ServerError
            else -> if (code == -1) SaveResult.NetworkError else SaveResult.ServerError
        }
    }
}
