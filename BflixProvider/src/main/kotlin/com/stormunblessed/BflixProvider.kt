package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup

open class BflixProvider : MainAPI() {
    override var mainUrl = "https://bflix.ru"
    override var name = "Bflix"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    companion object {
        private val mediaType = "application/json; charset=utf-8".toMediaType()
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
            Pair("Movies", "div.tab-content[data-name=movies] div.film div.film-inner"),
            Pair("Shows", "div.tab-content[data-name=shows] div.film div.film-inner"),
            Pair("Trending", "div.tab-content[data-name=trending] div.film div.film-inner"),
            Pair(
                "Latest Movies",
                "div.zone:contains(Latest Movies) div.film div.film-inner"
            ),
            Pair(
                "Latest TV Shows",
                "div.zone:contains(Latest TV Shows) div.film div.film-inner"
            ),
        )

        testa.apmap {(name, element) ->
            val test = soup.select(element).map {
                val title = it.selectFirst("a.film-name")!!.text()
                val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                val qualityInfo = it.selectFirst("div.quality")!!.text()
                val quality = getQualityFromString(qualityInfo)
                TvSeriesSearchResponse(
                    title,
                    link,
                    this.name,
                    if (link.contains("/movie/")) TvType.Movie else TvType.TvSeries,
                    it.selectFirst("a.film-poster img")!!.attr("data-src"),
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
/*
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
} */
override suspend fun search(query: String): List<SearchResponse>? {
    //val encodedquery = encodeVrf(query, mainKey)
    val url = "$mainUrl/filter?keyword=$query"
    val document = app.get(url).document
    return document.select("div.film div.film-inner").map {
        val title = it.selectFirst("a.film-name")!!.text()
        val href = fixUrl(it.selectFirst("a")!!.attr("href"))
        val image = it.selectFirst("a.film-poster img")!!.attr("data-src")
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
    @JsonProperty("result") val result: String
)

    data class VrfResponse (

        @JsonProperty("url" ) val url : String

    )
    private suspend fun  vrfHelper(action:String, query: String):String {
        val parse = app.get("https://9anime.eltik.net/$action?query=$query&apikey=lagrapps").parsed<VrfResponse>()
        return parse.url
    }

override suspend fun load(url: String): LoadResponse? {
    val soup = app.get(url).document
    val movieid = soup.selectFirst("div.film-rating")!!.attr("data-id")
    // val movieidencoded = encodeVrf(movieid, mainKey)
    val movieidencoded = vrfHelper("fmovies-vrf", movieid)

    val title = soup.selectFirst(".film-title")!!.text()
    val description = soup.selectFirst(".film-desc")?.text()?.trim()
    val poster: String? = try {
        soup.selectFirst("#film-detail .film-poster img")!!.attr("src")
    } catch (e: Exception) {
        soup.selectFirst(".info .poster img")!!.attr("src")
    }


    val backimginfo = (soup.selectFirst(".film-background") ?: soup.selectFirst(".player-bg"))?.attr("style")
    val backimgRegx = Regex("(http|https).*jpg")
    val backposter = backimgRegx.find(backimginfo.toString())?.value ?: poster
    val tags = soup.select(".film-info .film-meta div:contains(Genre) a").map { it.text() }

    //$mainUrl/ajax/episode/list/$movieid?vrf=$movieidencoded
    val vrfUrl = "$mainUrl/ajax/episode/list/$movieid?vrf=$movieidencoded"
    val episodes = ArrayList<Episode>()
    val asse = Jsoup.parse(
        app.get(
            vrfUrl
        ).parsed<Response>().result
    )
    var dataMovie = ""
    asse.select("ul.episodes").map {main ->
         val seasonNum = main.attr("data-season")
         main.select("li a").map { sec ->
             val epdata = sec.attr("data-slug")
             val epID = sec.attr("data-id")
             val seasonPair = Triple(seasonNum, epdata, epID)
             val epTitle = sec.select("a").text().replace(Regex("(Episode (\\d+):|Episode (\\d+)-|Episode (\\d+))"), "")
             episodes.add(
                 newEpisode(seasonPair.third){
                     this.name = epTitle
                     this.season = seasonPair.first.toString().toIntOrNull()
                     this.episode = seasonPair.second.toString().toIntOrNull()
                 }
             )
         }
     }

    val tvType =
        if (url.contains("/movie/") && episodes.size == 1) TvType.Movie else TvType.TvSeries


    if (tvType == TvType.Movie) {
        dataMovie = asse.select("ul.episodes li a").attr("data-id")
    }


    val rating = soup.selectFirst("div.score.live-label span b[itemprop=ratingValue]")?.text()?.toRatingInt()
    val durationdoc = (soup.selectFirst("div.meta > span:nth-child(4)") ?: soup.selectFirst("div.meta > span:nth-child(5)"))?.text() ?: ""
    val bflix = mainUrl == "https://bflix.to"
   // val year = if (bflix) soup.selectFirst("div.meta > span:nth-child(3)")?.text()
   // else soup.selectFirst("div.meta div span[itemprop=dateCreated]")?.text()?.substringBefore("-")
    return when (tvType) {
        TvType.TvSeries -> {
            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backposter
                this.plot = description
                this.rating = rating
                //this.recommendations = recommendations
                this.tags = tags
                //this.year = year?.toIntOrNull()
                addDuration(durationdoc)
            }
        }
        TvType.Movie -> {
            newMovieLoadResponse(title, url, tvType, dataMovie) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backposter
                this.plot = description
                this.rating = rating
                //this.recommendations = recommendations
                this.tags = tags
                //this.year = year?.toIntOrNull()
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

    data class Result(
        @JsonProperty("url")
        val url: String? = null,
        @JsonProperty("rawURL")
        val rawURL: String? = null
    )

    data class Links(
        @JsonProperty("result")
        val result: Result? = null
    )

data class Servers(
    @JsonProperty("28") val mcloud: String?,
    @JsonProperty("35") val mp4upload: String?,
    @JsonProperty("40") val streamtape: String?,
    @JsonProperty("41") val vidplay: String?,
    @JsonProperty("45") val filemoon: String?
)

class ServersID(elements: Map<String, String>) : HashMap<String, String>(elements)


    private fun serverName(serverID: String?): String? {
        val sss =
            when (serverID) {
                "28" -> "mcloud"
                "41" -> "vidplay"
                "45" -> "filemoon"
                "40" -> "streamtape"
                "35" -> "mp4upload"
                else -> null
            }
        return sss
    }
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

    data class BflixMediaInfo (
        @JsonProperty("result" ) val result : BflixResult? = BflixResult()
    )


    data class BflixResult (

        @JsonProperty("sources" ) var sources : ArrayList<BflixTracks> = arrayListOf(),
        @JsonProperty("tracks"  ) var tracks  : ArrayList<BflixTracks>  = arrayListOf()

    )
    data class BflixTracks (
        @JsonProperty("file"    ) var file    : String?  = null,
        @JsonProperty("label"   ) var label   : String?  = null,
    )


    data class BflixSubtitles (
        @JsonProperty("file"    ) var file    : String?  = null,
        @JsonProperty("label"   ) var label   : String?  = null,
        @JsonProperty("kind"    ) var kind    : String?  = null,
        @JsonProperty("default" ) var default : Boolean? = null
    )

class TestingSubs : ArrayList<TestingSubsItem>()

data class TestingSubsItem(
    @JsonProperty("file"    ) var file    : String?  = null,
    @JsonProperty("label"   ) var label   : String?  = null,
    @JsonProperty("kind"    ) var kind    : String?  = null,
    @JsonProperty("default" ) var default : Boolean? = null
)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataClean = data.replace("$mainUrl/","")
        val dataEncoded = encode(vrfHelper("fmovies-vrf",dataClean))
        //https://bflix.to/ajax/server/list/331685?vrf=QA%2BZPhjFWBg%3B
        val serversUrl = "$mainUrl/ajax/server/list/$dataClean?vrf=$dataEncoded"
        val serversPair = Jsoup.parse(
            app.get(serversUrl).parsed<Response>().result
        ).select(".film-server").map {
            val serverId = it.attr("data-id")
            val datalinkId = it.attr("data-link-id")
            val newSname = serverName(serverId)
            Pair(newSname, datalinkId)
        }

        serversPair.apmap {(sName, sId) ->
            val encId = vrfHelper("fmovies-vrf",sId)
            val urlEnc = app.get("$mainUrl/ajax/server/$sId?vrf=$encId").parsed<Links>().result?.url

            if (urlEnc != null) {
                val vids = sName == "vidplay"
                val mclo = sName == "mcloud"
                if (vids || mclo) {
                    val decUrl = vrfHelper("fmovies-decrypt", urlEnc)
                    val futoken = app.get("https://vidstream.pro/futoken").text
                    val comps = decUrl.split("/");
                    val vizId = comps[comps.size - 1];
                    //val jsonBody = "{\"query\":\"$vizId\",\"futoken\":\"$futoken\"}"
                    val map = mapOf("query" to vizId, "futoken" to futoken)
                    val jsonBody = JSONObject(map).toString()
                    val action = if (vids) "rawVizcloud" else "rawMcloud"
                    val mediaUrl = app.post("https://9anime.eltik.net/$action?apikey=lagrapps", requestBody = jsonBody.toRequestBody(mediaType)).parsed<Result>().rawURL
                    val ref = if (vids) "https://vidstream.pro/" else "https://mcloud.to/"
                    if (mediaUrl != null) {
                        val resultJson = app.get(mediaUrl, headers = mapOf("Referer" to ref)).parsed<BflixMediaInfo>()
                        val name = if (vids) "Vidplay" else "MyCloud"
                        resultJson.result?.sources?.apmap {
                            val source = it.file ?: ""
                            M3u8Helper.generateM3u8(
                                name,
                                source,
                                ref
                            ).forEach(callback)
                        }
                    }
                }
                if (!sName.isNullOrEmpty() && !vids || !mclo) {
                    val decUrl = vrfHelper("fmovies-decrypt", urlEnc)
                    loadExtractor(decUrl, subtitleCallback, callback)
                }
            }
            val subsUrl = app.get("$mainUrl/ajax/episode/subtitles/$dataClean").parsed<TestingSubs>()
            subsUrl.apmap {
                val subtitle = it.file ?: ""
                val lang = it.label ?: ""
                subtitleCallback.invoke(
                    SubtitleFile(lang, subtitle)
                )
            }
        }
        return true
    }


}