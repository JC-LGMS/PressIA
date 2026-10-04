package com.pressia.data.remote

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
        @Query("continuation") cont: String? = null
    ): FeedlyStream
    @GET("search/contents")
    suspend fun search(
        @Header("Authorization") h: String,
        @Query("streamId") streamId: String,
        @Query("query") q: String,
        @Query("count") c: Int = 50
    ): FeedlyStream
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
        @Query("output") o: String = "json"
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

    companion object {
        fun create(): InoreaderService {
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val client = OkHttpClient.Builder().build()
            return Retrofit.Builder().baseUrl("https://www.inoreader.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build().create(InoreaderService::class.java)
        }
    }
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
