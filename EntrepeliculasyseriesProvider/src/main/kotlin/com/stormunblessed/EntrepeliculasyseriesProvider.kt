package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class EntrepeliculasyseriesProvider : MainAPI() {
    override var mainUrl = "https://entrepeliculasyseries.nz"
    override var name = "EntrePeliculasySeries"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded //Due to evoload sometimes not loading

    override val mainPage = mainPageOf(
        Pair("$mainUrl/series/page/", "Series"),
        Pair("$mainUrl/peliculas/page/", "Peliculas"),
        Pair("$mainUrl/genero/animacion/page/", "Animes"),
    )

    override suspend fun getMainPage(
        page: Int,
        request : MainPageRequest
    ): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url).document
        val home = soup.select("ul.post-lst article").map {
            val title = it.selectFirst(".title")!!.text()
            val link = it.selectFirst("a")!!.attr("href")
            TvSeriesSearchResponse(
                title,
                link,
                this.name,
                if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                it.selectFirst("img")!!.attr("src"),
                null,
                null,
            )
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url,
                headers = mapOf(
                        "Host" to "entrepeliculasyseries.nz",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:126.0) Gecko/20100101 Firefox/126.0",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Cookie" to "cf_clearance=FB0f2bIIB.3Bm8NRqzg9EZ2.He88HXKboI2tOwG85P8-1718334330-1.0.1.1-k.Tlg68YmgqSkTp3UDXBrcNuN1l1YnZw3a0VjRswwjRTx3pZgVrGsdaJRLcmuGemFeSrQOjGVMfCvpSwCsDQOg",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "document",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Fetch-User" to "?1",
                        "TE" to "trailers",
                        "Alt-Used" to "entrepeliculasyseries.nz",
                        "Priority" to "u=4",
                        "Pragma" to "no-cache",
                        "Cache-Control" to "no-cache",

                        )
                ).document
        val killer = CloudflareKiller()
        //val kk = killer.getCookieHeaders(url).toMap()
        //val aa = killer.savedCookies

        return document.select("ul.post-lst article").map {
            val title = it.selectFirst(".title")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst("img")!!.attr("src")
            val isMovie = href.contains("/pelicula/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            }
        }.toList()
    }


    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document

        val title = soup.selectFirst("h1.title-post")!!.text()
        val description = soup.selectFirst("p.text-content:nth-child(3)")?.text()?.trim()
        val poster: String? = soup.selectFirst("article.TPost img.lazy")!!.attr("data-src")
        val backgroundposter = soup.selectFirst("div.image figure.Objf img.lazy")!!.attr("data-src")
        val episodes = soup.select(".TPostMv article").map { li ->
            val href = (li.select("a") ?: li.select(".C a") ?: li.select("article a")).attr("href")
            val epThumb = li.selectFirst("div.Image img")!!.attr("data-src").replace(Regex("\\/w\\d+\\/"),"/w780/")
            val seasonid = li.selectFirst("span.Year")!!.text().let { str ->
                str.split("x").mapNotNull { subStr -> subStr.toIntOrNull() }
            }
            val isValid = seasonid.size == 2
            val episode = if (isValid) seasonid.getOrNull(1) else null
            val season = if (isValid) seasonid.getOrNull(0) else null
            Episode(
                href,
                null,
                season,
                episode,
                fixUrl(epThumb)
            )
        }
        return when (val tvType =
            if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title,
                    url, tvType, episodes,){
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgroundposter
                    this.plot = description
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url){
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgroundposter
                    this.plot = description
                }
            }
            else -> null
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select(".video ul.dropdown-menu li").apmap {
            val servers = it.attr("data-link")
            val keys = servers.substringAfter("player.php?h=")
            val requestserrvers = app.post("https://entrepeliculasyseries.nz/r.php", allowRedirects = false,
                headers = mapOf(),
                data = mapOf(
                    "h" to keys
                )
            ).headers["location"]
            if (requestserrvers != null) {
                println("LOCATION $requestserrvers")
                loadExtractor(requestserrvers, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
