package eu.kanade.tachiyomi.extension.all.imhentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable
import uy.kohesive.injekt.injectLazy

class IMHentai(override val lang: String, private val imhLang: String) : ParsedHttpSource() {

    private val pageLoadHeaders: Headers = Headers.Builder().apply {
        add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        add("X-Requested-With", "XMLHttpRequest")
    }.build()

    override val baseUrl: String = "https://imhentai.xxx"
    override val name: String = "IMHentai"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // Popular

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            thumbnail_url = element.select(".inner_thumb img").attr("src")
            with(element.select(".caption a")) {
                url = this.attr("href")
                title = this.text()
            }
        }
    }

    override fun popularMangaNextPageSelector(): String = ".pagination li a:contains(Next):not([tabindex])"

    override fun popularMangaSelector(): String = ".thumbs_container .thumb"

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", getFilterList(SORT_ORDER_POPULAR))

    // Latest

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = searchMangaRequest(page, "", getFilterList(SORT_ORDER_LATEST))

    override fun latestUpdatesSelector(): String = popularMangaSelector()

    // Search

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("id:")) {
            val id = query.substringAfter("id:")
            return client.newCall(GET("$baseUrl/gallery/$id/"))
                .asObservableSuccess()
                .map { response ->
                    val manga = mangaDetailsParse(response)
                    manga.url = "/gallery/$id/"
                    MangasPage(listOf(manga), false)
                }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    private fun toBinary(boolean: Boolean) = if (boolean) "1" else "0"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("key", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter(getLanguageURIByName(imhLang).uri, toBinary(true)) // main language always enabled

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is LanguageFilters -> {
                    filter.state.forEach {
                        url.addQueryParameter(it.uri, toBinary(it.state))
                    }
                }
                is CategoryFilters -> {
                    filter.state.forEach {
                        url.addQueryParameter(it.uri, toBinary(it.state))
                    }
                }
                is SortOrderFilter -> {
                    getSortOrderURIs().forEachIndexed { index, pair ->
                        url.addQueryParameter(pair.second, toBinary(filter.state == index))
                    }
                }
                else -> {
                }
            }
        }

        return GET(url.toString())
    }

    override fun searchMangaSelector(): String = popularMangaSelector()

    // Details

    private fun Elements.csvText(splitTagSeparator: String = ", "): String {
        return this.joinToString {
            listOf(
                it.ownText(),
                it.select(".split_tag")?.text()
                    ?.trim()
                    ?.removePrefix("| ")
            )
                .filter { s -> !s.isNullOrBlank() }
                .joinToString(splitTagSeparator)
        }
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {

        title = document.selectFirst("div.right_details > h1").text()

        thumbnail_url = document.selectFirst("div.left_cover img").attr("abs:data-src")

        val mangaInfoElement = document.select(".galleries_info")
        val infoMap = mangaInfoElement.select("li:not(.pages)").map {
            it.select("span.tags_text").text().removeSuffix(":") to it.select(".tag")
        }.toMap()

        artist = infoMap["Artists"]?.csvText(" | ")

        author = artist

        genre = infoMap["Tags"]?.csvText()

        status = SManga.COMPLETED

        val pages = mangaInfoElement.select("li.pages").text().substringAfter("Pages: ")
        val altTitle = document.select(".subtitle").text().ifBlank { null }

        description = listOf(
            "Parodies",
            "Characters",
            "Groups",
            "Languages",
            "Category"
        ).map { it to infoMap[it]?.csvText() }
            .let { listOf(Pair("Alternate Title", altTitle)) + it + listOf(Pair("Pages", pages)) }
            .filter { !it.second.isNullOrEmpty() }
            .joinToString("\n\n") { "${it.first}:\n${it.second}" }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(response.request.url.toString().replace("gallery", "view") + "1")
                name = "Chapter"
                chapter_number = 1f
            }
        )
    }

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    override fun chapterListSelector(): String = throw UnsupportedOperationException("Not used")

    // Pages

    private val json: Json by injectLazy()

    override fun pageListParse(document: Document): List<Page> {
        val image_dir = document.select("#image_dir").`val`()
        val gallery_id = document.select("#gallery_id").`val`()
        val u_id = document.select("#u_id").`val`().toInt()

        val random_server = when (u_id) {
            in 1..274825 -> "m1.imhentai.xxx"
            in 274826..403818 -> "m2.imhentai.xxx"
            in 403819..527143 -> "m3.imhentai.xxx"
            in 527144..632481 -> "m4.imhentai.xxx"
            in 632482..816010 -> "m5.imhentai.xxx"
            else -> "m6.imhentai.xxx"
        }

        val images = json.parseToJsonElement(
            document.selectFirst("script:containsData(var g_th)").data()
                .substringAfter("$.parseJSON('").substringBefore("');").trim()
        ).jsonObject
        val pages = mutableListOf<Page>()

        for (image in images) {
            val iext = image.value.toString().replace("\"", "").split(",")[0]
            val iext_pr = when (iext) {
                "p" -> "png"
                "b" -> "bmp"
                "g" -> "gif"
                else -> "jpg"
            }
            pages.add(Page(image.key.toInt() - 1, "", "https://$random_server/$image_dir/$gallery_id/${image.key}.$iext_pr"))
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // Filters

    private class SortOrderFilter(sortOrderURIs: List<Pair<String, String>>, state: Int) :
        Filter.Select<String>("Sort By", sortOrderURIs.map { it.first }.toTypedArray(), state)

    private open class SearchFlagFilter(name: String, val uri: String, state: Boolean = true) : Filter.CheckBox(name, state)
    private class LanguageFilter(name: String, uri: String = name) : SearchFlagFilter(name, uri, false)
    private class LanguageFilters(flags: List<LanguageFilter>) : Filter.Group<LanguageFilter>("Other Languages", flags)
    private class CategoryFilters(flags: List<SearchFlagFilter>) : Filter.Group<SearchFlagFilter>("Categories", flags)

    override fun getFilterList() = getFilterList(SORT_ORDER_DEFAULT)

    private fun getFilterList(sortOrderState: Int) = FilterList(
        SortOrderFilter(getSortOrderURIs(), sortOrderState),
        CategoryFilters(getCategoryURIs()),
        LanguageFilters(getLanguageURIs().filter { it.name != imhLang }) // exclude main lang
    )

    private fun getCategoryURIs() = listOf(
        SearchFlagFilter("Manga", "manga"),
        SearchFlagFilter("Doujinshi", "doujinshi"),
        SearchFlagFilter("Western", "western"),
        SearchFlagFilter("Image Set", "imageset"),
        SearchFlagFilter("Artist CG", "artistcg"),
        SearchFlagFilter("Game CG", "gamecg")
    )

    // update sort order indices in companion object if order is changed
    private fun getSortOrderURIs() = listOf(
        Pair("Popular", "pp"),
        Pair("Latest", "lt"),
        Pair("Downloads", "dl"),
        Pair("Top Rated", "tr")
    )

    private fun getLanguageURIs() = listOf(
        LanguageFilter(LANGUAGE_ENGLISH, "en"),
        LanguageFilter(LANGUAGE_JAPANESE, "jp"),
        LanguageFilter(LANGUAGE_SPANISH, "es"),
        LanguageFilter(LANGUAGE_FRENCH, "fr"),
        LanguageFilter(LANGUAGE_KOREAN, "kr"),
        LanguageFilter(LANGUAGE_GERMAN, "de"),
        LanguageFilter(LANGUAGE_RUSSIAN, "ru")
    )

    private fun getLanguageURIByName(name: String): LanguageFilter {
        return getLanguageURIs().first { it.name == name }
    }

    companion object {

        // references to sort order indices
        private const val SORT_ORDER_POPULAR = 0
        private const val SORT_ORDER_LATEST = 1
        private const val SORT_ORDER_DEFAULT = SORT_ORDER_POPULAR

        // references to be used in factory
        const val LANGUAGE_ENGLISH = "English"
        const val LANGUAGE_JAPANESE = "Japanese"
        const val LANGUAGE_SPANISH = "Spanish"
        const val LANGUAGE_FRENCH = "French"
        const val LANGUAGE_KOREAN = "Korean"
        const val LANGUAGE_GERMAN = "German"
        const val LANGUAGE_RUSSIAN = "Russian"
    }
}
