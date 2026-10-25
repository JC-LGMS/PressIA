package com.pressia

import com.pressia.data.local.ArticleEntity
import com.pressia.data.local.ArticleFlags
import com.pressia.data.local.ReconcileState
import com.pressia.data.mergeIncomingArticle
import com.pressia.data.remote.FEEDLY_AUTO_READ_AGE_MS
import com.pressia.data.remote.autoReadAgeMs
import com.pressia.data.remote.autoReadCutoff
import com.pressia.data.remote.beyondAutoRead
import com.pressia.data.remote.closableUnread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lu/non-lu de Feedly au-delà de 31 jours : le service marque tout seul ces articles en lus.
 * L'état distant ne doit donc plus écraser l'état local ni fermer les non-lus — sinon chaque
 * synchronisation faisait disparaître de « non lus » des articles que l'utilisateur n'avait
 * jamais ouverts.
 */
class FeedlyAutoReadTest {

    private val day = 24L * 3600 * 1000
    private val now = 1_800_000_000_000L // date fixe : les tests ne dépendent pas de l'horloge
    private val cutoff = now - 31 * day

    @Test fun `feedly balaie les non lus apres 31 jours, les autres services non`() {
        assertEquals(31 * day, autoReadAgeMs("feedly"))
        assertEquals(FEEDLY_AUTO_READ_AGE_MS, autoReadAgeMs("feedly"))
        assertNull(autoReadAgeMs("inoreader"))
        assertNull(autoReadAgeMs("local"))
        assertEquals(cutoff, autoReadCutoff("feedly", now))
        assertNull(autoReadCutoff("inoreader", now))
    }

    @Test fun `un article au dela de la fenetre est reconnu comme ancien`() {
        // Publié il y a 40 jours (en ms) : hors fenêtre.
        assertTrue(beyondAutoRead(now - 40 * day, cutoff))
        // Publié il y a 10 jours : dans la fenêtre.
        assertFalse(beyondAutoRead(now - 10 * day, cutoff))
        // Juste à la limite : encore dans la fenêtre (l'ancienneté est stricte).
        assertFalse(beyondAutoRead(cutoff, cutoff))
        // Inoreader (pas de balayage) : jamais ancien.
        assertFalse(beyondAutoRead(now - 400 * day, null))
        // Date inconnue : on ne conclut pas à l'ancienneté.
        assertFalse(beyondAutoRead(0L, cutoff))
        // Date en secondes (Inoreader) : normalisée avant comparaison.
        assertTrue(beyondAutoRead((now - 40 * day) / 1000, cutoff))
    }

    @Test fun `resync feedly preserve l etat local d un vieil article`() {
        // Feedly renvoie l'article « lu » (balayage automatique) ; localement il est non lu.
        val incoming = article(unread = false, published = now - 40 * day)
        val prev = flags(unread = true, readAt = null)
        val kept = mergeIncomingArticle(incoming, prev, keepRead = false, keepReadBeforeMs = cutoff)
        assertTrue(kept.unread)
        assertNull(kept.readAt)
    }

    @Test fun `resync feedly preserve aussi un vieil article lu localement`() {
        // L'inverse compte autant : un article lu localement ne doit pas redevenir non lu.
        val incoming = article(unread = true, published = now - 40 * day)
        val prev = flags(unread = false, readAt = 1234L)
        val kept = mergeIncomingArticle(incoming, prev, keepRead = false, keepReadBeforeMs = cutoff)
        assertFalse(kept.unread)
        assertEquals(1234L, kept.readAt)
    }

    @Test fun `resync feedly applique l etat distant dans la fenetre`() {
        val incoming = article(unread = false, published = now - 10 * day)
        val kept = mergeIncomingArticle(incoming, flags(unread = true), keepRead = false, keepReadBeforeMs = cutoff)
        assertFalse(kept.unread)
    }

    @Test fun `un article inconnu garde l etat annonce par le serveur`() {
        // Rien de local à préserver : l'état du serveur est la seule information (un vieil
        // article Feedly arrive donc lu, comme chez Feedly).
        val incoming = article(unread = false, published = now - 40 * day)
        assertEquals(incoming, mergeIncomingArticle(incoming, null, keepRead = false, keepReadBeforeMs = cutoff))
    }

    @Test fun `la reconciliation ne ferme pas les non lus au dela de 31 jours`() {
        val states = listOf(
            ReconcileState("recent", unread = true, published = now - 5 * day),
            ReconcileState("ancien", unread = true, published = now - 45 * day),
            ReconcileState("sans-date", unread = true, published = 0L),
            ReconcileState("lu-local", unread = false, published = now - 60 * day)
        )
        // Feedly : seul le non-lu récent peut être fermé par le serveur.
        assertEquals(setOf("recent", "sans-date"), closableUnread(states, cutoff))
        // Inoreader (aucune fenêtre) : tout reste arbitrable par le serveur.
        assertEquals(setOf("recent", "ancien", "sans-date"), closableUnread(states, null))
    }

    private fun article(unread: Boolean, published: Long) = ArticleEntity(
        id = "a", feedId = "feed", title = "T", published = published, unread = unread
    )

    private fun flags(unread: Boolean, readAt: Long? = null) = ArticleFlags(
        "a", starred = false, laterSaved = false, imageCached = false, unread = unread, readAt = readAt
    )
}
