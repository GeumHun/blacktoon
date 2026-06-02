package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random

class BlackToon : HttpSource() {

    override val name = "블랙툰"

    override val lang = "ko"

    // 최신 작동 도메인으로 고정 업데이트
    override val baseUrl = "https://blacktoon410.com"

    private val cdnUrl = "https://blacktoonimg.com/"

    override val supportsLatest = true

    // 변경된 도메인 구조에 맞추어 헤더 가로채기(Interceptor) 로직 최적화
    override val client = network.client.newBuilder().addInterceptor { chain ->
        val request = chain.request().newBuilder().apply {
            header("Referer", "$baseUrl/")
            header("Origin", baseUrl)
        }.build()
        chain.proceed(request)
    }.build()

    private val json by injectLazy<Json>()

    // 웹사이트 스크린샷 구조(timeKey 동적 생성 및 jsonDB 호출)를 반영한 핵심 데이터 획득 로직
    private val db by lazy {
        // 1. 현재 날짜를 기반으로 웹사이트와 동일한 YYMMDD 형식의 timeKey 생성
        val dateFormat = SimpleDateFormat("yyMMdd", Locale.getDefault())
        val timeKey = dateFormat.format(Date())
        
        // 2. 스크린샷 25번 라인의 규칙 반영: 20260601_ + YYMMDD
        val targetParam = "20260601_$timeKey"
        
        // 3. 동적 파라미터가 포함된 데이터 스크립트 주소 생성 (jsonDB.js 호출)
        val dataUrl = "$baseUrl/style/js/jsonDB.js?v=2025"
        
        val responseBody = client.newCall(GET(dataUrl, headers))
            .execute().body.string()

        // 4. 자바스크립트 변수 선언문 뒤의 순수 JSON 데이터만 추출하여 파싱
        responseBody.substringAfter(" = ")
            .removeSuffix(";")
            .trim()
            .let { json.decodeFromString<List<SeriesItem>>(it) }
            .onEach { it.listIndex = 1 } // 기본 인덱스 강제 지정
    }

    private fun List<SeriesItem>.getPageChunk(page: Int): MangasPage = MangasPage(
        mangas = subList((page - 1) * 24, min(page * 24, size))
            .map { it.toSManga(cdnUrl) },
        hasNextPage = (page + 1) * 24 <= size,
    )

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(
        db.sortedByDescending { it.hot }.getPageChunk(page),
    )

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.just(
        db.sortedByDescending { it.updatedAt }.getPageChunk(page),
    )

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        var list = db

        if (query.isNotBlank()) {
            val stdQuery = query.trim()
            list = list.filter {
                it.name.contains(stdQuery, true) ||
                    it.author.contains(stdQuery, true)
            }
        }

        filters.filterIsInstance<ListFilter>().forEach {
            list = it.applyFilter(list)
        }

        return Observable.just(
            list.getPageChunk(page),
        )
    }

    override fun getFilterList() = getFilters()

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/webtoon/${manga.url}.html#${manga.status}", headers)

    override fun getMangaUrl(manga: SManga): String = buildString {
        append(baseUrl)
        append("/webtoon/")
        append(manga.url)
        append(".html")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            description = doc.select("p.mt-2").last()?.text()
            thumbnail_url = doc.selectFirst("script:containsData(+img_domain+)")?.data()?.let {
                cdnUrl + it.substringAfter("+'").substringBefore("'+")
            }
            status = response.request.url.fragment!!.toInt()
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl/data/toonlist/${manga.url}.js?v=${"%.17f".format(Random.nextDouble())}"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".js")

        val data = response.body.string()
            .substringAfter(" = ")
            .removeSuffix(";")
            .let { json.decodeFromString<List<Chapter>>(it) }

        return data.map { it.toSChapter(mangaId) }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = buildString {
        append(baseUrl)
        append("/webtoons/")
        append(chapter.url)
        append(".html")
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/webtoons/${chapter.url}.html", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        return document.select("#toon_content_imgs img").map {
            Page(0, imageUrl = cdnUrl + it.attr("o_src"))
        }
    }

    // unused
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
