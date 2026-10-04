package com.pressia.data.readability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/** Extraction "article complet" façon Readability (déclenchée au double-tap quand le RSS n'est qu'un extrait). */
object Readability {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14; PressIA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
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

    suspend fun fetchFull(url: String): String? = withContext(Dispatchers.IO) {
        when (val r = fetchFullResult(url)) {
            is FullResult.Ok -> r.html
            else -> null
        }
    }

    suspend fun fetchFullResult(url: String): FullResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.5")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Referer", "https://www.google.com/")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext FullResult.Failed
                val html = r.body?.string() ?: return@withContext FullResult.Failed
                if (isBlockedPage(html)) return@withContext FullResult.Blocked
                val extracted = extract(html, url)
                val text = Jsoup.parse(extracted).text()
                if (isPaywalledText(text)) return@withContext FullResult.Blocked
                if (isConsentWallText(text)) return@withContext FullResult.Blocked
                // Extraction vide alors que la page est consistante = contenu inaccessible (JS/paywall)
                if (text.length < 200 && html.length > 20_000) {
                    return@withContext FullResult.Blocked
                }
                FullResult.Ok(extracted)
            }
        } catch (_: Exception) { FullResult.Failed }
    }

    /** Vrai si le HTML ressemble à une page de blocage plutôt qu'à un article. */
    fun isBlockedPage(html: String): Boolean {
        return try {
            val text = norm(Jsoup.parse(html).text())
            if (text.length > 3000) return false // un vrai article long n'est pas une page de blocage
            BLOCKED_MARKERS.any { text.contains(it) }
        } catch (_: Exception) { false }
    }

    /** Vrai si un texte extrait n'est que du boilerplate paywall (pas de vrai corps d'article). */
    fun isPaywalledText(text: String): Boolean {
        if (text.length < 300) return false // trop court pour juger (extraits RSS légitimes)
        val low = norm(text)
        return PAYWALL_MARKERS.count { low.contains(it) } >= 3
    }

    /** Vrai si le texte extrait est surtout une bannière de consentement (cookies / RGPD). */
    fun isConsentWallText(text: String): Boolean {
        if (text.length < 200) return false
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
        return text.length >= 200 && !isPaywalledText(text) && !isConsentWallText(text)
    }

    fun extract(html: String, baseUri: String): String {
        val doc = Jsoup.parse(html, baseUri)
        doc.select("script, style, noscript, iframe[src*=ads], form, aside, nav, header, footer").remove()
        // candidat : bloc avec le plus de <p> significatifs
        var best: org.jsoup.nodes.Element? = null; var bestScore = 0
        for (el in doc.select("article, main, div, section")) {
            if (NOISE.containsMatchIn(el.className() + el.id())) continue
            val ps = el.select("p")
            val textLen = ps.sumOf { it.text().length }
            val score = ps.size * 20 + textLen / 100 - el.select("a").size * 10
            if (ps.size >= 2 && score > bestScore) { bestScore = score; best = el }
        }
        val target = best ?: doc.body() ?: return html
        target.select("img").forEach { it.attr("loading", "lazy") }
        // absolutiser
        target.select("img[src]").forEach { it.attr("src", it.absUrl("src")) }
        target.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
        target.select("[style]").removeAttr("style")
        return "<div class='pressia-full'>${target.html()}</div>"
    }
}
