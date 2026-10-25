package com.pressia.data.remote

/**
 * Session Inoreader capturée dans une WebView (page `www.inoreader.com`).
 *
 * Le mécanisme est celui de l'extension officielle Inoreader pour navigateur : pas de
 * formulaire de connexion dans l'application, la page s'ouvre, l'utilisateur s'y
 * connecte, et le **cookie de session** est récupéré. L'API Inoreader est ensuite
 * appelée exactement comme l'extension le fait : les cookies dans un en-tête `Cookie`
 * (avec `AppId`/`AppKey` en paramètre d'URL), **sans** en-tête `Authorization`. C'est
 * le cookie `al` qui porte la session.
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
 * `www.inoreader.com` vient en tête : c'est là que se trouve `al`, le cookie porteur.
 */
val INOREADER_COOKIE_HOSTS = listOf(
    "https://www.inoreader.com", "https://inoreader.com",
    "https://www.inoreader.com/", "https://inoreader.com/",
    "http://www.inoreader.com", "http://inoreader.com",
    "http://www.inoreader.com/", "http://inoreader.com/"
)

/**
 * Cookie de session porteur d'Inoreader — `al` en premier.
 *
 * Vérifié en direct : `al` seul authentifie l'API (`GET reader/api/0/user-info` → 200),
 * tandis que `ssid` seul est refusé (401). Les autres noms ne sont que des replis.
 * `al` est posé sur `.www.inoreader.com` ; les noms sont donc comparés sans tenir
 * compte de la casse (l'ancien `SID`/`LSID` du protocole `ClientLogin` reste accepté).
 */
val INOREADER_SESSION_COOKIES = listOf("al", "ssid", "SID", "LSID", "bsid")

private val SESSION_COOKIE_NAMES = INOREADER_SESSION_COOKIES.map { it.lowercase() }.toSet()

/**
 * Préfixe d'un jeton de compte Inoreader **authentifié par cookie de session web**.
 *
 * Le registre des comptes ne connaît qu'une chaîne (`CloudAccount.token`) : le préfixe
 * lève l'ambiguïté avec un jeton d'accès OAuth, que le fournisseur envoie autrement
 * (`Authorization: GoogleLogin auth=…`).
 */
const val INOREADER_COOKIE_PREFIX = "cookie:"

/**
 * Authentification d'un compte Inoreader, telle qu'elle est stockée dans le registre :
 * soit un jeton d'accès OAuth, soit l'en-tête `Cookie` d'une session web.
 */
sealed interface InoreaderAuth {
    /** Jeton d'accès OAuth : envoyé en `Authorization: GoogleLogin auth=…`. */
    data class OAuth(val token: String) : InoreaderAuth
    /** Cookie de session web : envoyé en en-tête `Cookie` (mécanisme de l'extension). */
    data class WebCookie(val cookie: String) : InoreaderAuth
}

/**
 * Interprète le jeton d'un compte Inoreader.
 *
 * Un jeton préfixé `cookie:` est une session web ; sinon un en-tête `Cookie` collé à la
 * main (plusieurs paires `nom=valeur`, sans préfixe) est reconnu à sa forme. Tout le
 * reste est un jeton OAuth — y compris les jetons déjà enregistrés par une version
 * précédente, qui ne se confondent pas avec un en-tête Cookie.
 */
fun inoreaderAuth(raw: String?): InoreaderAuth? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return null
    if (t.startsWith(INOREADER_COOKIE_PREFIX, ignoreCase = true)) {
        val cookie = t.substring(INOREADER_COOKIE_PREFIX.length).trim()
        return if (cookie.isNotEmpty()) InoreaderAuth.WebCookie(cookie) else null
    }
    if (looksLikeCookieHeader(t)) return InoreaderAuth.WebCookie(t)
    return InoreaderAuth.OAuth(t)
}

/**
 * Un en-tête `Cookie` : au moins une paire `nom=valeur` et un séparateur de paires.
 *
 * Un jeton OAuth est une valeur opaque unique : il peut contenir `=`, jamais `;` ni
 * espace. La présence d'un séparateur distingue donc les deux sans ambiguïté.
 */
fun looksLikeCookieHeader(value: String?): Boolean {
    val v = value.orEmpty()
    return v.contains('=') && (v.contains(';') || v.contains(' '))
}

/**
 * L'en-tête porte-t-il un cookie de session Inoreader (au moins `al`) ?
 *
 * Sert à ne pas lancer une vérification — un appel compté au quota — sur un bac à
 * cookies qui ne contient encore que des cookies publicitaires.
 */
fun inoreaderCookieHasSession(cookieHeader: String?): Boolean =
    cookieHeader.orEmpty().split(";").any { part ->
        val name = part.substringBefore('=').trim()
        part.contains('=') && part.substringAfter('=').trim().isNotEmpty() && name.lowercase() in SESSION_COOKIE_NAMES
    }

/**
 * En-têtes `Cookie` candidats, tirés d'un en-tête `Cookie` brut du navigateur.
 *
 * Le navigateur envoie *tous* les cookies de l'hôte : on essaie donc d'abord l'en-tête
 * complet (fidèle au navigateur), puis un en-tête réduit aux seuls cookies de session
 * (au cas où un cookie publicitaire mal formé casserait l'en-tête). Inoreader compte
 * les requêtes au quota : l'ordre est celui de la réussite attendue.
 */
fun inoreaderCookieCandidates(cookieHeader: String?): List<String> {
    val raw = cookieHeader?.trim().orEmpty()
    if (raw.isEmpty()) return emptyList()
    val sessionOnly = raw.split(";")
        .map { it.trim() }
        .filter { part ->
            val name = part.substringBefore('=').trim()
            part.contains('=') && part.substringAfter('=').trim().isNotEmpty() &&
                name.lowercase() in SESSION_COOKIE_NAMES
        }
        .joinToString("; ")
    return listOf(raw, sessionOnly).filter { it.isNotBlank() }.distinct()
}

/**
 * En-tête d'authentification d'un jeton OAuth Inoreader.
 *
 * `GoogleLogin auth=…` est le schéma de l'API Reader d'Inoreader — celui documenté pour
 * `ClientLogin`, et celui qu'utilise le flux OAuth. Une session web, elle, n'en a pas
 * besoin : ses cookies suffisent (voir [inoreaderCookieCandidates]).
 */
fun inoreaderAuthHeader(token: String): String = "GoogleLogin auth=$token"
