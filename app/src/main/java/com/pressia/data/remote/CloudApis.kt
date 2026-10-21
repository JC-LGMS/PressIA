package com.pressia.data.remote

import com.pressia.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

// ---------- Feedly Cloud API (v3, niveau gratuit suffisant : categories/subscriptions/streams/markers) ----------
@Serializable data class FeedlyCategory(val id: String, val label: String)
@Serializable data class FeedlySubscription(val id: String, val title: String? = null, val website: String? = null, val categories: List<FeedlyCategory> = emptyList(), val iconUrl: String? = null)
@Serializable data class FeedlyEntryOrigin(val streamId: String? = null, val title: String? = null, val htmlUrl: String? = null)
@Serializable data class FeedlyContent(val content: String? = null, val direction: String? = null)
@Serializable data class FeedlyEntry(
    val id: String,
    val title: String? = null,
    val author: String? = null,
    val origin: FeedlyEntryOrigin? = null,
    val content: FeedlyContent? = null,
    val summary: FeedlyContent? = null,
    val alternate: List<FeedlyAlternate> = emptyList(),
    val visual: FeedlyVisual? = null,
    val published: Long = 0L,
    val unread: Boolean = true,
    val tags: List<FeedlyTag> = emptyList()
)
@Serializable data class FeedlyAlternate(val href: String? = null, val type: String? = null)
@Serializable data class FeedlyVisual(val url: String? = null)
@Serializable data class FeedlyTag(val id: String, val label: String? = null)
@Serializable data class FeedlyStream(val id: String, val items: List<FeedlyEntry> = emptyList(), val continuation: String? = null)
/** IDs seuls d'un flux : ~20x moins de données que le contenu, suffit à la réconciliation. */
@Serializable data class FeedlyIdList(val ids: List<String> = emptyList(), val continuation: String? = null)
/** Compteurs de non-lus : 1 appel léger, détecteur de changement pour la réconciliation. */
@Serializable data class FeedlyUnreadCount(val id: String = "", val count: Int = 0)
@Serializable data class FeedlyUnreadCounts(val unreadcounts: List<FeedlyUnreadCount> = emptyList())
@Serializable data class FeedlyMarkers(val action: String, val type: String, val entryIds: List<String>? = null, val feedIds: List<String>? = null, val categoryIds: List<String>? = null)
@Serializable data class FeedlyProfile(
    val id: String? = null,
    val userName: String? = null,
    val email: String? = null,
    @SerialName("login_name") val loginName: String? = null,
    val picture: String? = null
)

interface FeedlyService {
    @GET("categories")
    suspend fun categories(@Header("Authorization") h: String): List<FeedlyCategory>
    @GET("subscriptions")
    suspend fun subscriptions(@Header("Authorization") h: String): List<FeedlySubscription>
    /** Profil du compte connecté (nom / e-mail affichés dans l'écran Comptes). */
    @GET("profile")
    suspend fun profile(@Header("Authorization") h: String): FeedlyProfile
    /**
     * Flux d'articles. Feedly n'expose que la forme `streams/contents?streamId=…` : la variante
     * `streams/{id}/contents` répond 404 sur le niveau gratuit de l'API v3.
     */
    @GET("streams/contents")
    suspend fun stream(
        @Header("Authorization") h: String,
        @Query("streamId") streamId: String,
        @Query("count") count: Int = 100,
        @Query("ranked") ranked: String = "newest",
        @Query("unreadOnly") unreadOnly: Boolean = false,
        @Query("continuation") cont: String? = null,
        // Articles plus récents que ce timestamp (ms) : null = sans filtre.
        @Query("newerThan") newerThan: Long? = null
    ): FeedlyStream
    @GET("search/contents")
    suspend fun search(
        @Header("Authorization") h: String,
        @Query("streamId") streamId: String,
        @Query("query") q: String,
        @Query("count") c: Int = 50
    ): FeedlyStream
    /**
     * IDs seuls (`streams/ids?streamId=…`, forme query comme `contents`, compatible niveau
     * gratuit — avec repli sur `contents` en cas de refus, voir `unreadIds`).
     */
    @GET("streams/ids")
    suspend fun streamIds(
        @Header("Authorization") h: String,
        @Query("streamId") streamId: String,
        @Query("count") count: Int = 1000,
        @Query("unreadOnly") unreadOnly: Boolean = false,
        @Query("continuation") cont: String? = null
    ): FeedlyIdList
    /** Total des non-lus du compte, en un appel. */
    @GET("markers/counts")
    suspend fun unreadCounts(@Header("Authorization") h: String): FeedlyUnreadCounts
    @POST("markers")
    suspend fun markers(@Header("Authorization") h: String, @Body b: FeedlyMarkers)

    companion object {
        fun create(): FeedlyService {
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val client = OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                .build()
            return Retrofit.Builder().baseUrl("https://cloud.feedly.com/v3/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(FeedlyService::class.java)
        }
    }
}

// ---------- Inoreader API (compatible Google Reader : /reader/api/0/...) ----------
interface InoreaderService {
    @GET("reader/api/0/subscription/list")
    suspend fun subs(@Header("Authorization") h: String, @Query("output") o: String = "json"): InoreaderSubList
    @GET("reader/api/0/tag/list")
    suspend fun tags(@Header("Authorization") h: String, @Query("output") o: String = "json"): InoreaderTagList
    @GET("reader/api/0/stream/contents/{stream}")
    suspend fun stream(
        @Header("Authorization") h: String,
        @Path(value = "stream", encoded = true) stream: String,
        @Query("n") n: Int = 100,
        @Query("c") cont: String? = null,
        @Query("output") o: String = "json",
        /** État à exclure (ex. `user/-/state/com.google/read` = non-lus seuls). */
        @Query("xt") xt: String? = null
    ): InoreaderStream
    @FormUrlEncoded @POST("reader/api/0/edit-tag")
    suspend fun editTag(
        @Header("Authorization") h: String,
        @Field("i") itemId: String,
        @Field("a") add: String? = null,
        @Field("r") remove: String? = null,
        @Field("T") token: String
    ): String
    @GET("reader/api/0/token")
    suspend fun token(@Header("Authorization") h: String): String
    /** Profil du compte connecté. */
    @GET("reader/api/0/user-info")
    suspend fun userInfo(@Header("Authorization") h: String): InoreaderUserInfo
    /** Compteurs de non-lus : 1 appel léger, détecteur de changement pour la réconciliation. */
    @GET("reader/api/0/unread-count")
    suspend fun unreadCount(
        @Header("Authorization") h: String,
        @Query("output") o: String = "json",
        @Query("all") all: Boolean = true
    ): InoreaderUnreadCounts
    /**
     * IDs seuls d'un flux (`stream/items/ids`) : détecteur de changement pour le tableau
     * « Starred », pendant léger de `stream/contents` (mêmes paramètres de fenêtre).
     */
    @GET("reader/api/0/stream/items/ids")
    suspend fun itemsIds(
        @Header("Authorization") h: String,
        @Query("s") stream: String,
        @Query("n") n: Int = 200,
        @Query("c") cont: String? = null,
        @Query("output") o: String = "json"
    ): InoreaderIdList

    companion object {
        fun create(): InoreaderService {
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val client = OkHttpClient.Builder()
                // Authentification applicative Inoreader : exigée sur *chaque* appel, à côté
                // de l'authentification utilisateur (en-tête `Authorization`). Sans elle,
                // l'API répond 403 (« You are not sending the correct AppID and/or
                // AppSecret ») — c'est aussi ce que fait l'extension officielle du
                // navigateur, qui passe les mêmes valeurs en paramètre d'URL parce
                // qu'elle ne maîtrise pas les en-têtes.
                .addInterceptor { chain ->
                    val builder = chain.request().newBuilder()
                    val id = InoreaderAppAuth.appId.trim()
                    val key = InoreaderAppAuth.appKey.trim()
                    // En-têtes omis quand les identifiants manquent : une application sans
                    // identifiant enregistré ne peut de toute façon pas passer
                    // l'authentification applicative, et Inoreader répond 403 dans les
                    // deux cas — autant ne pas envoyer un en-tête vide.
                    if (id.isNotEmpty() && key.isNotEmpty()) {
                        builder.header("AppId", id)
                        builder.header("AppKey", key)
                    }
                    chain.proceed(builder.build())
                }
                .build()
            return Retrofit.Builder().baseUrl("https://www.inoreader.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(InoreaderService::class.java)
        }
    }
}

/**
 * Identifiants d'application Inoreader (`AppId` / `AppKey`), injectés en en-tête sur
 * chaque appel d'API.
 *
 * Ils viennent de `local.properties` (donc de `BuildConfig`) et peuvent être
 * redéfinis par l'utilisateur dans les Réglages : d'où un détenteur mis à jour à chaud
 * plutôt qu'un en-tête figé à la construction du client Retrofit.
 */
object InoreaderAppAuth {
    @Volatile var appId: String = BuildConfig.INOREADER_APP_ID
    @Volatile var appKey: String = BuildConfig.INOREADER_APP_KEY
}

@Serializable data class InoreaderSub(val id: String, val title: String, val url: String? = null, val htmlUrl: String? = null, val categories: List<InoreaderCat> = emptyList(), val iconUrl: String? = null)
@Serializable data class InoreaderCat(val id: String, val label: String)
@Serializable data class InoreaderSubList(val subscriptions: List<InoreaderSub> = emptyList())
@Serializable data class InoreaderUserInfo(val userName: String? = null, val userEmail: String? = null, val userId: String? = null)
@Serializable data class InoreaderTagList(val tags: List<InoreaderTag> = emptyList())
@Serializable data class InoreaderTag(val id: String, val label: String? = null)
@Serializable data class InoreaderItem(
    val id: String,
    val title: String? = null,
    val author: String? = null,
    val canonical: List<InoreaderLink> = emptyList(),
    val alternate: List<InoreaderLink> = emptyList(),
    val summary: InoreaderContent? = null,
    val content: InoreaderContent? = null,
    val published: Long = 0L,
    val origin: InoreaderOrigin? = null,
    val categories: List<String> = emptyList()
)
@Serializable data class InoreaderLink(val href: String? = null)
@Serializable data class InoreaderContent(val content: String? = null, val direction: String? = null)
@Serializable data class InoreaderOrigin(val streamId: String? = null, val title: String? = null, val htmlUrl: String? = null)
@Serializable data class InoreaderStream(val id: String? = null, val items: List<InoreaderItem> = emptyList(), val continuation: String? = null)
@Serializable data class InoreaderIdRef(val id: String)
@Serializable data class InoreaderIdList(val itemRefs: List<InoreaderIdRef> = emptyList(), val continuation: String? = null)
@Serializable data class InoreaderUnreadItem(val id: String = "", val count: Int = 0)
@Serializable data class InoreaderUnreadCounts(val unreadcounts: List<InoreaderUnreadItem> = emptyList())
