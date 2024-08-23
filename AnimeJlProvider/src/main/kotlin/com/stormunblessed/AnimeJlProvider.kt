package com.stormunblessed

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnimeJlProvider : MainAPI() {
    override var mainUrl = "https://www.anime-jl.net"
    override var name = "AnimeJL"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Latino", "$mainUrl/animes?genre[]=46&order=updated"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Donghuas", "$mainUrl/animes?tipo[]=7&order=updated"),
            Pair("Peliculas", "$mainUrl/animes?tipo[]=3&order=updated"),
        )

        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select("ul.ListAnimes li").map {
                val title = it.selectFirst("article.Anime h3.Title")?.text()
                val link = it.selectFirst("article.Anime a")?.attr("href")
                val img = it.selectFirst("article.Anime a div.Image figure img")?.attr("src")
                    ?.replaceFirst("^/".toRegex(), "$mainUrl/")
                TvSeriesSearchResponse(
                    title!!,
                    link!!,
                    this.name,
                    TvType.Anime,
                    img,
                )
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/animes?q=$query"
        val doc = app.get(url).document
        return doc.select("ul.ListAnimes li").map {
            val title = it.selectFirst("article.Anime h3.Title")?.text()
            val link = it.selectFirst("article.Anime a")?.attr("href")
            val img = it.selectFirst("article.Anime a div.Image figure img")?.attr("src")
                ?.replaceFirst("^/".toRegex(), "$mainUrl/")
            TvSeriesSearchResponse(
                title!!,
                link!!,
                this.name,
                TvType.Anime,
                img,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.Ficha div.Container h1.Title")?.text() ?: ""
        val backimage = doc.selectFirst("div.Ficha div.Bg")!!.attr("style")
            .substringAfter("background-image:url(").substringBefore(")")
        val poster = doc.selectFirst("div.Container div.Image figure img")!!.attr("src")
        val description =
            doc.selectFirst("div.Container main.Main section.WdgtCn div.Description")!!.text()
        val tags =
            doc.select("div.Container main.Main section.WdgtCn nav.Nvgnrs a").map { it.text() }
        val episodes = ArrayList<Episode>()
        val script =
            doc.select("script").firstOrNull { it.html().contains("var episodes =") }?.html()
        if (!script.isNullOrEmpty()) {
            val jsonscript =
                script.substringAfter("episodes = ").substringBefore(";").replace(",]", "]")
            val json = parseJson<List<List<String>>>(jsonscript)
            json.map { list ->
                var epNum = 0
                var epTitle = ""
                var epurl = ""
                var realimg = ""
                list.forEachIndexed { idx, it ->
                    if (idx == 0) {
                        epNum = it.toIntOrNull() ?: 0
                    } else if (idx == 1) {
                        epurl = "$url/$it"
                    } else if (idx == 2) {
                        realimg = "$mainUrl/storage/$it"
                    } else if (idx == 3) {
                        epTitle = it.ifEmpty { "Episodio $epNum" }
                    }
                }
                episodes.add(
                    Episode(
                        epurl,
                        epTitle,
                        0,
                        epNum,
                        realimg,
                    )
                )
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url, TvType.Anime, episodes,
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backimage
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val regex = """(<iframe src=)"(.*?)"""".toRegex()
        app.get(data).document.select("script")
            .firstOrNull { it.html().contains("var video = [];") }?.let { frameUrl ->
            regex.findAll(frameUrl.html()).map { it.groupValues.get(2) }.toList().apmap {
                loadExtractor(it, data, subtitleCallback, callback)
            }
        }
        return true
    }

}