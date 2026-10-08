package com.pressia.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Instapaper, Simple API (`/api/authenticate` + `/api/add`, sans OAuth).
 *
 * Retenue plutôt que la Full API : l'ajout d'URL n'a besoin que de ça, pas de signature
 * OAuth 1.0a ni de clé d'application. Authentification par HTTP Basic Auth (recommandée
 * par la doc plutôt que les paramètres `username` / `password`).
 *
 * Codes retournés par `/api/add` : 201 ajouté, 400 requête invalide ou quota, 403
 * identifiants invalides, 500 erreur côté service.
 */
object Instapaper {
    private const val AUTH_URL = "https://www.instapaper.com/api/authenticate"
    private const val ADD_URL = "https://www.instapaper.com/api/add"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed interface AddResult {
        data object Ok : AddResult
        data object BadRequest : AddResult
        data object InvalidCredentials : AddResult
        data object ServerError : AddResult
        data object NetworkError : AddResult
    }

    /** Valide un couple identifiant / mot de passe, sans rien ajouter au compte. */
    suspend fun authenticate(user: String, pass: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(AUTH_URL)
                .header("Authorization", Credentials.basic(user, pass))
                .post(FormBody.Builder().build())
                .build()
            client.newCall(req).execute().use { it.code == 200 }
        } catch (_: Exception) {
            false
        }
    }

    /** Envoie une URL (et son titre) dans le compte. */
    suspend fun add(user: String, pass: String, url: String, title: String): AddResult =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("url", url)
                    .add("title", title.take(250))
                    .build()
                val req = Request.Builder()
                    .url(ADD_URL)
                    .header("Authorization", Credentials.basic(user, pass))
                    .post(body)
                    .build()
                client.newCall(req).execute().use {
                    when (it.code) {
                        201 -> AddResult.Ok
                        400 -> AddResult.BadRequest
                        403 -> AddResult.InvalidCredentials
                        500, 502, 503 -> AddResult.ServerError
                        else -> AddResult.ServerError
                    }
                }
            } catch (_: Exception) {
                AddResult.NetworkError
            }
        }
}
