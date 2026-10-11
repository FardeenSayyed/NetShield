package io.netshield.app.vpn

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object NetShieldBlocklist {

    enum class Category {
        ADVERTISING, TRACKING, ADULT, AI_ADULT
    }

    @Volatile
    private var alwaysBlockedDomains: Set<String> = emptySet()

    private class TrieNode {
        val children: ConcurrentHashMap<String, TrieNode> = ConcurrentHashMap()
        @Volatile var isBlockedRoot: Boolean = false
    }

    private class CategoryTrie {
        val root = TrieNode()
        val enabled = AtomicBoolean(true)

        fun insert(domain: String) {
            val labels = splitReversed(domain)
            if (labels.isEmpty()) return
            var node = root
            for (label in labels) {
                node = node.children.getOrPut(label) { TrieNode() }
            }
            node.isBlockedRoot = true
        }

        fun matches(domain: String): Boolean {
            val labels = splitReversed(domain)
            if (labels.isEmpty()) return false
            var node = root
            for (label in labels) {
                val next = node.children[label] ?: return false
                node = next
                if (node.isBlockedRoot) return true
            }
            return false
        }
    }

    private fun splitReversed(domain: String): List<String> {
        val normalized = normalize(domain)
        if (normalized.isEmpty()) return emptyList()
        val labels = normalized.split('.')
        return if (labels.any { it.isEmpty() }) emptyList() else labels.asReversed()
    }

    fun normalize(domain: String): String {
        var clean = domain.trim().lowercase()
        if (clean.startsWith("https://")) clean = clean.removePrefix("https://")
        if (clean.startsWith("http://")) clean = clean.removePrefix("http://")
        if (clean.startsWith("www.")) clean = clean.removePrefix("www.")
        clean = clean.substringBefore('/')
        return clean.trimEnd('.')
    }

    private val tries: Map<Category, CategoryTrie> = mapOf(
        Category.ADVERTISING to CategoryTrie(),
        Category.TRACKING to CategoryTrie(),
        Category.ADULT to CategoryTrie(),
        Category.AI_ADULT to CategoryTrie()
    )

    private val adultTlds = setOf("adult", "porn", "sex", "xxx", "erotica")

    init {
        loadDefaultAdvertisingDomains()
        loadDefaultTrackingDomains()
        loadDefaultAdultDomains()
        loadDefaultAiAdultDomains()
    }

    private fun loadDefaultAdvertisingDomains() {
        val trie = tries.getValue(Category.ADVERTISING)
        setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "googleads.g.doubleclick.net", "mobileads.google.com",
            "adsafeprotected.com", "adnxs.com", "adsrvr.org", "advertising.com", "adform.net",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "zedo.com", "quantserve.com",
            "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "33across.com",
            "media.net", "smartadserver.com", "amazon-adsystem.com", "moatads.com", "adsymptotic.com",
            "adzerk.com", "an.facebook.com", "ads.tiktok.com", "ads.snapchat.com", "buysellads.com",
            "mgid.com", "revcontent.com", "adroll.com", "triplelift.com", "teads.tv", "indexww.com",
            "ads.msn.com", "rads.msn.com",
            "unity3d.com", "ads.unity3d.com", "unityads.unity3d.com", "auction.unityads.unity3d.com",
            "ironsrc.com", "supersonicads.com", "init.supersonicads.com",
            "applovin.com", "applvn.com", "a.applovin.com",
            "inmobi.com", "ads.inmobi.com", "config.inmobi.com",
            "pangle.io", "pangleglobal.com", "api-pangle.pangle.io",
            "mintegral.com", "mbridge.io", "cdn-adn.mbridge.io",
            "smaato.com", "smaato.net", "soma.smaato.net",
            "fyber.com", "inner-active.mobi",
            "startapp.com", "startappservice.com",
            "adcolony.com", "vungle.com", "chartboost.com", "leadbolt.com", "leadboltmobile.net",
            "popads.net", "popcash.net", "propellerads.com", "adcash.com"
        ).forEach { trie.insert(it) }
    }

    private fun loadDefaultTrackingDomains() {
        val trie = tries.getValue(Category.TRACKING)
        setOf(
            "google-analytics.com", "googletagmanager.com", "analytics.google.com", "app-measurement.com",
            "facebook.net", "connect.facebook.net", "hotjar.com", "hotjar.io", "segment.io", "segment.com",
            "mixpanel.com", "amplitude.com", "branch.io", "appsflyer.com", "adjust.com", "scorecardresearch.com",
            "analytics.twitter.com", "analytics.tiktok.com", "statcounter.com", "crazyegg.com", "optimizely.com",
            "newrelic.com", "datadoghq.com", "bugsnag.com", "sentry.io", "clarity.ms", "intercom.io", "onesignal.com",
            "kochava.com", "control.kochava.com", "singular.net", "sdk-api.singular.net",
            "braze.com", "appboy.com", "flurry.com", "data.flurry.com", "leanplum.com", "count.ly", "airpush.com"
        ).forEach { trie.insert(it) }
    }

    private fun loadDefaultAdultDomains() {
        val trie = tries.getValue(Category.ADULT)
        setOf(
            "pornhub.com", "phncdn.com", "pornhubpremium.com", "pornhub.net",
            "xvideos.com", "xv-phim.com", "xvideos-cdn.com", "xvideos2.com",
            "xnxx.com", "xnxx-cdn.com", "xnxx.tv",
            "xhamster.com", "xhamster.desi", "xhcdn.com", "xhamster20.com",
            "redtube.com", "youporn.com", "spankbang.com", "spankbang.club",
            "tube8.com", "tnaflix.com", "brazzers.com", "chaturbate.com", "cb.live",
            "stripchat.com", "onlyfans.com", "xhnetwork.com", "pussyspace.com",
            "eporner.com", "txxx.com", "hclips.com", "cam4.com", "bongacams.com",
            "livejasmin.com", "beeg.com", "thumbzilla.com", "faphouse.com",
            "porndig.com", "erome.com", "javmost.xyz", "javhd.com", "motherless.com", "porntrex.com", "daftsex.org", "hqporner.com",
            "sunporno.com", "vjav.com", "javtiful.com",
            "sex.com", "xlovecam.com", "youjizz.com", "heavy-r.com", "4chan.org",
            "321sex.com", "xkeezmovies.com", "perfectgirls.net", "myfreecams.com",
            "camwhores.tv", "imlive.com", "cams.com", "adultfriendfinder.com",
            "fetlife.com", "ashleymadison.com", "seeking.com", "loverslab.com", "promptchan.com",
            "multporn.net", "doujins.com", "hitomi.la", "luscious.net",
            "nhentai.net", "nhentai.xxx", "e-hentai.org", "exhentai.org", "pururin.to",
            "8muses.com", "8muses.xxx", "choochootoons.com", "simply-hentai.com",
            "hentai2read.com", "hentaicube.net", "asmhentai.com", "imhentai.xxx",
            "toonily.com", "toonily.me", "webtoon.xyz", "manhwa18.com", "manhwa18.cc",
            "readmanhwa.com", "3toon.com", "hentai.tv", "hentaigasm.com", "xlecx.one"
        ).forEach { trie.insert(it) }
    }

    private fun loadDefaultAiAdultDomains() {
        val trie = tries.getValue(Category.AI_ADULT)
        setOf(
            "character.ai",
            "janitorai.com",
            "janitor.ai",
            "crushon.ai",
            "spicychat.ai",
            "spicychat.com",
            "chub.ai",
            "venus.chub.ai",
            "yodayo.com",
            "harpy.chat",
            "poly.ai",
            "moemate.io",
            "soulmate.ai",
            "talkie-ai.com",
            "muah.ai",
            "joyland.ai",
            "Tavern.ai",
            "sillytavern.app",
            "mybabes.ai",
            "candy.ai",
            "dreamgf.ai",
            "ourdream.ai",
            "fantasygf.ai",
            "kupid.ai",
            "girlfriend.ai",
            "nsfwlover.com",
            "romance.ai",
            "animesoga.com",
            "charstar.ai",
            "hammerai.com",
            "botify.ai",
            "roleplai.app",
            "civitai.com",
            "tensor.art",
            "seaart.ai",
            "perchance.org",
            "pixai.art",
            "pornpen.ai",
            "soulgen.ai",
            "clothoff.io",
            "nudify.online",
            "undress.app",
            "rule34.xxx",
            "rule34.art",
            "e621.net",
            "gelbooru.com",
            "danbooru.donmai.us",
            "aiallure.com",
            "goloveai.com",
            "secretdesires.ai",
            "pornaigirlfriend.com",
            "secrets.ai",
            "gptgirlfriend.online",
            "lovescape.com"
        ).forEach { trie.insert(it) }
    }

    fun setCustomDomainRules(alwaysBlocked: Set<String>) {
        alwaysBlockedDomains = alwaysBlocked.map { normalize(it) }.filter { it.isNotBlank() }.toSet()
        Log.d("NetShieldBlocklist", "Updated custom blocked domains (${alwaysBlockedDomains.size} active)")
    }

    fun isCustomBlocked(domain: String): Boolean {
        val normalized = normalize(domain)
        if (normalized.isEmpty() || alwaysBlockedDomains.isEmpty()) return false
        return alwaysBlockedDomains.any { normalized == it || normalized.endsWith(".$it") }
    }

    fun shouldBlock(domain: String, checkAds: Boolean, checkAdult: Boolean): Boolean {
        val normalized = normalize(domain)
        if (normalized.isEmpty()) return false

        if (isCustomBlocked(normalized)) {
            Log.d("NetShieldEngine", "BLOCKED: Custom Rule matched for $normalized")
            return true
        }

        if (checkAds) {
            if (tries.getValue(Category.ADVERTISING).matches(normalized) ||
                tries.getValue(Category.TRACKING).matches(normalized)) {
                Log.d("NetShieldEngine", "BLOCKED: Ad/Tracker domain matched for $normalized")
                return true
            }
        }

        if (checkAdult) {
            val tld = normalized.substringAfterLast('.', "")
            if (adultTlds.contains(tld)) {
                Log.d("NetShieldEngine", "BLOCKED: Adult TLD matched for $normalized")
                return true
            }

            if (tries.getValue(Category.ADULT).matches(normalized) ||
                tries.getValue(Category.AI_ADULT).matches(normalized)) {
                Log.d("NetShieldEngine", "BLOCKED: Adult Domain matched for $normalized")
                return true
            }
        }

        return false
    }
}
