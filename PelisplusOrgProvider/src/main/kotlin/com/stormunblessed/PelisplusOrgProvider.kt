package com.stormunblessed

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.time.LocalDate
import java.util.Calendar

class PelisplusOrgProvider : MainAPI() {
    override var mainUrl = "https://www.pelisplusgo.lat"
    override var name = "PelisplusOrg"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val urls = listOf(
            Pair("Películas Recién Agregadas", "$mainUrl/"),
            Pair("Estrenos", "$mainUrl/estrenos/"),
            Pair("Series", "$mainUrl/series/"),
            Pair("Estrenos $year", "$mainUrl/peliculas-$year/"),
        )

        urls.apmap { (name, url) ->
            val doc = app.get(url, referer = "$mainUrl/").document
            val home =
                doc.select("div.app div.main_cont div.main_lista div.main_lista_box a.card").map {
                    val title = it.selectFirst("div.card_title h3")?.text()
                    val link = it.attr("href")
                    val img =
                        it.selectFirst("div.card_imagen figure span.card_img img")?.attr("img")
                    val year = it.selectFirst("div.card_imagen figure span.card_anio")?.attr("data-year")
                        ?.toIntOrNull()
                    TvSeriesSearchResponse(
                        title!!,
                        link!!,
                        this.name,
                        TvType.TvSeries,
                        fixUrl(img!!),
                        year,
                    )
                }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar/$query/"
        val doc = app.get(url).document
        return doc.select("div.app div.main_cont div.main_lista a.card").apmap {
            val title = it.selectFirst("div.card_title h3")?.text()
            val link = it.attr("href")
            val img = it.selectFirst("div.card_imagen figure span.card_img")?.html()?.substringAfter("img=\"")?.substringBefore("\"")
            val year =
                it.selectFirst("div.card_imagen figure span.card_anio")?.attr("data-year")?.toIntOrNull()
            TvSeriesSearchResponse(
                title!!,
                link!!,
                this.name,
                TvType.TvSeries,
                fixUrl(img!!),
                year,
            )
        }
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) :
        HashMap<String, List<MainTemporadaElement>>(elements)

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.pelicula div.info div.info_text h1")?.text() ?: ""
        val backimage = doc.selectFirst("div.pelicula div div.pelicula_player img")?.attr("img")
        val poster = doc.selectFirst("div.pelicula div.info div.info_img img")!!.attr("img")
        val description = doc.selectFirst("div.pelicula div.info div.info_text div.descripcion p")!!.text()
        val tags = doc.selectFirst("div.pelicula div.info div.info_text div.m_info ul li span")?.text()?.split(", ")
        val year = doc.selectFirst("div.pelicula div.info div.info_text div.datos p span")?.text()?.toIntOrNull()
        var episodes = if (tvType == TvType.TvSeries) {
            doc.select("div.pelicula div.serie--selectorepisode div.box-inner div.serie--episodelist div.season").flatMap { season ->
                val seasonNumber = season.attr("data-season").toIntOrNull()
                season.select("a").map {
                    val epurl = it.attr("href")
                    val episodeNumber = epurl.split("/").filter { it.trim().isNotEmpty() }.last().split("x").last().toIntOrNull()
                    val epTitle = it.selectFirst("div.episode__center strong")!!.text()
                    val realimg = it.selectFirst("div.episode__cover img")?.attr("src")
                    Episode(
                        epurl,
                        epTitle,
                        seasonNumber,
                        episodeNumber,
                        realimg,
                    )
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage ?: poster
                    this.plot = description
                    this.tags = tags
                    this.year = year
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage ?: poster
                    this.plot = description
                    this.tags = tags
                    this.year = year
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
        val doc = app.get(data).document
        doc.select("ul.pelicula_opciones li").apmap {
            it.select("div.opt ul li").apmap {
                loadExtractor(it.attr("data-server"), mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }

}