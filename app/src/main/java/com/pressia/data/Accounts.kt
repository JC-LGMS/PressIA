package com.pressia.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Provider des flux saisis à la main, sans compte ni cloud. */
const val LOCAL_PROVIDER = "local"

/**
 * Un compte externe connecté (Feedly ou Inoreader). Plusieurs comptes du même service coexistent :
 * chacun a son jeton, ses dossiers et ses abonnements, sans jamais se confondre avec les autres.
 *
 * [id] est la clé d'appropriation utilisée dans Room : elle vaut `"<service>#<empreinte du jeton>"`,
 * ce qui la rend stable tant que le jeton ne change pas et impossible à confondre avec un autre
 * compte du même service.
 */
data class CloudAccount(
    val id: String,
    val provider: String,
    val token: String,
    /** Nom affiché (e-mail, identifiant) renseigné à la connexion ; purement informatif. */
    val name: String = ""
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
 * Empreinte courte et stable d'un jeton : sert de suffixe d'identification du compte. Elle ne
 * peut pas être reconstruite depuis le jeton (sha-256 tronqué), et un nouveau jeton donne un
 * nouveau compte, ce qui est le comportement attendu quand l'utilisateur se reconnecte.
 */
fun accountFingerprint(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.trim().toByteArray())
    return digest.take(4).joinToString("") { "%02x".format(it) }
}

/** Construit la clé d'appropriation d'un compte à partir de son service et de son jeton. */
fun accountIdOf(service: String, token: String): String =
    "${service.lowercase()}#${accountFingerprint(token)}"

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
                    CloudAccount(id, provider, token, o.optString("name").trim())
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

    /** Remplace un compte par un nouveau jeton, en conservant son identité si elle est stable. */
    fun refreshToken(accounts: List<CloudAccount>, service: String, token: String, name: String = ""): List<CloudAccount> {
        val byToken = accounts.firstOrNull { it.service == service.lowercase() && it.token == token }
        if (byToken != null) return accounts.map { if (it.id == byToken.id && name.isNotBlank()) it.copy(name = name) else it }
        val account = CloudAccount(accountIdOf(service, token), service.lowercase(), token, name)
        return upsert(accounts, account)
    }
}
