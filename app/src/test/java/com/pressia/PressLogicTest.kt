package com.pressia

import com.pressia.data.favScopeArg
import com.pressia.data.isSyncFresh
import com.pressia.ui.parseArticleActions
import com.pressia.data.local.PrefEntry
import com.pressia.data.local.Prefs
import com.pressia.data.local.ThumbStore
import com.pressia.data.remote.OAuth
import com.pressia.data.remote.feedlyGlobalStream
import com.pressia.data.remote.feedlyMarkAllMarkers
import com.pressia.data.remote.reconcileUnchanged
import com.pressia.data.remote.dedupHash
import com.pressia.data.remote.LocalRssFetcher
import com.pressia.data.remote.isSupportedFeedUrl
import com.pressia.data.remote.sanitizeFeedUrl
import com.pressia.data.mergeIncomingArticle
import com.pressia.data.remote.INOREADER_COOKIE_PREFIX
import com.pressia.data.remote.InoreaderAuth
import com.pressia.data.remote.inoreaderAuth
import com.pressia.data.remote.inoreaderAuthHeader
import com.pressia.data.remote.inoreaderCookieCandidates
import com.pressia.data.remote.inoreaderCookieHasSession
import com.pressia.data.readability.Readability
import com.pressia.ui.theme.toHex
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class PressLogicTest {
    @Test fun `readCapped lit en entier sous le plafond`() {
        val data = ByteArray(1000) { it.toByte() }
        val out = ThumbStore.readCapped(java.io.ByteArrayInputStream(data), 10 * 1024)
        assertNotNull(out)
        assertArrayEquals(data, out)
    }
    @Test fun `readCapped refuse au-dela du plafond`() {
        val data = ByteArray(3000) { it.toByte() }
        assertNull(ThumbStore.readCapped(java.io.ByteArrayInputStream(data), 1000))
    }
    @Test fun `reconcile sautee si rien n'a bouge`() {
        assertTrue(reconcileUnchanged(added = 0, hasPending = false, lastTotal = 42, currentTotal = 42))
    }
    @Test fun `reconcile rejouee au moindre changement`() {
        assertFalse(reconcileUnchanged(added = 1, hasPending = false, lastTotal = 42, currentTotal = 42))
        assertFalse(reconcileUnchanged(added = 0, hasPending = true, lastTotal = 42, currentTotal = 42))
        assertFalse(reconcileUnchanged(added = 0, hasPending = false, lastTotal = 42, currentTotal = 41))
        assertFalse(reconcileUnchanged(added = 0, hasPending = false, lastTotal = null, currentTotal = 42))
        assertFalse(reconcileUnchanged(added = 0, hasPending = false, lastTotal = 42, currentTotal = null))
    }
    @Test fun `lancement saute si synchro recente`() {
        // Fenêtre de 10 min : 5 min après, frais ; 15 min après, à refaire.
        assertTrue(isSyncFresh(lastMs = 1000L, nowMs = 1000L + 5 * 60_000L, windowMin = 10))
        assertFalse(isSyncFresh(lastMs = 1000L, nowMs = 1000L + 15 * 60_000L, windowMin = 10))
        // Jamais synchronisé : à faire. Fréquence manuelle (0) : toujours à faire.
        assertFalse(isSyncFresh(lastMs = 0L, nowMs = 1000L, windowMin = 10))
        assertFalse(isSyncFresh(lastMs = 1000L, nowMs = 1000L + 60_000L, windowMin = 0))
    }
    @Test fun `jeu favoris app mappe sur starred`() {
        assertEquals("starred", favScopeArg("app"))
        assertEquals("starred", favScopeArg("starred"))
        assertEquals("later", favScopeArg("later"))
        assertEquals("all", favScopeArg("all"))
        assertEquals("", favScopeArg(""))
        assertEquals("", favScopeArg("nimporte-quoi"))
    }
    @Test fun `icones article gardees en ordre sans doublons ni inconnus`() {
        assertEquals(listOf("star", "read", "share"), parseArticleActions("star,read,share"))
        assertEquals(listOf("share", "star"), parseArticleActions("share,star,share"))
        assertEquals(emptyList<String>(), parseArticleActions(""))
        assertEquals(listOf("read"), parseArticleActions("bazar,read"))
    }
    @Test fun `dedup hash identique pour même titre`() {
        val h1 = dedupHash("Hello World!", "https://ex.com/a?x=1")
        val h2 = dedupHash("hello  world ", "https://ex.com/a?y=2")
        assertEquals(h1.substringBefore(":"), h2.substringBefore(":"))
    }
    @Test fun `doublons groupes par titre, plus recent en tete`() {
        fun art(id: String, hash: String, published: Long) =
            com.pressia.data.local.ArticleEntity(id = id, feedId = "f", title = id, published = published, titleHash = hash)
        // Déjà triés du plus récent au plus ancien, comme la requête le rend.
        val groups = com.pressia.data.remote.groupDuplicates(
            listOf(
                art("a1", "T", 300), art("a2", "T", 200), art("b1", "U", 150),
                art("a3", "T", 100), art("c1", "V", 50)
            )
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("a1", "a2", "a3"), groups[0].map { it.id })
        // Un titre non normalisé (hash vide) ne forme jamais de groupe, même répété.
        assertEquals(
            0,
            com.pressia.data.remote.groupDuplicates(listOf(art("x", "", 2), art("y", "", 1))).size
        )
    }
    @Test fun `rss parse extrait titre lien image`() {
        val xml = """<rss><channel><item><title>T1</title><link>https://ex.com/1</link><description><![CDATA[hello <img src="https://ex.com/i.jpg"/>]]></description></item></channel></rss>"""
        val arts = LocalRssFetcher.parse(xml, "feed1")
        assertEquals(1, arts.size)
        assertEquals("T1", arts[0].title)
        assertEquals("https://ex.com/i.jpg", arts[0].imageUrl)
    }
    @Test fun `url de flux nettoyee des caracteres de controle`() {
        // Un NUL collé au clavier (invisible à l'écran) devenait `%00` dans la requête :
        // le serveur répondait 404 et le flux restait vide, sans message d'erreur.
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UC25t",
            sanitizeFeedUrl("https://www.youtube.com/feeds/videos.xml?channel_id=UC25t\u0000")
        )
        assertEquals("https://ex.com/flux.xml", sanitizeFeedUrl("  ex.com/flux.xml  "))
        assertEquals("http://10.0.2.2:8899/g1.xml", sanitizeFeedUrl("http://10.0.2.2:8899/g1.xml"))
        assertEquals("", sanitizeFeedUrl("\u0000  "))
        assertTrue(isSupportedFeedUrl(sanitizeFeedUrl("ex.com/flux.xml")))
        assertFalse(isSupportedFeedUrl("ftp://ex.com/flux.xml"))
    }
    @Test fun `atom youtube - miniature et description dans media group`() {
        // YouTube niche ses médias dans `media:group` : le parcours des enfants directs ne les
        // voyait pas — la miniature et le résumé de chaque vidéo restaient vides.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
 <title>Chaine</title>
 <entry>
  <id>yt:video:abc</id>
  <title>Une video</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=abc"/>
  <author><name>Chaine</name></author>
  <published>2026-10-21T16:19:38+00:00</published>
  <updated>2026-10-21T18:06:20+00:00</updated>
  <media:group>
   <media:content url="https://www.youtube.com/v/abc?version=3" type="application/x-shockwave-flash" width="640" height="390"/>
   <media:thumbnail url="https://i4.ytimg.com/vi/abc/hqdefault.jpg" width="480" height="360"/>
   <media:description>Le resume de la video</media:description>
  </media:group>
 </entry>
</feed>"""
        val arts = LocalRssFetcher.parse(xml, "yt")
        assertEquals(1, arts.size)
        assertEquals("Une video", arts[0].title)
        assertEquals("https://www.youtube.com/watch?v=abc", arts[0].url)
        // Le lecteur Flash de `media:content` n'est pas une image : c'est la vignette qui gagne.
        assertEquals("https://i4.ytimg.com/vi/abc/hqdefault.jpg", arts[0].imageUrl)
        assertEquals("Le resume de la video", arts[0].summary)
        // La date de publication prime sur la date de modification.
        assertEquals(java.time.Instant.parse("2026-10-21T16:19:38Z").toEpochMilli(), arts[0].published)
    }
    @Test fun `titre du flux youtube - nom de la chaine`() {
        // Le titre de la racine est le nom de la chaîne : c'est lui qui doit être affiché à la
        // place de l'URL (« youtube.com »), seule étiquette possible d'un flux saisi à la main.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
 <title>Talks with a Spy</title>
 <entry><id>yt:video:abc</id><title>Une video</title>
  <link rel="alternate" href="https://www.youtube.com/watch?v=abc"/></entry>
</feed>"""
        val parsed = LocalRssFetcher.parseFeed(xml, "yt")
        assertEquals("Talks with a Spy", parsed.title)
        assertEquals(1, parsed.articles.size)
        // Le titre d'une entrée ne se confond pas avec celui du flux.
        assertEquals("Une video", parsed.articles[0].title)
    }
    @Test fun `titre du flux rss classique - nom du site`() {
        val xml = """<rss><channel><title>Le Comptoir du Hardware</title>
            <item><title>T1</title><link>https://ex.com/1</link></item></channel></rss>"""
        assertEquals("Le Comptoir du Hardware", LocalRssFetcher.parseFeed(xml, "f1").title)
    }
    @Test fun `la vignette connue est conservee quand le flux ne la renvoie plus`() {
        // Feedly ne renvoie son champ « visual » que sur les entrées récentes : une entrée rejouée
        // sans image effaçait celle déjà affichée, et la vignette disparaissait de la liste à
        // chaque synchronisation.
        val stored = com.pressia.data.local.ArticleEntity(
            id = "a1", feedId = "f1", title = "T1", imageUrl = "https://ex.com/i.jpg")
        val prev = com.pressia.data.local.ArticleFlags(
            id = "a1", starred = false, laterSaved = false, imageCached = true,
            imageUrl = "https://ex.com/i.jpg")
        val sansImage = stored.copy(imageUrl = null)
        assertEquals("https://ex.com/i.jpg", mergeIncomingArticle(sansImage, prev, false).imageUrl)
        // Une image nouvelle remplace l'ancienne.
        val nouvelle = stored.copy(imageUrl = "https://ex.com/j.jpg")
        assertEquals("https://ex.com/j.jpg", mergeIncomingArticle(nouvelle, prev, false).imageUrl)
        // Sans état antérieur, l'article entrant fait foi.
        assertNull(mergeIncomingArticle(sansImage, null, false).imageUrl)
    }
    @Test fun `readability garde le contenu principal`() {
        val html = "<html><body><nav>menu</nav><article><p>un</p><p>deux trois quatre cinq six sept huit neuf dix onze douze</p><p>encore du texte long pour scorer</p></article></body></html>"
        val out = Readability.extract(html, "https://ex.com")
        assertTrue(out.contains("deux"))
    }
    @Test fun `readability prefere le corps d'article au formulaire newsletter (cas Frandroid)`() {
        // `article-content--has-sidebar` contient « sidebar » : le filtre anti-bruit ne doit pas
        // l'écarter, sinon c'est le formulaire newsletter (texte « consentement ») qui gagne et
        // l'extraction est classée « mur de consentement » puis bloquée.
        val body = (1..6).joinToString("") { "<p>Paragraphe $it du vrai article Frandroid avec du contenu journalistique détaillé et informatif.</p>" }
        val html = """<html><body><article class="container">
            <div class="columns"><div class="column container-with-sidebar">
            <div class="article-content article-content--has-sidebar">$body</div>
            <div class="mb-5 mt-4-mobile mt-4"><p>Votre newsletter personnalisée</p><p>Les données transmises par le biais de ce formulaire sont destinées à Humanoid afin de vous informer de nos actualités. Nous utilisons des cookies pour la prospection commerciale et vous pouvez retirer votre consentement à tout moment. Pour plus d'informations, lisez notre politique de protection des données.</p></div>
            </div></div></article></body></html>"""
        val out = Readability.extract(html, "https://www.frandroid.com/")
        assertTrue("le vrai corps doit être extrait", out.contains("vrai article Frandroid"))
        assertFalse("la newsletter ne doit pas être extraite", out.contains("newsletter personnalisée"))
        assertFalse(Readability.isConsentWallText(org.jsoup.Jsoup.parse(out).text()))
    }
    @Test fun `page anti-robot detectee comme bloquee`() {
        val html = "<html><body><div><p>A required part of this site couldn't load. " +
            "This may be due to a browser extension, network issues, or browser settings.</p></div></body></html>"
        assertTrue(Readability.isBlockedPage(html))
        // Apostrophe typographique (cas réel Le Monde)
        val html2 = html.replace("couldn't", "couldn’t")
        assertTrue(Readability.isBlockedPage(html2))
        assertFalse(Readability.isUsableFull("<div><p>$html</p></div>"))
    }
    @Test fun `vrai article non detecte comme bloque`() {
        val paras = (1..30).joinToString("") { "<p>Paragraphe $it avec du contenu journalistique assez long pour simuler un article réel et dépasser le seuil de détection.</p>" }
        val html = "<html><body><article><h1>Titre</h1>$paras</article></body></html>"
        assertFalse(Readability.isBlockedPage(html))
    }
    @Test fun `boilerplate paywall detecte`() {
        val paywall = "La suite est réservée à nos abonnés. Votre carte bancaire a expiré. " +
            "La suite est réservée à nos abonnés. Déjà abonné ? Se connecter. " +
            "Envie de lire la suite ? Abonnez-vous pour profiter en intégralité de tous nos articles. " +
            "Abonnez-vous pour profiter en intégralité. " +
            "Texte de remplissage supplémentaire pour dépasser trois cents caractères sans ajouter de vrai contenu journalistique. ".repeat(3)
        assertTrue(Readability.isPaywalledText(paywall))
        assertFalse(Readability.isUsableFull("<div>$paywall</div>"))
    }
    @Test fun `vrai article utilisable comme complet`() {
        val paras = (1..10).joinToString("") { "<p>Paragraphe $it avec du vrai contenu journalistique détaillé et informatif sur l'actualité du jour.</p>" }
        assertTrue(Readability.isUsableFull("<div>$paras</div>"))
        assertFalse(Readability.isPaywalledText("Court extrait RSS sans appel à l'abonnement."))
    }
    @Test fun `mur de consentement cookies detecte`() {
        val consent = "Les données transmises par le biais du présent formulaire sont destinées à Humanoid " +
            "afin de vous informer de nos actualités. Ce contenu est bloqué car vous n'avez pas accepté les cookies et autres traceurs. " +
            "Vous gardez la possibilité de retirer votre consentement à tout moment. " +
            "Nous utilisons des cookies pour la prospection commerciale et la gestion de la relation client. " +
            "Pour plus d'informations, nous vous invitons à lire notre politique de protection des données. ".repeat(2)
        assertTrue(Readability.isConsentWallText(consent))
        assertFalse(Readability.isUsableFull("<div>$consent</div>"))
    }
    @Test fun `urls oauth bien formees`() {
        val f = OAuth.feedlyAuthUrl("CID", "pressia://oauth/feedly")
        assertTrue(f.contains("feedly.com/v3/auth/auth") && f.contains("client_id=CID"))
        val i = OAuth.inoreaderAuthUrl("APP", "pressia://oauth/inoreader")
        assertTrue(i.contains("inoreader.com/oauth2/auth") && i.contains("client_id=APP"))
    }
    @Test fun `parse redirect oauth`() {
        val ok = OAuth.parseRedirect("pressia://oauth/feedly?code=ABC123&state=feedly", "pressia://oauth/feedly")
        assertEquals("ABC123", ok?.code)
        assertNull(OAuth.parseRedirect("https://feedly.com/", "pressia://oauth/feedly"))
        val denied = OAuth.parseRedirect("pressia://oauth/feedly?error=access_denied", "pressia://oauth/feedly")
        assertEquals("access_denied", denied?.error)
    }

    // ---- Session Inoreader capturée dans une WebView ------------------------------------
    @Test fun `auth inoreader - cookie prefixe contre jeton oauth`() {
        // Un jeton préfixé `cookie:` est une session web.
        assertEquals(
            InoreaderAuth.WebCookie("al=abc; ssid=def"),
            inoreaderAuth(INOREADER_COOKIE_PREFIX + "al=abc; ssid=def")
        )
        // Un en-tête Cookie collé à la main, sans préfixe, est reconnu à sa forme.
        assertEquals(InoreaderAuth.WebCookie("al=abc; ssid=def"), inoreaderAuth("al=abc; ssid=def"))
        // Un jeton OAuth reste un jeton OAuth, même avec un `=` de remplissage.
        assertEquals(InoreaderAuth.OAuth("ya29.abc=="), inoreaderAuth("ya29.abc=="))
        assertNull(inoreaderAuth(null))
        assertNull(inoreaderAuth("  "))
        assertNull(inoreaderAuth(INOREADER_COOKIE_PREFIX))
    }
    @Test fun `cookies de session - al reconnu, drapeaux ecartes`() {
        val header = "al=aaaaaaaaaaaaaaaa; ssid=bbbbbbbbbbbbbbbb; _ga=GA1.1.123; pl=1"
        assertTrue(inoreaderCookieHasSession(header))
        assertFalse(inoreaderCookieHasSession("_ga=GA1.1.123; _gcl_au=1.1.9"))
        assertFalse(inoreaderCookieHasSession("al="))
        assertFalse(inoreaderCookieHasSession(null))
        // Candidats : l'en-tête complet d'abord, puis la vue réduite aux cookies de session.
        val cands = inoreaderCookieCandidates(header)
        assertEquals(header, cands[0])
        assertEquals("al=aaaaaaaaaaaaaaaa; ssid=bbbbbbbbbbbbbbbb", cands[1])
    }
    @Test fun `candidats cookie - vide ou sans session`() {
        assertTrue(inoreaderCookieCandidates(null).isEmpty())
        assertTrue(inoreaderCookieCandidates("").isEmpty())
        // Que des cookies publicitaires : l'en-tête complet reste le seul candidat.
        assertEquals(listOf("_ga=GA1.1.1"), inoreaderCookieCandidates("_ga=GA1.1.1"))
    }
    @Test fun `en-tete auth oauth inoreader au schema Reader`() {
        assertEquals("GoogleLogin auth=abc123", inoreaderAuthHeader("abc123"))
    }
    private fun httpError(code: Int): retrofit2.HttpException {
        val body = okhttp3.ResponseBody.create(null, "")
        return retrofit2.HttpException(retrofit2.Response.error<String>(code, body))
    }
    @Test fun `echec auth detecte 401 et 403`() {
        assertTrue(com.pressia.data.isAuthFailure(httpError(401)))
        assertTrue(com.pressia.data.isAuthFailure(httpError(403)))
        // Le reste est transitoire : pas d'alerte à reconnecter.
        assertFalse(com.pressia.data.isAuthFailure(httpError(404)))
        assertFalse(com.pressia.data.isAuthFailure(httpError(500)))
        assertFalse(com.pressia.data.isAuthFailure(java.io.IOException("coupure")))
        // Retrofit enveloppe souvent l'erreur d'origine.
        assertTrue(com.pressia.data.isAuthFailure(RuntimeException("x", httpError(401))))
    }

    private fun httpError429(vararg headers: Pair<String, String>): retrofit2.HttpException {
        val raw = okhttp3.Response.Builder()
            .code(429).message("Too Many Requests").protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("https://cloud.feedly.com/v3/streams/contents").build())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return retrofit2.HttpException(
            retrofit2.Response.error<String>(okhttp3.ResponseBody.create(null, ""), raw)
        )
    }

    @Test fun `limite de debit detecte 429 et son delai`() {
        val hit = com.pressia.data.rateLimitOf(httpError429("Retry-After" to "120"), "Feedly")
        assertEquals(com.pressia.data.RateLimit("Feedly", 120L, "Retry-After"), hit)
        // Format réel de Feedly : pas de Retry-After, mais X-RateLimit-Reset (secondes).
        val feedly = com.pressia.data.rateLimitOf(
            httpError429("X-RateLimit-Limit" to "100000", "X-RateLimit-Count" to "100000", "X-RateLimit-Reset" to "23841"),
            "Feedly"
        )
        assertEquals(com.pressia.data.RateLimit("Feedly", 23841L, "X-RateLimit-Reset"), feedly)
        // Les deux présents : le standard gagne.
        val both = com.pressia.data.rateLimitOf(
            httpError429("Retry-After" to "60", "X-RateLimit-Reset" to "23841"), "Feedly"
        )
        assertEquals(com.pressia.data.RateLimit("Feedly", 60L, "Retry-After"), both)
        // Sans header ou illisible : 429 avéré, délai inconnu.
        assertEquals(com.pressia.data.RateLimit("Feedly", null, null), com.pressia.data.rateLimitOf(httpError429(), "Feedly"))
        assertEquals(com.pressia.data.RateLimit("Feedly", null, null), com.pressia.data.rateLimitOf(httpError429("X-RateLimit-Reset" to "soon"), "Feedly"))
        // Ni 429 ni enveloppé : rien à signaler (et jamais confondu avec un échec d'auth).
        assertNull(com.pressia.data.rateLimitOf(httpError(403), "Feedly"))
        assertNull(com.pressia.data.rateLimitOf(java.io.IOException("coupure"), "Feedly"))
        assertFalse(com.pressia.data.isAuthFailure(httpError429("Retry-After" to "120")))
        // Enveloppé : retrouvé via la chaîne des causes.
        assertEquals(com.pressia.data.RateLimit("Inoreader", 30L, "Retry-After"), com.pressia.data.rateLimitOf(RuntimeException("x", httpError429("Retry-After" to "30")), "Inoreader"))
    }

    @Test fun `message de limite avec delai du header`() {
        val withDelay = com.pressia.data.rateLimitMessage("Feedly", 120L, "Retry-After")
        assertTrue(withDelay.contains("Feedly") && withDelay.contains("429"))
        assertTrue(withDelay.contains("120") && withDelay.contains("2 min"))
        assertTrue(withDelay.contains("impossible"))
        val reset = com.pressia.data.rateLimitMessage("Feedly", 23841L, "X-RateLimit-Reset")
        assertTrue(reset.contains("X-RateLimit-Reset") && reset.contains("23841"))
        assertTrue(reset.contains("6 h"))
        val short = com.pressia.data.rateLimitMessage("Feedly", 20L, "Retry-After")
        assertTrue(short.contains("20 s"))
        val unknown = com.pressia.data.rateLimitMessage("Feedly", null, null)
        assertTrue(unknown.contains("429") && unknown.contains("quelques minutes"))
    }

    @Test fun `accueil vide quand tout est lu et filtre`() {
        val subs = listOf(
            com.pressia.data.local.SubscriptionEntity(id = "a", title = "A"),
            com.pressia.data.local.SubscriptionEntity(id = "b", title = "B")
        )
        // Aucun abonnement : autre message (« Aucun abonnement »), pas celui-ci.
        assertFalse(com.pressia.ui.allFeedsRead(emptyList(), emptyMap()))
        // Compteurs absents = zéro (carte pas encore chargée) : tout est lu.
        assertTrue(com.pressia.ui.allFeedsRead(subs, emptyMap()))
        assertTrue(com.pressia.ui.allFeedsRead(subs, mapOf("a" to 0, "b" to 0)))
        // Un seul non-lu suffit à remplir la page.
        assertFalse(com.pressia.ui.allFeedsRead(subs, mapOf("a" to 0, "b" to 3)))
    }
    @Test fun `comptes expires aller-retour`() {
        assertEquals("", com.pressia.data.encodeExpired(emptySet()))
        assertEquals("a#1,b#2", com.pressia.data.encodeExpired(setOf("b#2", "a#1", " ", "b#2")))
        assertEquals(setOf("a#1", "b#2"), com.pressia.data.parseExpired("a#1,b#2"))
        assertEquals(setOf("instapaper"), com.pressia.data.parseExpired(" instapaper ,,"))
        assertTrue(com.pressia.data.parseExpired(null).isEmpty())
        // Les pseudo-comptes Instapaper ne portent pas de dièse : le filtre par service
        // (reconnecté) ne les touche pas.
        assertEquals("instapaper", com.pressia.data.providerBase("instapaper"))
        assertEquals("feedly", com.pressia.data.providerBase("feedly#ab12"))
    }
    @Test fun `session web instapaper parsee (dossiers, tags, cle)`() {
        val s = com.pressia.data.remote.InstapaperWeb.parseSession(
            """{"user":{"id":42,"folders":[{"id":0,"title":"Home"},{"id":99,"title":"Recettes"}],"tags":[{"id":7,"name":"Cuisine","count":3}],"formKey":"FK"}}"""
        )
        assertEquals(listOf(com.pressia.data.remote.InstapaperWeb.Folder(99, "Recettes")), s?.folders)
        assertEquals(listOf(com.pressia.data.remote.InstapaperWeb.Tag(7, "Cuisine", 3)), s?.tags)
        assertEquals("FK", s?.formKey)
        // Variante snake_case + sans utilisateur = déconnecté.
        val s2 = com.pressia.data.remote.InstapaperWeb.parseSession(
            """{"user":{"folders":[],"tags":[],"form_key":"SK"}}"""
        )
        assertEquals("SK", s2?.formKey)
        assertNull(com.pressia.data.remote.InstapaperWeb.parseSession("""{"user":null}"""))
        assertNull(com.pressia.data.remote.InstapaperWeb.parseSession("pas du json"))
    }
    @Test fun `reponse post_v6 parsee (id, dossiers, tags)`() {
        val s = com.pressia.data.remote.InstapaperWeb.parseSaved(
            """{"bookmark_id":12345,"folders":[{"id":99,"name":"Recettes"}],"all_tags":[{"id":7,"name":"Cuisine","count":3}]}"""
        )
        assertEquals(12345L, s?.bookmarkId)
        assertEquals(listOf(com.pressia.data.remote.InstapaperWeb.Folder(99, "Recettes")), s?.folders)
        assertEquals(listOf(com.pressia.data.remote.InstapaperWeb.Tag(7, "Cuisine", 3)), s?.tags)
        assertNull(com.pressia.data.remote.InstapaperWeb.parseSaved("""{"bookmark_id":0}"""))
    }
    @Test fun `corps post_v6 et tags comme l'extension`() {
        val b = com.pressia.data.remote.InstapaperWeb.saveBody("https://x/y?a=b", "T+t")
        assertTrue(b.startsWith("gr=1&") && b.contains("u=https%3A%2F%2Fx%2Fy%3Fa%3Db") && b.contains("t=T%2Bt"))
        assertTrue(b.endsWith("&b="))
        val xsrf = com.pressia.data.remote.InstapaperWeb.extractXsrf(
            """<form action="/user/login" method="post"><input type="hidden" name="_xsrf" value="2|abc|def"/>"""
        )
        assertEquals("2|abc|def", xsrf)
        assertEquals("k2", com.pressia.data.remote.InstapaperWeb.formKey("a=1; form_key=k2; b=3", "IGNOREE"))
        assertEquals("SK", com.pressia.data.remote.InstapaperWeb.formKey("", "SK"))
        assertNull(com.pressia.data.remote.InstapaperWeb.formKey("", null))
    }
    @Test fun `action tags du site en JSON`() {
        // Forme du dialogue de tags du site : {"actions":[{bookmark_id, action:"tags", …}]}.
        val o = org.json.JSONObject()
            .put("actions", org.json.JSONArray().put(
                org.json.JSONObject()
                    .put("bookmark_id", 12345)
                    .put("action", "tags")
                    .put("add_tags", org.json.JSONArray(listOf(org.json.JSONObject().put("name", "Cuisine"))))
                    .put("remove_tags", org.json.JSONArray())
            ))
            .toString()
        assertTrue(o.contains("\"action\":\"tags\"") && o.contains("\"name\":\"Cuisine\""))
    }
    @Test fun `mode instapaper persiste`() {        assertTrue("instapaper_mode" in entryNames())
        assertTrue("instapaper_web" in entryNames())
        // Ni clé ni jeton : la session web n'a rien à protéger dans la sauvegarde.
        assertFalse("instapaper_token" in entryNames())
        assertFalse("instapaper_client_id" in entryNames())
    }
    @Test fun `pkce verificateur et defi bien formes`() {
        val v1 = OAuth.pkceVerifier()
        val v2 = OAuth.pkceVerifier()
        // 32 octets en base64url : 43 caractères, alphabet restreint, jamais deux fois pareil.
        assertEquals(43, v1.length)
        assertTrue(v1.matches(Regex("[A-Za-z0-9\\-_]{43}")))
        assertNotEquals(v1, v2)
        // Défi S256 stable et au bon format.
        val c = OAuth.pkceChallenge(v1)
        assertEquals(43, c.length)
        assertEquals(c, OAuth.pkceChallenge(v1))
        assertNotEquals(c, OAuth.pkceChallenge(v2))
    }
    @Test fun `couleur hex parsee utilisable`() {
        val c = com.pressia.ui.theme.parseHexColor("#FAFAFA")
        assertNotNull(c)
        // La couleur parsée doit supporter luminance() : avec le constructeur ULong elle était
        // corrompue et plantait (ArrayIndexOutOfBounds) au premier usage dans le thème.
        assertTrue(c!!.red > 0.97f && c.green > 0.97f && c.blue > 0.97f)
        assertTrue(c.luminance() > 0.9f)
        assertEquals("#FAFAFA", c.toHex())
        assertNull(com.pressia.ui.theme.parseHexColor("pas une couleur"))
        assertNull(com.pressia.ui.theme.parseHexColor("#12345"))
        // Le fond par défaut du thème jour est #D0D0CE.
        assertEquals("#D0D0CE", com.pressia.ui.theme.BACKGROUNDS.first { it.id == "default" }.light?.toHex())
    }
    @Test fun `nom de flux lisible`() {
        assertEquals("lemonde.fr", com.pressia.ui.prettyFeedTitle("https://www.lemonde.fr/rss/une.xml"))
        assertEquals("Mon blog", com.pressia.ui.prettyFeedTitle("Mon blog"))
        assertEquals("", com.pressia.ui.prettyFeedTitle(null))
    }
    @Test fun `favicon direct lit l icone declaree du site`() {
        // Tête réelle de Frandroid : plusieurs icônes (48, 192), apple-touch-icon, ms-tile
        // et splash screens → la 192 déclarée `rel="icon"` gagne.
        val head = "<link rel=\"manifest\" href=\"/pwa-manifest.json\">" +
            "<link rel=\"icon\" href=\"https://images.frandroid.com/x/cropped-60x60.png\" sizes=\"48x48\" />" +
            "<link rel=\"icon\" href=\"https://images.frandroid.com/x/cropped-192x192.png\" sizes=\"192x192\" />" +
            "<link rel=\"apple-touch-icon\" href=\"https://images.frandroid.com/x/cropped-180x180.png\" />" +
            "<link rel=\"mask-icon\" href=\"https://ex.com/mask.svg\" color=\"#000\" />" +
            "<meta name=\"msapplication-TileImage\" content=\"https://ex.com/tile.png\" />"
        assertEquals("https://images.frandroid.com/x/cropped-192x192.png",
            com.pressia.data.parseSiteIcon(head, "https://www.frandroid.com/"))
        // Href relatif + apple seule en repli.
        assertEquals("https://ex.com/icons/i.png",
            com.pressia.data.parseSiteIcon("<link REL=\"Apple-Touch-Icon\" href=\"/icons/i.png\">", "https://ex.com/blog/"))
        assertEquals("https://ex.com/blog/favicon.png",
            com.pressia.data.parseSiteIcon("<link rel='icon' href='favicon.png'>", "https://ex.com/blog/"))
        // Rien de déclarable : null (l'appelant retombe sur l'icône du compte / /favicon.ico).
        assertNull(com.pressia.data.parseSiteIcon("<link rel=\"stylesheet\" href=\"/s.css\">", "https://ex.com/"))
        assertNull(com.pressia.data.parseSiteIcon("pas du html", "https://ex.com/"))
        // Ordre direct : résolue > compte > /favicon.ico ; cache aller-retour.
        assertEquals("https://site.com/real.png",
            com.pressia.data.directIconUrl("https://site.com/real.png", "https://cdn.com/old.png", "site.com"))
        assertEquals("https://cdn.com/old.png",
            com.pressia.data.directIconUrl(null, "https://cdn.com/old.png", "site.com"))
        assertEquals("https://site.com/favicon.ico",
            com.pressia.data.directIconUrl(null, null, "site.com"))
        val map = mapOf("a" to "https://x.com/i.png")
        assertEquals(map, com.pressia.data.parseResolved(com.pressia.data.encodeResolved(map)))
        assertTrue(com.pressia.data.parseResolved("corrompu").isEmpty())
        // Le compte garde la priorité en agrégateur, la résolue en direct.
        val sub = com.pressia.data.local.SubscriptionEntity(
            id = "f1", title = "x", url = "https://site.com/rss", siteUrl = "https://site.com",
            iconUrl = "https://cdn.com/old.png")
        assertEquals("https://cdn.com/old.png", com.pressia.ui.feedFaviconUrl(sub))
        assertEquals("https://site.com/real.png",
            com.pressia.ui.feedFaviconUrl(sub, direct = true, resolved = "https://site.com/real.png"))
    }
    @Test fun `favicon depuis host`() {
        val sub = com.pressia.data.local.SubscriptionEntity(
            id = "https://www.lemonde.fr/rss/une.xml", title = "x",
            url = "https://www.lemonde.fr/rss/une.xml", siteUrl = "https://www.lemonde.fr")
        assertEquals("https://www.google.com/s2/favicons?domain=lemonde.fr&sz=128",
            com.pressia.ui.feedFaviconUrl(sub))
        val withIcon = sub.copy(iconUrl = "https://ex.com/i.png")
        assertEquals("https://ex.com/i.png", com.pressia.ui.feedFaviconUrl(withIcon))
        assertNull(com.pressia.ui.feedFaviconUrl(null))
    }
    @Test fun `blocs article texte et images`() {
        val html = "<p>Intro.</p><p><img src=\"https://ex.com/a.jpg\"/></p><p>Suite du texte.</p>"
        val blocks = com.pressia.ui.articleBlocks(html, "https://ex.com")
        assertTrue(blocks.any { it is com.pressia.ui.ABlock.T && (it as com.pressia.ui.ABlock.T).text.contains("Intro") })
        assertTrue(blocks.any { it is com.pressia.ui.ABlock.I && (it as com.pressia.ui.ABlock.I).url == "https://ex.com/a.jpg" })
    }
    @Test fun `blocs article - liens dans le flux du texte`() {
        // Un lien du flux (YouTube, article lié…) reste DANS le paragraphe : c'est une plage
        // cliquable du texte, pas un bloc à part — un bloc à part le ferait passer à la ligne au
        // milieu de la phrase.
        val html = "<p>Voir <a href=\"https://ex.com/article\">notre article</a> pour la suite.</p>"
        val blocks = com.pressia.ui.articleBlocks(html, "https://ex.com")
        assertEquals(1, blocks.size)
        val t = blocks.filterIsInstance<com.pressia.ui.ABlock.T>().first()
        assertEquals("Voir notre article pour la suite.", t.text)
        assertEquals(1, t.links.size)
        val l = t.links.first()
        assertEquals("notre article", t.text.substring(l.start, l.end))
        assertEquals("https://ex.com/article", l.url)
        // Un lien hors de tout paragraphe, lui, n'a pas de texte où se couler : bloc à part.
        val lonely = com.pressia.ui.articleBlocks(
            "<a href=\"https://ex.com/seul\">un lien seul</a>", "https://ex.com"
        )
        assertEquals("https://ex.com/seul", lonely.filterIsInstance<com.pressia.ui.ABlock.L>().first().url)
    }
    @Test fun `blocs article - texte en ligne et sous-blocs`() {
        // La mise en forme en ligne ne coupe pas le texte (son contenu était perdu : un <strong>
        // dans un paragraphe n'était ni texte, ni lien, ni média).
        val html = "<p>Un <strong>mot gras</strong>, un <em>mot italique</em> et " +
            "<span>une <a href=\"https://ex.com/x\">adresse</a></span>.</p>"
        val inline = com.pressia.ui.articleBlocks(html, "https://ex.com")
        assertEquals(1, inline.size)
        val t = inline.filterIsInstance<com.pressia.ui.ABlock.T>().first()
        assertEquals("Un mot gras, un mot italique et une adresse.", t.text)
        assertEquals("adresse", t.text.substring(t.links.first().start, t.links.first().end))
        // Un paragraphe dans un élément de liste est déjà pris par son parent : il ne doit pas
        // être compté deux fois.
        val nested = com.pressia.ui.articleBlocks("<ul><li><p>Un seul</p></li></ul>", "https://ex.com")
        assertEquals(1, nested.size)
        assertEquals("Un seul", nested.filterIsInstance<com.pressia.ui.ABlock.T>().first().text)
        // Deux paragraphes dans une citation restent deux blocs.
        val quoted = com.pressia.ui.articleBlocks("<blockquote><p>Un</p><p>Deux</p></blockquote>", "https://ex.com")
        assertEquals(2, quoted.size)
    }
    @Test fun `blocs article - video et iframe youtube`() {
        // Balise <video> : l'URL est celle de la vidéo, le poster est l'affiche.
        val html = "<p>Intro</p><video poster=\"https://ex.com/p.jpg\" src=\"https://ex.com/v.mp4\"></video>"
        val blocks = com.pressia.ui.articleBlocks(html, "https://ex.com")
        val video = blocks.filterIsInstance<com.pressia.ui.ABlock.V>().firstOrNull()
        assertNotNull(video)
        assertEquals("https://ex.com/v.mp4", video!!.url)
        assertEquals("https://ex.com/p.jpg", video.poster)
        // Iframe YouTube : l'identifiant de la vidéo sert à construire la miniature d'affiche.
        // (Un identifiant YouTube fait 11 caractères — le test en utilise un réaliste.)
        val yt = com.pressia.ui.articleBlocks(
            "<iframe src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"></iframe>", "https://ex.com"
        ).filterIsInstance<com.pressia.ui.ABlock.V>().firstOrNull()
        assertNotNull(yt)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ", yt!!.url)
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg", yt.poster)
        // Une iframe est une PAGE web, pas un média : le lecteur intégré ne pourrait pas la lire,
        // le tap doit donc ouvrir le navigateur (drapeau `embed`).
        assertTrue(yt.embed)
        // Une balise <video>, elle, pointe un fichier média : lecteur intégré.
        assertFalse(video!!.embed)
    }
    @Test fun `identifiant youtube - toutes les formes d'url`() {
        // Les flux emploient toutes les écritures : il faut en extraire l'identifiant pour
        // rejouer la vidéo DANS l'application (WebView d'intégration) et pour son affiche.
        assertEquals("dQw4w9WgXcQ", com.pressia.ui.youtubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", com.pressia.ui.youtubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", com.pressia.ui.youtubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", com.pressia.ui.youtubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", com.pressia.ui.youtubeVideoId("https://m.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"))
        // Ce n'est pas une vidéo YouTube : pas d'identifiant.
        assertNull(com.pressia.ui.youtubeVideoId("https://vimeo.com/123456"))
        assertNull(com.pressia.ui.youtubeVideoId("https://ex.com/embed/abc"))
        // L'affiche se déduit de l'identifiant.
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            com.pressia.ui.youtubePosterUrl("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        // La lecture se fait sur la page `watch` : l'intégration `embed` donne le son sans
        // l'image sur certains appareils (décodeur alimenté, images jamais composées), alors que
        // la page de lecture, document de premier niveau, rend la vidéo normalement.
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            com.pressia.ui.youtubeEmbedUrl("dQw4w9WgXcQ"))
        // Habillage : le bloc 16/9 ne doit montrer que le lecteur.
        assertTrue(com.pressia.ui.youtubePlayerScript().contains("position:fixed"))
        assertTrue(com.pressia.ui.youtubePlayerCss().contains("56.25vw"))
    }
    @Test fun `blocs article texte nu conserve`() {
        // Flux qui livre le corps hors balise de bloc, relevé en vrai sur Les Numériques : nœuds
        // texte de premier niveau, un <br>, l'image, un autre <br>, la suite. Le texte doit
        // rester des deux côtés de l'image, dans l'ordre du document (avant le correctif, le
        // sélecteur ne trouvait que l'image et l'article s'affichait sans son corps).
        val html = "Notre comparatif de 204 voitures accueille le test de la voiture.<br>" +
            "<img src=\"https://ex.com/b.jpg\"><br>" +
            "Le bilan reste néanmoins assez positif au regard du tarif."
        val blocks = com.pressia.ui.articleBlocks(html, "https://ex.com")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is com.pressia.ui.ABlock.T && (blocks[0] as com.pressia.ui.ABlock.T).text.startsWith("Notre comparatif"))
        assertTrue(blocks[1] is com.pressia.ui.ABlock.I && (blocks[1] as com.pressia.ui.ABlock.I).url == "https://ex.com/b.jpg")
        assertTrue(blocks[2] is com.pressia.ui.ABlock.T && (blocks[2] as com.pressia.ui.ABlock.T).text.contains("bilan"))
    }
    @Test fun `articles regroupes par jour avec entete`() {
        val now = 1_760_000_000_000L
        val day = 86_400_000L
        fun art(id: String, ts: Long) = com.pressia.data.local.ArticleEntity(
            id = id, feedId = "f", title = id, published = ts
        )
        val list = listOf(
            art("a", now - 1000L),
            art("b", now - 2000L),
            art("c", now - day - 1000L),
            art("d", now - day - 2000L)
        )
        val grouped = com.pressia.ui.groupByDay(list, now)
        // 2 en-têtes de jour + 4 articles
        assertEquals(6, grouped.size)
        val days = grouped.filterIsInstance<com.pressia.ui.StreamItem.Day>()
        assertEquals(2, days.size)
        assertEquals("Aujourd'hui", days[0].label)
        assertEquals(com.pressia.ui.dayKey(now - day), days[1].key)
        // les articles restent dans l'ordre
        assertEquals(listOf("a", "b", "c", "d"),
            grouped.filterIsInstance<com.pressia.ui.StreamItem.Art>().map { it.article.id })
    }
    @Test fun `libelle de jour relatif`() {
        val now = 1_760_000_000_000L
        assertEquals("Aujourd'hui", com.pressia.ui.dayLabel(com.pressia.ui.dayKey(now), now))
        assertEquals("Hier", com.pressia.ui.dayLabel(com.pressia.ui.dayKey(now - 86_400_000L), now))
    }
    @Test fun `image du flux déjà presente detectee comme doublon`() {
        assertTrue(com.pressia.ui.isSameImage("https://cdn.ex.com/photo.jpg?w=800", "https://cdn.ex.com/photo.jpg"))
        assertTrue(com.pressia.ui.isSameImage("//cdn.ex.com/photo.jpg", "https://www.cdn.ex.com/photo.jpg"))
        assertFalse(com.pressia.ui.isSameImage("https://cdn.ex.com/photo.jpg", "https://cdn.ex.com/autre.jpg"))
        assertFalse(com.pressia.ui.isSameImage("", "https://cdn.ex.com/photo.jpg"))
        // Variantes de recadrage (Les Numériques) et de taille (Next) : même photo que l'original,
        // la vignette du flux ne doit pas s'afficher en plus de l'image déjà dans le contenu.
        assertTrue(com.pressia.ui.isSameImage(
            "https://cdn.lesnumeriques.com/optim/native/26/262116/e4cbdc58-shokz__800_800__overflow.jpg",
            "https://cdn.lesnumeriques.com/optim/native/26/262116/e4cbdc58-shokz.jpg"))
        assertTrue(com.pressia.ui.isSameImage(
            "https://next.ink/wp-content/uploads/2026/10/Bd-givre-titre-1024x495.webp",
            "https://next.ink/wp-content/uploads/2026/10/Bd-givre-titre.webp"))
        // Deux photos différentes restent différentes (un simple suffixe n'est pas une variante).
        assertFalse(com.pressia.ui.isSameImage(
            "https://cdn.ex.com/photo-350x230.jpg", "https://cdn.ex.com/photo-2.jpg"))
    }
    @Test fun `version du flux preferee a l'extrait court`() {
        val base = com.pressia.data.local.ArticleEntity(
            id = "a", feedId = "f", title = "t",
            summary = "extrait court", content = "<p>" + "x".repeat(400) + "</p>")
        // Flux « complet » : son HTML est déjà lisible en entier, il fait foi.
        assertEquals(base.content, com.pressia.ui.feedHtml(base))
        // Flux qui ne donne qu'un petit extrait : c'est l'extrait qui s'affiche.
        val thin = base.copy(content = "<p>court</p>")
        assertEquals("extrait court", com.pressia.ui.feedHtml(thin))
        // Extraction en base : la version du flux mise de côté dans `summary` reprend la main.
        assertEquals("extrait court", com.pressia.ui.feedHtml(base.copy(fullFetched = true)))
    }
    @Test fun `duree de lecture lisible`() {
        assertEquals("0 s", com.pressia.data.fmtDuration(0))
        assertEquals("42 s", com.pressia.data.fmtDuration(42))
        assertEquals("4 min 05 s", com.pressia.data.fmtDuration(245))
        assertEquals("2 h 07 min", com.pressia.data.fmtDuration(7620))
    }
    @Test fun `periode stats`() {
        val now = 1_760_000_000_000L
        val day = com.pressia.data.startOfDay(now)
        // Aujourd'hui : depuis minuit, jusqu'à maintenant
        val d = com.pressia.data.statsRange(com.pressia.data.StatsPeriod.DAY, now = now)
        assertEquals(day, d[0]); assertEquals(now, d[1])
        // 7 jours : 7 jours glissants incluant aujourd'hui (minuit - 6 j)
        assertEquals(day - 6L * 86400_000L, com.pressia.data.statsRange(com.pressia.data.StatsPeriod.WEEK, now = now)[0])
        assertEquals(day - 29L * 86400_000L, com.pressia.data.statsRange(com.pressia.data.StatsPeriod.MONTH, now = now)[0])
        // Depuis le début : 1re lecture, ou 0 si l'app n'a jamais enregistré de lecture
        assertEquals(1_700_000_000_000L, com.pressia.data.statsRange(com.pressia.data.StatsPeriod.ALL, now = now, first = 1_700_000_000_000L)[0])
        assertEquals(0L, com.pressia.data.statsRange(com.pressia.data.StatsPeriod.ALL, now = now, first = 0L)[0])
        // L'année est prioritaire sur la période. `now` vaut octobre 2025 :
        // sur 2025 (année en cours) la borne haute est ramenée à maintenant.
        val c = java.util.Calendar.getInstance()
        c.clear(); c.set(2025, 0, 1, 0, 0, 0)
        val current = com.pressia.data.statsRange(com.pressia.data.StatsPeriod.WEEK, year = 2025, now = now)
        assertEquals(c.timeInMillis, current[0]); assertEquals(now, current[1])
        // Sur une année passée : borne haute = 1er janvier suivant moins 1 ms
        c.clear(); c.set(2024, 0, 1, 0, 0, 0)
        val past = com.pressia.data.statsRange(com.pressia.data.StatsPeriod.WEEK, year = 2024, now = now)
        assertEquals(c.timeInMillis, past[0])
        c.clear(); c.set(2025, 0, 1, 0, 0, 0)
        assertEquals(c.timeInMillis - 1, past[1])
        assertEquals("7d", com.pressia.data.StatsPeriod.of("7d").id)
        assertEquals(com.pressia.data.StatsPeriod.WEEK, com.pressia.data.StatsPeriod.of("inconnu"))
    }
    @Test fun `tri des flux par temps ou ouvertures`() {
        val f = listOf("a" to (10 to 1), "b" to (30 to 5), "c" to (20 to 3))
        assertEquals(listOf("b", "c", "a"), com.pressia.data.sortFeeds(f, com.pressia.data.FeedSort.TIME_DESC).map { it.first })
        assertEquals(listOf("a", "c", "b"), com.pressia.data.sortFeeds(f, com.pressia.data.FeedSort.TIME_ASC).map { it.first })
        assertEquals(listOf("b", "c", "a"), com.pressia.data.sortFeeds(f, com.pressia.data.FeedSort.OPENS_DESC).map { it.first })
        assertEquals(listOf("a", "c", "b"), com.pressia.data.sortFeeds(f, com.pressia.data.FeedSort.OPENS_ASC).map { it.first })
    }
    @Test fun `libelle court de jour stats`() {
        assertEquals("jeu. 1/10", com.pressia.data.dayLabelShort("2026-10-01"))
        assertTrue(com.pressia.data.dayLabelShort("n-importe").contains("n-importe"))
    }
    @Test fun `sections de sauvegarde et secrets`() {
        assertEquals(listOf("prefs", "stats", "accounts", "read"), com.pressia.data.Sections.ALL)
        // Les secrets ne sont jamais exportés implicitement
        assertTrue("token" in com.pressia.data.SECRET_PREFS)
        assertTrue("refresh" in com.pressia.data.SECRET_PREFS)
        assertTrue("feedly_client_secret" in com.pressia.data.SECRET_PREFS)
        assertFalse("theme" in com.pressia.data.SECRET_PREFS)
    }

    @Test fun `etat local jamais exporte`() {
        // La file des favoris restaurés n'est pas un réglage : elle ne doit pas sortir, et son
        // absence dans ENTRIES garantit qu'elle ne peut pas être réimportée par un nom.
        assertTrue("restored_starred" in com.pressia.data.NON_EXPORTED_PREFS)
        assertFalse("restored_starred" in entryNames())
        // Idem pour la file des lus locaux restaurés.
        assertTrue("restored_read" in com.pressia.data.NON_EXPORTED_PREFS)
        assertFalse("restored_read" in entryNames())
        // Idem pour les caches dérivés : totaux de non-lus, empreintes Read later et icônes
        // de sites résolues — recalculés ou re-résolus après restauration, jamais exportés.
        listOf("reconcile_totals", "readlater_hashes", "favicon_resolved").forEach {
            assertTrue("$it ne doit pas sortir", it in com.pressia.data.NON_EXPORTED_PREFS)
            assertFalse("$it ne doit pas être réimportable", it in entryNames())
        }
        // Idem pour la mémoire de la passe de favicons (date + abonnements couverts) : une
        // restauration refait la passe, ces deux clés ne doivent pas voyager.
        listOf("favicons_at", "favicons_feeds").forEach {
            assertTrue("$it ne doit pas sortir", it in com.pressia.data.NON_EXPORTED_PREFS)
            assertFalse("$it ne doit pas être réimportable", it in entryNames())
        }
        // Date de la dernière synchro réussie : garde-fou de fraîcheur propre à l'appareil. Elle
        // ne doit ni sortir (une sauvegarde partagée emportait la date de l'autre appareil) ni
        // rentrer (la restauration sautait ou relançait une synchro sans rapport).
        assertTrue("last_sync_at ne doit pas sortir", "last_sync_at" in com.pressia.data.NON_EXPORTED_PREFS)
        assertFalse("last_sync_at ne doit pas être réimportable", "last_sync_at" in entryNames())
        // Aucun secret ne doit être doublement classé, sinon les deux filtres se contredisent.
        assertTrue(com.pressia.data.SECRET_PREFS.intersect(com.pressia.data.NON_EXPORTED_PREFS).isEmpty())
    }

    @Test fun `favoris restaures additionnes entre deux imports`() {
        // Un premier fichier, dont les articles ne sont pas encore arrivés.
        val premier = listOf("a:1", "a:2")
        val file = com.pressia.data.encodeRestoredStarred(premier)
        assertEquals(premier, com.pressia.data.parseRestoredStarred(file))
        // Un second import alors que la file est encore en attente : elle s'additionne, sans
        // doublon, au lieu d'écraser (l'écrasement perdait les favoris du premier fichier).
        val second = listOf("b:7", "a:2")
        val fusion = com.pressia.data.parseRestoredStarred(file) + second
        val merged = com.pressia.data.parseRestoredStarred(com.pressia.data.encodeRestoredStarred(fusion))
        assertEquals(listOf("a:1", "a:2", "b:7"), merged)
        // File vide ou abîmée : jamais de ligne fantôme.
        assertTrue(com.pressia.data.parseRestoredStarred(null).isEmpty())
        assertTrue(com.pressia.data.parseRestoredStarred("\n  \n").isEmpty())
        assertEquals("", com.pressia.data.encodeRestoredStarred(listOf("", "  ")))
    }

    @Test fun `favicons seulement quand c'est du`() {
        val jour = 24 * 60 * 60 * 1000L
        val maintenant = 1_700_000_000_000L
        val ids = setOf("a", "b")
        // Première passe de l'appareil : due, même avec tout en cache.
        assertTrue(com.pressia.data.faviconsDue(0L, maintenant, emptySet(), ids, 0))
        // Rien de nouveau, mois non écoulé, aucune icône manquante : pas de passe.
        assertFalse(com.pressia.data.faviconsDue(maintenant - jour, maintenant, ids, ids, 0))
        // Un flux arrivé depuis la dernière passe : due.
        assertTrue(com.pressia.data.faviconsDue(maintenant - jour, maintenant, setOf("a"), ids, 0))
        // Une icône absente : due, mais pas plus d'une fois par jour (échec passager réparé,
        // icône jamais servie par le site non redemandée à chaque synchro).
        assertTrue(com.pressia.data.faviconsDue(maintenant - jour, maintenant, ids, ids, 2))
        assertFalse(com.pressia.data.faviconsDue(maintenant - 60_000L, maintenant, ids, ids, 2))
        // Rafraîchissement mensuel.
        assertTrue(com.pressia.data.faviconsDue(maintenant - 30 * jour, maintenant, ids, ids, 0))
        assertFalse(com.pressia.data.faviconsDue(maintenant - 29 * jour, maintenant, ids, ids, 0))
        // Aucun abonnement : jamais de passe, même la première fois.
        assertFalse(com.pressia.data.faviconsDue(0L, maintenant, emptySet(), emptySet(), 0))
        // Le marqueur d'abonnements couverts fait l'aller-retour (un par ligne).
        val encoded = com.pressia.data.encodeFaviconFeedIds(listOf("b", "a", "a"))
        assertEquals("a\nb", encoded)
        assertEquals(setOf("a", "b"), com.pressia.data.parseFaviconFeedIds(encoded))
        assertTrue(com.pressia.data.parseFaviconFeedIds(null).isEmpty())
    }

    @Test fun `compteur de non lus du perimetre affiche`() {
        val unreads = mapOf("f1" to 3, "f2" to 4)
        val folders = mapOf("d1" to 7, "merged:x" to 2)
        // Un flux : son propre compteur.
        assertEquals(3, com.pressia.ui.scopeUnread(com.pressia.ui.Scope(feedId = "f1"), unreads, folders))
        assertEquals(0, com.pressia.ui.scopeUnread(com.pressia.ui.Scope(feedId = "inconnu"), unreads, folders))
        // Un dossier, fusionné compris : le total du dossier.
        assertEquals(7, com.pressia.ui.scopeUnread(com.pressia.ui.Scope(categoryId = "d1"), unreads, folders))
        assertEquals(2, com.pressia.ui.scopeUnread(com.pressia.ui.Scope(categoryId = "merged:x"), unreads, folders))
        // « Tous les articles » : la somme des flux, comme le compteur de l'accueil.
        assertEquals(7, com.pressia.ui.scopeUnread(com.pressia.ui.Scope(), unreads, folders))
    }

    @Test fun `sauvegarde ne garde que les dossiers locaux`() {
        val cats = listOf(
            com.pressia.data.local.CategoryEntity("d1", "Perso", "local"),
            com.pressia.data.local.CategoryEntity("feedly#ab12:tech", "Tech", "feedly#ab12"),
            com.pressia.data.local.CategoryEntity("inoreader#cd34:dev", "Dev", "inoreader#cd34")
        )
        assertEquals(listOf("d1"), com.pressia.data.backupCategories(cats).map { it.id })
    }

    @Test fun `sauvegarde detache les flux des dossiers non sauvegardes`() {
        val subs = listOf(
            com.pressia.data.local.SubscriptionEntity("u1", "Blog", "https://a.fr/rss", categoryId = "d1", provider = "local"),
            com.pressia.data.local.SubscriptionEntity("f1", "Cloud", "https://b.fr/rss", categoryId = "feedly#ab12:tech", provider = "feedly#ab12"),
            com.pressia.data.local.SubscriptionEntity("u2", "Seul", "https://c.fr/rss", categoryId = null, provider = "local")
        )
        val out = com.pressia.data.backupSubscriptions(subs, setOf("d1")).associateBy { it.id }
        assertEquals("d1", out.getValue("u1").categoryId)
        assertNull(out.getValue("f1").categoryId)
        assertNull(out.getValue("u2").categoryId)
    }

    @Test fun `formatCacheBytes en francais`() {
        assertEquals("512 o", com.pressia.data.formatCacheBytes(512))
        assertEquals("68,0 Ko", com.pressia.data.formatCacheBytes(68L * 1024))
        assertEquals("17,3 Mo", com.pressia.data.formatCacheBytes((17.3 * 1024 * 1024).toLong()))
    }

    @Test fun `entrees de preferences sans doublon et sans trou connu`() {
        val names = entryNames()
        assertEquals("un nom de préférence ne doit être déclaré qu'une fois", names.size, names.toSet().size)
        // last_sync_at est un état de l'appareil, pas un réglage : il est volontairement absent
        // d'ENTRIES et exclu de l'export (voir `etat local jamais exporte`).
        assertFalse("last_sync_at ne doit pas être réimportable", "last_sync_at" in names)
        // Les trois OAuth / comptes que l'écran de connexion renseigne doivent rester exportables
        // (leur secret est filtré séparément, leur identifiant public est destiné à être partagé).
        listOf("inoreader_app_id", "feedly_client_id", "accounts", "active_account").forEach {
            assertTrue("$it doit figurer dans ENTRIES", it in names)
        }
        // Réglage exporté : l'historique remonté doit survivre à une restauration.
        assertTrue("history_months doit figurer dans ENTRIES", "history_months" in names)
        // Défaut d'historique : 1 mois, aligné sur la fenêtre Feedly (~31 j au-delà = lu).
        assertEquals(1, Prefs.HISTORY_MONTHS_DEFAULT)
        // Réglage exporté : la source des favicons doit survivre à une restauration.
        assertTrue("favicon_source doit figurer dans ENTRIES", "favicon_source" in names)
        // Filtre œil mémorisé par flux : ses choix posés doivent survivre à une restauration.
        assertTrue("unread_only_by_feed doit figurer dans ENTRIES", "unread_only_by_feed" in names)
        // Métadonnée exportée : la date de dernière sauvegarde survit à une restauration.
        assertTrue("last_backup_at doit figurer dans ENTRIES", "last_backup_at" in names)
        assertTrue(Prefs.ENTRIES.any { it is PrefEntry.LongPref && it.name == "last_backup_at" })
        // Réglage exporté : la persistance du mode Readability doit survivre à une restauration.
        assertTrue("readability_sticky doit figurer dans ENTRIES", "readability_sticky" in names)
        // Réglages exportés : les gestes d'article doivent survivre à une restauration.
        listOf("double_tap_action", "article_swipe_left", "article_swipe_right").forEach {
            assertTrue("$it doit figurer dans ENTRIES", it in names)
        }
        // Borne de remise à zéro des stats : une sauvegarde restaurée doit la retrouver, sinon
        // les statistiques effacées réapparaîtraient avec les traces restaurées.
        assertTrue("stats_since doit figurer dans ENTRIES", "stats_since" in names)
        assertTrue(Prefs.ENTRIES.any { it is PrefEntry.LongPref && it.name == "stats_since" })
    }

    @Test fun `borne basse des stats apres remise a zero`() {
        // Sans remise à zéro (0), la période demandée reste la borne.
        assertEquals(1000L, com.pressia.data.statsLowerBound(1000L, 0L))
        assertEquals(0L, com.pressia.data.statsLowerBound(0L, 0L))
        // Remise à zéro postérieure au début de la période : elle prend le dessus.
        assertEquals(2000L, com.pressia.data.statsLowerBound(1000L, 2000L))
        // Remise à zéro antérieure : la période demandée reste la borne.
        assertEquals(2000L, com.pressia.data.statsLowerBound(2000L, 1000L))
    }

    private fun entryNames(): Set<String> = Prefs.ENTRIES.map { it.name }.toSet()

    @Test fun `lus restaures aller-retour tolerant`() {
        val rows = listOf(
            com.pressia.data.RestoredRead("https://exemple.fr/rss", "https://exemple.fr/rss:123", 1700000000000L),
            com.pressia.data.RestoredRead("feed/x", "feed/x:-99", null)
        )
        val back = com.pressia.data.parseRestoredRead(com.pressia.data.encodeRestoredRead(rows))
        assertEquals(rows, back)
        // Lignes abîmées ignorées, `readAt` absent ou nul → null, espaces rognés.
        assertEquals(
            listOf(
                com.pressia.data.RestoredRead("f", "a", null),
                com.pressia.data.RestoredRead("f", "a", null),
                com.pressia.data.RestoredRead("f", "a", 42)
            ),
            com.pressia.data.parseRestoredRead("f|a|\nbroken\nf|a|0\n  f  |  a  | 42 ")
        )
        assertTrue(com.pressia.data.parseRestoredRead(null).isEmpty())
        assertTrue(com.pressia.data.parseRestoredRead("").isEmpty())
        // Identifiants ou flux vides : jamais programmés.
        assertEquals("", com.pressia.data.encodeRestoredRead(listOf(com.pressia.data.RestoredRead("", "a", null))))
    }

    // ----- Registre multi-comptes -----

    @Test fun `identifiant de compte stable et distinct`() {
        val a = com.pressia.data.accountIdOf("feedly", "tok-1")
        // Le même jeton donne toujours le même compte : une reconnexion ne crée pas de doublon.
        assertEquals(a, com.pressia.data.accountIdOf("feedly", "tok-1"))
        // Deux comptes du même service ne se confondent jamais.
        assertNotEquals(a, com.pressia.data.accountIdOf("feedly", "tok-2"))
        assertNotEquals(a, com.pressia.data.accountIdOf("inoreader", "tok-1"))
        assertTrue(a.startsWith("feedly#"))
    }

    @Test fun `cle de proprietaire lue jusqu'au service`() {
        assertEquals("feedly", com.pressia.data.providerBase("feedly#ab12cd34"))
        assertEquals("inoreader", com.pressia.data.providerBase("inoreader#0011"))
        assertEquals(com.pressia.data.LOCAL_PROVIDER, com.pressia.data.providerBase(null))
        assertEquals(com.pressia.data.LOCAL_PROVIDER, com.pressia.data.providerBase("  "))
        // Les flux saisis à la main ne sont jamais assimilés à un compte.
        assertTrue(com.pressia.data.isLocalProvider(null))
        assertTrue(com.pressia.data.isLocalProvider("local"))
        assertFalse(com.pressia.data.isLocalProvider("feedly#ab12"))
        assertTrue(com.pressia.data.sameAccount("feedly#ab12", "feedly#ab12"))
        assertFalse(com.pressia.data.sameAccount("feedly#ab12", "feedly#cd34"))
        assertFalse(com.pressia.data.sameAccount(null, "feedly#ab12"))
    }

    @Test fun `registre de comptes aller-retour`() {
        val accounts = listOf(
            com.pressia.data.CloudAccount("feedly#aa", "feedly", "t1", "moi@ex.com"),
            com.pressia.data.CloudAccount("inoreader#bb", "inoreader", "t2", "")
        )
        val back = com.pressia.data.AccountStore.parse(com.pressia.data.AccountStore.encode(accounts))
        assertEquals(accounts, back)
        // Registre vide ou corrompu : aucun compte, plutôt qu'une exception au démarrage.
        assertTrue(com.pressia.data.AccountStore.parse(null).isEmpty())
        assertTrue(com.pressia.data.AccountStore.parse("[]").isEmpty())
        assertTrue(com.pressia.data.AccountStore.parse("pas du json").isEmpty())
        assertTrue(com.pressia.data.AccountStore.parse("""[{"provider":"","token":"x"}]""").isEmpty())
        // Deux lignes de même identifiant n'en font qu'une.
        assertEquals(1, com.pressia.data.AccountStore.parse(
            """[{"id":"feedly#aa","provider":"feedly","token":"t1"},{"id":"feedly#aa","provider":"feedly","token":"t1"}]"""
        ).size)
    }

    @Test fun `jeton de renouvellement conserve et remplace`() {
        // Le jeton de renouvellement accompagne le compte : il doit survivre à l'aller-retour.
        val acc = com.pressia.data.CloudAccount("inoreader#bb", "inoreader", "t1", "moi@ex.com", "uid-1", "refresh-1")
        val back = com.pressia.data.AccountStore.parse(com.pressia.data.AccountStore.encode(listOf(acc)))
        assertEquals("refresh-1", back[0].refreshToken)
        assertEquals(acc, back[0])
        // Un refresh vide conserve celui déjà enregistré (une reconnexion par cookie n'en
        // apporte pas) ; un refresh fourni le remplace.
        val kept = com.pressia.data.AccountStore.refreshToken(
            listOf(acc), "inoreader", "t2", "moi@ex.com", "uid-1")
        assertEquals("refresh-1", kept[0].refreshToken)
        assertEquals("t2", kept[0].token)
        // L'identité du compte est conservée : ses dossiers et ses articles suivent.
        assertEquals(acc.id, kept[0].id)
        val replaced = com.pressia.data.AccountStore.refreshToken(
            listOf(acc), "inoreader", "t3", "moi@ex.com", "uid-1", "refresh-2")
        assertEquals("refresh-2", replaced[0].refreshToken)
    }

    @Test fun `jetons oauth parses`() {
        val both = com.pressia.data.remote.OAuth.parseTokens(
            """{"access_token":"AAA","refresh_token":"RRR","expires_in":3600}""")
        assertEquals("AAA", both?.access)
        assertEquals("RRR", both?.refresh)
        // Inoreader peut ne pas renvoyer de refresh_token : il reste vide, l'ancien est
        // conservé par l'appelant.
        val noRefresh = com.pressia.data.remote.OAuth.parseTokens("""{"access_token":"AAA"}""")
        assertEquals("AAA", noRefresh?.access)
        assertEquals("", noRefresh?.refresh)
        // Sans jeton d'accès, la réponse est inutilisable.
        assertNull(com.pressia.data.remote.OAuth.parseTokens("""{"error":"invalid_grant"}"""))
        assertNull(com.pressia.data.remote.OAuth.parseTokens(""))
    }

    @Test fun `ajout et rafraichissement de jeton`() {
        val one = listOf(com.pressia.data.CloudAccount("feedly#aa", "feedly", "t1", "moi@ex.com"))
        // Même jeton : on ne duplique pas, on met juste à jour le nom.
        val same = com.pressia.data.AccountStore.refreshToken(one, "feedly", "t1", "autre@ex.com")
        assertEquals(1, same.size)
        assertEquals("autre@ex.com", same[0].name)
        // Nouveau jeton sans identifiant distant : impossible de dire si c'est le même
        // utilisateur — un second compte distinct, l'ancien est conservé (voir le test suivant).
        val two = com.pressia.data.AccountStore.refreshToken(same, "feedly", "t2", "pro@ex.com")
        assertEquals(2, two.size)
        assertEquals(2, two.count { it.service == "feedly" })
        assertNotEquals(two[0].id, two[1].id)
        // upsert remplace la ligne de même identifiant.
        val replaced = com.pressia.data.AccountStore.upsert(two, two[0].copy(name = "renomme"))
        assertEquals(2, replaced.size)
        assertEquals("renomme", replaced.first { it.id == two[0].id }.name)
    }

    @Test fun `pas deux fois le meme compte`() {
        val one = listOf(com.pressia.data.CloudAccount("feedly#aa", "feedly", "t1", "moi@ex.com", "uid-1"))
        // Reconnexion du même utilisateur (nouveau jeton) : le jeton remplace l'ancien sur
        // place — même identité, donc dossiers et abonnements suivent sans migration.
        val same = com.pressia.data.AccountStore.refreshToken(one, "feedly", "t2", "moi@ex.com", "uid-1")
        assertEquals(1, same.size)
        assertEquals("feedly#aa", same[0].id)
        assertEquals("t2", same[0].token)
        // Un autre utilisateur du même service coexiste : pas de fusion abusive.
        val two = com.pressia.data.AccountStore.refreshToken(same, "feedly", "t3", "autre@ex.com", "uid-2")
        assertEquals(2, two.size)
        // Sans identifiant distant des deux côtés : on ne devine rien, deux lignes.
        val blanks = com.pressia.data.AccountStore.refreshToken(
            listOf(com.pressia.data.CloudAccount("feedly#aa", "feedly", "t1")),
            "feedly", "t2"
        )
        assertEquals(2, blanks.size)
        // L'identifiant distant survit à l'aller-retour JSON (et son absence vaut "").
        val back = com.pressia.data.AccountStore.parse(com.pressia.data.AccountStore.encode(same))
        assertEquals("uid-1", back[0].userId)
        assertEquals("", com.pressia.data.AccountStore.parse("""[{"provider":"feedly","token":"t1"}]""")[0].userId)
    }

    @Test fun `partage autour d une ancre exclut l ancre`() {
        val ids = listOf("n2", "n1", "x", "o1", "o2")
        val (newer, older) = com.pressia.ui.splitAround(ids, "x")
        // Ordre d'affichage = du plus récent au plus ancien : avant = plus récents.
        assertEquals(listOf("n2", "n1"), newer)
        assertEquals(listOf("o1", "o2"), older)
        // Ancre en tête / en queue : un côté vide (option désactivée dans le menu).
        assertEquals(emptyList<String>(), com.pressia.ui.splitAround(ids, "n2").first)
        assertEquals(emptyList<String>(), com.pressia.ui.splitAround(ids, "o2").second)
        // Ancre inconnue : rien des deux côtés, jamais tout cocher par erreur.
        assertEquals(emptyList<String>() to emptyList<String>(), com.pressia.ui.splitAround(ids, "zz"))
    }
    @Test fun `bouton bas s arrete au milieu des non-lus`() {
        // Liste mixte, écran de 10 : 1er appui depuis le haut → arrêt centré sur le dernier
        // non-lu (20 - 5 = 15), 2e appui (arrêt atteint) → tout en bas, sans état mémorisé.
        assertEquals(15, com.pressia.ui.downTarget(0, 20, 29, 10, true))
        assertEquals(29, com.pressia.ui.downTarget(15, 20, 29, 10, true))
        // Déjà au-delà de l'arrêt (défilement manuel) : tout en bas direct.
        assertEquals(29, com.pressia.ui.downTarget(16, 20, 29, 10, true))
        // Dernier non-lu déjà visible (arrêt 0) : tout en bas direct, pas d'arrêt inutile.
        assertEquals(29, com.pressia.ui.downTarget(0, 3, 29, 10, true))
        // Aucun non-lu, ou arrêt coupé (favoris / read later) : tout en bas direct.
        assertEquals(29, com.pressia.ui.downTarget(0, -1, 29, 10, true))
        assertEquals(29, com.pressia.ui.downTarget(0, 20, 29, 10, false))
        // Centrage : demi-écran avant, plancher 0.
        assertEquals(15, com.pressia.ui.centeredStopIndex(20, 10))
        assertEquals(0, com.pressia.ui.centeredStopIndex(3, 10))
    }

    @Test fun `index paresseux du dernier non-lu compte les intercalaires`() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        fun art(id: String, daysAgo: Long, unread: Boolean) =
            com.pressia.data.local.ArticleEntity(id = id, feedId = "f", title = id, published = now - daysAgo * day, unread = unread)
        // Jour 1 : 2 non-lus ; jour 2 : 1 lu puis 1 non-lu.
        val items = com.pressia.ui.groupByDay(listOf(
            art("a", 0, true), art("b", 0, true),
            art("c", 3, false), art("d", 3, true)
        ), now)
        // Groupés : [Jour, a, b, Jour, c, d] → affichés : [Jour, a, b, espace, Jour, c, d] : d en 6.
        assertEquals(6, com.pressia.ui.lastUnreadLazyIndex(items))
        // Tout lu : -1 (le bouton va tout en bas direct).
        val lus = com.pressia.ui.groupByDay(listOf(art("a", 0, false), art("b", 3, false)), now)
        assertEquals(-1, com.pressia.ui.lastUnreadLazyIndex(lus))
        // Non-lu étoilé ou « read later » en fin de liste : ignoré, l'arrêt recule au dernier
        // non-lu simple (ici b).
        assertEquals(2, com.pressia.ui.lastUnreadLazyIndex(items) { it.id == "d" })
    }

    @Test fun `libelle du jeu de favoris`() {
        assertEquals("Favoris PressIA", com.pressia.ui.favSetLabel("app"))
        assertEquals("Read later", com.pressia.ui.favSetLabel("later"))
        // Une valeur inconnue retombe sur les étoiles plutôt que d'afficher un libellé vide.
        assertEquals("Favoris PressIA", com.pressia.ui.favSetLabel("inconnu"))
    }

    @Test fun `reconciliation lu-non-lu distant`() {
        val localUnread = setOf("a", "b", "c")
        val remoteUnread = setOf("b", "d")
        // `a` lu ailleurs → en lu ; `d` rouvert ailleurs → en non-lu.
        val r = com.pressia.data.remote.computeReadReconciliation(
            localUnread, remoteUnread, setOf("d"), emptySet(), true, false
        )
        assertEquals(setOf("a", "c"), r.markRead)
        assertEquals(setOf("d"), r.markUnread)
        // Énumération plafonnée : on rouvre mais on ne passe rien en lu à l'aveugle.
        val capped = com.pressia.data.remote.computeReadReconciliation(
            localUnread, remoteUnread, setOf("d"), emptySet(), false, false
        )
        assertTrue(capped.markRead.isEmpty())
        assertEquals(setOf("d"), capped.markUnread)
        // Actions locales non poussées : jamais écrasées (ni lu distant, ni rouverture).
        val pend = com.pressia.data.remote.computeReadReconciliation(
            localUnread, remoteUnread, setOf("b"), setOf("b", "c"), true, false
        )
        assertEquals(setOf("a"), pend.markRead)
        assertTrue(pend.markUnread.isEmpty())
        // « Tout marquer lu » en attente : aucun passage en lu distant en attendant la pousse.
        val mall = com.pressia.data.remote.computeReadReconciliation(
            localUnread, remoteUnread, emptySet(), emptySet(), true, true
        )
        assertTrue(mall.markRead.isEmpty())
    }

    @Test fun `newerThan seulement pour 1 mois`() {
        // 1 mois (31 j, le défaut rapide) : le cutoff part à Feedly tel quel.
        val cutoff = 1_700_000_000_000L
        assertEquals(cutoff, com.pressia.data.historyNewerThan(1, cutoff))
        // Au-delà (calendaires) et sans limite : filtre client uniquement.
        assertNull(com.pressia.data.historyNewerThan(2, cutoff))
        assertNull(com.pressia.data.historyNewerThan(36, cutoff))
        assertNull(com.pressia.data.historyNewerThan(0, cutoff))
    }

    @Test fun `action de geste inconnue retombe sur le defaut`() {
        assertEquals("readability", com.pressia.ui.articleGestureAction("readability", "readability", com.pressia.ui.DOUBLE_TAP_IDS))
        assertEquals("older", com.pressia.ui.articleGestureAction("bidule", "older", com.pressia.ui.ARTICLE_SWIPE_IDS - "newer"))
        assertEquals("newer", com.pressia.ui.articleGestureAction("", "newer", com.pressia.ui.ARTICLE_SWIPE_IDS - "older"))
    }

    @Test fun `fenetre d'historique en mois calendaires`() {
        // 14 oct. 2026, midi UTC : fields lus dans le même fuseau des deux côtés.
        val cal = java.util.Calendar.getInstance()
        cal.clear(); cal.set(2026, java.util.Calendar.OCTOBER, 14, 12, 0, 0)
        val now = cal.timeInMillis
        // 0 ou négatif = sans limite.
        assertEquals(0L, com.pressia.data.historyCutoffMs(now, 0))
        assertEquals(0L, com.pressia.data.historyCutoffMs(now, -3))
        // 1 mois = 31 jours fixes (fenêtre Feedly), pas le mois calendaire.
        assertEquals(now - 31L * 24 * 3600 * 1000, com.pressia.data.historyCutoffMs(now, 1))
        // 12 mois : même jour un an plus tôt (mois calendaires, pas 365 jours).
        val cut12 = java.util.Calendar.getInstance()
        cut12.timeInMillis = com.pressia.data.historyCutoffMs(now, 12)
        assertEquals(2025, cut12.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.OCTOBER, cut12.get(java.util.Calendar.MONTH))
        assertEquals(14, cut12.get(java.util.Calendar.DAY_OF_MONTH))
        // 2 mois : mois calendaires, jour conservé (14 août 2026).
        val cut2 = java.util.Calendar.getInstance()
        cut2.timeInMillis = com.pressia.data.historyCutoffMs(now, 2)
        assertEquals(2026, cut2.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.AUGUST, cut2.get(java.util.Calendar.MONTH))
        assertEquals(14, cut2.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun art(
        id: String,
        feed: String,
        starred: Boolean = false,
        laterSaved: Boolean = false,
        published: Long = 0L
    ) = com.pressia.data.local.ArticleEntity(
        id = id, feedId = feed, title = "t$id",
        starred = starred, laterSaved = laterSaved, published = published
    )

    @Test fun `saturation d'un flux au quota`() {
        // Quota atteint sans rien à rafraîchir : les pages suivantes seraient jetées.
        assertTrue(com.pressia.data.feedSaturated(count = 100, keep = 100, hasKnown = false))
        // Quota non atteint, ou page recroisant du connu : on continue.
        assertFalse(com.pressia.data.feedSaturated(count = 99, keep = 100, hasKnown = false))
        assertFalse(com.pressia.data.feedSaturated(count = 100, keep = 100, hasKnown = true))
        assertFalse(com.pressia.data.feedSaturated(count = 0, keep = 100, hasKnown = false))
    }

    @Test fun `decouverte des flux jamais approvisionnes`() {
        // Compte déjà provisionné : seuls les flux sans articles partent en rattrapage.
        assertEquals(
            listOf("neuf"),
            com.pressia.data.discoverFeeds(listOf("vieux", "neuf"), setOf("vieux", "autre"))
        )
        // Aucun article nulle part : c'est l'approvisionnement global qui s'en charge.
        assertTrue(com.pressia.data.discoverFeeds(listOf("a", "b"), emptySet()).isEmpty())
        // Rien de neuf : rien à faire.
        assertTrue(com.pressia.data.discoverFeeds(listOf("a"), setOf("a")).isEmpty())
    }

    @Test fun `flux desabonnes detectes par difference`() {
        assertEquals(setOf("vieux"), com.pressia.data.removedFeeds(setOf("vieux", "garde"), setOf("garde")))
        // Rien de parti : rien à supprimer (cas courant, coût nul).
        assertTrue(com.pressia.data.removedFeeds(setOf("a"), setOf("a", "b")).isEmpty())
        assertTrue(com.pressia.data.removedFeeds(emptySet(), setOf("a")).isEmpty())
    }

    @Test fun `file montante poussee dans l'ordre`() {
        val rows = listOf(
            com.pressia.data.local.SyncActionEntity(articleId = "a1", feedId = "f", kind = "read"),
            com.pressia.data.local.SyncActionEntity(articleId = "a2", feedId = "f", kind = "read"),
            com.pressia.data.local.SyncActionEntity(articleId = "a3", feedId = "f", kind = "unread"),
            com.pressia.data.local.SyncActionEntity(articleId = "vieux", feedId = "f", kind = "star"),
            com.pressia.data.local.SyncActionEntity(articleId = "", feedId = "f", kind = "markAllRead")
        )
        val runs = com.pressia.data.syncActionRuns(rows)
        // Lignes consécutives de même sorte : 2 lus → 1 appel. Les étoiles locales ne partent pas.
        assertEquals(listOf("read", "unread", "markAllRead"), runs.map { it.first().kind })
        assertEquals(listOf("a1", "a2"), runs[0].map { it.articleId })
        // L'ordre chronologique est conservé : l'« Annuler » (« unread » ajouté après un
        // « markAllRead » encore en file) part APRÈS lui, sinon le serveur repasserait tout en lu.
        val undone = rows + com.pressia.data.local.SyncActionEntity(articleId = "a3", feedId = "f", kind = "unread")
        assertEquals(
            listOf("read", "unread", "markAllRead", "unread"),
            com.pressia.data.syncActionRuns(undone).map { it.first().kind }
        )
        assertTrue(com.pressia.data.syncActionRuns(emptyList()).isEmpty())
    }

    @Test fun `tout marquer lu visait les flux et le compte`() {
        fun row(feed: String?) =
            com.pressia.data.local.SyncActionEntity(articleId = "", feedId = feed, kind = "markAllRead")
        // Vue « Tous les articles » : ligne SANS flux = tout le compte. Elle était jetée sans
        // réseau, donc le marquage n'atteignait jamais le serveur et la synchro le rouvrait.
        val global = com.pressia.data.markAllReadPush(listOf(row(null)))
        assertTrue(global.global)
        assertTrue(global.feedIds.isEmpty())
        assertFalse(global.nothing)
        // Vue flux / dossier : la liste des flux, une seule fois chacun.
        val byFeeds = com.pressia.data.markAllReadPush(listOf(row("a"), row("b"), row("a")))
        assertEquals(listOf("a", "b"), byFeeds.feedIds)
        assertFalse(byFeeds.global)
        // Les deux côte à côte (tout le compte puis un flux) : les deux appels partent.
        val both = com.pressia.data.markAllReadPush(listOf(row(null), row("a")))
        assertEquals(listOf("a"), both.feedIds)
        assertTrue(both.global)
        // Ligne d'une version antérieure (flux blanc) : tout le compte aussi, pas un rejet.
        assertTrue(com.pressia.data.markAllReadPush(listOf(row(""))).global)
        // Aucune cible : rien à pousser (la ligne sera simplement vidée).
        assertTrue(com.pressia.data.markAllReadPush(emptyList()).nothing)
    }

    @Test fun `tout marquer lu Feedly passe par les identifiants`() {
        // Feedly accepte puis ignore ses marqueurs de masse (« feeds », « categories ») :
        // HTTP 200, corps vide, compteurs inchangés (constaté en direct sur un flux à 3
        // non-lus). Le marquage doit donc partir article par article, par paquets — sinon la
        // synchro suivante rouvre tout, le serveur faisant foi.
        val ids = (1..250).map { "entry-$it" }
        val payloads = feedlyMarkAllMarkers(ids)
        assertEquals(3, payloads.size)
        payloads.forEach {
            assertEquals("markAsRead", it.action)
            assertEquals("entries", it.type)
            assertNull(it.feedIds)
            assertNull(it.categoryIds)
        }
        assertEquals(ids, payloads.flatMap { it.entryIds!! })
        // Rien à marquer : aucun appel, pas un marqueur vide qui part dans le vide.
        assertTrue(feedlyMarkAllMarkers(emptyList()).isEmpty())
    }

    @Test fun `flux global Feedly exige un identifiant de compte`() {
        assertEquals("user/u1/category/global.all", feedlyGlobalStream("u1"))
        assertNull(feedlyGlobalStream(""))
        assertNull(feedlyGlobalStream("  "))
    }

    @Test fun `flux en retard a rattraper`() {
        // Le compte dit 13 non-lus, le local n'en connaît que 3 avec de la place : à remonter.
        assertEquals(
            listOf("mini"),
            com.pressia.data.laggingFeeds(
                mapOf("mini" to 13, "monde" to 104),
                mapOf("mini" to 3, "monde" to 104),
                mapOf("mini" to 3, "monde" to 500),
                keep = 1000
            )
        )
        // À jour ou au quota (la taille annulerait le rattrapage) : rien.
        assertTrue(
            com.pressia.data.laggingFeeds(
                mapOf("ok" to 5, "plein" to 99),
                mapOf("ok" to 5, "plein" to 0),
                mapOf("plein" to 1000),
                keep = 1000
            ).isEmpty()
        )
        // Sans aucune ligne locale : à rattraper aussi (l'appelant exclut ceux que la
        // découverte vient déjà de couvrir dans la même passe).
        assertEquals(
            listOf("vide"),
            com.pressia.data.laggingFeeds(mapOf("vide" to 7), emptyMap(), emptyMap(), keep = 1000)
        )
    }

    @Test fun `fenetre d'historique hors favoris`() {
        val now = cal(2026, java.util.Calendar.OCTOBER, 14)
        val cutoff = com.pressia.data.historyCutoffMs(now, 1) // 13 sept. 2026 (31 jours)
        fun ms(y: Int, m: Int, d: Int) = cal(y, m, d)
        val articles = listOf(
            art("recent", "f", published = ms(2026, java.util.Calendar.OCTOBER, 1)),
            art("vieux", "f", published = ms(2026, java.util.Calendar.JANUARY, 5)),
            art("vieux-star", "f", starred = true, published = ms(2025, java.util.Calendar.MARCH, 2)),
            art("vieux-later", "f", laterSaved = true, published = ms(2025, java.util.Calendar.MARCH, 2)),
            art("vieux-connu", "f", published = ms(2025, java.util.Calendar.MARCH, 2)),
            art("sans-date", "f")
        )
        val kept = com.pressia.data.withinHistory(articles, cutoff, setOf("vieux-connu"))
        assertEquals(listOf("recent", "vieux-star", "vieux-later", "vieux-connu", "sans-date"), kept.map { it.id })
        // Fenêtre nulle (sans limite) : tout passe, même le vieux.
        assertEquals(
            articles.map { it.id },
            com.pressia.data.withinHistory(articles, 0L, emptySet()).map { it.id }
        )
    }

    private fun cal(y: Int, m: Int, d: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.clear(); c.set(y, m, d, 12, 0, 0)
        return c.timeInMillis
    }

    @Test fun `plafond du quota par flux a l'import`() {
        val counts = mutableMapOf("f" to 1)
        // Quota de 2 avec 1 déjà compté : un seul inédit accepté, le suivant saute ;
        // déjà connu et étoile passent toujours (contenu rafraîchi, drapeau préservé).
        val kept = com.pressia.data.capByQuota(
            listOf(art("n1", "f"), art("n2", "f"), art("old", "f"), art("s", "f", starred = true)),
            counts, setOf("old"), keep = 2
        )
        assertEquals(listOf("n1", "old", "s"), kept.map { it.id })
        assertEquals(2, counts["f"])
        // Autre flux : compteur indépendant.
        val other = com.pressia.data.capByQuota(listOf(art("x", "g")), mutableMapOf(), emptySet(), keep = 2)
        assertEquals(listOf("x"), other.map { it.id })
    }

    @Test fun `publishedMs comprend secondes et millisecondes`() {
        assertEquals(1_760_000_000_000L, com.pressia.data.remote.publishedMs(1_760_000_000_000L))
        assertEquals(1_760_000_000_000L, com.pressia.data.remote.publishedMs(1_760_000_000L))
        assertEquals(0L, com.pressia.data.remote.publishedMs(0L))
        assertEquals(30L * 24 * 3600 * 1000, com.pressia.data.remote.RECONCILE_WINDOW_MS)
    }

    @Test fun `empreinte du tableau read later`() {
        val h = com.pressia.data.remote.readLaterHash(setOf("b", "a"))
        // Déterministe, insensible à l'ordre, SHA-256 hexadécimal.
        assertEquals(h, com.pressia.data.remote.readLaterHash(setOf("a", "b")))
        assertTrue(h.matches(Regex("[0-9a-f]{64}")))
        // Un ajout, un retrait ou le vide changent l'empreinte (sinon le saut raterait).
        assertNotEquals(h, com.pressia.data.remote.readLaterHash(setOf("a", "b", "c")))
        assertNotEquals(h, com.pressia.data.remote.readLaterHash(setOf("a")))
        assertNotEquals(h, com.pressia.data.remote.readLaterHash(emptySet()))
    }

    @Test fun `nom de miniature stable par url`() {
        val n1 = com.pressia.data.local.ThumbStore.thumbFileName("https://ex.com/a.jpg")
        val n2 = com.pressia.data.local.ThumbStore.thumbFileName("https://ex.com/a.jpg")
        val n3 = com.pressia.data.local.ThumbStore.thumbFileName("https://ex.com/b.jpg")
        // Même URL = même fichier (plusieurs articles partagent le téléchargement).
        assertEquals(n1, n2)
        assertNotEquals(n1, n3)
        assertTrue(n1.endsWith(".jpg"))
        // SHA-1 hexadécimal : pas de séparateur, pas de casse ambiguë.
        assertTrue(n1.dropLast(4).matches(Regex("[0-9a-f]{40}")))
    }

    @Test fun `sous-echantillonnage cale dans le gabarit`() {
        val s = com.pressia.data.local.ThumbStore.sampleSize(4000, 3000, 1080, 500)
        assertEquals(2, s)
        // Puissance de 2.
        assertTrue(s >= 1 && (s and (s - 1)) == 0)
        // Le décodé couvre encore le gabarit dans les deux dimensions (pas de pixels inventés
        // à la mise à l'échelle finale), mais un cran de plus ne le couvrirait plus.
        assertTrue(4000 / s >= 1080 && 3000 / s >= 500)
        assertTrue(4000 / (s * 2) < 1080 || 3000 / (s * 2) < 500)
        // Petite image : pas de réduction.
        assertEquals(1, com.pressia.data.local.ThumbStore.sampleSize(100, 100, 1080, 500))
        assertEquals(1, com.pressia.data.local.ThumbStore.sampleSize(0, 0, 1080, 500))
    }

    @Test fun `dimensions finales gardent le ratio`() {
        // Paysage : largeur du gabarit, hauteur proportionnelle.
        assertEquals(1080 to 500, com.pressia.data.local.ThumbStore.fitSize(2160, 1000, 1080, 500))
        // Portrait : hauteur du gabarit, largeur proportionnelle.
        assertEquals(250 to 500, com.pressia.data.local.ThumbStore.fitSize(1000, 2000, 1080, 500))
        // Plus petit que le gabarit : jamais agrandi (pas de pixels inventés).
        assertEquals(56 to 56, com.pressia.data.local.ThumbStore.fitSize(56, 56, 1080, 500))
    }

    @Test fun `favicon prefere l icone du compte puis le host`() {
        // Icône fournie par le compte : telle quelle.
        assertEquals(
            "https://example.com/icon.png",
            com.pressia.data.faviconUrlFor("https://example.com/icon.png", "https://www.frandroid.com/x", null, null)
        )
        // Sinon service Google sur le host (www. retiré).
        assertEquals(
            "https://www.google.com/s2/favicons?domain=frandroid.com&sz=128",
            com.pressia.data.faviconUrlFor(null, "https://www.frandroid.com/articles", null, null)
        )
        // Sans host exploitable : null (l'UI affiche l'initiale).
        assertNull(com.pressia.data.faviconUrlFor(null, null, null, "pas une url"))
    }

    private fun flags(
        id: String = "a", starred: Boolean = false, later: Boolean = false,
        cached: Boolean = false, unread: Boolean = true, readAt: Long? = null
    ) = com.pressia.data.local.ArticleFlags(id, starred, later, cached, unread, readAt)

    @Test fun `resync d un flux local garde l etat lu`() {
        // Le fichier du flux ne porte aucun état de lecture : sans préservation, chaque
        // synchronisation repassait en non lus tous les articles déjà lus.
        val incoming = com.pressia.data.local.ArticleEntity(id = "a", feedId = "feed", title = "T")
        val prev = flags(unread = false, readAt = 1234L)
        val kept = com.pressia.data.mergeIncomingArticle(incoming, prev, keepRead = true)
        assertFalse(kept.unread)
        assertEquals(1234L, kept.readAt)
        // Un article rouvert localement (unread = true) le reste aussi : l'article fait foi.
        val reopened = com.pressia.data.mergeIncomingArticle(incoming, flags(unread = true, readAt = null), keepRead = true)
        assertTrue(reopened.unread)
        assertNull(reopened.readAt)
    }

    @Test fun `resync d un compte cloud garde l etat distant`() {
        // Compte connecté : le serveur fait foi (la file montante a déjà été poussée avant).
        val incoming = com.pressia.data.local.ArticleEntity(id = "a", feedId = "feed", title = "T", unread = false)
        val kept = com.pressia.data.mergeIncomingArticle(incoming, flags(unread = true), keepRead = false)
        assertFalse(kept.unread)
    }

    @Test fun `resync garde etoiles read later et miniatures`() {
        val incoming = com.pressia.data.local.ArticleEntity(id = "a", feedId = "feed", title = "T")
        val kept = com.pressia.data.mergeIncomingArticle(
            incoming, flags(starred = true, later = true, cached = true), keepRead = false
        )
        assertTrue(kept.starred)
        assertTrue(kept.laterSaved)
        assertTrue(kept.imageCached)
        // Article inconnu : rien à reporter, la ligne arrive telle quelle.
        assertEquals(incoming, com.pressia.data.mergeIncomingArticle(incoming, null, keepRead = true))
    }

    @Test fun `images d un corps HTML resolues et dedupliquees`() {
        val html = """<p>texte</p><img src="/relatif.jpg"><img src="https://ex.com/abs.jpg">
            <img src="//cdn.ex.com/proto.jpg"><img src="https://ex.com/abs.jpg"><img src="data:image/png;base64,AAA">"""
        assertEquals(
            listOf("https://ex.com/relatif.jpg", "https://ex.com/abs.jpg", "https://cdn.ex.com/proto.jpg"),
            com.pressia.data.htmlImageUrls(html, "https://ex.com/article")
        )
        assertEquals(emptyList<String>(), com.pressia.data.htmlImageUrls(null, "https://ex.com"))
        assertEquals(emptyList<String>(), com.pressia.data.htmlImageUrls("   ", "https://ex.com"))
    }

    @Test fun `toutes les images d un article, miniature declaree puis corps`() {
        // La miniature déclarée par le flux vient en tête (c'est elle qui sert de bannière), puis
        // les images du corps : c'est cette liste qui est téléchargée à la synchronisation.
        assertEquals(
            listOf(
                "https://ex.com/miniature.jpg",
                "https://ex.com/corps-1.jpg",
                "https://ex.com/corps-2.jpg",
                "https://ex.com/corps-3.jpg"
            ),
            com.pressia.data.articleImageUrls(
                "https://ex.com/miniature.jpg",
                """<p>texte</p><img src="/corps-1.jpg"><img src="https://ex.com/corps-2.jpg">""",
                """<img src="/corps-3.jpg">""",
                "https://ex.com/a"
            )
        )
        // Une même URL (miniature reprise dans le corps) ne compte qu'une fois ; une miniature
        // relative ou absente est ignorée : elle ne se met pas en cache sous une clé d'URL.
        assertEquals(
            listOf("https://ex.com/thumb.jpg", "https://ex.com/corps-1.jpg"),
            com.pressia.data.articleImageUrls(
                "https://ex.com/thumb.jpg",
                """<img src="https://ex.com/thumb.jpg"><img src="https://ex.com/corps-1.jpg">""",
                null,
                "https://ex.com/a"
            )
        )
        assertEquals(listOf("https://ex.com/corps-1.jpg"), com.pressia.data.articleImageUrls("/miniature.jpg", """<img src="https://ex.com/corps-1.jpg">""", null, "https://ex.com/a"))
        assertEquals(emptyList<String>(), com.pressia.data.articleImageUrls(null, null, "  ", "https://ex.com/a"))
    }
}
