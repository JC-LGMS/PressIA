package com.pressia.data

/** Favicons via l'agrégateur (service Google) : défaut, sans visiter les sites. */
const val FAVICON_SOURCE_AGGREGATOR = "aggregator"
/** Favicons demandés aux sites eux-mêmes (`/favicon.ico`), en direct. */
const val FAVICON_SOURCE_DIRECT = "direct"

/**
 * URL du favicon d'un abonnement : icône fournie par le compte (Feedly/Inoreader), sinon
 * selon la source — agrégateur : favicon du site via son host (service Google) ;
 * direct : `/favicon.ico` du site lui-même — sinon null (l'UI affiche alors l'initiale).
 *
 * Version pure (sans Compose) de ce que l'UI affiche : le pré-téléchargement immédiat
 * ([PressRepository.refreshFavicons]) et l'affichage paresseux partagent exactement
 * la même résolution d'URL.
 */
fun faviconUrlFor(iconUrl: String?, siteUrl: String?, url: String?, id: String?, direct: Boolean = false): String? {
    if (!iconUrl.isNullOrBlank()) return iconUrl
    val host = listOfNotNull(siteUrl, url, id).firstNotNullOfOrNull { raw ->
        runCatching {
            java.net.URI(raw.trim()).host?.removePrefix("www.")?.takeIf { it.contains(".") }
        }.getOrNull()
    } ?: return null
    return if (direct) "https://$host/favicon.ico"
    else "https://www.google.com/s2/favicons?domain=$host&sz=128"
}
