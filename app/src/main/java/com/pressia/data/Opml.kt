package com.pressia.data

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * OPML : le format d'échange des listes d'abonnements RSS (fichiers `.opml` que produisent et
 * lisent Feedly, Inoreader, FreshRSS, NetNewsWire…). Un `outline` avec `xmlUrl` est un flux ;
 * les autres sont des dossiers qui contiennent les suivants.
 *
 * Tout ici est pur (Jsoup seul) : testé en JVM.
 */

/** Plafond de lecture d'un fichier OPML : une liste d'abonnements pèse quelques kilo-octets. */
const val MAX_OPML_BYTES = 2 * 1024 * 1024

/** Un flux d'un fichier OPML : adresse, nom affiché (facultatif) et dossier le plus proche. */
data class OpmlFeed(val url: String, val title: String?, val folder: String?)

/**
 * Flux d'un fichier OPML, dans l'ordre du fichier. Seul le dossier **immédiat** est retenu :
 * un dossier dans un dossier devient un simple dossier (PressIA n'a qu'un niveau).
 * Un fichier sans flux (ou illisible) rend une liste vide — l'appelant le dit.
 */
fun parseOpml(xml: String): List<OpmlFeed> {
    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
    return doc.select("outline").mapNotNull { el ->
        // `xmlUrl` est l'orthographe standard, `xmlurl` se rencontre dans des exports anciens.
        val url = el.attr("xmlUrl").ifBlank { el.attr("xmlurl") }.trim()
        if (url.isEmpty()) return@mapNotNull null
        val name = el.attr("text").ifBlank { el.attr("title") }.trim()
        val folder = el.parents().firstOrNull { it.tagName() == "outline" }
            ?.let { p -> p.attr("text").ifBlank { p.attr("title") }.trim() }
            ?.takeIf { it.isNotEmpty() }
        OpmlFeed(url, name.takeIf { it.isNotEmpty() }, folder)
    }
}

/**
 * Fichier OPML des flux donnés, groupés par dossier (`folder` null = à la racine). Les valeurs
 * sont échappées : un titre contient régulièrement `&`, et un attribut mal échappé rend le
 * fichier illisible pour le lecteur d'en face.
 */
fun buildOpml(feeds: List<OpmlFeed>, title: String = "PressIA"): String {
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<opml version=\"2.0\">\n  <head>\n    <title>")
        .append(escapeXml(title)).append("</title>\n  </head>\n  <body>\n")
    fun feed(f: OpmlFeed, pad: String) {
        val name = escapeXml(f.title?.trim()?.ifEmpty { null } ?: f.url)
        sb.append(pad).append("<outline type=\"rss\" text=\"").append(name)
            .append("\" title=\"").append(name)
            .append("\" xmlUrl=\"").append(escapeXml(f.url)).append("\"/>\n")
    }
    feeds.groupBy { it.folder?.trim()?.ifEmpty { null } }.forEach { (folder, items) ->
        if (folder == null) {
            items.forEach { feed(it, "    ") }
        } else {
            sb.append("    <outline text=\"").append(escapeXml(folder))
                .append("\" title=\"").append(escapeXml(folder)).append("\">\n")
            items.forEach { feed(it, "      ") }
            sb.append("    </outline>\n")
        }
    }
    sb.append("  </body>\n</opml>\n")
    return sb.toString()
}

/** Échappement XML minimal, `&` d'abord (sinon les autres entités seraient ré-échappées). */
fun escapeXml(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")
