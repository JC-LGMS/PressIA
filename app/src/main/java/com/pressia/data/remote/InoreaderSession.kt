package com.pressia.data.remote

/**
 * Session Inoreader capturée dans une WebView (page `www.inoreader.com`).
 *
 * Le mécanisme est celui de l'extension officielle Inoreader pour navigateur : pas de
 * formulaire de connexion dans l'application, la page s'ouvre, l'utilisateur s'y
 * connecte, et le **cookie de session** est récupéré automatiquement. Ce cookie porte
 * l'identifiant de session — le `SID` historique du protocole `ClientLogin` — qui est
 * aussi la valeur attendue par l'API Reader d'Inoreader :
 * `Authorization: GoogleLogin auth=<SID>`.
 *
 * Toute la logique de ce fichier est **pure** (aucune dépendance Android) : elle est
 * couverte par les tests unitaires JVM.
 */

/**
 * Hôtes où chercher les cookies de session après connexion.
 *
 * La page pose certains cookies sur `inoreader.com` et d'autres sur `www.inoreader.com` :
 * deux domaines sont scrutés, avec leurs variantes `http`/`https` et `www`/apex (le
 * CookieManager accepte l'URL complète, la forme exacte est conservée telle quelle).
 */
val INOREADER_COOKIE_HOSTS = listOf(
    "https://www.inoreader.com", "https://inoreader.com",
    "https://www.inoreader.com/", "https://inoreader.com/",
    "http://www.inoreader.com", "http://inoreader.com",
    "http://www.inoreader.com/", "http://inoreader.com/"
)

/**
 * Noms des cookies de session connus d'Inoreader, par ordre de préférence.
 *
 * `SID` est l'identifiant de session (protocole `ClientLogin` d'origine) et `LSID` la
 * session longue. Les autres cookies du site (préférences, CSRF…) ne sont essayés qu'en
 * dernier recours : un essai raté ne coûte qu'un appel, mais Inoreader compte les
 * requêtes au quota.
 */
val INOREADER_SESSION_COOKIES = listOf("SID", "LSID", "SSID", "BSID")

/**
 * Longueur minimale d'un jeton de session plausible.
 *
 * Les drapeaux (`SID=1`, `remember=1`…) sont ainsi écartés : un jeton de session est
 * une longue valeur opaque.
 */
private const val MIN_SESSION_LEN = 12

/**
 * Un jeton de session plausible ? Long, opaque, sans séparateur de cookie.
 *
 * Écarte aussi les valeurs qui contiendraient un `,` : un en-tête `Cookie` mal formed
 * ne doit jamais produire un en-tête `Authorization` cassé.
 */
fun plausibleInoreaderToken(value: String?): String? {
    val v = value?.trim()?.trim('"')?.trim() ?: return null
    if (v.length < MIN_SESSION_LEN) return null
    if (v.any { it == ';' || it == ' ' || it == '\n' || it == '\r' || it == ',' }) return null
    return v
}

/**
 * Valeurs candidates d'un jeton de session, tirées d'un en-tête `Cookie`.
 *
 * Les cookies de session nommés (`SID`, `LSID`) d'abord, puis les autres valeurs
 * opaques dans l'ordre du header. L'ordre compte : Inoreader compte les requêtes au
 * quota, on ne veut pas multiplier les essais.
 */
fun inoreaderSessionCandidates(cookieHeader: String?): List<String> {
    val pairs = cookieHeader.orEmpty()
        .split(";")
        .mapNotNull { part ->
            val t = part.trim()
            if (!t.contains('=')) return@mapNotNull null
            val name = t.substringBefore('=').trim()
            val value = t.substringAfter('=').trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
    val known = pairs.filter { it.first.uppercase() in INOREADER_SESSION_COOKIES }
    val others = pairs.filterNot { it.first.uppercase() in INOREADER_SESSION_COOKIES }
    return (known + others)
        .mapNotNull { plausibleInoreaderToken(it.second) }
        .distinct()
}

/**
 * En-tête d'authentification d'un jeton de session Inoreader.
 *
 * `GoogleLogin auth=…` est le schéma de l'API Reader d'Inoreader — celui documenté
 * pour `ClientLogin`, et celui que l'application utilise déjà pour le jeton OAuth.
 * Le même schéma pour les deux sources de jeton : rien à distinguer côté appels.
 */
fun inoreaderAuthHeader(token: String): String = "GoogleLogin auth=$token"
