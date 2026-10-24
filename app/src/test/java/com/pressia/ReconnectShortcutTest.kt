package com.pressia

import com.pressia.data.CloudAccount
import com.pressia.ui.expiredReconnectRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Raccourci « Reconnecter » du bandeau : la destination dépend du service du compte expiré.
 * Feedly et Inoreader se reconnectent dans leur page intégrée ; Instapaper (pseudo-compte
 * sans `CloudAccount`) et un identifiant inconnu renvoient vers l'écran Comptes.
 */
class ReconnectShortcutTest {

    private val feedly = CloudAccount("feedly#aaaa", "feedly", "jeton-feedly")
    private val inoreader = CloudAccount("inoreader#bbbb", "inoreader", "jeton-inoreader")
    private val accounts = listOf(feedly, inoreader)

    @Test fun `aucun compte expire, aucun raccourci`() {
        assertNull(expiredReconnectRoute(emptySet(), accounts))
    }

    @Test fun `feedly expire ouvre sa page de connexion integree`() {
        assertEquals("feedly-signin", expiredReconnectRoute(setOf(feedly.id), accounts))
    }

    @Test fun `inoreader expire ouvre sa page de connexion integree`() {
        assertEquals("inoreader-signin", expiredReconnectRoute(setOf(inoreader.id), accounts))
    }

    @Test fun `le premier compte expire du registre decide`() {
        // Les deux sont expirés : le registre commence par Feedly.
        assertEquals(
            "feedly-signin",
            expiredReconnectRoute(setOf(inoreader.id, feedly.id), accounts)
        )
    }

    @Test fun `instapaper et identifiant inconnu renvoient vers Comptes`() {
        assertEquals("login", expiredReconnectRoute(setOf("instapaper"), accounts))
        assertEquals("login", expiredReconnectRoute(setOf("feedly#inconnu"), accounts))
    }
}
