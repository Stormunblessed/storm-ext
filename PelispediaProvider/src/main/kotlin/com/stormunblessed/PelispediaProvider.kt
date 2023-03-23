package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class PelispediaProvider:MainAPI() {
    override var mainUrl = "https://pelispedia.is"
    override var name = "Pelispedia"
    override var lang = "es"

    override val hasQuickSearch = false
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
            Pair("Películas","$mainUrl/cartelera-peliculas/"),
            Pair("Series","$mainUrl/cartelera-series/"),
        )
        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home =  doc.select("section.movies article").map {
                val title = it.selectFirst("h2.entry-title")?.text() ?: ""
                val img = it.selectFirst("img")?.attr("src") ?: ""
                val link = it.selectFirst("a.lnk-blk")?.attr("href") ?: ""
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    TvType.Movie,
                    fixUrl(img),
                    null,
                    null,
                )
            }
            items.add(HomePageList(name, home))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("section.movies article").map {
            val title = it.selectFirst("h2.entry-title")?.text() ?: ""
            val img = it.selectFirst("img")!!.attr("src")
            val link = it.selectFirst("a.lnk-blk")!!.attr("href")
            TvSeriesSearchResponse(
                title,
                link,
                this.name,
                TvType.Movie,
                fixUrl(img),
                null,
                null,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val poster = doc.selectFirst(".alg-ss img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/") ?: ""
        val backimage = doc.selectFirst(".bghd  img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"),"/p/original/") ?: poster
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val plot = doc.selectFirst(".description > p:nth-child(2)")?.text() ?: doc.selectFirst(".description > p")?.text()
        val tags = doc.select("span.genres a").map { it.text() }
        val yearrr = doc.selectFirst("span.year.fa-calendar.far")?.text()?.toIntOrNull()
        val duration = doc.selectFirst("span.duration.fa-clock.far")?.text()
        val seasonsdoc = doc.select("div.choose-season li a").map {
            val seriesid = it.attr("data-post")
            val dataseason = it.attr("data-season")
            Pair(seriesid, dataseason)
        }
        val epi = ArrayList<Episode>()
        seasonsdoc.apmap {(serieid, data) ->
            val seasonsrequest = app.post("https://pelispedia.is/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "action_select_season",
                    "season" to data,
                    "post" to serieid,
                )
            ).document
            seasonsrequest.select("li article.episodes").map { li ->
                val href = li.selectFirst("a.lnk-blk")!!.attr("href")
                val seasonregex = Regex("(temporada-\\d+-capitulo-\\d+)")
                val seasonstring = seasonregex.find(href)?.destructured?.component1()
                    ?.replace("temporada-","")?.replace("-capitulo","") ?: ""
                val seasonid = seasonstring.let { str ->
                    str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
                }
                val isValid = seasonid.size == 2
                val episode = if (isValid) seasonid.getOrNull(1) else null
                val season = if (isValid) seasonid.getOrNull(0) else null
                epi.add(Episode(
                    href,
                    null,
                    season,
                    episode,
                ))
            }
        }

        val recs = doc.select("article.movies").mapNotNull { rec ->
            val recTitle = rec.selectFirst(".entry-title")?.text() ?: ""
            val recImg = rec.selectFirst("img")?.attr("src") ?: ""
            val recLink = rec.selectFirst("a")?.attr("href") ?: ""
            newTvSeriesSearchResponse(recTitle, recLink, TvType.TvSeries) {
                this.posterUrl = fixUrl(recImg)
            }
        }


        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title,
                    url, tvType, epi,){
                    this.posterUrl = fixUrl(poster)
                    this.backgroundPosterUrl = fixUrl(backimage)
                    this.plot = plot
                    this.tags = tags
                    this.year = yearrr
                    this.recommendations = recs
                    addDuration(duration)
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url){
                    this.posterUrl = fixUrl(poster)
                    this.backgroundPosterUrl = fixUrl(backimage)
                    this.plot = plot
                    this.tags = tags
                    this.year = yearrr
                    this.recommendations = recs
                    addDuration(duration)
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
        app.get(data).document.select(".player iframe").apmap {
            val trembedlink = it.attr("data-src")
            val tremrequest = app.get(trembedlink).document
            val link = tremrequest.selectFirst("div.Video iframe")!!.attr("src")
            loadExtractor(link, data, subtitleCallback, callback)
        }
        return true
    }
}