package com.pressia.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Lit [stream] en entier, ou rend `null` dès que [cap] octets sont dépassés.
 *
 * Plafond commun de tout corps téléchargé : miniatures (`ThumbStore`), flux RSS locaux
 * (`LocalRssFetcher`) et pages d'articles (`Readability`). Sans lui, un serveur qui répond une
 * archive, un flux sans fin ou une page énorme gonfle la mémoire jusqu'à l'`OutOfMemoryError` ;
 * un dépassement est traité comme un échec de récupération.
 *
 * Pur JVM : testé en unitaire.
 */
fun readCapped(stream: InputStream, cap: Int): ByteArray? {
    if (cap <= 0) return null
    val out = ByteArrayOutputStream(minOf(cap, 256 * 1024))
    val buf = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        total += n
        if (total > cap) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * Le plafond est-il déjà dépassé d'après la taille **annoncée** (`Content-Length`) ?
 *
 * Permet d'échouer avant même de lire quand le serveur déclare une archive de 200 Mo ; une
 * longueur inconnue (`-1`) ne dit rien, et c'est [readCapped] qui tranche pendant la lecture.
 * Pur JVM : testé en unitaire.
 */
fun exceedsCap(contentLength: Long, cap: Int): Boolean = contentLength > cap
