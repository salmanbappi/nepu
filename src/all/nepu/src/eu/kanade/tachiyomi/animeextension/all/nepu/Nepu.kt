package eu.kanade.tachiyomi.animeextension.all.nepu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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

    override val id: Long = 5181466391484419855L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/discovery/page/$page", headers)

    override fun popularAnimeSelector(): String = "div#archive-content article, div.items article, div.grid div.item, .movie-item, .anime-item, article.item, article.w_item_a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst("h2, h3, .title, .name")?.text() 
            ?: element.selectFirst("img")?.attr("alt")
            ?: link.attr("title") 
            ?: ""
        val img = element.selectFirst("img")
        thumbnail_url = img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }?.ifEmpty { img.attr("abs:data-lazy-src") } ?: ""
    }

    override fun popularAnimeNextPageSelector(): String? = "nav a:contains(Next), .pagination a[rel=next], a.next, div.resppages > a > span.fa-chevron-right"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/new-releases/page/$page", headers)

    override fun latestUpdatesSelector(): String = "div.content article, " + popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            return GET("$baseUrl/page/$page/?s=$query", headers)
        }

        val listingFilter = filters.filterIsInstance<ListingFilter>().firstOrNull()
        if (listingFilter != null && listingFilter.state != 0) {
            val path = listingFilter.toPath()
            return GET("$baseUrl/$path/page/$page", headers)
        }

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        if (genreFilter != null && genreFilter.state != 0) {
            val genreSlug = genreFilter.toSlug()
            return GET("$baseUrl/category/$genreSlug/page/$page", headers)
        }

        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        if (typeFilter != null && typeFilter.state != 0) {
            val typeSlug = typeFilter.toSlug()
            return GET("$baseUrl/$typeSlug/page/$page", headers)
        }

        return popularAnimeRequest(page)
    }

    override fun searchAnimeSelector(): String = "div.result-item article, div.result-item, " + popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(searchAnimeRequest(page, query, filters)).execute()
        val pageResults = searchAnimeParse(response)

        val filteredList = if (query.isNotEmpty()) {
            pageResults.animes.sortedByDescending {
                diceCoefficient(it.title.lowercase(), query.lowercase())
            }
        } else {
            pageResults.animes
        }

        return AnimesPage(filteredList, pageResults.hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val sheader = document.selectFirst("div.sheader")
        title = sheader?.selectFirst("div.data > h1")?.text() ?: document.selectFirst("h1.title, .entry-title, .m-title")?.text() ?: ""
        description = document.selectFirst("div#info p, .description, .entry-content p, .storyline")?.text()
        genre = document.select("div.sgeneros a, .genres a, .entry-content .genre a, .ganre-wrapper a").joinToString { it.text() }
        status = SAnime.UNKNOWN
        thumbnail_url = sheader?.selectFirst("div.poster img")?.attr("abs:src") ?: document.selectFirst(".poster img")?.attr("abs:src") ?: ""
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "ul.episodios li, .list-episodes a, .ep-item, .episode-item"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        val epTitle = element.selectFirst("span, .name, .ep-title")?.text() ?: element.text()
        name = epTitle.trim().ifEmpty { "Episode 1" }
        episode_number = parseEpisodeNumber(name)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select("div#seasons > div")
        return if (seasons.isEmpty()) {
            super.episodeListParse(response)
        } else {
            seasons.flatMap { season ->
                val seasonName = season.selectFirst("span.se-t")?.text() ?: ""
                season.select(episodeListSelector()).map { element ->
                    episodeFromElement(element).apply {
                        name = if (seasonName.isNotEmpty()) "$seasonName - $name" else name
                    }
                }
            }.reversed()
        }
    }

    private fun parseEpisodeNumber(text: String): Float {
        return Regex("""(?i)(?:Episode|Ep|E|Vol|Temporada)\.?\s*(\d+(\.\d+)?)""").find(text)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
    }

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "div.source-box iframe, iframe, .player-iframe"

    override fun videoFromElement(element: Element): Video {
        val url = element.attr("abs:src").ifEmpty { element.attr("abs:data-src") }
        return Video(url, "Video", url)
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        ListingFilter(),
        AnimeFilter.Separator(),
        TypeFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
    )

    private class ListingFilter : AnimeFilter.Select<String>(
        "Listing",
        arrayOf("Default", "Discovery", "New Releases", "Latest Movies", "Latest TV Shows", "Trending")
    ) {
        fun toPath() = when (state) {
            1 -> "discovery"
            2 -> "new-releases"
            3 -> "movies"
            4 -> "tvshows"
            5 -> "trending"
            else -> ""
        }
    }

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Movies", "TV Shows")
    ) {
        fun toSlug() = when (state) {
            1 -> "movies"
            2 -> "tvshows"
            else -> ""
        }
    }

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRES.map { it.first }.toTypedArray()
    ) {
        fun toSlug() = GENRES[state].second
    }

    // ============================== Utils ==============================

    private fun diceCoefficient(s1: String, s2: String): Double {
        val n1 = s1.length
        val n2 = s2.length
        if (n1 == 0 || n2 == 0) return 0.0
        val bigrams1 = HashSet<String>()
        for (i in 0 until n1 - 1) bigrams1.add(s1.substring(i, i + 2))
        var intersection = 0
        for (i in 0 until n2 - 1) {
            val bigram = s2.substring(i, i + 2)
            if (bigrams1.contains(bigram)) intersection++
        }
        return (2.0 * intersection) / (n1 + n2 - 2).coerceAtLeast(1)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    companion object {
        private val GENRES = arrayOf(
            "All" to "",
            "Action" to "action",
            "Adventure" to "adventure",
            "Animation" to "animation",
            "Anime" to "anime",
            "Comedy" to "comedy",
            "Crime" to "crime",
            "Documentary" to "documentary",
            "Drama" to "drama",
            "Family" to "family",
            "Fantasy" to "fantasy",
            "History" to "history",
            "Horror" to "horror",
            "Music" to "music",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "Science Fiction" to "sci-fi",
            "Thriller" to "thriller",
            "TV Movie" to "tv-movie",
            "War" to "war",
            "Western" to "western"
        )
    }
}
