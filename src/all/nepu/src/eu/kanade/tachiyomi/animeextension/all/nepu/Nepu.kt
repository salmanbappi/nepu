package eu.kanade.tachiyomi.animeextension.all.nepu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Nepu : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Nepu"

    override val baseUrl = "https://nepu.to"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419848L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(Injekt.get()))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.grid div.item, div.items article, .movie-item, .anime-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("h2, h3, .title, .name")?.text() ?: link.attr("title") ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")?.ifEmpty { element.selectFirst("img")?.attr("abs:data-src") } ?: ""
    }

    override fun popularAnimeNextPageSelector(): String = "nav a:contains(Next), .pagination a[rel=next]"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-updates?page=$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?q=$query&page=$page", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1.title, .entry-title")?.text() ?: ""
        description = document.selectFirst(".description, .entry-content p")?.text()
        genre = document.select(".genres a, .entry-content .genre a").joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "ul.episodios li, .list-episodes a, .ep-item"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        name = element.text().trim().ifEmpty { "Episode 1" }
        episode_number = name.filter { it.isDigit() }.toFloatOrNull() ?: 1f
    }

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "div.source-box iframe, iframe"

    override fun videoFromElement(element: Element): Video {
        val url = element.attr("abs:src")
        return Video(url, "Video", url)
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
