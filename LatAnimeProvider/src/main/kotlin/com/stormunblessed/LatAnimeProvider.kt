package com.lagradost.cloudstream3.animeproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.*


class LatAnimeProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Pelicula")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://latanime.org"
    override var name = "LatAnime"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
            TvType.AnimeMovie,
            TvType.OVA,
            TvType.Anime,
    )

    private val cloudflareKiller = CloudflareKiller()
    suspend fun appGetChildMainUrl(url: String): NiceResponse {
        return app.get(url, interceptor = cloudflareKiller )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
                Pair("$mainUrl/emision", "En emisión"),
                Pair(
                        "$mainUrl/animes?fecha=false&genero=false&letra=false&categoria=Película",
                        "Peliculas"
                ),
                Pair("$mainUrl/animes", "Animes"),
        )

        val items = ArrayList<HomePageList>()
        val isHorizontal = true
//        items.add(
//                HomePageList(
//                        "Capítulos actualizados",
//                        appGetChildMainUrl(mainUrl).document.select(".col-6").map {
//                            val title = it.selectFirst("p.animetitles")?.text()
//                                    ?: it.selectFirst(".animetitles")?.text() ?: ""
//                            val poster =
//                                    it.selectFirst("img")?.attr("data-src") ?: ""
//
//                            val epRegex = Regex("episodio-(\\d+)")
//                            val url = it.selectFirst("a")?.attr("href")!!.replace("ver/", "anime/")
//                                    .replace(epRegex, "sub-espanol")
//                            val epNum = (it.selectFirst(".positioning h5")?.text()
//                                    ?: it.selectFirst("div.positioning p")?.text())?.toIntOrNull()
//                            newAnimeSearchResponse(title, url) {
//                                this.posterUrl = fixUrl(poster)
//                                addDubStatus(getDubStatus(title), epNum)
//                                this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
//                            }
//                        }, isHorizontal)
//        )

        urls.apmap { (url, name) ->
            val home = appGetChildMainUrl(url).document.select("html body div.container div.row div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
                val title = it.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series div.seriedetails h3.my-1")!!.text()
                val poster =
                        it.selectFirst("div.col-md-4.col-lg-3.col-xl-2.col-6.my-3 a div.series div.serieimg.shadown img.img-fluid2.shadow-sm")?.attr("src")
                                ?: ""

                newAnimeSearchResponse(title, fixUrl(it.selectFirst("a")!!.attr("href"))) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title))
                    this.posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
                }
            }

            items.add(HomePageList(name, home))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return appGetChildMainUrl("$mainUrl/buscar?q=$query").document.select("html body div.container div.row div.col-md-4.col-lg-3.col-xl-2.col-6.my-3").map {
            val title = it.selectFirst("a div.series div.seriedetails h3.my-1")!!.text()
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image = it.selectFirst("a div.series div.serieimg.shadown img.img-fluid2.shadow-sm")!!.attr("src")
            AnimeSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Anime,
                    fixUrl(image),
                    null,
                    if (title.contains("Latino") || title.contains("Castellano")) EnumSet.of(
                            DubStatus.Dubbed
                    ) else EnumSet.of(DubStatus.Subbed),
                    posterHeaders = if (image.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = appGetChildMainUrl(url).document
        val poster = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
        val backimage = doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha img.img-fluid2")!!.attr("src")
        val title = doc.selectFirst("div.col-lg-9.col-md-8 h2")!!.text()
        val type = doc.selectFirst("div.chapterdetls2")?.text() ?: ""
        val description = doc.selectFirst("div.col-lg-9.col-md-8 p.my-2.opacity-75")!!.text().replace("Ver menos", "")
        val genres = doc.select("div.col-lg-9.col-md-8 a div.btn").map { it.text() }
        val status = when (doc.selectFirst("div.col-lg-3.col-md-4 div.series2 div.serieimgficha div.my-2")?.text()) {
            "Estreno" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val episodes = doc.select("div.row div.col-lg-9.col-md-8 div.row div a").map {
            val name = it.selectFirst("div.cap-layout")!!.text()
            val link = it!!.attr("href")
            val epThumb = it.selectFirst(".animeimghv")?.attr("data-src")
                    ?: it.selectFirst("div.animeimgdiv img.animeimghv")?.attr("src")
            Episode(link, name)
        }
        return newAnimeLoadResponse(title, url, getType(title)) {
            posterUrl = poster
            backgroundPosterUrl = backimage
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            tags = genres
            posterHeaders = if (poster.contains(mainUrl)) cloudflareKiller.getCookieHeaders(mainUrl).toMap() else emptyMap<String, String>()
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        appGetChildMainUrl(data).document.select("div.container-fluid div.row div.col-md-12.col-lg-8.seiya ul.cap_repro.d-flex.flex-wrap li#play-video").apmap {
            val encodedurl = it.select("a").attr("data-player")
            val urlDecoded = base64Decode(encodedurl)
            val url = (urlDecoded).replace("https://monoschinos2.com/reproductor?url=", "")
                    .replace("https://sblona.com", "https://watchsb.com")
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}