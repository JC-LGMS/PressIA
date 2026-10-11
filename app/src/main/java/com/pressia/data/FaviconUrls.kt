package com.pressia.data

/**
 * URL du favicon d'un abonnement : icône fournie par le compte (Feedly/Inoreader), sinon
 * favicon du site via son host (service Google), sinon null (l'UI affiche alors l'initiale).
 *
 * Version pure (sans Compose) de ce que l'UI affiche : le pré-téléchargement immédiat
 * ([PressRepository.refreshFavicons]) et l'affichage paresseux partagent exactement
 * la même résolution d'URL.
 */
fun faviconUrlFor(iconUrl: String?, siteUrl: String?, url: String?, id: String?): String? {
    if (!iconUrl.isNullOrBlank()) return iconUrl
    val host = listOfNotNull(siteUrl, url, id).firstNotNullOfOrNull { raw ->
        runCatching {
            java.net.URI(raw.trim()).host?.removePrefix("www.")?.takeIf { it.contains(".") }
        }.getOrNull()
    } ?: return null
    return "https://www.google.com/s2/favicons?domain=$host&sz=128"
}
