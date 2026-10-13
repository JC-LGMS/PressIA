package com.pressia.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Catégorie / dossier (Feedly category ou dossier local).
 *
 * [sortOrder] est l'ordre d'affichage choisi par l'utilisateur. Les comptes imposent leur propre
 * ordre (Feedly renvoie ses dossiers dans le sien) ; on ne l'écrit que pour les dossiers créés
 * dans PressIA, les autres restant ordonnés par libellé comme le compte les présente.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val label: String,
    val provider: String = "local",
    val sortOrder: Int = 0
)

/** Abonnement / flux. Même convention d'ordre que [CategoryEntity.sortOrder]. */
@Entity(
    tableName = "subscriptions",
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class, parentColumns = ["id"],
        childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("categoryId")]
)
data class SubscriptionEntity(
    @PrimaryKey val id: String, // feedId (feedly) ou URL (local)
    val title: String,
    val url: String = "",
    val siteUrl: String = "",
    val categoryId: String? = null,
    val iconUrl: String? = null,
    val unreadCount: Int = 0,
    val provider: String = "local",
    val sortOrder: Int = 0
)

/** Article mis en cache pour lecture hors-ligne. */
@Entity(
    tableName = "articles",
    indices = [Index("feedId"), Index("published"), Index("unread"), Index("starred"), Index("laterSaved"),
        // Requête des miniatures en attente : filtre sur imageCached + tri par published, en
        // couvrant (imageUrl incluse) pour ne jamais toucher la table — dont les colonnes
        // summary/content sont lourdes — même avec des milliers d'articles.
        Index(value = ["imageCached", "published", "imageUrl"])]
)
data class ArticleEntity(
    @PrimaryKey val id: String, // entryId cloud ou hash lien
    val feedId: String,
    val title: String,
    val author: String? = null,
    val url: String = "",
    val summary: String? = null,   // extrait RSS
    val content: String? = null,   // HTML complet (ou extrait via Readability)
    val imageUrl: String? = null,
    val published: Long = 0L,
    val unread: Boolean = true,
    val starred: Boolean = false,
    /** Rangé par le compte dans son tableau « Read later » (à distinguer d'une étoile PressIA). */
    val laterSaved: Boolean = false,
    val fullFetched: Boolean = false, // article complet extrait ?
    val imageCached: Boolean = false,
    val titleHash: String = "",  // pour déduplication
    val readAt: Long? = null
)

/** File de synchronisation bidirectionnelle : actions offline à pousser. */
@Entity(tableName = "sync_queue")
data class SyncActionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val articleId: String,
    val kind: String, // "read" | "unread" | "star" | "unstar" | "markAllRead"
    val feedId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val tries: Int = 0
)

/** Évènement de lecture pour les stats. */
@Entity(tableName = "reading_events")
data class ReadingEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val articleId: String,
    val feedId: String,
    val openedAt: Long = System.currentTimeMillis(),
    val durationSec: Int = 0,
    val completed: Boolean = false
)

/** Tentative d'extraction complète (Readability) : suivi du taux de réussite. */
@Entity(tableName = "extract_events", indices = [Index("createdAt")])
data class ExtractEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val articleId: String,
    val feedId: String,
    val ok: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface PressDao {
    // --- Catégories / subs ---
    /**
     * Les dossiers sont d'abord rangés par l'ordre choisi dans l'application, puis par libellé.
     * Le tri secondaire garde un affichage stable quand aucun ordre n'a été saisi : deux dossiers
     * importés d'un compte ne se réordonnent pas d'une synchronisation à l'autre.
     */
    @Query("SELECT * FROM categories ORDER BY sortOrder, label")
    suspend fun categories(): List<CategoryEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(c: List<CategoryEntity>)

    @Query("SELECT * FROM subscriptions ORDER BY sortOrder, title")
    suspend fun subscriptions(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions WHERE categoryId = :catId ORDER BY sortOrder, title")
    suspend fun subscriptionsForCategory(catId: String): List<SubscriptionEntity>
    @Query("SELECT * FROM subscriptions WHERE categoryId IN (:catIds) ORDER BY sortOrder, title")
    suspend fun subscriptionsForCategories(catIds: List<String>): List<SubscriptionEntity>

    /**
     * Réécrit l'ordre d'affichage des dossiers. Les identifiants sont rangés dans l'ordre voulu et
     * reçoivent leur rang : l'écriture se fait en une transaction pour qu'un ordre à moitié écrit
     * ne s'affiche jamais.
     */
    @Transaction
    suspend fun reorderCategories(ids: List<String>) {
        ids.forEachIndexed { i, id -> setCategoryOrder(id, i) }
    }

    @Query("UPDATE categories SET sortOrder = :order WHERE id = :id")
    suspend fun setCategoryOrder(id: String, order: Int)

    /** Idem pour les flux, tous dossiers confondus ou dans un seul d'entre eux. */
    @Transaction
    suspend fun reorderSubscriptions(ids: List<String>) {
        ids.forEachIndexed { i, id -> setSubscriptionOrder(id, i) }
    }

    @Query("UPDATE subscriptions SET sortOrder = :order WHERE id = :id")
    suspend fun setSubscriptionOrder(id: String, order: Int)

    // --- Dossiers (CRUD utilisateur) ---
    /** Identifiant d'un dossier local créé dans PressIA (jamaiscollision avec un id cloud). */
    @Query("SELECT COUNT(*) FROM categories WHERE id = :id")
    suspend fun categoryExists(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(c: CategoryEntity)
    @Query("UPDATE categories SET label = :label WHERE id = :id")
    suspend fun renameCategory(id: String, label: String)
    @Query("UPDATE subscriptions SET categoryId = :categoryId WHERE id IN (:feedIds)")
    suspend fun moveFeeds(feedIds: List<String>, categoryId: String?)
    /**
     * Supprime un dossier. Les flux qu'il contenait ne sont pas supprimés : ils repassent
     * simplement dans « Flux sans dossier ».
     */
    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: String)
    /**
     * Vide les dossiers d'un seul compte. Les dossiers d'un autre compte sont intacts :
     * c'est ce qui permet d'organiser ses flux RSS locaux même en utilisant Feedly ensuite.
     */
    @Query("DELETE FROM categories WHERE provider = :provider")
    suspend fun clearCategoriesOf(provider: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSubscriptions(s: List<SubscriptionEntity>)
    /** Ne retire que les flux d'un compte : les flux des autres comptes doivent survivre à sa synchronisation. */
    @Query("DELETE FROM subscriptions WHERE provider = :provider")
    suspend fun clearSubscriptionsOf(provider: String)
    /** Retire un seul flux (désabonnement d'un flux local). */
    @Query("DELETE FROM subscriptions WHERE id = :feedId")
    suspend fun deleteSubscription(feedId: String)
    /** Supprime un compte : ses dossiers, ses flux et les articles correspondants. */
    @Query("DELETE FROM articles WHERE feedId IN (SELECT id FROM subscriptions WHERE provider = :provider)")
    suspend fun clearArticlesOf(provider: String)

    // --- Articles ---
    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun article(id: String): ArticleEntity?
    /**
     * [favScope] sélectionne le périmètre des favoris : "" = aucun filtre, "starred" = les étoiles
     * PressIA, "later" = le tableau « Read later » du compte, "all" = les deux réunis.
     *
     * Les deux ensembles sont volontairement disjoints : une étoile PressIA est un état local, le
     * tableau « Read later » appartient au compte. Un article peut appartenir aux deux — il a pu
     * être Gauché sur l'appareil puis enregistré depuis un autre — d'où un filtre « tous ».
     */
    @Query("SELECT * FROM articles WHERE (:feedId IS NULL OR feedId = :feedId) AND (:catEmpty = 1 OR feedId IN (:catFeedIds)) AND (:onlyUnread = 0 OR unread = 1) AND (:favScope = '' OR (:favScope = 'starred' AND starred = 1) OR (:favScope = 'later' AND laterSaved = 1) OR (:favScope = 'all' AND (starred = 1 OR laterSaved = 1))) ORDER BY published DESC LIMIT :limit OFFSET :offset")
    suspend fun stream(feedId: String?, catFeedIds: List<String>, catEmpty: Int, onlyUnread: Int, favScope: String, limit: Int, offset: Int): List<ArticleEntity>
    /** Même requête, réactive : la liste visible se rafraîchit dès que la sync insère des articles. */
    @Query("SELECT * FROM articles WHERE (:feedId IS NULL OR feedId = :feedId) AND (:catEmpty = 1 OR feedId IN (:catFeedIds)) AND (:onlyUnread = 0 OR unread = 1) AND (:favScope = '' OR (:favScope = 'starred' AND starred = 1) OR (:favScope = 'later' AND laterSaved = 1) OR (:favScope = 'all' AND (starred = 1 OR laterSaved = 1))) ORDER BY published DESC LIMIT :limit OFFSET :offset")
    fun streamFlow(feedId: String?, catFeedIds: List<String>, catEmpty: Int, onlyUnread: Int, favScope: String, limit: Int, offset: Int): Flow<List<ArticleEntity>>
    /**
     * Variante « collante » : les articles passés en lus pendant la session restent listés
     * tant qu'on reste dans le même flux ([keepIds]), au lieu de disparaître sous le nez.
     */
    @Query("SELECT * FROM articles WHERE ((:feedId IS NULL OR feedId = :feedId) AND (:catEmpty = 1 OR feedId IN (:catFeedIds)) AND (:onlyUnread = 0 OR unread = 1) AND (:favScope = '' OR (:favScope = 'starred' AND starred = 1) OR (:favScope = 'later' AND laterSaved = 1) OR (:favScope = 'all' AND (starred = 1 OR laterSaved = 1)))) OR id IN (:keepIds) ORDER BY published DESC LIMIT :limit OFFSET :offset")
    fun streamFlowSticky(feedId: String?, catFeedIds: List<String>, catEmpty: Int, onlyUnread: Int, favScope: String, limit: Int, offset: Int, keepIds: List<String>): Flow<List<ArticleEntity>>
    @Query("SELECT COUNT(*) FROM articles WHERE (:feedId IS NULL OR feedId = :feedId) AND unread = 1")
    suspend fun unreadCount(feedId: String?): Int
    @Query("SELECT feedId AS feedId, COUNT(*) AS c FROM articles WHERE unread = 1 GROUP BY feedId")
    suspend fun unreadByFeed(): List<FeedUnread>
    @Query("SELECT title FROM subscriptions WHERE id = :feedId LIMIT 1")
    suspend fun feedTitle(feedId: String): String?
    @Query("SELECT * FROM subscriptions WHERE id = :feedId LIMIT 1")
    suspend fun subscription(feedId: String): SubscriptionEntity?
    @Query("DELETE FROM articles")
    suspend fun clearArticles()
    /** Vide les articles d'un flux désabonné. */
    @Query("DELETE FROM articles WHERE feedId = :feedId")
    suspend fun deleteArticlesOfFeed(feedId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArticles(a: List<ArticleEntity>)
    @Query("UPDATE articles SET unread = :unread, readAt = :readAt WHERE id = :id")
    suspend fun setRead(id: String, unread: Boolean, readAt: Long?)
    @Query("UPDATE articles SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)
    /**
     * Drapeaux locaux d'articles déjà connus, à relire avant une réinsertion.
     *
     * `upsertArticles` remplace la ligne entière : sans cette relecture, chaque synchronisation
     * effacerait les étoiles PressIA et le tableau « Read later », que le serveur ne connaît pas
     * en tant que tels.
     */
    @Query("SELECT id, starred, laterSaved FROM articles WHERE id IN (:ids)")
    suspend fun favFlagsOf(ids: List<String>): List<FavFlags>
    /** Oublie le « Read later » des flux d'un compte, avant d'y replonger le contenu à jour. */
    @Query("UPDATE articles SET laterSaved = 0 WHERE feedId IN (:feedIds)")
    suspend fun clearLater(feedIds: List<String>)
    /** Pose le marquage « Read later » (étoile optimiste, rattrapage de file). */
    @Query("UPDATE articles SET laterSaved = 1 WHERE id IN (:ids)")
    suspend fun markLaterSaved(ids: List<String>)
    /** Retire le marquage « Read later » (retrait optimiste, rattrapage de file). */
    @Query("UPDATE articles SET laterSaved = 0 WHERE id IN (:ids)")
    suspend fun unmarkLaterSaved(ids: List<String>)
    @Query("UPDATE articles SET unread = 0, readAt = :now WHERE feedId IN (:feedIds)")
    suspend fun markFeedsRead(feedIds: List<String>, now: Long)
    @Query("UPDATE articles SET unread = 0, readAt = :now")
    suspend fun markAllRead(now: Long)
    /**
     * Réconciliation du lu/non-lu distant : non-lus locaux des flux d'un compte, pour les
     * comparer aux non-lus du compte (lus ailleurs → à passer en lus).
     */
    @Query("SELECT a.id FROM articles a JOIN subscriptions s ON s.id = a.feedId WHERE s.provider = :provider AND a.unread = 1")
    suspend fun unreadIdsOfProvider(provider: String): List<String>
    /**
     * États locaux utiles à la réconciliation, en une seule passe : non-lus du compte, plus
     * lus locaux parmi des IDs distants (non lus ailleurs → à rouvrir). Remplace deux
     * balayages ([unreadIdsOfProvider] + [readIdsOfProviderAmong]) par un seul par paquet ;
     * [unreadIdsOfProvider] reste pour le cas dégénéré (aucun ID distant : `IN ()` invalide).
     */
    @Query("SELECT a.id, a.unread FROM articles a JOIN subscriptions s ON s.id = a.feedId WHERE s.provider = :provider AND (a.unread = 1 OR a.id IN (:ids))")
    suspend fun readStatesOfProvider(provider: String, ids: List<String>): List<ReconcileState>
    @Query("UPDATE articles SET unread = 0, readAt = :now WHERE id IN (:ids)")
    suspend fun markIdsRead(ids: List<String>, now: Long)
    @Query("UPDATE articles SET unread = 1, readAt = NULL WHERE id IN (:ids)")
    suspend fun markIdsUnread(ids: List<String>)
    @Query("UPDATE articles SET content = :html, fullFetched = 1 WHERE id = :id")
    suspend fun saveFullContent(id: String, html: String)
    /**
     * URLs des miniatures restant à stocker : les plus récentes d'abord (ordre d'affichage),
     * sans borne — la première synchronisation télécharge tout le stock, les suivantes ne
     * récupèrent que les nouveautés. Dédupliquées : une même image partagée par plusieurs
     * articles ne se télécharge qu'une fois.
     */
    @Query("SELECT DISTINCT imageUrl FROM articles WHERE imageUrl IS NOT NULL AND imageUrl != '' AND imageCached = 0 ORDER BY published DESC")
    suspend fun pendingThumbUrls(): List<String>
    /** Toutes les URLs d'images encore référencées : sert au nettoyage des fichiers orphelins. */
    @Query("SELECT DISTINCT imageUrl FROM articles WHERE imageUrl IS NOT NULL AND imageUrl != ''")
    suspend fun allThumbUrls(): List<String>
    /** Marque tous les articles partageant cette URL : le fichier local est unique par URL. */
    @Query("UPDATE articles SET imageCached = 1 WHERE imageUrl = :url")
    suspend fun markThumbCached(url: String)
    /** Variante groupée : une requête par paquet plutôt qu'une par URL. */
    @Query("UPDATE articles SET imageCached = 1 WHERE imageUrl IN (:urls)")
    suspend fun markThumbsCached(urls: List<String>)
    /** Cache vidé : tout est à récupérer à la prochaine synchronisation. */
    @Query("UPDATE articles SET imageCached = 0")
    suspend fun resetThumbsCached()
    /**
     * Suivi « miniature stockée » des articles déjà connus, à relire avant une réinsertion.
     * Comme les étoiles, `imageCached` serait écrasé par `upsertArticles` (REPLACE) à chaque
     * synchronisation, ce qui retéléchargerait toutes les images à chaque fois.
     */
    @Query("SELECT id, imageCached FROM articles WHERE id IN (:ids)")
    suspend fun thumbFlagsOf(ids: List<String>): List<ThumbFlags>
    /** Purge : les favoris et le « Read later » du compte sont toujours conservés. */
    @Query("DELETE FROM articles WHERE published < :olderThan AND starred = 0 AND laterSaved = 0")
    suspend fun prune(olderThan: Long): Int
    /**
     * Flux dépassant le quota d'articles conservés (hors favoris et « Read later ») : seuls
     * ceux-là justifient une passe de suppression. Une requête au lieu d'une par flux à
     * chaque synchronisation — même résultat que le balayage complet (quota baissé,
     * désétoilage et approvisionnement compris, tous sans nouveaux articles).
     */
    @Query("SELECT feedId FROM articles WHERE starred = 0 AND laterSaved = 0 GROUP BY feedId HAVING COUNT(*) > :keep")
    suspend fun overQuotaFeedIds(keep: Int): List<String>
    /**
     * Trop-plein d'un flux au-delà du quota : les plus anciens d'abord (favoris et
     * « Read later » déjà exclus). OFFSET seul exige LIMIT -1 sous SQLite.
     */
    @Query("SELECT id FROM articles WHERE feedId = :feed AND starred = 0 AND laterSaved = 0 ORDER BY published DESC, id DESC LIMIT -1 OFFSET :keep")
    suspend fun overflowArticleIds(feed: String, keep: Int): List<String>
    /** Suppression par paquets (limite de variables SQLite) du trop-plein par flux. */
    @Query("DELETE FROM articles WHERE id IN (:ids)")
    suspend fun deleteArticlesByIds(ids: List<String>): Int
    @Query("SELECT * FROM articles WHERE titleHash != '' GROUP BY titleHash HAVING SUM(CASE WHEN unread = 1 THEN 1 ELSE 0 END) > 0 ORDER BY published DESC LIMIT 200")
    suspend fun duplicateGroupsSeedUnread(): List<ArticleEntity>
    @Query("SELECT * FROM articles WHERE titleHash = :hash ORDER BY published DESC")
    suspend fun duplicatesOf(hash: String): List<ArticleEntity>

    // --- Recherche périmètre (FTS simplifié via LIKE, suffisant offline) ---
    @Query("SELECT * FROM articles WHERE (:feedId IS NULL OR feedId = :feedId) AND (:catEmpty = 1 OR feedId IN (:feedIds)) AND (title LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%') ORDER BY published DESC LIMIT 200")
    suspend fun search(q: String, feedId: String?, feedIds: List<String>, catEmpty: Int): List<ArticleEntity>

    // --- Sync queue ---
    @Insert
    suspend fun enqueue(a: SyncActionEntity)
    @Query("SELECT * FROM sync_queue ORDER BY createdAt LIMIT 100")
    suspend fun pendingSync(): List<SyncActionEntity>
    @Query("DELETE FROM sync_queue WHERE rowId = :rowId")
    suspend fun dequeue(rowId: Long)
    @Query("UPDATE sync_queue SET tries = tries + 1 WHERE rowId = :rowId")
    suspend fun bumpTry(rowId: Long)
    @Query("DELETE FROM sync_queue")
    suspend fun clearQueue()

    // --- Stats ---
    @Insert
    suspend fun logReading(e: ReadingEventEntity)
    /** Bornes des lectures enregistrées : [0] = plus ancien, [1] = plus récent. */
    @Query("SELECT IFNULL(MIN(openedAt), 0) FROM reading_events")
    suspend fun firstReadingAt(): Long
    /** Années (AAAA) ayant au moins une lecture, de la plus récente à la plus ancienne. */
    @Query("SELECT DISTINCT strftime('%Y', openedAt / 1000, 'unixepoch', 'localtime') AS y FROM reading_events ORDER BY y DESC")
    suspend fun readingYears(): List<String>
    @Query("SELECT COUNT(*) FROM reading_events WHERE openedAt BETWEEN :from AND :to")
    suspend fun readsBetween(from: Long, to: Long): Int
    /** Ouvertures agrégées par jour local (« 2026-10-01 »). */
    @Query("SELECT strftime('%Y-%m-%d', openedAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS c FROM reading_events WHERE openedAt BETWEEN :from AND :to GROUP BY day ORDER BY day")
    suspend fun readsPerLocalDay(from: Long, to: Long): List<LocalDayCount>
    /** Temps de lecture cumulé (secondes). */
    @Query("SELECT COALESCE(SUM(durationSec), 0) FROM reading_events WHERE openedAt BETWEEN :from AND :to")
    suspend fun secondsBetween(from: Long, to: Long): Int
    /** Temps de lecture par dossier (catégorie du flux). */
    @Query("SELECT s.categoryId AS categoryId, COALESCE(SUM(e.durationSec), 0) AS secs FROM reading_events e JOIN subscriptions s ON s.id = e.feedId WHERE e.openedAt BETWEEN :from AND :to AND s.categoryId IS NOT NULL GROUP BY s.categoryId ORDER BY secs DESC")
    suspend fun secondsByCategory(from: Long, to: Long): List<CategorySeconds>
    /** Temps de lecture par flux. */
    @Query("SELECT e.feedId AS feedId, COALESCE(SUM(e.durationSec), 0) AS secs, COUNT(*) AS c FROM reading_events e WHERE e.openedAt BETWEEN :from AND :to GROUP BY e.feedId ORDER BY secs DESC")
    suspend fun secondsByFeed(from: Long, to: Long): List<FeedSeconds>
    /** Articles passés en lu sans avoir été ouverts (marquage groupé, swipe, etc.). */
    @Query("SELECT COUNT(*) FROM articles a WHERE a.readAt IS NOT NULL AND a.readAt BETWEEN :from AND :to AND NOT EXISTS (SELECT 1 FROM reading_events e WHERE e.articleId = a.id)")
    suspend fun readWithoutOpeningBetween(from: Long, to: Long): Int

    // --- Extraction (Readability) ---
    @Insert
    suspend fun logExtract(e: ExtractEventEntity)
    @Query("SELECT ok, COUNT(*) AS c FROM extract_events WHERE createdAt BETWEEN :from AND :to GROUP BY ok")
    suspend fun extractStatsBetween(from: Long, to: Long): List<ExtractCount>

    // --- Sauvegarde / restauration ---
    @Query("SELECT * FROM reading_events")
    suspend fun allReadingEvents(): List<ReadingEventEntity>
    @Query("SELECT * FROM extract_events")
    suspend fun allExtractEvents(): List<ExtractEventEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadingEvents(e: List<ReadingEventEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExtractEvents(e: List<ExtractEventEntity>)
    @Query("DELETE FROM reading_events")
    suspend fun clearReadingEvents()
    @Query("DELETE FROM extract_events")
    suspend fun clearExtractEvents()
    /**
     * Clés naturelles des évènements déjà connus : une réimport du même fichier ne doit pas
     * dupliquer les stats (les lignes portent un identifiant auto-incrémenté, l'IGNORE seul
     * ne dédupliquerait jamais).
     */
    @Query("SELECT articleId, openedAt FROM reading_events")
    suspend fun readingKeys(): List<ReadingKey>
    @Query("SELECT articleId, createdAt FROM extract_events")
    suspend fun extractKeys(): List<ExtractKey>
    /**
     * Sauvegarde des favoris.
     *
     * L'étoile PressIA étant un état local pour **tous** les flux (Feedly et Inoreader compris,
     * dont le tableau du compte est un ensemble distinct), elle est sauvegardée intégralement.
     */
    @Query("SELECT a.id AS id, a.unread AS unread, a.starred AS starred, a.readAt AS readAt, a.feedId AS feedId FROM articles a WHERE a.starred = 1")
    suspend fun starredStates(): List<ReadStateRow>
    /**
     * États lus des flux saisis à la main, pour la sauvegarde : seuls les lus sont exportés
     * (le non-lu est l'état d'arrivée), et seuls les flux locaux (le lu/non-lu cloud appartient
     * au service, qui le réimpose à la prochaine réconciliation de toute façon).
     */
    @Query("SELECT a.id AS id, a.feedId AS feedId, a.readAt AS readAt, a.published AS published FROM articles a JOIN subscriptions s ON s.id = a.feedId WHERE s.provider = 'local' AND a.unread = 0")
    suspend fun localReadStates(): List<LocalReadRow>
    /** Rejoue les favoris restaurés d'une sauvegarde sur les articles tout juste synchronisés. */
    @Query("UPDATE articles SET starred = 1 WHERE id IN (:ids)")
    suspend fun markStarred(ids: List<String>)
    /** Quels de ces identifiants sont déjà présents en base (les autres arriveront plus tard). */
    @Query("SELECT id FROM articles WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>
}

data class FeedUnread(val feedId: String, val c: Int)data class LocalDayCount(val day: String, val c: Int)
data class CategorySeconds(val categoryId: String?, val secs: Int)
data class FeedSeconds(val feedId: String, val secs: Int, val c: Int)
data class ExtractCount(val ok: Boolean, val c: Int)
data class ReadStateRow(val id: String, val unread: Boolean, val starred: Boolean, val readAt: Long?, val feedId: String)
/** État lu d'un article local à sauvegarder (voir [PressDao.localReadStates]). */
data class LocalReadRow(val id: String, val feedId: String, val readAt: Long?, val published: Long)
/** Clé naturelle d'un évènement de lecture (voir [PressDao.readingKeys]). */
data class ReadingKey(val articleId: String, val openedAt: Long)
/** Clé naturelle d'une tentative d'extraction (voir [PressDao.extractKeys]). */
data class ExtractKey(val articleId: String, val createdAt: Long)

/** Drapeaux de favoris d'un article déjà en base (voir [PressDao.favFlagsOf]). */
data class FavFlags(val id: String, val starred: Boolean, val laterSaved: Boolean)

/** Suivi « miniature stockée » d'un article déjà en base (voir [PressDao.thumbFlagsOf]). */
data class ThumbFlags(val id: String, val imageCached: Boolean)

/** État lu d'un article pour la réconciliation distante (voir [PressDao.readStatesOfProvider]). */
data class ReconcileState(val id: String, val unread: Boolean)

@Database(
    entities = [CategoryEntity::class, SubscriptionEntity::class, ArticleEntity::class, SyncActionEntity::class, ReadingEventEntity::class, ExtractEventEntity::class],
    version = 5, exportSchema = false
)
abstract class PressDatabase : RoomDatabase() {
    abstract fun dao(): PressDao

    companion object {
        /** v1 → v2 : table de suivi des extractions Readability (aucune donnée existante perdue). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `extract_events` (" +
                        "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`articleId` TEXT NOT NULL, `feedId` TEXT NOT NULL, " +
                        "`ok` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_extract_events_createdAt` ON `extract_events` (`createdAt`)")
            }
        }

        /**
         * v2 → v3 : marquage « Read later » des comptes, distinct de l'étoile PressIA. Les articles
         * déjà enregistrés restent intacts, la colonne simplement vaut 0 par défaut.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `articles` ADD COLUMN `laterSaved` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_laterSaved` ON `articles` (`laterSaved`)")
            }
        }

        /**
         * v3 → v4 : ordre d'affichage des dossiers et des flux.
         *
         * Les colonnes valent 0 par défaut, ce qui reproduit exactement l'ordre antérieur (tri par
         * libellé puis par titre) : personne ne voit ses dossiers changer de place au mise à jour.
         * L'ordre saisi par l'utilisateur s'écrit ensuite par-dessus.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `categories` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5 : index couvrant pour les miniatures en attente (`imageCached`, `published`,
         * `imageUrl`). La requête ne lit plus que l'index — étroit — au lieu de balayer la table
         * et ses gros contenus HTML. Aucune donnée touchée.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_articles_imageCached_published_imageUrl` ON `articles` (`imageCached`, `published`, `imageUrl`)")
            }
        }
    }
}
