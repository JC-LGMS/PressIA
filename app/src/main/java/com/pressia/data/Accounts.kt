package com.pressia.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Provider des flux saisis à la main, sans compte ni cloud. */
const val LOCAL_PROVIDER = "local"

/**
 * Un compte externe connecté à Feedly. Plusieurs comptes du même service coexistent :
 * chacun a son jeton, ses dossiers et ses abonnements, sans jamais se confondre avec les autres.
 *
 * [id] est la clé d'appropriation utilisée dans Room : elle vaut `"<service>#<empreinte du jeton>"`
 * à la création. Une reconnexion du MÊME utilisateur (nouveau jeton) ne crée pas un second
 * compte : le jeton remplace l'ancien sur place (même [id], voir [AccountStore.refreshToken]),
 * sinon les dossiers et abonnements seraient dupliqués.
 *
 * [userId] est l'identifiant distant stable (profil du service), vide si inconnu : c'est lui,
 * avec le service, qui dit « même compte » lors d'une reconnexion — pas le jeton, qui change
 * à chaque connexion, ni le [name], purement informatif.
 */
data class CloudAccount(
    val id: String,
    val provider: String,
    val token: String,
    /** Nom affiché (e-mail, identifiant) renseigné à la connexion ; purement informatif. */
    val name: String = "",
    /** Identifiant distant stable (profil) ; vide si inconnu (anciennes versions). */
    val userId: String = ""
) {
    /** Service de base, sans l'empreinte du compte (« feedly#ab12… » → « feedly »). */
    val service: String get() = providerBase(id)
    val label: String get() = serviceLabel(service)
}

/** Nom lisible d'un service de compte. */
fun serviceLabel(service: String): String = when (service) {
    "feedly" -> "Feedly"
    "inoreader" -> "Inoreader"
    else -> "Compte"
}

/** Partie « service » d'une clé d'appropriation : `"feedly#ab12"` → `"feedly"`. */
fun providerBase(key: String?): String {
    if (key.isNullOrBlank()) return LOCAL_PROVIDER
    return key.substringBefore('#').ifBlank { LOCAL_PROVIDER }
}

/** Cette clé d'appropriation est-elle celle des flux RSS saisis à la main ? */
fun isLocalProvider(key: String?): Boolean = providerBase(key) == LOCAL_PROVIDER

/** Deux clés d'appropriation appartiennent-elles au même compte ? */
fun sameAccount(a: String?, b: String?): Boolean = a != null && b != null && a == b

/**
 * Empreinte courte et stable d'un jeton : sert de suffixe d'identification du compte à sa
 * création. Elle ne peut pas être reconstruite depuis le jeton (sha-256 tronqué). Un nouveau
 * jeton du MÊME utilisateur ne crée pas un nouveau compte (voir [AccountStore.refreshToken]) :
 * seule une première connexion forge un identifiant.
 */
fun accountFingerprint(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.trim().toByteArray())
    return digest.take(4).joinToString("") { "%02x".format(it) }
}

/** Construit la clé d'appropriation d'un compte à partir de son service et de son jeton. */
fun accountIdOf(service: String, token: String): String =
    "${service.lowercase()}#${accountFingerprint(token)}"

/**
 * Échec d'authentification (jeton révoqué / expiré, identifiants refusés) ? Les appels cloud
 * (Retrofit) lèvent [retrofit2.HttpException] avec le code HTTP ; 401 et 403 signifient que la
 * session est morte, tout le reste (500, réseau…) est transitoire. Remonte la chaîne des causes,
 * Retrofit et OkHttp enveloppant souvent l'erreur d'origine.
 */
fun isAuthFailure(t: Throwable): Boolean {
    var c: Throwable? = t
    while (c != null) {
        if (c is retrofit2.HttpException && (c.code() == 401 || c.code() == 403)) return true
        val next = c.cause
        if (next == null || next === c) break
        c = next
    }
    return false
}

/**
 * Limitation de débit (HTTP 429) : le serveur dit stop, avec le délai demandé en secondes et
 * le nom du header qui le donne (`Retry-After`, ou `X-RateLimit-Reset` chez Feedly :
 * secondes restantes avant réinitialisation du compteur). Délai null si aucun des deux
 * n'est présent ou lisible.
 */
data class RateLimit(val service: String, val retryAfterSec: Long?, val header: String? = null)

/**
 * Un 429 se cache-t-il dans cette erreur ? Même remontée de causes que [isAuthFailure].
 * 429 ≠ session expirée : le jeton est bon, c'est le quota d'appels qui est épuisé —
 * jamais d'alerte à reconnecter pour ce cas.
 */
fun rateLimitOf(t: Throwable, service: String): RateLimit? {
    var c: Throwable? = t
    while (c != null) {
        if (c is retrofit2.HttpException && c.code() == 429) {
            val headers = c.response()?.headers()
            // Standard d'abord, spécifique Feedly ensuite (secondes dans les deux cas).
            val retryAfter = headers?.get("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                ?.let { "Retry-After" to it }
                ?: headers?.get("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                    ?.let { "X-RateLimit-Reset" to it }
            return RateLimit(service, retryAfter?.second, retryAfter?.first)
        }
        val next = c.cause
        if (next == null || next === c) break
        c = next
    }
    return null
}

/** Message final quand un 429 a empêché la synchronisation, avec le délai du header. */
fun rateLimitMessage(service: String, retryAfterSec: Long?, header: String? = null): String {
    val wait = retryAfterSec?.let { s ->
        if (s >= 3600) "~${s / 3600} h" else if (s >= 60) "~${s / 60} min" else "~$s s"
    }
    val origin = if (header != null && retryAfterSec != null) ", $header : $retryAfterSec s" else ""
    return if (wait != null) "$service limite les requêtes (erreur 429$origin) : synchronisation impossible, réessayez dans $wait"
    else "$service limite les requêtes (erreur 429) : synchronisation impossible, réessayez dans quelques minutes"
}

/** Comptes à la session expirée, sérialisés (virgules, triés) pour [com.pressia.data.local.Prefs]. */
fun encodeExpired(ids: Set<String>): String = ids.map { it.trim() }.filter { it.isNotEmpty() }.sorted().joinToString(",")

/** Parse [encodeExpired], tolérant (vide, doublons, espaces). */
fun parseExpired(raw: String?): Set<String> =
    raw.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Lecture / écriture du registre de comptes. Volontairement sans dépendance Android : le format
 * est couvert par les tests unitaires.
 */
object AccountStore {

    fun parse(raw: String?): List<CloudAccount> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "[]") return emptyList()
        return try {
            JSONArray(text).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val provider = o.optString("provider").trim().lowercase()
                    val token = o.optString("token").trim()
                    if (provider.isBlank() || token.isBlank()) return@mapNotNull null
                    val id = o.optString("id").trim().ifBlank { accountIdOf(provider, token) }
                    CloudAccount(id, provider, token, o.optString("name").trim(), o.optString("userId").trim())
                }
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(accounts: List<CloudAccount>): String {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("provider", a.provider)
                    .put("token", a.token)
                    .put("name", a.name)
                    .put("userId", a.userId)
            )
        }
        return arr.toString()
    }

    /**
     * Ajoute un compte à un registre existant. Un jeton déjà présent n'est pas dupliqué : on se
     * contente de rafraîchir son nom, ce qui évite deux lignes pour un même compte reconnecté.
     */
    fun upsert(accounts: List<CloudAccount>, account: CloudAccount): List<CloudAccount> =
        accounts.filterNot { it.id == account.id } + account

    /**
     * Enregistre une connexion : remplace le jeton sur place quand c'est le même utilisateur
     * (même service + même [userId] non vide), au lieu d'ajouter une seconde ligne — sinon
     * chaque reconnexion (nouveau jeton) dupliquait le compte, ses dossiers et ses flux.
     * L'identité est conservée ([id] inchangé) : abonnements et articles suivent sans migration.
     * Sans [userId] (inconnu), seul un jeton identique est reconnu : deux utilisateurs
     * distincts ne fusionnent jamais, même sans identifiant.
     */
    fun refreshToken(accounts: List<CloudAccount>, service: String, token: String, name: String = "", userId: String = ""): List<CloudAccount> {
        val svc = service.lowercase()
        val byToken = accounts.firstOrNull { it.service == svc && it.token == token }
        if (byToken != null) {
            return accounts.map {
                if (it.id != byToken.id) it
                else it.copy(
                    name = name.ifBlank { it.name },
                    userId = it.userId.ifBlank { userId.trim() }
                )
            }
        }
        val uid = userId.trim()
        val sameUser = accounts.firstOrNull { it.service == svc && it.userId.isNotBlank() && uid.isNotBlank() && it.userId == uid }
        if (sameUser != null) {
            return accounts.map {
                if (it.id != sameUser.id) it
                else it.copy(token = token, name = name.ifBlank { it.name })
            }
        }
        val account = CloudAccount(accountIdOf(service, token), svc, token, name, uid)
        return upsert(accounts, account)
    }
}
