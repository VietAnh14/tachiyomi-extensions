package eu.kanade.tachiyomi.extension.all.mangadex

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.all.mangadex.dto.AggregateDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.AtHomeDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ChapterDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ChapterListDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ListDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaListDto
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

abstract class MangaDex(override val lang: String, val dexLang: String) :
    ConfigurableSource,
    HttpSource() {
    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val helper = MangaDexHelper()

    override fun headersBuilder() = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Tachiyomi " + System.getProperty("http.agent"))

    override val client = network.client.newBuilder()
        .addNetworkInterceptor(mdRateLimitInterceptor)
        .addInterceptor(coverInterceptor)
        .addInterceptor(MdAtHomeReportInterceptor(network.client, headersBuilder().build()))
        .build()

    // POPULAR Manga Section

    override fun popularMangaRequest(page: Int): Request {
        val url = MDConstants.apiMangaUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("order[followedCount]", "desc")
            addQueryParameter("availableTranslatedLanguage[]", dexLang)
            addQueryParameter("limit", MDConstants.mangaLimit.toString())
            addQueryParameter("offset", helper.getMangaListOffset(page))
            addQueryParameter("includes[]", MDConstants.coverArt)
            preferences.getStringSet(
                MDConstants.getContentRatingPrefKey(dexLang),
                MDConstants.contentRatingPrefDefaults
            )?.forEach { addQueryParameter("contentRating[]", it) }
            preferences.getStringSet(
                MDConstants.getOriginalLanguagePrefKey(dexLang),
                setOf()
            )?.forEach {
                addQueryParameter("originalLanguage[]", it)
                // dex has zh and zh-hk for chinese manhua
                if (it == MDConstants.originalLanguagePrefValChinese) {
                    addQueryParameter("originalLanguage[]", MDConstants.originalLanguagePrefValChineseHk)
                }
            }
        }.build().toUrl().toString()
        return GET(
            url = url,
            headers = headers,
            cache = CacheControl.FORCE_NETWORK
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }

        if (response.code == 204) {
            return MangasPage(emptyList(), false)
        }
        val mangaListDto = helper.json.decodeFromString<MangaListDto>(response.body!!.string())
        val hasMoreResults = mangaListDto.limit + mangaListDto.offset < mangaListDto.total

        val coverSuffix = preferences.getString(MDConstants.getCoverQualityPreferenceKey(dexLang), "")

        val mangaList = mangaListDto.data.map { mangaDataDto ->
            val fileName = mangaDataDto.relationships.firstOrNull { relationshipDto ->
                relationshipDto.type.equals(MDConstants.coverArt, true)
            }?.attributes?.fileName
            helper.createBasicManga(mangaDataDto, fileName, coverSuffix, dexLang)
        }

        return MangasPage(mangaList, hasMoreResults)
    }

    // LATEST section API can't sort by date yet so not implemented
    override fun latestUpdatesParse(response: Response): MangasPage {
        val chapterListDto = helper.json.decodeFromString<ChapterListDto>(response.body!!.string())
        val hasMoreResults = chapterListDto.limit + chapterListDto.offset < chapterListDto.total

        val mangaIds = chapterListDto.data.map { it.relationships }.flatten()
            .filter { it.type == MDConstants.manga }.map { it.id }.distinct()

        val mangaUrl = MDConstants.apiMangaUrl.toHttpUrlOrNull()!!.newBuilder().apply {
            addQueryParameter("includes[]", MDConstants.coverArt)
            addQueryParameter("limit", mangaIds.size.toString())

            preferences.getStringSet(
                MDConstants.getContentRatingPrefKey(dexLang),
                MDConstants.contentRatingPrefDefaults
            )?.forEach { addQueryParameter("contentRating[]", it) }

            mangaIds.forEach { id ->
                addQueryParameter("ids[]", id)
            }
        }.build().toString()

        val mangaResponse = client.newCall(GET(mangaUrl, headers, CacheControl.FORCE_NETWORK)).execute()
        val mangaListDto = helper.json.decodeFromString<MangaListDto>(mangaResponse.body!!.string())

        val mangaDtoMap = mangaListDto.data.associateBy({ it.id }, { it })

        val coverSuffix = preferences.getString(MDConstants.getCoverQualityPreferenceKey(dexLang), "")

        val mangaList = mangaIds.mapNotNull { mangaDtoMap[it] }.map { mangaDataDto ->
            val fileName = mangaDataDto.relationships.firstOrNull { relationshipDto ->
                relationshipDto.type.equals(MDConstants.coverArt, true)
                relationshipDto.type.equals(MDConstants.coverArt, true)
            }?.attributes?.fileName
            helper.createBasicManga(mangaDataDto, fileName, coverSuffix, dexLang)
        }

        return MangasPage(mangaList, hasMoreResults)
    }
    override fun latestUpdatesRequest(page: Int): Request {
        val url = MDConstants.apiChapterUrl.toHttpUrlOrNull()!!.newBuilder().apply {
            addQueryParameter("offset", helper.getLatestChapterOffset(page))
            addQueryParameter("limit", MDConstants.latestChapterLimit.toString())
            addQueryParameter("translatedLanguage[]", dexLang)
            addQueryParameter("order[publishAt]", "desc")
            addQueryParameter("includeFutureUpdates", "0")
            preferences.getStringSet(
                MDConstants.getOriginalLanguagePrefKey(dexLang),
                setOf()
            )?.forEach {
                addQueryParameter("originalLanguage[]", it)
                // dex has zh and zh-hk for chinese manhua
                if (it == MDConstants.originalLanguagePrefValChinese) {
                    addQueryParameter("originalLanguage[]", MDConstants.originalLanguagePrefValChineseHk)
                }
            }
            preferences.getStringSet(
                MDConstants.getContentRatingPrefKey(dexLang),
                MDConstants.contentRatingPrefDefaults
            )?.forEach { addQueryParameter("contentRating[]", it) }
            MDConstants.defaultBlockedGroups.forEach {
                addQueryParameter("excludedGroups[]", it)
            }
            preferences.getString(
                MDConstants.getBlockedGroupsPrefKey(dexLang), ""
            )?.split(",")?.sorted()?.forEach { if (it.isNotEmpty()) addQueryParameter("excludedGroups[]", it.trim()) }
            preferences.getString(
                MDConstants.getBlockedUploaderPrefKey(dexLang),
                ""
            )?.split(", ")?.sorted()?.forEach { if (it.isNotEmpty()) addQueryParameter("excludedUploaders[]", it.trim()) }
        }.build().toString()
        return GET(url, headers, CacheControl.FORCE_NETWORK)
    }

    // SEARCH section

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        when {
            query.startsWith(MDConstants.prefixChSearch) ->
                return getMangaIdFromChapterId(query.removePrefix(MDConstants.prefixChSearch)).flatMap { manga_id ->
                    super.fetchSearchManga(page, MDConstants.prefixIdSearch + manga_id, filters)
                }
            query.startsWith(MDConstants.prefixUsrSearch) ->
                return client.newCall(searchMangaUploaderRequest(page, query.removePrefix(MDConstants.prefixUsrSearch)))
                    .asObservableSuccess()
                    .map { latestUpdatesParse(it) }
            query.startsWith(MDConstants.prefixListSearch) ->
                return client.newCall(GET(MDConstants.apiListUrl + "/" + query.removePrefix(MDConstants.prefixListSearch), headers, CacheControl.FORCE_NETWORK))
                    .asObservableSuccess()
                    .map { searchMangaListRequest(it, page) }
            else ->
                return super.fetchSearchManga(page, query, filters)
        }
    }

    private fun getMangaIdFromChapterId(id: String): Observable<String> {
        return client.newCall(GET("${MDConstants.apiChapterUrl}/$id", headers))
            .asObservableSuccess()
            .map { response ->
                if (response.isSuccessful.not()) {
                    throw Exception("Unable to process Chapter request. HTTP code: ${response.code}")
                }

                helper.json.decodeFromString<ChapterDto>(response.body!!.string()).data.relationships
                    .find {
                        it.type == MDConstants.manga
                    }!!.id
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tempUrl = MDConstants.apiMangaUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", MDConstants.mangaLimit.toString())
            addQueryParameter("offset", (helper.getMangaListOffset(page)))
            addQueryParameter("includes[]", MDConstants.coverArt)
        }

        when {
            query.startsWith(MDConstants.prefixIdSearch) -> {
                val url = MDConstants.apiMangaUrl.toHttpUrlOrNull()!!.newBuilder().apply {
                    addQueryParameter("ids[]", query.removePrefix(MDConstants.prefixIdSearch))
                    addQueryParameter("includes[]", MDConstants.coverArt)
                    addQueryParameter("contentRating[]", "safe")
                    addQueryParameter("contentRating[]", "suggestive")
                    addQueryParameter("contentRating[]", "erotica")
                    addQueryParameter("contentRating[]", "pornographic")
                }.build().toString()

                return GET(url, headers, CacheControl.FORCE_NETWORK)
            }
            query.startsWith(MDConstants.prefixGrpSearch) -> {
                val groupID = query.removePrefix(MDConstants.prefixGrpSearch)
                if (!helper.containsUuid(groupID)) {
                    throw Exception("Not a valid group ID")
                }

                tempUrl.apply {
                    addQueryParameter("group", groupID)
                }
            }
            query.startsWith(MDConstants.prefixAuthSearch) -> {
                val authorID = query.removePrefix(MDConstants.prefixAuthSearch)
                if (!helper.containsUuid(authorID)) {
                    throw Exception("Not a valid author ID")
                }

                tempUrl.apply {
                    addQueryParameter("authors[]", authorID)
                    addQueryParameter("artists[]", authorID)
                }
            }
            else -> {
                tempUrl.apply {
                    val actualQuery = query.replace(MDConstants.whitespaceRegex, " ")
                    if (actualQuery.isNotBlank()) {
                        addQueryParameter("title", actualQuery)
                    }
                }
            }
        }

        val finalUrl = helper.mdFilters.addFiltersToUrl(tempUrl, filters, dexLang)

        return GET(finalUrl, headers, CacheControl.FORCE_NETWORK)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun searchMangaListRequest(response: Response, page: Int): MangasPage {
        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }

        val listDto = helper.json.decodeFromString<ListDto>(response.body!!.string())
        val listDtoFiltered = listDto.data.relationships.filter { it.type != "Manga" }
        val amount = listDtoFiltered.count()
        if (amount < 1){
            throw Exception("No Manga in List")
        }
        val minIndex = (page - 1) * MDConstants.mangaLimit

        val url = MDConstants.apiMangaUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", MDConstants.mangaLimit.toString())
            addQueryParameter("offset", "0")
            addQueryParameter("includes[]", MDConstants.coverArt)
        }
        listDtoFiltered.forEachIndexed() { index, relationshipDto ->
            if (index >= minIndex && index < (minIndex + MDConstants.mangaLimit)) {
                url.addQueryParameter("ids[]", relationshipDto.id)
            }
        }

        val request = client.newCall(GET(url.build().toString(), headers, CacheControl.FORCE_NETWORK))
        val mangaList = searchMangaListParse(request.execute())
        return MangasPage(mangaList, amount.toFloat() / MDConstants.mangaLimit - (page.toFloat() - 1) > 1)
    }

    private fun searchMangaListParse(response: Response): List<SManga> {
        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }

        val mangaListDto = helper.json.decodeFromString<MangaListDto>(response.body!!.string())

        val coverSuffix = preferences.getString(MDConstants.getCoverQualityPreferenceKey(dexLang), "")

        val mangaList = mangaListDto.data.map { mangaDataDto ->
            val fileName = mangaDataDto.relationships.firstOrNull { relationshipDto ->
                relationshipDto.type.equals(MDConstants.coverArt, true)
            }?.attributes?.fileName
            helper.createBasicManga(mangaDataDto, fileName, coverSuffix, dexLang)
        }

        return mangaList
    }

    private fun searchMangaUploaderRequest(page: Int, uploader: String): Request {
        val url = MDConstants.apiChapterUrl.toHttpUrlOrNull()!!.newBuilder().apply {
            addQueryParameter("offset", helper.getLatestChapterOffset(page))
            addQueryParameter("limit", MDConstants.latestChapterLimit.toString())
            addQueryParameter("translatedLanguage[]", dexLang)
            addQueryParameter("order[publishAt]", "desc")
            addQueryParameter("includeFutureUpdates", "0")
            addQueryParameter("uploader", uploader)
            preferences.getStringSet(
                MDConstants.getOriginalLanguagePrefKey(dexLang),
                setOf()
            )?.forEach {
                addQueryParameter("originalLanguage[]", it)
                // dex has zh and zh-hk for chinese manhua
                if (it == MDConstants.originalLanguagePrefValChinese) {
                    addQueryParameter("originalLanguage[]", MDConstants.originalLanguagePrefValChineseHk)
                }
            }
            preferences.getStringSet(
                MDConstants.getContentRatingPrefKey(dexLang),
                MDConstants.contentRatingPrefDefaults
            )?.forEach { addQueryParameter("contentRating[]", it) }
            MDConstants.defaultBlockedGroups.forEach {
                addQueryParameter("excludedGroups[]", it)
            }
            preferences.getString(
                MDConstants.getBlockedGroupsPrefKey(dexLang), ""
            )?.split(",")?.sorted()?.forEach { if (it.isNotEmpty()) addQueryParameter("excludedGroups[]", it.trim()) }
            preferences.getString(
                MDConstants.getBlockedUploaderPrefKey(dexLang),
                ""
            )?.split(", ")?.sorted()?.forEach { if (it.isNotEmpty()) addQueryParameter("excludedUploaders[]", it.trim()) }
        }.build().toString()
        return GET(url, headers, CacheControl.FORCE_NETWORK)
    }

    // Manga Details section

    // Shenanigans to allow "open in webview" to show a webpage instead of JSON
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(apiMangaDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        // remove once redirect for /manga is fixed
        val title = manga.title
        val url = "${baseUrl}${manga.url.replace("manga", "title")}"
        val shareUrl = "$url/" + helper.titleToSlug(title)
        return GET(shareUrl, headers)
    }

    /**
     * get manga details url throws exception if the url is the old format so people migrate
     */
    private fun apiMangaDetailsRequest(manga: SManga): Request {
        if (!helper.containsUuid(manga.url.trim())) {
            throw Exception("Migrate this manga from MangaDex to MangaDex to update it")
        }
        val url = (MDConstants.apiUrl + manga.url).toHttpUrl().newBuilder().apply {
            addQueryParameter("includes[]", MDConstants.coverArt)
            addQueryParameter("includes[]", MDConstants.author)
            addQueryParameter("includes[]", MDConstants.artist)
        }.build().toString()
        return GET(url, headers, CacheControl.FORCE_NETWORK)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = helper.json.decodeFromString<MangaDto>(response.body!!.string())
        val coverSuffix = preferences.getString(MDConstants.getCoverQualityPreferenceKey(dexLang), "")
        return helper.createManga(manga.data, fetchSimpleChapterList(manga, dexLang), dexLang, coverSuffix)
    }

    /**
     * get a quick-n-dirty list of the chapters to be used in determining the manga status.
     * uses the 'aggregate' endpoint
     * @see MangaDexHelper.getPublicationStatus
     * @see MangaDexHelper.doubleCheckChapters
     * @see AggregateDto
     */
    private fun fetchSimpleChapterList(manga: MangaDto, langCode: String): List<String> {
        val url = "${MDConstants.apiMangaUrl}/${manga.data.id}/aggregate?translatedLanguage[]=$langCode"
        val response = client.newCall(GET(url, headers)).execute()
        val chapters: AggregateDto
        try {
            chapters = helper.json.decodeFromString(response.body!!.string())
        } catch (e: SerializationException) {
            return emptyList()
        }
        if (chapters.volumes.isNullOrEmpty()) return emptyList()
        return chapters.volumes.values.flatMap { it.chapters.values }.map { it.chapter }
    }

    // Chapter list section
    /**
     * get chapter list if manga url is old format throws exception
     */
    override fun chapterListRequest(manga: SManga): Request {
        if (!helper.containsUuid(manga.url)) {
            throw Exception("Migrate this manga from MangaDex to MangaDex to update it")
        }
        return actualChapterListRequest(helper.getUUIDFromUrl(manga.url), 0)
    }

    /**
     * Required because api is paged
     */
    private fun actualChapterListRequest(mangaId: String, offset: Int): Request {
        val url = helper.getChapterEndpoint(mangaId, offset, dexLang).toHttpUrlOrNull()!!.newBuilder().apply {
            addQueryParameter("contentRating[]", "safe")
            addQueryParameter("contentRating[]", "suggestive")
            addQueryParameter("contentRating[]", "erotica")
            addQueryParameter("contentRating[]", "pornographic")
            preferences.getString(
                MDConstants.getBlockedGroupsPrefKey(dexLang), ""
            )?.split(",")?.sorted()?.forEach { if (it.isNotEmpty()) addQueryParameter("excludedGroups[]", it.trim()) }
            preferences.getString(
                MDConstants.getBlockedUploaderPrefKey(dexLang),
                ""
            )?.split(",")?.sorted()?.forEach { if (it.isNotEmpty()) addQueryParameter("excludedUploaders[]", it.trim()) }
        }.build().toString()
        return GET(url, headers = headers, cache = CacheControl.FORCE_NETWORK)
    }
    override fun chapterListParse(response: Response): List<SChapter> {
        if (response.isSuccessful.not()) {
            throw Exception("HTTP ${response.code}")
        }
        if (response.code == 204) {
            return emptyList()
        }
        try {
            val chapterListResponse = helper.json.decodeFromString<ChapterListDto>(response.body!!.string())

            val chapterListResults = chapterListResponse.data.toMutableList()

            val mangaId =
                response.request.url.toString().substringBefore("/feed")
                    .substringAfter("${MDConstants.apiMangaUrl}/")

            val limit = chapterListResponse.limit

            var offset = chapterListResponse.offset

            var hasMoreResults = (limit + offset) < chapterListResponse.total

            // max results that can be returned is 500 so need to make more api calls if limit+offset > total chapters
            while (hasMoreResults) {
                offset += limit
                val newResponse =
                    client.newCall(actualChapterListRequest(mangaId, offset)).execute()
                val newChapterList = helper.json.decodeFromString<ChapterListDto>(newResponse.body!!.string())
                chapterListResults.addAll(newChapterList.data)
                hasMoreResults = (limit + offset) < newChapterList.total
            }

            val now = Date().time

            return chapterListResults.mapNotNull { helper.createChapter(it) }
                .filter {
                    it.date_upload <= now
                }
        } catch (e: Exception) {
            Log.e("MangaDex", "error parsing chapter list", e)
            throw(e)
        }
    }
    override fun pageListRequest(chapter: SChapter): Request {
        if (!helper.containsUuid(chapter.url)) {
            throw Exception("Migrate this manga from MangaDex to MangaDex to update it")
        }
        val chapterId = chapter.url.substringAfter("/chapter/")
        val usingStandardHTTPS =
            preferences.getBoolean(MDConstants.getStandardHttpsPreferenceKey(dexLang), false)
        val atHomeRequestUrl = if (usingStandardHTTPS) {
            "${MDConstants.apiUrl}/at-home/server/$chapterId?forcePort443=true"
        } else {
            "${MDConstants.apiUrl}/at-home/server/$chapterId"
        }

        return helper.mdAtHomeRequest(atHomeRequestUrl, headers, CacheControl.FORCE_NETWORK)
    }

    override fun pageListParse(response: Response): List<Page> {
        val atHomeRequestUrl = response.request.url
        val atHomeDto = helper.json.decodeFromString<AtHomeDto>(response.body!!.string())
        val host = atHomeDto.baseUrl
        val usingDataSaver =
            preferences.getBoolean(MDConstants.getDataSaverPreferenceKey(dexLang), false)

        // have to add the time, and url to the page because pages timeout within 30mins now
        val now = Date().time

        val hash = atHomeDto.chapter.hash
        val pageSuffix = if (usingDataSaver) {
            atHomeDto.chapter.dataSaver.map { "/data-saver/$hash/$it" }
        } else {
            atHomeDto.chapter.data.map { "/data/$hash/$it" }
        }

        return pageSuffix.mapIndexed { index, imgUrl ->
            val mdAtHomeMetadataUrl = "$host,$atHomeRequestUrl,$now"
            Page(index, mdAtHomeMetadataUrl, imgUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        return helper.getValidImageUrlForPage(page, headers, client)
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val coverQualityPref = ListPreference(screen.context).apply {
            key = MDConstants.getCoverQualityPreferenceKey(dexLang)
            title = "Manga Cover Quality"
            entries = MDConstants.getCoverQualityPreferenceEntries()
            entryValues = MDConstants.getCoverQualityPreferenceEntryValues()
            setDefaultValue(MDConstants.getCoverQualityPreferenceDefaultValue())
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(MDConstants.getCoverQualityPreferenceKey(dexLang), entry).commit()
            }
        }

        val dataSaverPref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getDataSaverPreferenceKey(dexLang)
            title = "Data saver"
            summary = "Enables smaller, more compressed images"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getDataSaverPreferenceKey(dexLang), checkValue)
                    .commit()
            }
        }

        val standardHttpsPortPref = SwitchPreferenceCompat(screen.context).apply {
            key = MDConstants.getStandardHttpsPreferenceKey(dexLang)
            title = "Use HTTPS port 443 only"
            summary =
                "Enable to only request image servers that use port 443. This allows users with stricter firewall restrictions to access MangaDex images"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit()
                    .putBoolean(MDConstants.getStandardHttpsPreferenceKey(dexLang), checkValue)
                    .commit()
            }
        }

        val contentRatingPref = MultiSelectListPreference(screen.context).apply {
            key = MDConstants.getContentRatingPrefKey(dexLang)
            title = "Default content rating"
            summary = "Show content with the selected ratings by default"
            entries = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
            entryValues = arrayOf(
                MDConstants.contentRatingPrefValSafe,
                MDConstants.contentRatingPrefValSuggestive,
                MDConstants.contentRatingPrefValErotica,
                MDConstants.contentRatingPrefValPornographic
            )
            setDefaultValue(MDConstants.contentRatingPrefDefaults)
            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Set<String>
                preferences.edit()
                    .putStringSet(MDConstants.getContentRatingPrefKey(dexLang), checkValue)
                    .commit()
            }
        }

        val originalLanguagePref = MultiSelectListPreference(screen.context).apply {
            key = MDConstants.getOriginalLanguagePrefKey(dexLang)
            title = "Filter original languages"
            summary = "Only show content that was originally published in the selected languages in both latest and browse"
            entries = arrayOf("Japanese", "Chinese", "Korean")
            entryValues = arrayOf(
                MDConstants.originalLanguagePrefValJapanese,
                MDConstants.originalLanguagePrefValChinese,
                MDConstants.originalLanguagePrefValKorean
            )
            setDefaultValue(setOf<String>())
            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Set<String>
                preferences.edit()
                    .putStringSet(MDConstants.getOriginalLanguagePrefKey(dexLang), checkValue)
                    .commit()
            }
        }

        val blockedGroupsPref = EditTextPreference(screen.context).apply {
            key = MDConstants.getBlockedGroupsPrefKey(dexLang)
            title = "Block Groups by UUID"
            summary = "Chapters from blocked groups will not show up in Latest or Manga feed.\n" +
                "Enter as a Comma-separated list of group UUIDs"
            setOnPreferenceChangeListener { _, newValue ->
                val groupsBlocked = newValue.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { helper.containsUuid(it) }
                    .joinToString(separator = ", ")

                preferences.edit()
                    .putString(MDConstants.getBlockedGroupsPrefKey(dexLang), groupsBlocked)
                    .commit()
            }
        }

        val blockedUploaderPref = EditTextPreference(screen.context).apply {
            key = MDConstants.getBlockedUploaderPrefKey(dexLang)
            title = "Block Uploader by UUID"
            summary = "Chapters from blocked users will not show up in Latest or Manga feed.\n" +
                "Enter as a Comma-separated list of uploader UUIDs"
            setOnPreferenceChangeListener { _, newValue ->
                val uploaderBlocked = newValue.toString()
                    .split(",")
                    .map { it.trim() }
                    .filter { helper.containsUuid(it) }
                    .joinToString(separator = ", ")

                preferences.edit()
                    .putString(MDConstants.getBlockedUploaderPrefKey(dexLang), uploaderBlocked)
                    .commit()
            }
        }

        screen.addPreference(coverQualityPref)
        screen.addPreference(dataSaverPref)
        screen.addPreference(standardHttpsPortPref)
        screen.addPreference(contentRatingPref)
        screen.addPreference(originalLanguagePref)
        screen.addPreference(blockedGroupsPref)
        screen.addPreference(blockedUploaderPref)
    }

    override fun getFilterList(): FilterList =
        helper.mdFilters.getMDFilterList(preferences, dexLang)
}
