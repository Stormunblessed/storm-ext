package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.extractors.helper.CryptoJS

class PelisplusHDProvider:MainAPI() {
    override var mainUrl = "https://pelisplushd.bz"
    override var name = "PelisplusHD"
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
        val document = app.get(mainUrl).document
        val map = mapOf(
            "Películas" to "#default-tab-1",
            "Series" to "#default-tab-2",
            "Anime" to "#default-tab-3",
            "Doramas" to "#default-tab-4",
        )
        map.forEach {
            items.add(HomePageList(
                it.key,
                document.select(it.value).select("a.Posters-link").map { element ->
                    element.toSearchResult()
                }
            ))
        }
        return HomePageResponse(items)
    }
    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".listing-content p").text()
        val href = this.select("a").attr("href")
        val posterUrl = fixUrl(this.select(".Posters-img").attr("src"))
        val isMovie = href.contains("/pelicula/")
        val isAnime = href.contains("/anime/")
        return if (isMovie) {
            MovieSearchResponse(
                title,
                href,
                name,
                TvType.Movie,
                posterUrl,
                null
            )
        }else {
            TvSeriesSearchResponse(
                title,
                href,
                name,
                TvType.Movie,
                posterUrl,
                null,
                null
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url).document

        return document.select("a.Posters-link").map {
            val title = it.selectFirst(".listing-content p")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst(".Posters-img")?.attr("src")?.let { it1 -> fixUrl(it1) }
            val isMovie = href.contains("/pelicula/")
            val isAnime = href.contains("/anime/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Anime,
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
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document

        val title = soup.selectFirst(".m-b-5")?.text()
        val description = soup.selectFirst("div.text-large")?.text()?.trim()
        val poster: String? = soup.selectFirst(".img-fluid")?.attr("src")
        val episodes = soup.select("div.tab-pane .btn").map { li ->
            val href = li.selectFirst("a")?.attr("href")
            val name = li.selectFirst(".btn-primary.btn-block")?.text()?.replace(Regex("(T(\\d+).*E(\\d+):)"),"")?.trim()
            val seasoninfo = href?.substringAfter("temporada/")?.replace("/capitulo/","-")
            val seasonid =
                seasoninfo.let { str ->
                    str?.split("-")?.mapNotNull { subStr -> subStr.toIntOrNull() }
                }
            val isValid = seasonid?.size == 2
            val episode = if (isValid) seasonid?.getOrNull(1) else null
            val season = if (isValid) seasonid?.getOrNull(0) else null
            Episode(
                href!!,
                name,
                season,
                episode,
            )
        }

        val year = soup.selectFirst(".p-r-15 .text-semibold")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = soup.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold")
            .map { it?.text()?.trim().toString().replace(", ","") }

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title!!,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    fixUrl(poster!!),
                    year,
                    description,
                    null,
                    null,
                    tags,
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title!!,
                    url,
                    this.name,
                    tvType,
                    url,
                    fixUrl(poster!!),
                    year,
                    description,
                    null,
                    tags,
                )
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
        app.get(data).document.select("div.player").map{script ->
            fetchUrls(script.data()).apmap { link ->
                val doc = app.get(link).document
                val isNewEmbed = doc.select(".ODDIV div").isEmpty()

                if(!isNewEmbed){
                    doc.select(".ODDIV div li").apmap {
                        val linkExtracted = it.attr("onclick").substringAfter("go_to_playerVast('")
                            .substringBefore("',")
                        loadExtractor(linkExtracted, subtitleCallback,callback)
                    }
                }else{
                    val scriptData = doc.select("script").lastOrNull().toString()
                    val regexKey = """CryptoJS\.AES\.decrypt\(.*?,\s*['"](.+?)['"]\)""".toRegex()
                    val aesKey = regexKey.find(scriptData)!!.value.substringAfter("CryptoJS.AES.decrypt(encryptedLink, '")
                        .substringBefore("')")
                    val jsonRegex = """const dataLink = (\[.*?\]);""".toRegex(RegexOption.DOT_MATCHES_ALL)
                    val jsonExtracted = jsonRegex.find(scriptData)!!.groupValues[1]
                    val dataLink = JSONObject("{\"dataLink\": $jsonExtracted}").getJSONArray("dataLink")

                    for (i in 0 until dataLink.length()){
                        val sortedEmbeds = dataLink.getJSONObject(i).getJSONArray("sortedEmbeds")

                        for (j in 0 until sortedEmbeds.length()){
                            val linkExtracted = sortedEmbeds.getJSONObject(j).getString("link")
                            val linkDecrypted = CryptoJS.decrypt(aesKey, linkExtracted)
                            loadExtractor(linkDecrypted, subtitleCallback, callback)
                        }
                    }

                }
            }
        }
        return true
    }
}
