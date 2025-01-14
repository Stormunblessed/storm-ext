package com.lagradost.cloudstream3.animeproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.*
import kotlin.collections.ArrayList


class ReyDonghuaProvider : MainAPI() {
    companion object {
        var latestCookie: Map<String, String> = emptyMap()
        var latestToken = ""
    }

    override var mainUrl = "https://reydonghua.org"
    override var name = "ReyDonghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
    )

    private suspend fun getToken(url: String): Map<String, String> {
        val maintas = app.get(url)
        val token =
            maintas.document.selectFirst("html head meta[name=csrf-token]")?.attr("content") ?: ""
        val cookies = maintas.cookies
        latestToken = token
        latestCookie = cookies
        return latestCookie
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val isHorizontal = true
        items.add(
            HomePageList(
                "Nuevos Episodios",
                app.get(mainUrl).document.select(".mt-1 a").map {
                    val title = it.selectFirst("div.col div.card div.bg-card h2.card-title")?.text()
                        ?.substringBefore(" - ") ?: ""
                    val poster =
                        it.selectFirst("div.col div.card img.lazy")?.attr("data-src") ?: ""
                    val url =
                        it.attr("href").replace("/ver", "/donghua").substringBefore("-episodio")
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = fixUrl(poster)
                        dubStatus = EnumSet.of(DubStatus.Subbed)
                    }
                }, isHorizontal
            )
        )
        items.add(
            HomePageList(
                "Series recientes",
                app.get(mainUrl).document.select("div.mt-4 div.container-lg div.container-lg div.row-cols-xxl-6 a").map {
                    val title = it.selectFirst("div.col h2")!!.text()
                    val poster =
                        it.selectFirst("div.col img.lazy")?.attr("data-src") ?: ""
                    val url = it.attr("href")
                    newAnimeSearchResponse(title, url) {
                        this.posterUrl = fixUrl(poster)
                        this.dubStatus = EnumSet.of(DubStatus.Subbed)
                    }
                }
            )
        )
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/buscar?q=$query", timeout = 120).document.select("li.col").map {
            val title = it.selectFirst("h2")!!.text()
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image = it.selectFirst("img")!!.attr("data-src")
            AnimeSearchResponse(
                title,
                href,
                this.name,
                TvType.Anime,
                fixUrl(image),
                null,
                EnumSet.of(
                    DubStatus.Subbed
                ),
            )
        }
    }

    data class PaginateUrl(
        @JsonProperty("paginate_url") val paginateUrl: String,
    )

    data class CapList(
        @JsonProperty("caps") val caps: List<Ep>,
    )

    data class Ep(
        val episodio: Int?,
        val url: String?,
    )

    suspend fun getCaps(caplist: String, referer: String): NiceResponse {
        val res = app.post(
            caplist,
            headers = mapOf(
                "Host" to URL(mainUrl).host,
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to referer,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "TE" to "trailers"
            ),
            cookies = latestCookie,
            data = mapOf(
                "_token" to latestToken,
                "p" to "1",
                "order" to "1"
            )
        )
        latestCookie = res.cookies
        return res
    }

    override suspend fun load(url: String): LoadResponse {
        getToken(url)
        val doc = app.get(url, timeout = 120).document
        val caplist = doc.selectFirst(".caplist")!!.attr("data-ajax")
        val poster = doc.selectFirst("div.mierda img.lazy")!!.attr("data-src")
        val title = doc.selectFirst("div.container div.row div.col h2.text-light")!!.text()
        val description = doc.selectFirst("div.container div.row div.col p.text-muted")!!.text()
        val genres = doc.select("div.container div#profile-tab-pane div.row div.lh-lg a span")
            .map { it.text() }
        val status =
            when (doc.selectFirst("div.mb-4 > div:nth-child(1) > div:nth-child(1) > div:nth-child(1) > div:nth-child(2) > div:nth-child(2)")
                ?.text()) {
                "Estreno" -> ShowStatus.Ongoing
                "Finalizado" -> ShowStatus.Completed
                else -> null
            }
        val pagurl = getCaps(caplist, url).parsed<PaginateUrl>()
        val capJson = getCaps(pagurl.paginateUrl, url).parsed<CapList>()
        val epList = capJson.caps.map { epnum ->
            newEpisode(
                epnum.url
            ) {
                this.episode = epnum.episodio
            }
        }
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            backgroundPosterUrl = poster
            addEpisodes(DubStatus.Subbed, epList)
            showStatus = status
            plot = description
            tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("#myTab li").apmap {
            val encodedurl = it.select(".play-video").attr("data-player")
            val urlDecoded =
                base64Decode(encodedurl).replace("https://playerwish.com", "https://streamwish.to")
            loadExtractor(urlDecoded, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}