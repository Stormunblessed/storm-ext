package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://cuevana3.ch"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/peliculas", "Recientemente actualizadas"),
            Pair("$mainUrl/estrenos", "Estrenos"),
        )
        items.add(
            HomePageList(
                "Series",
                app.get("$mainUrl/serie", timeout = 120).document.select("section.home-series li")
                    .map {
                        val title = it.selectFirst("h2.Title")!!.text()
                        val poster = it.selectFirst("img.lazy")!!.attr("data-src").replaceFirst("//", "https://")
                        val url = it.selectFirst("a")!!.attr("href")
                        TvSeriesSearchResponse(
                            title,
                            url,
                            this.name,
                            TvType.Anime,
                            poster,
                            null,
                            null,
                        )
                    })
        )
        urls.apmap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("section li.xxx.TPostMv").map {
                val title = it.selectFirst("h2.Title")!!.text()
                val link = it.selectFirst("a")!!.attr("href")
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst("img.lazy")!!.attr("data-src").replaceFirst("//", "https://"),
                    null,
                    null,
                )
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.html?keyword=${query}"
        val document = app.get(url).document

        return document.select("li.xxx.TPostMv").map {
            val title = it.selectFirst("h2.Title")!!.text()
            val href = it.selectFirst("a")!!.attr("href").replaceFirst("/", "$mainUrl/")
            val image = it.selectFirst("img.lazy")!!.attr("data-src").replaceFirst("//", "https://")
            val isSerie = href.contains("/serie/")

            if (isSerie) {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            } else {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        val title = soup.selectFirst("h1.Title")!!.text()
        val description = soup.selectFirst(".Description p")?.text()?.trim()
        val poster: String? = soup.selectFirst(".movtv-info div.Image img")!!.attr("data-src")
            .replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/")
        val backgrounposter = soup.selectFirst("img.lazy")!!.attr("data-src").replaceFirst("//", "https://")
        val year1 = soup.selectFirst("footer p.meta").toString()
        val yearRegex = Regex("<span>(\\d+)</span>")
        val yearf =
            yearRegex.find(year1)?.destructured?.component1()?.replace(Regex("<span>|</span>"), "")
        val year = if (yearf.isNullOrBlank()) null else yearf.toIntOrNull()
        val episodes = soup.select(".all-episodes li.TPostMv article").map { li ->
            val href = li.select("a").attr("href").replaceFirst("^/".toRegex(), "$mainUrl/")
            val epThumb =
                li.selectFirst("div.Image img")?.attr("data-src") ?: li.selectFirst("img.lazy")!!
                    .attr("data-srcc").replace(Regex("\\/w\\d+\\/"),"/w780/")
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
        val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a").map { it.text() }
        val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
        val recelement =
            if (tvType == TvType.TvSeries) "main section div.series_listado.series div.xxx"
            else "main section ul.MovieList li"
        val recommendations =
            soup.select(recelement).mapNotNull { element ->
                val recTitle = element.select("h2.Title").text() ?: return@mapNotNull null
                val image = element.select("figure img")?.attr("data-src")
                val recUrl = fixUrl(element.select("a").attr("href"))
                MovieSearchResponse(
                    recTitle,
                    recUrl,
                    this.name,
                    TvType.Movie,
                    image,
                    year = null
                )
            }
        val trailer = soup.selectFirst("div.TPlayer.embed_div div[id=OptY] iframe")?.attr("data-src") ?: ""


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title,
                    url, tvType, episodes,){
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgrounposter
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url){
                    this.posterUrl = poster
                    this.plot = description
                    this.backgroundPosterUrl = backgrounposter
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                    if (trailer.isNotBlank()) addTrailer(trailer)
                }

            }
            else -> null
        }
    }

    data class Femcuevana(
        @JsonProperty("url") val url: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("li.open_submenu").map {
            it.select("li.clili").map {
                val iframe = fixUrl(it.attr("data-video").replaceFirst("^//".toRegex(), "https://"))
                loadExtractor(iframe, mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}