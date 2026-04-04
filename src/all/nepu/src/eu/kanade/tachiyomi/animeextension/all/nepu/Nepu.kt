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
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.3 Aniyomi/0.15.3")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val path = if (page == 1) "discovery" else "discovery/page/$page"
        return GET("$baseUrl/$path/", headers) // Added trailing slash for stability
    }

    override fun popularAnimeSelector(): String = ".list-movie, .list-episode, .jws-post-wrapper, .movie-item, .anime-item, .item, .w_item_a, .post-item, article.post"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = element.selectFirst("a") ?: element
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst(".list-title, .jws-post-title, h2, h3, .title, .name")?.text() 
            ?: element.selectFirst("img")?.attr("alt")
            ?: link.attr("title") 
            ?: ""
        
        val styleElement = element.selectFirst("[style*='url(']") ?: element.selectFirst(".media, .list-media, .poster, .thumb") ?: element
        val style = styleElement.attr("style") ?: ""
        thumbnail_url = if (style.contains("url(")) {
            style.substringAfter("url(").substringBefore(")")
                .replace("'", "")
                .replace("\"", "")
                .trim()
                .let { if (it.startsWith("http")) it else if (it.startsWith("//")) "https:$it" else "$baseUrl$it" }
        } else {
            val img = element.selectFirst("img")
            img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }?.ifEmpty { img.attr("abs:data-lazy-src") } ?: ""
        }
    }

    override fun popularAnimeNextPageSelector(): String? = ".pagination a.next, a.next, .next.page-numbers"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val path = if (page == 1) "new-releases" else "new-releases/page/$page"
        return GET("$baseUrl/$path/", headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotEmpty()) {
            val homeResponse = client.newCall(GET(baseUrl, headers)).execute()
            val homeDoc = homeResponse.asJsoup()
            val token = homeDoc.selectFirst("input[name=_TOKEN]")?.attr("value")
                ?: throw Exception("Failed to find search token")

            val searchBody = okhttp3.FormBody.Builder()
                .add("_TOKEN", token)
                .add("_ACTION", "search")
                .add("q", query)
                .build()

            val searchRequest = Request.Builder()
                .url("$baseUrl/search")
                .post(searchBody)
                .headers(headers)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", "$baseUrl/")
                .build()

            val response = client.newCall(searchRequest).execute()
            val pageResults = searchAnimeParse(response)

            val filteredList = pageResults.animes.sortedByDescending {
                diceCoefficient(it.title.lowercase(), query.lowercase())
            }

            return AnimesPage(filteredList, pageResults.hasNextPage)
        }

        val listingFilter = filters.filterIsInstance<ListingFilter>().firstOrNull()
        if (listingFilter != null && listingFilter.state != 0) {
            val path = listingFilter.toPath()
            val response = client.newCall(GET("$baseUrl/$path/page/$page", headers)).execute()
            return searchAnimeParse(response)
        }

        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        if (genreFilter != null && genreFilter.state != 0) {
            val genreSlug = genreFilter.toSlug()
            val response = client.newCall(GET("$baseUrl/category/$genreSlug/page/$page", headers)).execute()
            return searchAnimeParse(response)
        }

        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        if (typeFilter != null && typeFilter.state != 0) {
            val typeSlug = typeFilter.toSlug()
            val response = client.newCall(GET("$baseUrl/$typeSlug/page/$page", headers)).execute()
            return searchAnimeParse(response)
        }

        val response = client.newCall(popularAnimeRequest(page)).execute()
        return searchAnimeParse(response)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val sheader = document.selectFirst("div.sheader, div.detail-content")
        title = sheader?.selectFirst("div.data > h1, div.caption h1")?.text() 
            ?: document.selectFirst("h1.title, .entry-title, .m-title, .jws-post-title, h1")?.text() ?: ""
        description = document.selectFirst("div#info p, .description, .entry-content p, .storyline, #edit-2, div.detail div.text")?.text()
        genre = document.select("div.sgeneros a, .genres a, .entry-content .genre a, .ganre-wrapper a, div.video-attr:contains(Genre) a").joinToString { it.text() }
        status = SAnime.UNKNOWN
        thumbnail_url = sheader?.selectFirst("[style*='url('], div.poster img, .media-poster, .media-cover")?.let {
            val style = it.attr("style")
            if (style.contains("url(")) {
                style.substringAfter("url(").substringBefore(")")
                    .replace("'", "")
                    .replace("\"", "")
                    .trim()
                    .let { url -> if (url.startsWith("http")) url else if (url.startsWith("//")) "https:$url" else "$baseUrl$url" }
            } else {
                it.attr("abs:src")
            }
        } ?: document.selectFirst(".poster img")?.attr("abs:src") ?: ""
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "ul.episodios li, .list-episodes a, .ep-item, .episode-item, .episodes.tab-content a"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("href"))
        val epTitle = element.selectFirst("span, .name, .ep-title, .episode")?.text() ?: element.text()
        name = epTitle.trim().ifEmpty { "Episode 1" }
        episode_number = parseEpisodeNumber(name)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select("div#seasons > div, div.season-list div.tab-pane")
        return if (seasons.isEmpty()) {
            doc.select(episodeListSelector()).map { episodeFromElement(it) }
        } else {
            seasons.flatMap { season ->
                val seasonId = season.attr("id")
                val seasonName = doc.selectFirst("a[href='#$seasonId']")?.text() 
                    ?: season.selectFirst("span.se-t")?.text() 
                    ?: ""
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
        arrayOf("Discovery", "Trending Now", "Latest Movies", "Latest TV Shows", "New Releases")
    ) {
        fun toPath() = when (state) {
            0 -> "discovery"
            1 -> "trending"
            2 -> "movies"
            3 -> "tvshows"
            4 -> "new-releases"
            else -> "discovery"
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
