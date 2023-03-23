package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.TextNode
import com.lagradost.cloudstream3.*
import com.fasterxml.jackson.module.kotlin.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class AnimensionProvider:MainAPI() {
    override var mainUrl = "https://animension.to"
    override var name = "Animension"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)
    override val hasQuickSearch = true

    class HomeInfo : ArrayList<HomeInfoSubList>()

    class HomeInfoSubList : ArrayList<Any>()
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val links = listOf(
            Pair("Animes (SUB)","$mainUrl/public-api/index.php?page=1&mode=sub"),
            Pair("Animes (DUB)","$mainUrl/public-api/index.php?page=1&mode=dub"),
        )
        links.apmap { (name, url) ->
            val test = app.get(url).parsed<HomeInfo>()
            val sub = name.contains("(SUB)")
            val dub = name.contains("(DUB)")
            val home = test.map {
                val title = it[0]
                val id = it[1]
                val epnum = it[3]
                val img = it[4]
                newAnimeSearchResponse(title.toString(), "$mainUrl/$id", TvType.Anime){
                    this.posterUrl = img.toString()
                    addDubStatus(
                        dub,
                        sub,
                        epnum.toString().toIntOrNull(),
                        epnum.toString().toIntOrNull())
                }
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val search = ArrayList<SearchResponse>()
        val re = app.get( "$mainUrl/public-api/search.php?search_text=$query&page=1").parsed<HomeInfo>()
        re.map {
            val title = it[0]
            val id = it[1]
            val img = it[2]
            val dubinfo = it[3]
            val dubexist = dubinfo == 1
            val subexist = dubinfo == 0
            search.add(newAnimeSearchResponse(title.toString(), "$mainUrl/$id", TvType.Anime){
                this.posterUrl = img.toString()
                addDubStatus(dubexist, subexist)
            })
        }
        return search
    }


    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val id = url.substringAfter("$mainUrl/")
        val title = doc.selectFirst(".entry-title")?.text() ?: ""
        val poster = doc.selectFirst(".thumb > img")?.attr("src") ?: ""
        val desc = doc.selectFirst(".desc")?.text() ?: ""
        val tags = doc.select(".genxed a").map { it.text() }
        val epsurl = app.get("$mainUrl/public-api/episodes.php?id=$id").parsed<HomeInfo>()
        val episodes = epsurl.reversed().map {
            val epid = it[1]
            val epnum = it[2]
            val epinfo = "$mainUrl/public-api/episode.php?id=$epid"
            Episode(
                epinfo,
                episode = epnum.toString().toIntOrNull()
            )
        }
        return newAnimeLoadResponse(title, url, TvType.Anime){
            addEpisodes(DubStatus.Subbed, episodes)
            this.posterUrl = poster
            this.plot = desc
            this.tags = tags
        }
    }


    class LoadInfo : ArrayList<Any>()

    data class ServersInfo (
        @JsonProperty("Direct-directhls" ) var direct : String? = null,
        @JsonProperty("VidCDN-embed"     ) var vidCDN    : String? = null,
        @JsonProperty("Streamsb-embed"   ) var streamsb   : String? = null,
        @JsonProperty("Xstreamcdn-embed" ) var xstreamcdn : String? = null,
        @JsonProperty("Mp4upload-embed"  ) var mp4upload  : String? = null,
        @JsonProperty("Doodstream-embed" ) var doodstream : String? = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uuu = app.get(data).parsed<LoadInfo>()
        val uuutwo = uuu[3]
        val servertext = uuutwo.toString()
        val json = parseJson<ServersInfo>(servertext)
        val list = listOfNotNull(
            json.direct,
            json.vidCDN,
            json.streamsb,
            json.mp4upload,
            json.doodstream,
            json.xstreamcdn
        )
        list.apmap {
            val link = it.replace("https://streamsss.net","https://watchsb.com").replace("https://fembed9hd.com","https://embedsito.com")
            println(link)
            if (link.contains("m3u8")) {
                generateM3u8(
                    "${this.name} DirectHLS",
                    link,
                    ""
                ).forEach(callback)
            }
            else if (link.contains(Regex("mp4\$")))
            {
                callback(
                    ExtractorLink(
                        this.name,
                        "${this.name} MP4",
                        link,
                        "",
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }
            else {
                loadExtractor(link, subtitleCallback,callback)
            }
        }
        return true
    }
}