package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class ElifilmsProvider : MainAPI() {
    override var mainUrl: String = "https://allcalidad.ms/"
    override var name: String = "Elifilms"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val newest = app.get(mainUrl).document.selectFirst("a.fav_link.premiera")?.attr("href")
        val urls = listOf(
            Pair(mainUrl, "Películas recientes"),
            Pair("$mainUrl/4k-peliculas/", "Películas en 4k"),
            Pair(newest, "Últimos estrenos"),
        )
        urls.apmap { (url, name) ->
            val searchlist = ArrayList<TvSeriesSearchResponse>()
            val soup = app.get(url ?: "").document
            val home = soup.select("article.shortstory.cf").map {
                val title = it.selectFirst(".short_header")?.text() ?: ""
                val link = it.selectFirst("div a")?.attr("href") ?: ""
                val verified = !link.contains("disney") && !link.contains("hbo-max")
                val image = it.selectFirst("a.ah-imagge img")?.attr("data-src")
                if (verified) {
                    searchlist.add(
                        TvSeriesSearchResponse(
                            title,
                            link,
                            this.name,
                            TvType.Movie,
                            image,
                            null,
                            null,
                        ))
                }

            }
            items.add(HomePageList(name, searchlist))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        val search = ArrayList<MovieSearchResponse>()
        doc.select("article.cf").map {
            val href = it.selectFirst("div.short_content a")?.attr("href") ?: ""
            val poster = it.selectFirst("a.ah-imagge img")?.attr("data-src")
            val verified = !href.contains("disney") && !href.contains("hbo-max")
            val name = it.selectFirst(".short_header")?.text() ?: ""
            if (verified) {
                search.add(
                    (MovieSearchResponse(name, href, this.name, TvType.Movie, poster, null)))
            }
        }
        return search
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 120).document
        val title = document.selectFirst(".post_title h1")?.text() ?: ""
        val rating = document.select("span.imdb.rki").toString().toIntOrNull()
        val poster = document.selectFirst(".poster img")?.attr("src")
        val desc = document.selectFirst("div.notext .actors p")?.text()
        val tags = document.select("td.notext a")
            .map { it?.text()?.trim().toString() }
        return MovieLoadResponse(
            title,
            url,
            this.name,
            TvType.Movie,
            url,
            poster,
            null,
            desc,
            rating,
            tags
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("li.change-server a").apmap {
            val encodedurl = it.attr("data-id")
            val urlDecoded = base64Decode(encodedurl)
            val url = fixUrl(urlDecoded)
            loadExtractor(url, data, subtitleCallback, callback)
        }
        return true
    }
}
