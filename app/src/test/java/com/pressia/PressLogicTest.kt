package com.pressia

import com.pressia.data.favScopeArg
import com.pressia.ui.isSyncFresh
import com.pressia.ui.parseArticleActions
import com.pressia.data.local.PrefEntry
import com.pressia.data.local.Prefs
import com.pressia.data.local.ThumbStore
import com.pressia.data.remote.OAuth
import com.pressia.data.remote.reconcileUnchanged
import com.pressia.data.remote.dedupHash
import com.pressia.data.remote.LocalRssFetcher
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
    @Test fun `rss parse extrait titre lien image`() {
        val xml = """<rss><channel><item><title>T1</title><link>https://ex.com/1</link><description><![CDATA[hello <img src="https://ex.com/i.jpg"/>]]></description></item></channel></rss>"""
        val arts = LocalRssFetcher.parse(xml, "feed1")
        assertEquals(1, arts.size)
        assertEquals("T1", arts[0].title)
        assertEquals("https://ex.com/i.jpg", arts[0].imageUrl)
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
        // Aucun secret ne doit être doublement classé, sinon les deux filtres se contredisent.
        assertTrue(com.pressia.data.SECRET_PREFS.intersect(com.pressia.data.NON_EXPORTED_PREFS).isEmpty())
    }

    @Test fun `entrees de preferences sans doublon et sans trou connu`() {
        val names = entryNames()
        assertEquals("un nom de préférence ne doit être déclaré qu'une fois", names.size, names.toSet().size)
        // last_sync_at est un Long : sans LongPref il partait dans la sauvegarde mais en sortait
        // toujours, et la restauration déclenchait une synchronisation inutile.
        assertTrue("last_sync_at" in names)
        assertTrue(Prefs.ENTRIES.any { it is PrefEntry.LongPref && it.name == "last_sync_at" })
        // Les trois OAuth / comptes que l'écran de connexion renseigne doivent rester exportables
        // (leur secret est filtré séparément, leur identifiant public est destiné à être partagé).
        listOf("inoreader_app_id", "feedly_client_id", "accounts", "active_account").forEach {
            assertTrue("$it doit figurer dans ENTRIES", it in names)
        }
    }

    private fun entryNames(): Set<String> = Prefs.ENTRIES.map { it.name }.toSet()

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

    @Test fun `ajout et rafraichissement de jeton`() {
        val one = listOf(com.pressia.data.CloudAccount("feedly#aa", "feedly", "t1", "moi@ex.com"))
        // Même jeton : on ne duplique pas, on met juste à jour le nom.
        val same = com.pressia.data.AccountStore.refreshToken(one, "feedly", "t1", "autre@ex.com")
        assertEquals(1, same.size)
        assertEquals("autre@ex.com", same[0].name)
        // Nouveau jeton du même service : un second compte distinct, l'ancien est conservé.
        val two = com.pressia.data.AccountStore.refreshToken(same, "feedly", "t2", "pro@ex.com")
        assertEquals(2, two.size)
        assertEquals(2, two.count { it.service == "feedly" })
        assertNotEquals(two[0].id, two[1].id)
        // upsert remplace la ligne de même identifiant.
        val replaced = com.pressia.data.AccountStore.upsert(two, two[0].copy(name = "renomme"))
        assertEquals(2, replaced.size)
        assertEquals("renomme", replaced.first { it.id == two[0].id }.name)
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

    @Test fun `publishedMs comprend secondes et millisecondes`() {
        assertEquals(1_760_000_000_000L, com.pressia.data.remote.publishedMs(1_760_000_000_000L))
        assertEquals(1_760_000_000_000L, com.pressia.data.remote.publishedMs(1_760_000_000L))
        assertEquals(0L, com.pressia.data.remote.publishedMs(0L))
        assertEquals(30L * 24 * 3600 * 1000, com.pressia.data.remote.RECONCILE_WINDOW_MS)
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
}
