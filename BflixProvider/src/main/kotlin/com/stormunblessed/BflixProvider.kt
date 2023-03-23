package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

open class BflixProvider : MainAPI() {
    override var mainUrl = "https://bflix.ru"
    override var name = "Bflix"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
        private const val bfliKey =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        private const val mainKey = "DZmuZuXqa9O0z3b7"

        fun encodeVrf(text: String, mainKey: String): String {
            return encode(
                encrypt(
                    cipher(mainKey, encode(text)),
                    bfliKey
                )//.replace("""=+$""".toRegex(), "")
            )
        }

        fun decodeVrf(text: String, mainKey: String): String {
            return decode(cipher(mainKey, decrypt(text, bfliKey)))
        }

        fun encrypt(input: String, key: String): String {
            if (input.any { it.code > 255 }) throw Exception("illegal characters!")
            var output = ""
            for (i in input.indices step 3) {
                val a = intArrayOf(-1, -1, -1, -1)
                a[0] = input[i].code shr 2
                a[1] = (3 and input[i].code) shl 4
                if (input.length > i + 1) {
                    a[1] = a[1] or (input[i + 1].code shr 4)
                    a[2] = (15 and input[i + 1].code) shl 2
                }
                if (input.length > i + 2) {
                    a[2] = a[2] or (input[i + 2].code shr 6)
                    a[3] = 63 and input[i + 2].code
                }
                for (n in a) {
                    if (n == -1) output += "="
                    else {
                        if (n in 0..63) output += key[n]
                    }
                }
            }
            return output
        }

        fun cipher(key: String, text: String): String {
            val arr = IntArray(256) { it }

            var u = 0
            var r: Int
            arr.indices.forEach {
                u = (u + arr[it] + key[it % key.length].code) % 256
                r = arr[it]
                arr[it] = arr[u]
                arr[u] = r
            }
            u = 0
            var c = 0

            return text.indices.map { j ->
                c = (c + 1) % 256
                u = (u + arr[c]) % 256
                r = arr[c]
                arr[c] = arr[u]
                arr[u] = r
                (text[j].code xor arr[(arr[c] + arr[u]) % 256]).toChar()
            }.joinToString("")
        }

        @Suppress("SameParameterValue")
        private fun decrypt(input: String, key: String): String {
            val t = if (input.replace("""[\t\n\f\r]""".toRegex(), "").length % 4 == 0) {
                input.replace("""==?$""".toRegex(), "")
            } else input
            if (t.length % 4 == 1 || t.contains("""[^+/0-9A-Za-z]""".toRegex())) throw Exception("bad input")
            var i: Int
            var r = ""
            var e = 0
            var u = 0
            for (o in t.indices) {
                e = e shl 6
                i = key.indexOf(t[o])
                e = e or i
                u += 6
                if (24 == u) {
                    r += ((16711680 and e) shr 16).toChar()
                    r += ((65280 and e) shr 8).toChar()
                    r += (255 and e).toChar()
                    e = 0
                    u = 0
                }
            }
            return if (12 == u) {
                e = e shr 4
                r + e.toChar()
            } else {
                if (18 == u) {
                    e = e shr 2
                    r += ((65280 and e) shr 8).toChar()
                    r += (255 and e).toChar()
                }
                r
            }
        }

        fun encode(input: String): String =
            java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20")

        private fun decode(input: String): String = java.net.URLDecoder.decode(input, "utf-8")
    }


    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val soup = app.get("$mainUrl/home").document
        val testa = listOf(
            Pair("Movies", "div.tab-content[data-name=movies] div.filmlist div.item"),
            Pair("Shows", "div.tab-content[data-name=shows] div.filmlist div.item"),
            Pair("Trending", "div.tab-content[data-name=trending] div.filmlist div.item"),
            Pair(
                "Latest Movies",
                "div.container section.bl:contains(Latest Movies) div.filmlist div.item"
            ),
            Pair(
                "Latest TV-Series",
                "div.container section.bl:contains(Latest TV-Series) div.filmlist div.item"
            ),
        )

        testa.apmap {(name, element) ->
            val test = soup.select(element).map {
                val title = it.selectFirst("h3 a")!!.text()
                val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                val qualityInfo = it.selectFirst("div.quality")!!.text()
                val quality = getQualityFromString(qualityInfo)
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst("a.poster img")!!.attr("src"),
                    null,
                    null,
                    quality = quality
                )
            }
            items.add(HomePageList(name, test))
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }


    data class QuickSearchResult(
        @JsonProperty("html") val html: String? = null,
        //@JsonProperty("linkMore") val linkMore: String? = null
    )
    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        val encodedquery = encodeVrf(query, mainKey)
        val url = "$mainUrl/ajax/film/search?vrf=$encodedquery&keyword=$query"
        val response = app.get(url).parsedSafe<QuickSearchResult>()
        val elementa = if (mainUrl.contains("fmovies")) "a.item" else "a"
        val document = Jsoup.parse(response?.html ?: return null)
        return document.select(elementa).mapNotNull {element ->
            val link = fixUrl(element?.attr("href") ?: return@mapNotNull null)
            val title = (element.selectFirst("div.title") ?: element.selectFirst("div.name"))?.text() ?: return@mapNotNull null
            val img = (element.selectFirst("div.poster img") ?: element.selectFirst("img"))?.attr("src") ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, link){
                this.posterUrl = img
            }
        }
    }
    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedquery = encodeVrf(query, mainKey)
        val url = "$mainUrl/search?keyword=$query&vrf=$encodedquery"
        val html = app.get(url).text
        val document = Jsoup.parse(html)

        return document.select(".filmlist div.item").map {
            val title = it.selectFirst("h3 a")!!.text()
            val href = fixUrl(it.selectFirst("a")!!.attr("href"))
            val image = it.selectFirst("a.poster img")!!.attr("src")
            val isMovie = href.contains("/movie/")
            val qualityInfo = it.selectFirst("div.quality")!!.text()
            val quality = getQualityFromString(qualityInfo)

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null,
                    quality = quality
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null,
                    quality = quality
                )
            }
        }
    }

    data class Response(
        @JsonProperty("html") val html: String
    )

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document
        val movieid = soup.selectFirst("div#watch")!!.attr("data-id")
        val movieidencoded = encodeVrf(movieid, mainKey)
        val title = soup.selectFirst("div.info h1")!!.text()
        val description = soup.selectFirst(".info .desc")?.text()?.trim()
        val poster: String? = try {
            soup.selectFirst("img.poster")!!.attr("src")
        } catch (e: Exception) {
            soup.selectFirst(".info .poster img")!!.attr("src")
        }


        val backimginfo = (soup.selectFirst(".play")?: soup.selectFirst(".backdrop"))?.attr("style")
        val backimgRegx = Regex("(http|https).*jpg")
        val backposter = backimgRegx.find(backimginfo.toString())?.value ?: poster
        val tags = soup.select("div.info .meta div:contains(Genre) a").map { it.text() }
        val vrfUrl = "$mainUrl/ajax/film/servers?id=$movieid&vrf=$movieidencoded"
        val episodes = Jsoup.parse(
            app.get(
                vrfUrl
            ).parsed<Response>().html
        ).select("div.episode").map {
            val a = it.selectFirst("a")
            val href = fixUrl(a!!.attr("href"))
            val extraData = a.attr("data-kname").let { str ->
                str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
            }
            val isValid = extraData.size == 2
            val episode = if (isValid) extraData.getOrNull(1) else null
            val season = if (isValid) extraData.getOrNull(0) else null
            val secondhref = if (episode == null || season == null) "$url/1-full" else "$url/$season-$episode"
            val eptitle = it.selectFirst(".episode a span.name")!!.text()
            val secondtitle = it.selectFirst(".episode a span")!!.text()
                .replace(Regex("(Episode (\\d+):|Episode (\\d+)-|Episode (\\d+))"), "")
            Episode(
                secondhref,
                secondtitle + eptitle,
                season,
                episode,
            )
        }
        val tvType =
            if (url.contains("/movie/") && episodes.size == 1) TvType.Movie else TvType.TvSeries
        val recommendations =
            soup.select("div.bl-2 section.bl div.content div.filmlist div.item")
                .mapNotNull { element ->
                    val recTitle = element.select("h3 a").text() ?: return@mapNotNull null
                    val image = element.select("a.poster img").attr("src")
                    val recUrl = fixUrl(element.select("a").attr("href"))
                    MovieSearchResponse(
                        recTitle,
                        recUrl,
                        this.name,
                        if (recUrl.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                        image,
                        year = null
                    )
                }
        val rating = soup.selectFirst(".info span.imdb")?.text()?.toRatingInt()
        val durationdoc = (soup.selectFirst("div.meta > span:nth-child(4)") ?: soup.selectFirst("div.meta > span:nth-child(3)"))?.text() ?: ""
        val bflix = mainUrl == "https://bflix.ru"
        val year = if (bflix) soup.selectFirst("div.meta > span:nth-child(3)")?.text()
        else soup.selectFirst("div.meta div span[itemprop=dateCreated]")?.text()?.substringBefore("-")
        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backposter
                    this.plot = description
                    this.rating = rating
                    this.recommendations = recommendations
                    this.tags = tags
                    this.year = year?.toIntOrNull()
                    addDuration(durationdoc)
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backposter
                    this.plot = description
                    this.rating = rating
                    this.recommendations = recommendations
                    this.tags = tags
                    this.year = year?.toIntOrNull()
                    addDuration(durationdoc)
                }
            }
            else -> null
        }
    }


    data class Subtitles(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val kind: String
    )

    data class Links(
        @JsonProperty("url") val url: String
    )

    data class Servers(
        @JsonProperty("28") val mcloud: String?,
        @JsonProperty("35") val mp4upload: String?,
        @JsonProperty("40") val streamtape: String?,
        @JsonProperty("41") val vidstream: String?,
        @JsonProperty("45") val filemoon: String?
    )

    class ServersID(elements: Map<String, String>) : HashMap<String, String>(elements)



    private suspend fun getStream(
        streamLink: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    )  {
        return M3u8Helper.generateM3u8(
            name,
            streamLink,
            referer
        ).forEach(callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val soup = app.get(data).document

        val movieid = encode(soup.selectFirst("div#watch")?.attr("data-id") ?: return false)
        val movieidencoded = encodeVrf(movieid, mainKey)
        Jsoup.parse(
            parseJson<Response>(
                app.get(
                    "$mainUrl/ajax/film/servers?id=$movieid&vrf=$movieidencoded"
                ).text
            ).html
        )
            .select("html body #episodes").map {
                val cleandata = data.replace(mainUrl, "")
                val a = it.select("a").map {
                    it.attr("data-kname")
                }
                val tvType =
                    if (data.contains("movie/") && a.size == 1) TvType.Movie else TvType.TvSeries
                val servers = if (tvType == TvType.Movie) it.select(".episode a").attr("data-ep")
                else
                    it.select(".episode a[href=$cleandata]").attr("data-ep")
                        ?: it.select(".episode a[href=${cleandata.replace("/1-full", "")}]")
                            .attr("data-ep")
                val jsonservers = parseJson<Servers?>(servers) ?: return@map
                listOfNotNull(
                    jsonservers.vidstream,
                    jsonservers.streamtape,
                    jsonservers.filemoon,
                ).map {
                    val epserver = app.get("$mainUrl/ajax/episode/info?id=$it").text
                    if (epserver.contains("url")) {
                        val serversJson = parseJson<Links>(epserver)
                        val links = decode(decodeVrf(serversJson.url, mainKey))
                        if (links.contains("vidstream")) {
                            val regex = Regex("(.+?/)e(?:mbed)?/([a-zA-Z0-9]+)")
                            val group = regex.find(links)!!.groupValues
                            val vizId = group[2]
                            val ssae = app.get("https://api.consumet.org/anime/9anime/helper?query=$vizId&action=vizcloud").text
                            val reg2 = Regex("((https|http).*list.*(m3u8|.mp4))")
                            val m3u8 = reg2.find(ssae)?.destructured?.component1() ?: ""
                            val ref = "https://vidstream.pro/"
                            M3u8Helper.generateM3u8(
                                "Vidstream",
                                m3u8.replace("#.mp4", ""),
                                ref
                            ).apmap {
                                callback(
                                    ExtractorLink(
                                        "Vidstream",
                                        "Vidstream",
                                        it.url,
                                        ref,
                                        getQualityFromName(it.quality.toString()),
                                        true
                                    )
                                )
                            }
                        } else {
                            loadExtractor(links, subtitleCallback, callback)
                        }
                    }
                }
                //Apparently any server works, I haven't found any diference
                val sublink =
                    app.get("$mainUrl/ajax/episode/subtitles/${jsonservers.mcloud}").text
                val jsonsub = parseJson<List<Subtitles>>(sublink)
                jsonsub.forEach { subtitle ->
                    subtitleCallback(
                        SubtitleFile(subtitle.label, subtitle.file)
                    )
                }
            }

        return true
    }
}