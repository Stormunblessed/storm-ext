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
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url).document
        val home = soup.select("ul.post-lst article").apmap {
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
        val cloudflareKiller = CloudflareKiller()
        val resp = app.get(
            url,
            headers = mapOf(
                "user-agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:101.0) Gecko/20100101 Firefox/101.0",
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache",
            ),
           interceptor = cloudflareKiller
        )
        val document = resp.document
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
        return when (val tvType =
            if (url.contains("/movies/")) TvType.Movie else TvType.TvSeries) {
            TvType.TvSeries -> {
                val title = soup.selectFirst("h1.title")!!.text()
                val description =
                    soup.selectFirst("div#tt-bd article.post header.entry-header aside p.entry-content")
                        ?.text()?.trim()
                val poster: String? =
                    soup.selectFirst("div#tt-bd article.post header.entry-header aside figure.post-thumbnail img")!!
                        .attr("src")
                val year =
                    soup.selectFirst("div#tt-bd article.post header.entry-header aside div.meta span.tag")
                        ?.text()?.toIntOrNull()
                val episodes = soup.select("div#MvTb-episodes div.widget").flatMap { season ->
                        val seasonNumber = season.selectFirst("div.title span")!!.text().toIntOrNull()
                        season.select("aside.anm-a div.episodes-cn nav.episodes-nv a.far").map {
                            val epurl = it.attr("href")
                            val epTitle = it.selectFirst("span")!!.text()
                            val episodeNumber = epTitle.substringAfter("Ep.").trim().toIntOrNull()
                            Episode(
                                epurl,
                                epTitle,
                                seasonNumber,
                                episodeNumber,
                            )
                        }
                    }

                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.year = year
                }
            }

            TvType.Movie -> {
                val title =
                    soup.selectFirst("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post div.entry-header header h1.title")!!
                        .text()
                val description =
                    soup.selectFirst("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post div.entry-header p.entry-content")
                        ?.text()?.trim()
                val poster: String? =
                    soup.selectFirst("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post figure.post-thumbnail img")!!
                        .attr("src")
                val tags =
                    soup.select("div#tt-bd div.tt-cont div.content main.site-main div.widget article.post div.entry-header div ul.more-details li p a")
                        .map { it.text() }
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
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
        val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
        app.get(data).document.selectFirst("iframe")?.attr("src")?.let { frameUrl ->
            app.get(frameUrl).document.selectFirst("iframe")?.attr("src")?.let { frameUrl2 ->
                regex.findAll(app.get(frameUrl2).document.html()).map { it.groupValues.get(2) }
                    .toList().apmap {
                    loadExtractor(it, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
