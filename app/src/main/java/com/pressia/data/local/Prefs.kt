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
        val TRAILING_ACTION = stringPreferencesKey("trailing_action") // "star"|"read"|"share"
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
        val IMAGES_OFFLINE = booleanPreferencesKey("images_offline")
        val SYNC_INTERVAL_MIN = intPreferencesKey("sync_interval_min") // 0 = manuel
        val SHOW_UNREAD_ONLY = booleanPreferencesKey("show_unread_only")

        /** Synchronisation automatique au lancement de l'application. */
        val SYNC_ON_LAUNCH = booleanPreferencesKey("sync_on_launch")
        /**
         * Instant (ms) de la dernière synchronisation complète réussie. Sert de garde-fou au
         * réglage précédent : sans lui, chaque ouverture de l'application relançait une
         * synchronisation, y compris quand on ne fait qu'y jeter un œil.
         */
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        /** Échelle des libellés du drawer, en pourcentage de la taille du thème (80..140). */
        val DRAWER_TEXT_SCALE = intPreferencesKey("drawer_text_scale")
        /** Masquer les flux sans aucun article non lu (drawer). */
        val HIDE_EMPTY_FEEDS = booleanPreferencesKey("hide_empty_feeds")
        /** Même réglage pour l'écran Accueil (indépendant du drawer). */
        val HIDE_EMPTY_SUBS = booleanPreferencesKey("hide_empty_subs")
        /** Identifiants de dossiers repliés, séparés par des virgules (état conservé). */
        val COLLAPSED_FOLDERS = stringPreferencesKey("collapsed_folders")
        /** Périmètre des favoris : "app" (étoiles PressIA) | "later" (Read later) | "all" (les deux). */
        val STARRED_SCOPE = stringPreferencesKey("starred_scope")

        val INOREADER_APP_ID = stringPreferencesKey("inoreader_app_id")
        val INOREADER_APP_KEY = stringPreferencesKey("inoreader_app_key")
        val FEEDLY_CLIENT_ID = stringPreferencesKey("feedly_client_id")
        val FEEDLY_CLIENT_SECRET = stringPreferencesKey("feedly_client_secret")

        /**
         * Identifiants d'articles mis en favori par une restauration de sauvegarde, rejoués à chaque
         * synchronisation. Indispensable : sur une installation vierge les articles n'existent pas encore
         * au moment de l'import, un simple UPDATE ne toucherait aucune ligne et les favoris seraient perdus.
         * Volontairement hors de [ENTRIES] : c'est un état local, pas un réglage à sauvegarder.
         */
        val RESTORED_STARRED = stringPreferencesKey("restored_starred")

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
            PrefEntry.Str("trailing_action", TRAILING_ACTION), PrefEntry.Str("home_screen", HOME_SCREEN),
            PrefEntry.Bool("justify", JUSTIFY), PrefEntry.Bool("wifi_only", WIFI_ONLY),
            PrefEntry.Str("open_mode", OPEN_MODE), PrefEntry.Bool("merge_folders", MERGE_FOLDERS),
            PrefEntry.IntPref("max_items", MAX_ITEMS), PrefEntry.Bool("images_offline", IMAGES_OFFLINE),
            PrefEntry.IntPref("sync_interval_min", SYNC_INTERVAL_MIN), PrefEntry.Bool("show_unread_only", SHOW_UNREAD_ONLY),
            PrefEntry.Bool("sync_on_launch", SYNC_ON_LAUNCH), PrefEntry.IntPref("drawer_text_scale", DRAWER_TEXT_SCALE),
            PrefEntry.Bool("hide_empty_feeds", HIDE_EMPTY_FEEDS), PrefEntry.Bool("hide_empty_subs", HIDE_EMPTY_SUBS),
            PrefEntry.Str("collapsed_folders", COLLAPSED_FOLDERS), PrefEntry.Str("starred_scope", STARRED_SCOPE),
            PrefEntry.Str("inoreader_app_id", INOREADER_APP_ID), PrefEntry.Str("inoreader_app_key", INOREADER_APP_KEY),
            PrefEntry.Str("feedly_client_id", FEEDLY_CLIENT_ID), PrefEntry.Str("feedly_client_secret", FEEDLY_CLIENT_SECRET)
        )
    }

    val data get() = ctx.ds.data
    suspend fun <T> set(key: Preferences.Key<T>, v: T) { ctx.ds.updateData { it.toMutablePreferences().apply { set(key, v) }.toPreferences() } }

    fun <T> flow(key: Preferences.Key<T>, def: T): Flow<T> = data.map { it[key] ?: def }

    /** Écrit une préférence à partir de son nom et d'une valeur JSON (booléen / entier / texte). */
    @Suppress("UNCHECKED_CAST")
    suspend fun setByName(name: String, value: Any) {
        when (val e = ENTRIES.firstOrNull { it.name == name }) {
            is PrefEntry.Bool -> set(e.key, value as Boolean)
            is PrefEntry.IntPref -> set(e.key, value as Int)
            is PrefEntry.Str -> set(e.key, value as String)
            else -> Unit
        }
    }
}