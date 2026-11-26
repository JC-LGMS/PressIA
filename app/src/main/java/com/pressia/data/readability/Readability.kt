package com.pressia.data.readability

import com.pressia.data.exceedsCap
import com.pressia.data.readCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** Extraction "article complet" façon Readability (déclenchée au double-tap quand le RSS n'est qu'un extrait). */
object Readability {
    /** Client partagé (page d'article, titre oEmbed) : délais globaux, dont un plafond total. */
    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14; PressIA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /**
     * Longueur minimale d'un corps extrait pour le considérer comme un article (et non comme un
     * mur anti-robot ou un bloc de navigation). Assez bas pour une brève, assez haut pour un
     * extrait vide.
     */
    private const val MIN_ARTICLE_CHARS = 120

    /** Plafond de la page récupérée pour l'extraction (voir `ThumbStore.readCapped`). */
    private const val MAX_PAGE_BYTES = 8 * 1024 * 1024
    private val NOISE = Regex("(?i)nav|header|footer|sidebar|comment|share|related|advert|cookie|menu|widget|subscribe|newsletter|popup|banner")

    /** Marqueurs de page "bloquée" : anti-robot, vérification JS, paywall brutal, Cloudflare… */
    private val BLOCKED_MARKERS = listOf(
        "couldn't load", "required part of this site", "browser extension", "network issues", "browser settings",
        "please verify you are a human", "verify you are human", "are you a robot",
        "just a moment", "attention required", "performing security verification",
        "enable javascript", "veuillez activer javascript", "javascript est désactivé",
        "accès refusé", "access denied", "forbidden", "anti-robot",
        "désactivez votre bloqueur", "bloqueur de publicité", "adblock",
        "captcha", "cloudflare", "datadome", "perimeterx", "kasada",
        "something went wrong", "une erreur est survenue"
    )

    /** Marqueurs de boilerplate paywall (page 200 mais corps = appels à l'abonnement). */
    private val PAYWALL_MARKERS = listOf(
        "réservée à nos abonnés", "réservé aux abonnés", "abonnez-vous", "déjà abonné",
        "subscribe to continue", "to continue reading", "subscribers only", "sign up to read",
        "register to continue", "create an account to continue",
        "mettez à jour ma cb", "votre paiement a échoué", "votre carte bancaire a expiré"
    )

    /** Marqueurs de mur de consentement (le texte extrait n'est que la bannière cookies). */
    private val CONSENT_MARKERS = listOf(
        "retirer votre consentement", "retirer votre accord", "retirer consentement",
        "accepter l'usage", "cookies et autres traceurs", "politique de protection des données",
        "prospection commerciale", "gestion de la relation client", "gestion de la relation client",
        "vous opposer à ces communications", "nous utilisons des cookies",
        "refuser et continuer", "gérer les options de consentement", "j'accepte tout",
        "before you continue to", "accept all cookies", "reject all", "manage your consent",
        "your privacy choices", "consent management"
    )

    sealed interface FullResult {
        data class Ok(val html: String) : FullResult
        /** Page récupérée mais extraction impossible (anti-robot, paywall, page 100 % JS). */
        data object Blocked : FullResult
        /** Échec réseau / HTTP. */
        data object Failed : FullResult
    }

    suspend fun fetchFullResult(url: String): FullResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext FullResult.Failed
                val body = r.body ?: return@withContext FullResult.Failed
                // Taille annoncée : échouer avant de lire (une page de 200 Mo n'a pas à défiler).
                if (exceedsCap(body.contentLength(), MAX_PAGE_BYTES)) return@withContext FullResult.Failed
                // Plafond : une page énorme (archive, image encodée, réponse sans fin) partait
                // entière en mémoire puis dans jsoup. Au-delà, l'extraction est un échec.
                val bytes = readCapped(body.byteStream(), MAX_PAGE_BYTES)
                    ?: return@withContext FullResult.Failed
                // Jsoup lit les octets : il applique le charset de l'en-tête, sinon celui déclaré
                // par la page (`<meta charset>`), sinon UTF-8. Convertir en texte avant lui
                // figeait un mauvais encodage.
                // Charset **nul** (et non vide) quand l'en-tête n'en annonce pas : jsoup refuse
                // une chaîne vide (`Must set charset arg…`) et l'extraction échouait alors sur
                // tout serveur qui n'annonce pas `; charset=…` (frandroid, numerama, debian…).
                val doc = Jsoup.parse(
                    java.io.ByteArrayInputStream(bytes),
                    body.contentType()?.charset()?.name(),
                    url
                )
                if (isBlockedPage(doc)) return@withContext FullResult.Blocked
                val extracted = extract(doc)
                val text = Jsoup.parse(extracted).text()
                if (isPaywalledText(text)) return@withContext FullResult.Blocked
                if (isConsentWallText(text)) return@withContext FullResult.Blocked
                // Extraction vide alors que la page est consistante = contenu inaccessible (JS/paywall).
                // Le seuil est bas (120) : une brève réelle fait parfois 150 caractères. On garde les
                // deux bornes de page (octets **et** texte) : une coquille JS lourde en balisage mais
                // pauvre en texte doit rester un blocage, pas un corps vide mis en cache.
                if (text.length < MIN_ARTICLE_CHARS && (bytes.size > 20_000 || doc.text().length > 2_000)) {
                    return@withContext FullResult.Blocked
                }
                FullResult.Ok(extracted)
            }
        } catch (_: Exception) { FullResult.Failed }
    }

    /** Vrai si le HTML ressemble à une page de blocage plutôt qu'à un article. */
    fun isBlockedPage(html: String): Boolean = isBlockedPage(Jsoup.parse(html))

    /** Variante qui réutilise un document déjà analysé (voir [extract]). */
    fun isBlockedPage(doc: org.jsoup.nodes.Document): Boolean {
        return try {
            val text = norm(doc.text())
            if (text.length > 3000) return false // un vrai article long n'est pas une page de blocage
            // Un **article court** qui parle de Cloudflare, d'un captcha ou d'un « accès refusé »
            // a des paragraphes ; un mur anti-robot n'en a pas. Sans ce test, un seul marqueur
            // suffisait à déclarer bloqué un vrai article de 2 500 caractères.
            if (doc.select("p").size >= 4 && text.length >= 500) return false
            BLOCKED_MARKERS.any { text.contains(it) }
        } catch (_: Exception) { false }
    }

    /** Vrai si un texte extrait n'est que du boilerplate paywall (pas de vrai corps d'article). */
    fun isPaywalledText(text: String): Boolean {
        // Même plancher que `isUsableFull` : une bannière courte (120–199 car.) doit être
        // détectée, sinon elle était acceptée comme corps d'article et mise en cache.
        if (text.length < MIN_ARTICLE_CHARS) return false // trop court pour juger
        val low = norm(text)
        return PAYWALL_MARKERS.count { low.contains(it) } >= 3
    }

    /** Vrai si le texte extrait est surtout une bannière de consentement (cookies / RGPD). */
    fun isConsentWallText(text: String): Boolean {
        if (text.length < MIN_ARTICLE_CHARS) return false
        val low = norm(text)
        return CONSENT_MARKERS.count { low.contains(it) } >= 3
    }

    /** Normalisation : minuscules + apostrophes typographiques → ASCII. */
    fun norm(s: String): String =
        s.lowercase().replace(Regex("[’‘‚‛`´]"), "'")

    /** Un contenu complet en cache est-il réutilisable (ni vide, ni blocage, ni boilerplate paywall) ? */
    fun isUsableFull(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        if (isBlockedPage(html)) return false
        val text = try { Jsoup.parse(html).text() } catch (_: Exception) { return false }
        return text.length >= MIN_ARTICLE_CHARS && !isPaywalledText(text) && !isConsentWallText(text)
    }

    /** Le vrai corps d'article porte souvent une classe explicite : on le cherche d'abord. */
    private val CONTENT_SELECTORS = listOf(
        "div.article-content", "div.entry-content", "div.post-content", "div.post-body",
        "div.story-content", "div.field--name-body", "[itemprop=articleBody]",
        // Next : le corps est dans #next-single-post ; son conteneur `article` embarque aussi les
        // commentaires, qui gonflaient l'extrait (37 810 car. contre 4 811 pour le vrai corps).
        "#next-single-post"
    )

    /**
     * Premier message d'un fil de forum : le sujet **est** ce message, les réponses qui suivent
     * ne sont que des commentaires. Elles sont souvent plus longues et gagnaient le score
     * générique — « News fr » (Invision) affichait une réponse à la place de la news.
     */
    private val FIRST_POST_SELECTORS = listOf(
        "div.post[id^=post-]",     // Invision Power Board 3 (logic-sunrise)
        "article.ipsComment",      // Invision 4
        "article.message",         // XenForo
        "div.postbody",            // phpBB
        "div.cooked"               // Discourse
    )

    /** Balises de bloc : tout le reste est en ligne (texte, liens, images, `<br>`…). */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "nav", "aside",
        "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody", "tr", "td", "th",
        "blockquote", "pre", "figure", "figcaption", "hr", "h1", "h2", "h3", "h4", "h5",
        "h6", "form", "fieldset"
    )

    /** Conteneurs dont le texte nu doit devenir des paragraphes (pas `p`, `li`, titres…). */
    private val STRUCTURAL_TAGS = setOf(
        "div", "section", "article", "main", "header", "footer", "nav", "aside",
        "td", "th", "dl", "dt", "dd", "figure", "fieldset"
    )

    /** Un bloc « contenu » ne doit jamais être écarté comme bruit (ex. `article-content--has-sidebar`). */
    private val CONTENT_HINT = Regex("(?i)article|content|post-body|entry")

    fun extract(html: String, baseUri: String): String = extract(Jsoup.parse(html, baseUri))

    /** Variante qui réutilise un document déjà analysé (voir [fetchFullResult]). */
    fun extract(doc: org.jsoup.nodes.Document): String {
        doc.select("script, style, noscript, iframe[src*=ads], form, aside, nav, header, footer").remove()
        // 1) conteneur de contenu explicite (WordPress & co) : évite qu'une newsletter ou un
        // formulaire (texte « consentement ») gagne le score générique, comme sur Frandroid.
        val explicit = CONTENT_SELECTORS.asSequence()
            .flatMap { doc.select(it) }
            .filter { it.select("p").size >= 2 || it.text().length >= 300 }
            .maxByOrNull { it.select("p").size * 20 + it.text().length / 100 }
        if (explicit != null) return cleaned(explicit)
        // 1 bis) premier message d'un forum (voir FIRST_POST_SELECTORS). Pris avant le score
        // générique : les réponses, plus longues, l'emportaient sur le message qui porte le sujet.
        val firstPost = FIRST_POST_SELECTORS.asSequence()
            .mapNotNull { sel -> doc.select(sel).firstOrNull { it.text().length >= 300 } }
            .firstOrNull()
        if (firstPost != null) return cleaned(firstPost)
        // 2) repli générique : bloc avec le plus de <p> significatifs. Le comptage se fait en
        // **un seul parcours** de <p> et de <a>, accumulé sur leurs ancêtres : réinterroger le
        // sous-arbre de chaque `div` coûtait O(n²) sur une page longue.
        val candidates = doc.select("article, main, div, section")
        val candidateSet = candidates.toHashSet()
        val pCount = HashMap<org.jsoup.nodes.Element, Int>()
        val pLen = HashMap<org.jsoup.nodes.Element, Int>()
        val aCount = HashMap<org.jsoup.nodes.Element, Int>()
        doc.select("p").forEach { p ->
            val len = p.text().length
            var e = p.parent()
            while (e != null) {
                if (e in candidateSet) {
                    pCount[e] = (pCount[e] ?: 0) + 1
                    pLen[e] = (pLen[e] ?: 0) + len
                }
                e = e.parent()
            }
        }
        val aLen = HashMap<org.jsoup.nodes.Element, Int>()
        doc.select("a").forEach { a ->
            val len = a.text().length
            var e = a.parent()
            while (e != null) {
                if (e in candidateSet) {
                    aCount[e] = (aCount[e] ?: 0) + 1
                    aLen[e] = (aLen[e] ?: 0) + len
                }
                e = e.parent()
            }
        }
        // Densité de liens calculée depuis les compteurs déjà accumulés : reparcourir le
        // sous-arbre de chaque candidat (`el.text()`, `el.select("a")`) était O(n²).
        fun density(el: org.jsoup.nodes.Element): Double {
            val text = pLen[el] ?: 0
            if (text <= 0) return 1.0
            return ((aLen[el] ?: 0).toDouble() / text).coerceIn(0.0, 1.0)
        }
        var best: org.jsoup.nodes.Element? = null; var bestScore = 0
        // Repli : un article fait surtout de liens (revue de liens) serait écarté par le seuil
        // de densité ; on garde le meilleur candidat « classique » pour ce cas.
        var fallback: org.jsoup.nodes.Element? = null; var fallbackScore = 0
        for (el in candidates) {
            val cls = el.className() + " " + el.id()
            if (NOISE.containsMatchIn(cls) && !CONTENT_HINT.containsMatchIn(cls)) continue
            val ps = pCount[el] ?: 0
            if (ps < 2) continue
            val score = ps * 20 + (pLen[el] ?: 0) / 100 - (aCount[el] ?: 0) * 10
            if (score > fallbackScore) { fallbackScore = score; fallback = el }
            // Plus de la moitié du texte en liens = liste de titres, encart « à la une »,
            // sommaire… jamais le corps d'un article. C'est ce qui faisait gagner la colonne
            // « derniers articles » d'un site (titres courts mais nombreux) contre l'article.
            if (density(el) > 0.5) continue
            if (score > bestScore) { bestScore = score; best = el }
        }
        val target = best ?: fallback ?: doc.body() ?: return doc.outerHtml()
        // Un <article> du document qui porte au moins la moitié du texte du gagnant est le
        // corps : un volet de connexion masqué en CSS (10 <p> courts, cas p-nintendo) ou une
        // colonne « derniers articles » ne doivent pas le battre. Un <article> rangé dans une
        // zone de commentaires ou de contenus liés est écarté.
        val targetLen = target.text().length
        val semantic = doc.select("article")
            .filter { it.text().length * 2 >= targetLen }
            .filter { linkDensity(it) <= 0.5 }
            .filter { a -> a.parents().none { p -> NOISE.containsMatchIn(p.className() + " " + p.id()) } }
            .maxByOrNull { it.text().length }
        return cleaned(semantic ?: target)
    }

    /** Part du texte portée par des liens (calcul direct, pour le choix de l'`article`). */
    private fun linkDensity(el: org.jsoup.nodes.Element): Double {
        val text = el.text().length
        if (text <= 0) return 1.0
        val linked = el.select("a").sumOf { it.text().length }
        return (linked.toDouble() / text).coerceIn(0.0, 1.0)
    }

    /**
     * Les forums et certains sites écrivent le corps en texte nu dans des `<div>` (BBCode
     * d'Invision, `<div>texte<br>…</div>` de p-nintendo) : le rendu ne garde que les blocs
     * (`p`, `li`, médias…) et perdrait ce texte. Les nœuds en ligne sont regroupés en
     * paragraphes, à leur place, sans toucher aux blocs ni à leurs enfants.
     */
    private fun paragraphsFromInline(root: org.jsoup.nodes.Element) {
        val containers = (root.select("*").toList() + root)
            .filter { it.tagName().lowercase() in STRUCTURAL_TAGS }
            .asReversed()
        for (c in containers) {
            fun inline(n: org.jsoup.nodes.Node): Boolean =
                n is org.jsoup.nodes.TextNode ||
                    (n is org.jsoup.nodes.Element && n.tagName().lowercase() !in BLOCK_TAGS)
            val rebuilt = mutableListOf<org.jsoup.nodes.Node>()
            var run = mutableListOf<org.jsoup.nodes.Node>()
            fun flush() {
                if (run.any { it is org.jsoup.nodes.TextNode && it.text().isNotBlank() }) {
                    val p = org.jsoup.nodes.Element("p")
                    run.forEach { p.appendChild(it) }
                    rebuilt += p
                } else {
                    rebuilt += run
                }
                run = mutableListOf()
            }
            for (n in c.childNodes().toList()) {
                if (inline(n)) run += n else { flush(); rebuilt += n }
            }
            flush()
            c.empty()
            rebuilt.forEach { c.appendChild(it) }
        }
    }

    private fun cleaned(target: org.jsoup.nodes.Element): String {
        // Bruit **interne** au bloc retenu : le corps peut englober la section des commentaires
        // (p-nintendo en fin d'article) ou un encart de partage. Le filtre de bruit ne servait
        // qu'au choix du bloc, pas à son contenu.
        target.select("div, section, aside").forEach { el ->
            val cls = el.className() + " " + el.id()
            if (NOISE.containsMatchIn(cls) && !CONTENT_HINT.containsMatchIn(cls)) el.remove()
        }
        paragraphsFromInline(target)
        target.select("img").forEach { it.attr("loading", "lazy") }
        // absolutiser
        target.select("img[src]").forEach { it.attr("src", it.absUrl("src")) }
        target.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
        target.select("[style]").removeAttr("style")
        return "<div class='pressia-full'>${target.html()}</div>"
    }
}
