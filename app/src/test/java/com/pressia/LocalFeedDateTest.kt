package com.pressia

import com.pressia.data.remote.LocalRssFetcher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Dates d'un flux RSS local : un format non reconnu laisse `published = 0`, et l'article
 * repasse alors pour une nouveauté à chaque synchronisation.
 */
class LocalFeedDateTest {

    private fun publishedOf(dateTag: String): Long {
        val xml = "<rss><channel><title>T</title><item>" +
            "<title>A</title><link>https://exemple.fr/a</link>" +
            "<pubDate>$dateTag</pubDate></item></channel></rss>"
        return LocalRssFetcher.parse(xml, "feed").single().published
    }

    private fun epoch(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test fun `pubDate RFC 822 avec decalage horaire`() {
        assertEquals(
            epoch("2026-11-21T16:19:38Z"),
            publishedOf("Tue, 21 Nov 2026 16:19:38 +0000")
        )
    }

    @Test fun `pubDate RFC 822 avec fuseau GMT`() {
        assertEquals(
            epoch("2026-11-21T16:19:38Z"),
            publishedOf("Sat, 21 Nov 2026 16:19:38 GMT")
        )
    }

    @Test fun `date seule`() {
        assertEquals(epoch("2026-11-21T00:00:00Z"), publishedOf("2026-11-21"))
    }

    @Test fun `date ISO`() {
        assertEquals(epoch("2026-11-21T16:19:38Z"), publishedOf("2026-11-21T16:19:38Z"))
    }

    @Test fun `declaration xml respectee sans charset http`() {
        // Beaucoup de serveurs annoncent `text/xml` sans charset : la déclaration fait foi.
        val xml = """<?xml version="1.0" encoding="ISO-8859-1"?>
            <rss><channel><title>T</title><item><title>Café</title>
            <link>https://exemple.fr/a</link></item></channel></rss>"""
        val bytes = xml.toByteArray(java.nio.charset.Charset.forName("ISO-8859-1"))
        val decoded = LocalRssFetcher.decodeFeed(bytes, headerCharset = null)
        assertEquals("Café", LocalRssFetcher.parse(decoded, "feed").single().title)
    }
}
