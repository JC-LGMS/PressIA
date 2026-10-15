package com.pressia.data

import org.json.JSONObject

/** Favicons via l'agrégateur (service Google) : défaut, sans visiter les sites. */
const val FAVICON_SOURCE_AGGREGATOR = "aggregator"
/**
 * Favicons demandés aux sites eux-mêmes : l'icône déclarée dans le HTML (`<link rel="icon">`,
 * sinon `/favicon.ico` en repli) — `/favicon.ico` seul ne suffit pas, beaucoup de sites
 * (Frandroid, …) n'en exposent pas et déclarent leur icône ailleurs.
 */
const val FAVICON_SOURCE_DIRECT = "direct"

/** Hôte du site d'un abonnement (sans `www.`), ou null si introuvable. */
fun siteHost(siteUrl: String?, url: String?, id: String?): String? =
    listOfNotNull(siteUrl, url, id).firstNotNullOfOrNull { raw ->
        runCatching {
            java.net.URI(raw.trim()).host?.removePrefix("www.")?.takeIf { it.contains(".") }
        }.getOrNull()
    }

/** Page d'accueil du site (pour y lire l'icône déclarée), ou null si introuvable. */
fun sitePageUrl(siteUrl: String?, url: String?, id: String?): String? {
    siteUrl?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
    return siteHost(siteUrl, url, id)?.let { "https://$it/" }
}

/**
 * URL du favicon d'un abonnement : icône fournie par le compte (Feedly/Inoreader), sinon
 * selon la source — agrégateur : favicon du site via son host (service Google) ;
 * direct : icône résolue du site ([resolved], lue dans son HTML — voir [parseSiteIcon]),
 * sinon icône du compte, sinon `/favicon.ico` en dernier repli — sinon null (l'UI affiche
 * alors l'initiale).
 *
 * Version pure (sans Compose) de ce que l'UI affiche : le pré-téléchargement immédiat
 * ([PressRepository.refreshFavicons]) et l'affichage paresseux partagent exactement
 * la même résolution d'URL.
 */
fun faviconUrlFor(iconUrl: String?, siteUrl: String?, url: String?, id: String?, direct: Boolean = false, resolved: String? = null): String? {
    val host = siteHost(siteUrl, url, id)
    if (!direct) {
        if (!iconUrl.isNullOrBlank()) return iconUrl
        return host?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" }
    }
    return directIconUrl(resolved, iconUrl, host)
}

/**
 * Ordre de la source directe : icône résolue du site (son HTML fait foi — le compte peut
 * fournir une icône périmée), sinon icône du compte, sinon `/favicon.ico` en dernier repli.
 * Pur et testé.
 */
fun directIconUrl(resolved: String?, account: String?, host: String?): String? =
    resolved?.takeIf { it.isNotBlank() }
        ?: account?.takeIf { it.isNotBlank() }
        ?: host?.let { "https://$it/favicon.ico" }

private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ATTR = Regex("(\\w[\\w:.-]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
private val SIZES = Regex("(\\d+)\\s*[x×]\\s*(\\d+)")

/**
 * Icône déclarée par une page HTML (`<link rel="icon" href="…">`), absolutisée contre
 * [pageUrl] — ou null si aucune. Préfère `rel="icon"` (dont `shortcut icon`) à
 * `apple-touch-icon`, et la plus grande taille déclarée ; ignore `mask-icon` (monochrome).
 * Pure et testée (dont un `<head>` réel de Frandroid).
 */
fun parseSiteIcon(html: String, pageUrl: String): String? {
    data class Cand(val href: String, val kind: Int, val area: Long)
    val cands = LINK_TAG.findAll(html).mapNotNull { m ->
        val attrs = ATTR.findAll(m.value).associate {
            val v = it.groupValues
            v[1].lowercase() to (v[3].takeIf { s -> s.isNotEmpty() } ?: v[4].takeIf { s -> s.isNotEmpty() } ?: v[5])
        }
        val rel = attrs["rel"]?.lowercase()?.split(Regex("\\s+")) ?: return@mapNotNull null
        if ("mask-icon" in rel) return@mapNotNull null
        val kind = when {
            "icon" in rel -> 2
            rel.any { it == "apple-touch-icon" || it.endsWith("-touch-icon") } -> 1
            else -> return@mapNotNull null
        }
        val href = attrs["href"]?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val area = attrs["sizes"]?.let { s ->
            SIZES.find(s)?.let { (it.groupValues[1].toLongOrNull() ?: 0) * (it.groupValues[2].toLongOrNull() ?: 0) }
        } ?: 0
        Cand(href, kind, area)
    }.toList()
    if (cands.isEmpty()) return null
    val best = cands.sortedWith(compareByDescending<Cand> { it.kind }.thenByDescending { it.area }).first()
    return runCatching { java.net.URI(pageUrl).resolve(best.href).toString() }.getOrNull()
}

/** Cache `feedId → icône résolue` (voir `Prefs.FAVICON_RESOLVED`) : aller-retour JSON. */
fun encodeResolved(icons: Map<String, String>): String {
    val o = JSONObject()
    icons.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) o.put(k, v) }
    return o.toString()
}

/** Décode [encodeResolved] ; registre corrompu = vide, jamais d'exception. */
fun parseResolved(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { o.optString(it).trim() }
            .filterValues { it.isNotBlank() }
    }.getOrDefault(emptyMap())
}
