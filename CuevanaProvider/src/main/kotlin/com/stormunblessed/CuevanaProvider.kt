package com.lagradost.cloudstream3.movieproviders

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://cuevana.biz"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/peliculas", "Peliculas actualizadas"),
            Pair("$mainUrl/peliculas/estrenos", "Peliculas Estrenos"),
            Pair("$mainUrl/series", "Series actualizadas"),
            Pair("$mainUrl/series/estrenos", "Series Estrenos"),
        )
        urls.apmap { (url, name) ->
            val soup = app.get(url).document
            val home = soup.select("section li.TPostMv").map {
                val title = it.selectFirst("span.Title")!!.text()
                val link = it.selectFirst("a")!!.attr("href").replaceFirst("/", "$mainUrl/")
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst("img")!!.attr("src").replaceFirst("/", "$mainUrl/"),
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
        val url = "$mainUrl/search?q=${query}"
        val document = app.get(url).document

        return document.select("li.TPostMv").map {
            val title = it.selectFirst("span.Title")!!.text()
            val href = it.selectFirst("a")!!.attr("href").replaceFirst("/", "$mainUrl/")
            val image = it.selectFirst("img")!!.attr("src").replaceFirst("/", "$mainUrl/")
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

    data class SeriesResponse(
        val props: Props?
    )

    data class Props(
        val pageProps: PageProps
    )

    data class PageProps(
        val thisSerie: Series
    )

    data class Series(
        val seasons: List<Season>,
        val titles: Titles
    )

    data class Season(
        val number: Int,
        val episodes: List<Episode>
    )

    data class Episode(
        val title: String,
        val TMDbId: String,
        val number: Int,
        val releaseDate: String,
        val image: String,
        val url: Url
    )

    data class Titles(
        val name: String,
        val original: OriginalTitle
    )

    data class OriginalTitle(
        val name: String
    )

    data class Url(
        val slug: String
    )

    fun parseSeriesData(jsonString: String): SeriesResponse? {
        try {
            return parseJson<SeriesResponse>(jsonString)
        } catch (error: Exception) {
            return null;
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        val title = soup.selectFirst("h1.Title")!!.text()
        val description = soup.selectFirst(".Description p")?.text()?.trim()
        val poster: String? = soup.selectFirst("div.backdrop article.TPost div.Image img")!!.attr("src")
            .replaceFirst("/", "$mainUrl/")
        val backgrounposter =
            soup.selectFirst("div.Image:nth-child(2) img")!!.attr("src").replaceFirst("/", "$mainUrl/")
        val year1 = soup.selectFirst("footer p.meta").toString()
        val yearRegex = Regex("<span>(\\d+)</span>")
        val yearf =
            yearRegex.find(year1)?.destructured?.component1()?.replace(Regex("<span>|</span>"), "")
        val year = if (yearf.isNullOrBlank()) null else yearf.toIntOrNull()
        val episodes = soup.select("script#__NEXT_DATA__").firstOrNull().let {
            parseSeriesData(it!!.html())?.props?.pageProps?.thisSerie?.seasons?.flatMap { season ->
                season.episodes.apmap {
                    Episode(
                        it.url.slug
                            .replace("series/", "$mainUrl/serie/")
                            .replace("seasons/", "temporada/")
                            .replace("episodes/", "episodio/"),
                        it.title,
                        season.number,
                        it.number,
                        it.image
                    )
                }
            }
        }.orEmpty()
        val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a").map { it.text() }
        val tvType = if (episodes == null || episodes.isEmpty()) TvType.Movie else TvType.TvSeries
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
        val trailer =
            soup.selectFirst("div.TPlayer.embed_div div[id=OptY] iframe")?.attr("data-src") ?: ""


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backgrounposter
                    this.plot = description
                    this.year = year
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
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
            it.select("li.clili").apmap {
                val iframe = fixUrl(it.attr("data-tr"))
                app.get(iframe).document.select("script")
                    .firstOrNull { it.html().contains("var url = '") }?.html()
                    ?.substringAfter("var url = '")?.substringBefore("';")?.let {
                        loadExtractor(it, mainUrl, subtitleCallback, callback)
                    }
            }
        }
        return true
    }
}