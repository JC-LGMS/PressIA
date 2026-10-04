package com.pressia.data

import com.pressia.data.local.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sauvegarde complète de PressIA dans un unique fichier JSON.
 *
 * L'utilisateur choisit ce qu'il veut inclure / restaurer :
 *  - [SECTION_PREFS]     toutes les options (thème, couleurs, polices, vues, hors-ligne…)
 *  - [SECTION_STATS]     les statistiques de lecture et d'extraction
 *  - [SECTION_ACCOUNTS]  les comptes / flux (abonnements + dossiers + fournisseur courant)
 *  - [SECTION_READ]      les favoris PressIA (étoiles locales, tous flux confondus)
 *
 * Les jetons de connexion (mots de passe d'API) ne sont jamais exportés par défaut :
 * il faut cocher [INCLUDE_TOKENS] explicitement.
 */
object Sections {
    const val PREFS = "prefs"
    const val STATS = "stats"
    const val ACCOUNTS = "accounts"
    const val READ = "read"
    val ALL = listOf(PREFS, STATS, ACCOUNTS, READ)
    const val INCLUDE_TOKENS = "tokens"
}

const val BACKUP_VERSION = 1

/**
 * Préférences tenant un secret (mots de passe d'API) : jamais exportées / restaurées
 * automatiquement, seulement si l'utilisateur coche explicitement les jetons.
 */
val SECRET_PREFS = setOf("token", "refresh", "accounts", "feedly_client_id", "feedly_client_secret", "inoreader_app_key")

/** Résultat d'un import, pour l'affichage utilisateur. */
data class ImportReport(
    val prefs: Int = 0,
    val stats: Int = 0,
    val accounts: Int = 0,
    val read: Int = 0
) {
    val total: Int get() = prefs + stats + accounts + read
}

/** Sérialise l'état demandé vers une chaîne JSON (pretty-print pour rester lisible à la main). */
suspend fun PressRepository.exportJson(sections: List<String>, includeTokens: Boolean = false): String = withContext(Dispatchers.IO) {
    val dao = db.dao()
    val root = JSONObject()
    root.put("app", "PressIA")
    root.put("version", BACKUP_VERSION)
    root.put("generatedAt", System.currentTimeMillis())
    root.put("sections", JSONArray(sections))
    if (includeTokens) root.put("tokens", true)

    if (Sections.PREFS in sections) {
        val o = JSONObject()
        prefs.data.first().asMap().forEach { (k, v) ->
            // Les secrets ne sortent que si l'utilisateur a coché « jetons ».
            if (includeTokens || k.name !in SECRET_PREFS) o.put(k.name, jsonValue(v))
        }
        root.put(Sections.PREFS, o)
    }
    if (Sections.ACCOUNTS in sections) {
        val o = JSONObject()
        o.put("provider", prefs.data.first()[Prefs.PROVIDER] ?: "local")
        if (includeTokens) {
            prefs.data.first()[Prefs.TOKEN]?.let { o.put("token", it) }
        }
        o.put("categories", JSONArray().apply {
            dao.categories().forEach { c ->
                put(JSONObject().put("id", c.id).put("label", c.label).put("provider", c.provider))
            }
        })
        o.put("subscriptions", JSONArray().apply {
            dao.subscriptions().forEach { s ->
                put(
                    JSONObject()
                        .put("id", s.id).put("title", s.title).put("url", s.url).put("siteUrl", s.siteUrl)
                        .put("categoryId", s.categoryId ?: JSONObject.NULL).put("iconUrl", s.iconUrl ?: JSONObject.NULL)
                        .put("provider", s.provider)
                )
            }
        })
        root.put(Sections.ACCOUNTS, o)
    }
    if (Sections.STATS in sections) {
        val o = JSONObject()
        o.put("reading", JSONArray().apply {
            dao.allReadingEvents().forEach { e ->
                put(
                    JSONObject()
                        .put("articleId", e.articleId).put("feedId", e.feedId)
                        .put("openedAt", e.openedAt).put("durationSec", e.durationSec).put("completed", e.completed)
                )
            }
        })
        o.put("extract", JSONArray().apply {
            dao.allExtractEvents().forEach { e ->
                put(
                    JSONObject()
                        .put("articleId", e.articleId).put("feedId", e.feedId)
                        .put("ok", e.ok).put("createdAt", e.createdAt)
                )
            }
        })
        root.put(Sections.STATS, o)
    }
    if (Sections.READ in sections) {
        root.put(Sections.READ, JSONArray().apply {
            dao.starredStates().forEach { r ->
                put(
                    JSONObject()
                        .put("id", r.id).put("unread", r.unread).put("starred", r.starred)
                        .put("readAt", r.readAt ?: JSONObject.NULL)
                )
            }
        })
    }
    root.toString(2)
}

/** Restaure les sections présentes dans [json] et présentes dans [sections]. [replaceStats] remplace les stats existantes. */
suspend fun PressRepository.importJson(
    json: String,
    sections: List<String>,
    replaceStats: Boolean = false
): ImportReport = withContext(Dispatchers.IO) {
    val dao = db.dao()
    val root = JSONObject(json)
    val hasTokens = root.optBoolean("tokens", false)
    var report = ImportReport()

    root.optJSONObject(Sections.PREFS)?.let { o ->
        if (Sections.PREFS in sections) {
            var n = 0
            val keys = o.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val v = o.get(key)
                when (v) {
                    // Sans « jetons », on n'écrase jamais un secret déjà présent sur l'appareil.
                    is Boolean -> { if (hasTokens || key !in SECRET_PREFS) { prefs.setByName(key, v); n++ } }
                    is Int -> { if (hasTokens || key !in SECRET_PREFS) { prefs.setByName(key, v); n++ } }
                    is String -> { if (hasTokens || key !in SECRET_PREFS) { prefs.setByName(key, v); n++ } }
                }
            }
            report = report.copy(prefs = n)
        }
    }

    root.optJSONObject(Sections.ACCOUNTS)?.let { o ->
        if (Sections.ACCOUNTS in sections) {
            val cats = o.optJSONArray("categories")?.toCategoryList().orEmpty()
            val subs = o.optJSONArray("subscriptions")?.toSubscriptionList().orEmpty()
            if (cats.isNotEmpty()) dao.upsertCategories(cats)
            if (subs.isNotEmpty()) dao.upsertSubscriptions(subs)
            o.optString("provider").takeIf { it.isNotBlank() }?.let { prefs.set(Prefs.PROVIDER, it) }
            if (hasTokens) o.optString("token").takeIf { it.isNotBlank() }?.let { prefs.set(Prefs.TOKEN, it) }
            report = report.copy(accounts = cats.size + subs.size)
        }
    }

    root.optJSONObject(Sections.STATS)?.let { o ->
        if (Sections.STATS in sections) {
            val reading = o.optJSONArray("reading")?.toReadingList().orEmpty()
            val extract = o.optJSONArray("extract")?.toExtractList().orEmpty()
            if (replaceStats) { dao.clearReadingEvents(); dao.clearExtractEvents() }
            if (reading.isNotEmpty()) dao.insertReadingEvents(reading)
            if (extract.isNotEmpty()) dao.insertExtractEvents(extract)
            report = report.copy(stats = reading.size + extract.size)
        }
    }

    root.optJSONArray(Sections.READ)?.let { arr ->
        if (Sections.READ in sections) {
            // La section ne contient plus que des favoris (flux RSS locaux) : on ne restaure
            // que l'étoile, l'état lu/non-lu des comptes cloud étant géré par le service lui-même.
            val starred = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                if (o.optBoolean("starred", false)) starred += id
            }
            if (starred.isNotEmpty()) dao.markStarred(starred.toList())
            // Les favoris importés sont mémorisés : sur une installation neuve les articles
            // arrivent plus tard, et l'UPDATE ci-dessus n'aurait porté sur aucune ligne.
            prefs.set(Prefs.RESTORED_STARRED, starred.joinToString("\n"))
            report = report.copy(read = starred.size)
        }
    }
    report
}

// ----- Conversions JSON -----

private fun jsonValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Boolean, is Int, is Long, is Float, is Double, is String -> v
    is Set<*> -> JSONArray(v.map { jsonValue(it) })
    else -> v.toString()
}

private fun JSONArray.toCategoryList(): List<CategoryEntity> = (0 until length()).mapNotNull { i ->
    optJSONObject(i)?.let {
        CategoryEntity(
            id = it.optString("id"),
            label = it.optString("label"),
            provider = it.optString("provider", "local")
        )
    }
}

private fun JSONArray.toSubscriptionList(): List<SubscriptionEntity> = (0 until length()).mapNotNull { i ->
    optJSONObject(i)?.let {
        SubscriptionEntity(
            id = it.optString("id"),
            title = it.optString("title"),
            url = it.optString("url"),
            siteUrl = it.optString("siteUrl"),
            categoryId = it.optString("categoryId").takeIf { s -> s.isNotBlank() && s != "null" },
            iconUrl = it.optString("iconUrl").takeIf { s -> s.isNotBlank() && s != "null" },
            provider = it.optString("provider", "local")
        )
    }
}

private fun JSONArray.toReadingList(): List<ReadingEventEntity> = (0 until length()).mapNotNull { i ->
    optJSONObject(i)?.let {
        ReadingEventEntity(
            articleId = it.optString("articleId"),
            feedId = it.optString("feedId"),
            openedAt = it.optLong("openedAt"),
            durationSec = it.optInt("durationSec"),
            completed = it.optBoolean("completed")
        )
    }
}

private fun JSONArray.toExtractList(): List<ExtractEventEntity> = (0 until length()).mapNotNull { i ->
    optJSONObject(i)?.let {
        ExtractEventEntity(
            articleId = it.optString("articleId"),
            feedId = it.optString("feedId"),
            ok = it.optBoolean("ok"),
            createdAt = it.optLong("createdAt")
        )
    }
}