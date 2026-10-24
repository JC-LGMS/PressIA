package com.pressia.ui

import com.pressia.data.CloudAccount

/**
 * Message final du bandeau quand un compte a une session morte (401/403).
 *
 * Le bandeau y joint le raccourci « Reconnecter » : c'est la même phrase qui sert de signal
 * entre le modèle ([com.pressia.ui.PressVM]) et l'écran ([com.pressia.MainActivity]), plutôt
 * qu'une comparaison de sous-chaîne.
 */
const val EXPIRED_SESSION_MSG = "Session expirée : reconnectez le compte"

/**
 * Écran où amène le raccourci « Reconnecter » du bandeau, pour un compte à la session expirée.
 *
 * Feedly et Inoreader se reconnectent dans leur **page de connexion intégrée** (même mécanisme
 * que la première connexion : l'utilisateur se connecte sur le site, le jeton ou le cookie de
 * session est repris automatiquement). Instapaper, dont les identifiants se saisissent à la
 * main, et tout compte inconnu renvoient vers Comptes : c'est le seul écran qui sait les
 * reconnecter.
 *
 * Le choix suit l'ordre du registre des comptes : le premier compte expiré décide de la
 * destination. `null` quand rien n'est expiré (pas de raccourci à proposer).
 */
fun expiredReconnectRoute(expired: Set<String>, accounts: List<CloudAccount>): String? {
    if (expired.isEmpty()) return null
    val account = accounts.firstOrNull { it.id in expired }
    return when (account?.service) {
        "feedly" -> "feedly-signin"
        "inoreader" -> "inoreader-signin"
        else -> "login"
    }
}
