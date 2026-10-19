package com.pressia.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("pressia")

/** Entrée de préférence typée : permet la sauvegarde / restauration par nom. */
sealed class PrefEntry(val name: String) {
    class Bool(name: String, val key: Preferences.Key<Boolean>) : PrefEntry(name)
    class IntPref(name: String, val key: Preferences.Key<Int>) : PrefEntry(name)
    class LongPref(name: String, val key: Preferences.Key<Long>) : PrefEntry(name)
    class Str(name: String, val key: Preferences.Key<String>) : PrefEntry(name)
}

/** Réglages PressIA (miroir des prefs de Press + ajouts). */
class Prefs(private val ctx: Context) {
    companion object {
        val PROVIDER = stringPreferencesKey("provider") // "feedly" | "inoreader" | "local" (compte actif)
        val TOKEN = stringPreferencesKey("token")
        val REFRESH = stringPreferencesKey("refresh")
        val USER_ID = stringPreferencesKey("user_id")

        /**
         * Comptes externes connectés, sérialisés en JSON ([AccountStore.encode]). Plusieurs comptes
         * du même service sont acceptés : chacun a son propre jeton, ses propres dossiers et ses
         * propres abonnements. [ACTIVE_ACCOUNT] désigne celui qui sert aux opérations courantes.
         */
        val ACCOUNTS = stringPreferencesKey("accounts")
        val ACTIVE_ACCOUNT = stringPreferencesKey("active_account")
        val THEME = stringPreferencesKey("theme") // "system"|"light"|"dark"|"amoled" (legacy, remplacé par APPEARANCE/NIGHT_THEME)
        /** Apparence choisie dans le drawer : "auto" | "light" (jour) | "night" (nuit). */
        val APPEARANCE = stringPreferencesKey("appearance")
        /** Thème appliqué quand l'apparence est « nuit » : "dark" (sombre) | "amoled". */
        val NIGHT_THEME = stringPreferencesKey("night_theme")
        val ACCENT = stringPreferencesKey("accent") // "red"|"blue"|"green"|"violet"|"orange"|"pink"|"teal"
        val BG = stringPreferencesKey("background") // "default"|"white"|"black"|"sepia"|"grey"|"night"
        val VIEW_MODE = stringPreferencesKey("view_mode") // "list"|"card"|"magazine"|"compact"
        val FONT_SIZE = intPreferencesKey("font_size") // corps du texte article, sp 12..24
        val TITLE_FONT_SIZE = intPreferencesKey("title_font_size") // titres de liste, sp 12..24
        val ARTICLE_TITLE_SIZE = intPreferencesKey("article_title_size") // titre écran article, sp 12..28
        val DATE_FONT_SIZE = intPreferencesKey("date_font_size") // dates de liste, sp 10..16
        val TRAILING_ACTION = stringPreferencesKey("trailing_action") // "star"|"read"|"share"|"instapaper"|"none"
        /**
         * Icônes d'action de l'écran article, dans l'ordre d'affichage : identifiants séparés par
         * des virgules parmi "star"|"read"|"share"|"instapaper". Défaut : favoris, lu/non-lu, partage.
         */
        val ARTICLE_ACTIONS = stringPreferencesKey("article_actions")        /** Glissement d'un article vers la gauche : "star"|"read"|"share"|"instapaper"|"none". */
        val SWIPE_ACTION = stringPreferencesKey("swipe_action")
        /** Glissement d'un article vers la droite : mêmes options, défaut "read". */
        val SWIPE_RIGHT_ACTION = stringPreferencesKey("swipe_right_action")
        val HOME_SCREEN = stringPreferencesKey("home_screen") // "stream"|"subs"
        val JUSTIFY = booleanPreferencesKey("justify")
        /** Ouverture d'un article : "internal" (vue dans PressIA) | "external" (navigateur). */
        val OPEN_MODE = stringPreferencesKey("open_mode")
        /**
         * Dossiers fusionnés entre comptes : vrai = un seul dossier par nom commun à tous les
         * comptes, faux = un dossier par compte (le nom du compte est ajouté).
         */
        val MERGE_FOLDERS = booleanPreferencesKey("merge_folders")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val MAX_ITEMS = intPreferencesKey("max_items") // taille max cache par flux
        /**
         * Mois calendaires d'historique remontés à la connexion d'un compte (0 = sans limite
         * de temps). Le quota par flux ([MAX_ITEMS]) reste un garde-fou dans tous les cas.
         */
        val HISTORY_MONTHS = intPreferencesKey("history_months")
        val IMAGES_OFFLINE = booleanPreferencesKey("images_offline")
        val SYNC_INTERVAL_MIN = intPreferencesKey("sync_interval_min") // 0 = manuel
        /** Fréquence de synchronisation par défaut, en minutes (fenêtre anti-doublon au lancement). */
        const val SYNC_INTERVAL_DEFAULT = 10
        /** Historique remonté par défaut, en mois calendaires (voir [HISTORY_MONTHS]) :
         * 1 mois, aligné sur la fenêtre au-delà de laquelle Feedly passe en lu (~31 j). */
        const val HISTORY_MONTHS_DEFAULT = 1
        /** Double-tap par défaut dans un article : Readability (voir [DOUBLE_TAP_ACTION]). */
        const val DOUBLE_TAP_DEFAULT = "readability"
        /** Balayage gauche par défaut dans un article : article plus ancien. */
        const val ARTICLE_SWIPE_LEFT_DEFAULT = "older"
        /** Balayage droit par défaut dans un article : article plus récent. */
        const val ARTICLE_SWIPE_RIGHT_DEFAULT = "newer"
        /**
         * Source des favicons : agrégateur (service Google, défaut) ou sites eux-mêmes
         * (`/favicon.ico`, en direct). L'icône fournie par le compte reste prioritaire
         * dans les deux cas.
         */
        val FAVICON_SOURCE = stringPreferencesKey("favicon_source")
        /** « Non-lus seuls » : défaut pour les périmètres jamais réglés (voir [UNREAD_ONLY_BY_FEED]). */
        val SHOW_UNREAD_ONLY = booleanPreferencesKey("show_unread_only")
        /**
         * Choix d'œil posés par périmètre (JSON `{clé: bool}`) : chaque flux rouvre avec son
         * dernier choix, flux (`feed:<id>`), dossier (`cat:<id>`) ou global (`all`). Absent =
         * [SHOW_UNREAD_ONLY]. Une entrée par périmètre visité, chaînes minuscules : pas de
         * migration à écrire, un JSON abîmé repart simplement du défaut.
         */
        val UNREAD_ONLY_BY_FEED = stringPreferencesKey("unread_only_by_feed")

        /** Synchronisation automatique au lancement de l'application. */
        val SYNC_ON_LAUNCH = booleanPreferencesKey("sync_on_launch")
        /**
         * Instant (ms) de la dernière synchronisation complète réussie. Sert de garde-fou au
         * réglage précédent : sans lui, chaque ouverture de l'application relançait une
         * synchronisation, y compris quand on ne fait qu'y jeter un œil.
         */
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        /**
         * Instant (ms) de la dernière sauvegarde exportée dans un fichier. Affiché sur l'écran
         * « Sauvegarde/Restauration », 0 = aucune. Posé à chaque export réussi, jamais à
         * l'import (restaurer n'est pas sauvegarder) — sauf restauration d'une sauvegarde qui
         * le contenait, comme [LAST_SYNC_AT].
         */
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_META = stringPreferencesKey("last_backup_meta")
        /**
         * Borne basse des statistiques de lecture (ms, 0 = depuis toujours). Posée par
         * « Remettre les statistiques à zéro » (voir `PressRepository.resetStats`) : tout ce
         * qui précède est ignoré, y compris les compteurs dérivés des articles. Les traces
         * elles-mêmes ne disparaissent plus avec un flux supprimé — sans quoi supprimer un
         * compte remettait les statistiques à zéro.
         */
        val STATS_SINCE = longPreferencesKey("stats_since")
        /** Échelle des libellés du drawer, en pourcentage de la taille du thème (80..140). */
        val DRAWER_TEXT_SCALE = intPreferencesKey("drawer_text_scale")
        /** Échelle des libellés dossier / flux de l'écran Accueil, en pourcentage (70..140). */
        val HOME_TEXT_SCALE = intPreferencesKey("home_text_scale")
        /** Masquer les flux sans aucun article non lu sur l'écran Accueil. */
        val HIDE_EMPTY_SUBS = booleanPreferencesKey("hide_empty_subs")
        /** Identifiants de dossiers repliés, séparés par des virgules (état conservé). */
        val COLLAPSED_FOLDERS = stringPreferencesKey("collapsed_folders")
        /** Périmètre des favoris : "app" (étoiles PressIA) | "later" (Read later) | "all" (les deux). */
        val STARRED_SCOPE = stringPreferencesKey("starred_scope")
        val TAP_FAVICON_READ = booleanPreferencesKey("tap_favicon_read")
        /**
         * Readability persistant : si vrai, le mode « article complet » épinglé par son icône
         * survit au retour à l'accueil et ne se coupe que manuellement. Faux (défaut) : revenir
         * à l'accueil désépingle, chaque article s'ouvre ensuite en extrait RSS.
         */
        val READABILITY_STICKY = booleanPreferencesKey("readability_sticky")
        /**
         * Double-tap sur un article ouvert : "readability" (défaut) | "star" | "read" |
         * "share" | "instapaper" | "none".
         */
        val DOUBLE_TAP_ACTION = stringPreferencesKey("double_tap_action")
        /** Balayage gauche dans un article : "older" (article plus ancien, défaut) | action | "none". */
        val ARTICLE_SWIPE_LEFT = stringPreferencesKey("article_swipe_left")
        /** Balayage droit dans un article : "newer" (article plus récent, défaut) | action | "none". */
        val ARTICLE_SWIPE_RIGHT = stringPreferencesKey("article_swipe_right")

        val INOREADER_APP_ID = stringPreferencesKey("inoreader_app_id")
        val INOREADER_APP_KEY = stringPreferencesKey("inoreader_app_key")
        val FEEDLY_CLIENT_ID = stringPreferencesKey("feedly_client_id")
        val FEEDLY_CLIENT_SECRET = stringPreferencesKey("feedly_client_secret")
        /**
         * Derniers totaux de non-lus par compte (JSON `{id: total}`), pour sauter la
         * réconciliation quand rien n'a changé. Cache dérivé, volontairement hors de
         * [ENTRIES] : une restauration refait une réconciliation complète, sans danger.
         */
        val RECONCILE_TOTALS = stringPreferencesKey("reconcile_totals")
        /**
         * Empreintes des tableaux « Read later » par compte (JSON `{id: sha256}`), pour sauter
         * l'import quand rien n'a changé. Cache dérivé comme ci-dessus, hors sauvegarde.
         */
        val READLATER_HASHES = stringPreferencesKey("readlater_hashes")
        /** Identifiant Instapaper (Simple API : le mot de passe est renvoyé à chaque appel). */
        val INSTAPAPER_USER = stringPreferencesKey("instapaper_user")
        /**
         * Mot de passe Instapaper, secret : exporté seulement si l'utilisateur coche les jetons,
         * et restauré seulement dans ce cas. Même niveau de protection que les jetons stockés en
         * base (lisible sur l'appareil), mais au moins il ne voyage pas dans un fichier JSON
         * sauf demande explicite.
         */
        val INSTAPAPER_PASS = stringPreferencesKey("instapaper_pass")
        /**
         * Envoi vers Instapaper : "direct" (API simple, sans dossier ni tag) ou "choose"
         * (dialogue dossier + tags à chaque envoi, via la session web du compte).
         */
        val INSTAPAPER_MODE = stringPreferencesKey("instapaper_mode")
        /**
         * Comptes à la session expirée (virgules, voir `encodeExpired`) : jeton révoqué côté
         * Feedly/Inoreader (401/403), identifiants Instapaper refusés. Posé à la détection,
         * retiré dès qu'un appel authentifié du compte réussit ou qu'il est reconnecté /
         * supprimé — jamais périmé, et auto-réparé par la prochaine synchronisation.
         */
        val EXPIRED_ACCOUNTS = stringPreferencesKey("expired_accounts")
        /**
         * Session web Instapaper active (dossiers + tags disponibles) : les cookies vivent dans la
         * WebView, ce drapeau ne fait que mémoriser le dernier état vérifié. À la moindre
         * expiration (401/403), une reconnexion silencieuse est tentée avec les identifiants
         * simples stockés, sinon l'utilisateur est renvoyé vers l'écran Comptes.
         */
        val INSTAPAPER_WEB = booleanPreferencesKey("instapaper_web")

        /**
         * Identifiants d'articles mis en favori par une restauration de sauvegarde, rejoués à chaque
         * synchronisation. Indispensable : sur une installation vierge les articles n'existent pas encore
         * au moment de l'import, un simple UPDATE ne toucherait aucune ligne et les favoris seraient perdus.
         * Volontairement hors de [ENTRIES], et explicitement exclue de l'export : c'est un état
         * local d'attente, pas un réglage. La restaurer dans une sauvegarde n'aurait aucun sens.
         */
        val RESTORED_STARRED = stringPreferencesKey("restored_starred")

        /**
         * Identifiants d'articles lus des flux locaux, rejoués comme les favoris restaurés :
         * le lu/non-lu local n'existe nulle part ailleurs (ni serveur, ni export de contenu),
         * donc sans cette file une restauration rouvrirait tout. Lignes `feedId|articleId|readAt`,
         * le flux étant revérifié au rejeu (jamais de marquage vers un compte cloud).
         * Même statut que [RESTORED_STARRED] : hors [ENTRIES], jamais exporté.
         */
        val RESTORED_READ = stringPreferencesKey("restored_read")

        /**
         * Icônes de sites résolues (`feedId → URL lue dans le HTML`, voir `parseSiteIcon`) :
         * cache local de la source « en direct », re-résolu à chaque actualisation manuelle
         * des favicons. Même statut que [RESTORED_STARRED] : hors [ENTRIES], jamais exporté
         * (rouvrir un export sur un autre appareil re-résout, les icônes pouvant changer).
         */
        val FAVICON_RESOLVED = stringPreferencesKey("favicon_resolved")

        /** Toutes les préférences, typées : sauvegarde / restauration par nom. */
        val ENTRIES: List<PrefEntry> = listOf(
            PrefEntry.Str("provider", PROVIDER), PrefEntry.Str("token", TOKEN),
            PrefEntry.Str("refresh", REFRESH), PrefEntry.Str("user_id", USER_ID),
            PrefEntry.Str("accounts", ACCOUNTS), PrefEntry.Str("active_account", ACTIVE_ACCOUNT),
            PrefEntry.Str("theme", THEME), PrefEntry.Str("appearance", APPEARANCE),
            PrefEntry.Str("night_theme", NIGHT_THEME),
            PrefEntry.Str("accent", ACCENT),
            PrefEntry.Str("background", BG), PrefEntry.Str("view_mode", VIEW_MODE),
            PrefEntry.IntPref("font_size", FONT_SIZE), PrefEntry.IntPref("title_font_size", TITLE_FONT_SIZE),
            PrefEntry.IntPref("article_title_size", ARTICLE_TITLE_SIZE), PrefEntry.IntPref("date_font_size", DATE_FONT_SIZE),
            PrefEntry.Str("trailing_action", TRAILING_ACTION), PrefEntry.Str("article_actions", ARTICLE_ACTIONS), PrefEntry.Str("swipe_action", SWIPE_ACTION),
            PrefEntry.Str("swipe_right_action", SWIPE_RIGHT_ACTION),
            PrefEntry.Str("home_screen", HOME_SCREEN),
            PrefEntry.Bool("justify", JUSTIFY), PrefEntry.Bool("wifi_only", WIFI_ONLY),
            PrefEntry.Str("open_mode", OPEN_MODE), PrefEntry.Bool("merge_folders", MERGE_FOLDERS),
            PrefEntry.IntPref("max_items", MAX_ITEMS), PrefEntry.Bool("images_offline", IMAGES_OFFLINE),
            PrefEntry.IntPref("history_months", HISTORY_MONTHS),
            PrefEntry.Str("favicon_source", FAVICON_SOURCE),
            PrefEntry.IntPref("sync_interval_min", SYNC_INTERVAL_MIN), PrefEntry.Bool("show_unread_only", SHOW_UNREAD_ONLY),
            PrefEntry.Str("unread_only_by_feed", UNREAD_ONLY_BY_FEED),
            PrefEntry.Bool("sync_on_launch", SYNC_ON_LAUNCH), PrefEntry.LongPref("last_sync_at", LAST_SYNC_AT),
            PrefEntry.LongPref("last_backup_at", LAST_BACKUP_AT),
            PrefEntry.Str("last_backup_meta", LAST_BACKUP_META),
            PrefEntry.LongPref("stats_since", STATS_SINCE),
            PrefEntry.IntPref("drawer_text_scale", DRAWER_TEXT_SCALE),
            PrefEntry.IntPref("home_text_scale", HOME_TEXT_SCALE),
            PrefEntry.Bool("hide_empty_subs", HIDE_EMPTY_SUBS),
            PrefEntry.Str("collapsed_folders", COLLAPSED_FOLDERS), PrefEntry.Str("starred_scope", STARRED_SCOPE),
            PrefEntry.Bool("tap_favicon_read", TAP_FAVICON_READ),
            PrefEntry.Bool("readability_sticky", READABILITY_STICKY),
            PrefEntry.Str("double_tap_action", DOUBLE_TAP_ACTION),
            PrefEntry.Str("article_swipe_left", ARTICLE_SWIPE_LEFT),
            PrefEntry.Str("article_swipe_right", ARTICLE_SWIPE_RIGHT),
            PrefEntry.Str("inoreader_app_id", INOREADER_APP_ID), PrefEntry.Str("inoreader_app_key", INOREADER_APP_KEY),
            PrefEntry.Str("feedly_client_id", FEEDLY_CLIENT_ID), PrefEntry.Str("feedly_client_secret", FEEDLY_CLIENT_SECRET),
            PrefEntry.Str("instapaper_user", INSTAPAPER_USER), PrefEntry.Str("instapaper_pass", INSTAPAPER_PASS),
            PrefEntry.Str("instapaper_mode", INSTAPAPER_MODE), PrefEntry.Bool("instapaper_web", INSTAPAPER_WEB),
            PrefEntry.Str("expired_accounts", EXPIRED_ACCOUNTS)
        )
    }

    val data get() = ctx.ds.data
    suspend fun <T> set(key: Preferences.Key<T>, v: T) { ctx.ds.updateData { it.toMutablePreferences().apply { set(key, v) }.toPreferences() } }

    fun <T> flow(key: Preferences.Key<T>, def: T): Flow<T> = data.map { it[key] ?: def }

    /**
     * Écrit une préférence à partir de son nom et d'une valeur JSON, et renvoie `true` si
     * l'écriture a eu lieu.
     *
     * Un nom inconnu de [ENTRIES] ou un type incompatible renvoie `false` au lieu de lever :
     * une sauvegarde est un fichier extérieur, il ne doit jamais faire tomber la restauration
     * entière à cause d'une entrée aberrante. Les entiers sont acceptés pour une clé
     * [PrefEntry.LongPref] et réciproquement, l'ordre de grandeur n'ayant pas d'importance pour
     * un compteur.
     */
    suspend fun setByName(name: String, value: Any): Boolean {
        val e = ENTRIES.firstOrNull { it.name == name } ?: return false
        val write = when (e) {
            is PrefEntry.Bool -> (value as? Boolean)?.let { set(e.key, it) }
            is PrefEntry.IntPref -> (value as? Number)?.toInt()?.let { set(e.key, it) }
            is PrefEntry.LongPref -> (value as? Number)?.toLong()?.let { set(e.key, it) }
            is PrefEntry.Str -> (value as? String)?.let { set(e.key, it) }
        }
        return write != null
    }
}