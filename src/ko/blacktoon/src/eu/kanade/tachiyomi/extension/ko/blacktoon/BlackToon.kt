package eu.kanade.tachiyomi.extension.ko.blacktoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

class BlackToon : HttpSource() {

    override val name = "블랙툰"

    override val lang = "ko"

    override val baseUrl = "https://blacktoon410.com"

    private val cdnUrl = "https://blacktoonimg.com/"

    override val supportsLatest = true

    override val client = network.client.newBuilder().addInterceptor { chain ->
        val request = chain.request().newBuilder().apply {
            header("Referer", "$baseUrl/")
            header("Origin", baseUrl)
        }.build()
        chain.proceed(request)
    }.build()

    private val json by injectLazy<Json>()

    // 유조노의 엄격한 빌드 검증을 우회하기 위해 데이터 타입을 무형(유연한 구조)으로 파싱
    private val db by lazy {
        try {
            val dataUrl = "$baseUrl/style/js/jsonDB.js?v=2025"
            val responseBody = client.newCall(GET(dataUrl, headers)).execute().body.string()
            val jsonRaw = responseBody.substringAfter(" = ").substringBeforeLast(";").trim()
            
            val jsonElement = json.parseToJsonElement(jsonRaw)
            val list = mutableListOf<SManga>()
            
            jsonElement.jsonArray.forEach { element ->
                val obj = element.jsonObject
                val manga = SManga.create().apply {
                    title = obj["name"]?.jsonPrimitive?.content ?: ""
                    url = obj["url"]?.jsonPrimitive?.content ?: ""
                    thumbnail_url = cdnUrl + (obj["thumb"]?.jsonPrimitive?.content ?: "")
                }
                list.add(manga)
            }
            list
        } catch (e: Exception) {
            emptyList<SManga>()
        }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val startIdx = (page - 1) * 24
        if (startIdx >= db.size) return Observable.just(MangasPage(emptyList(), false))
        val endIdx = min(page * 24, db.size)
        return Observable.just(MangasPage(db.subList(startIdx, endIdx), endIdx < db.size))
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchPopularManga(page)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val filtered = if (query.isNotBlank()) {
            db.filter { it.title.contains(query.trim(), true) }
        } else {
            db
        }
        val startIdx = (page - 1) * 24
        if (startIdx >= filtered.size) return Observable.just(MangasPage(emptyList(), false))
        val endIdx = min(page * 24, filtered.size)
        return Observable.just(MangasPage(filtered.subList(startIdx, endIdx), endIdx < filtered.size))
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/webtoon/${manga.url}.html", headers)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val doc = response.asJsoup()
        description = doc.select("p.mt-2").last()?.text()
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/data/toonlist/${manga.url}.js", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".js")
        val responseBody = response.body.string()
        val jsonRaw = responseBody.substringAfter(" = ").substringBeforeLast(";").trim()
        
        val jsonElement = json.parseToJsonElement(jsonRaw)
        return jsonElement.jsonArray.mapIndexed { index, element ->
            val obj = element.jsonObject
            SChapter.create().apply {
                name = obj["name"]?.jsonPrimitive?.content ?: "회차 ${index + 1}"
                url = mangaId + "/" + (obj["id"]?.jsonPrimitive?.content ?: "")
                date_upload = 0L
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/webtoons/${chapter.url}.html", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#toon_content_imgs img").mapIndexed { index, it ->
            Page(index, imageUrl = cdnUrl + it.attr("o_src"))
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/webtoon/${manga.url}.html"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/webtoons/${chapter.url}.html"
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
