package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class PlayhubProvider:MainAPI() {
    override var mainUrl = "https://playhublite.com"
    override var name = "Playhub"
    override var lang = "es"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    companion object  {
        private const val playhubAPI = "https://api.playhublite.com/api/v2/"
        private val playhubHeaders = mapOf(
            "Host" to "api.playhublite.com",
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Authorization" to "Bearer null",
            "X-Requested-With " to "XMLHttpRequest",
            "Origin" to "https://playhublite.com",
            "DNT" to " 1",
            "Connection" to "keep-alive",
            "Referer" to " https://playhublite.com/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-site",
            "TE" to " trailers",
        )
    }

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }

    data class PlayHubMain (
        @JsonProperty("home"        ) var home        : ArrayList<PlayHubHome>        = arrayListOf(),
        //@JsonProperty("random"      ) var random      : Random?                = Random(),
    )

    data class PlayHubHome (
        @JsonProperty("type"  ) var type  : String?         = null,
        @JsonProperty("title" ) var title : String?         = null,
        @JsonProperty("slug"  ) var slug  : String?         = null,
        @JsonProperty("data"  ) var data  : ArrayList<HomeMetaData>? = arrayListOf()
    )

    data class HomeMetaData (
        @JsonProperty("id"            ) var id           : Int?    = null,
        @JsonProperty("title"         ) var title        : String? = null,
        @JsonProperty("name" ) var name : String? = null,
        @JsonProperty("poster_path"   ) var posterPath   : String? = null,
        @JsonProperty("backdrop_path" ) var backdropPath : String? = null,
        @JsonProperty("release_date"  ) var releaseDate  : String? = null,
        @JsonProperty("first_air_date" ) var firstAirDate : String? = null,
        @JsonProperty("last_air_date"  ) var lastAirDate  : String? = null
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val test = app.get(playhubAPI, headers = playhubHeaders).parsed<PlayHubMain>()
        test.home.map {
            val name = it.title ?: ""
            val type = it.type
            val home = it.data?.map { info ->
                val title = info.title ?: info.name ?: ""
                val id = info.id.toString()
                val posterinfo = info.posterPath
                val poster = getImageUrl(posterinfo)
                val airdate = info.lastAirDate.isNullOrEmpty()
                val data = if (type == "serie") "${mainUrl}/series/$id" else if (type == "movie") "$mainUrl/movies/$id" else if (!airdate) "${mainUrl}/series/$id"
                else if (airdate) "$mainUrl/movies/$id"
                else ""
                TvSeriesSearchResponse(
                    title,
                    data,
                    this.name,
                    TvType.TvSeries,
                    poster,
                )
            }
            items.add(HomePageList(name, home!!))
        }
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }
    data class PlayhubSearchMain (
        @JsonProperty("movies" ) var movies : ArrayList<PlayhubSearchInfo>? = arrayListOf(),
        @JsonProperty("series" ) var series : ArrayList<PlayhubSearchInfo>? = arrayListOf()
    )


    data class PlayhubSearchInfo (
        @JsonProperty("id"               ) var id             : Int?    = null,
        @JsonProperty("name"             ) var name           : String? = null,
        @JsonProperty("original_name"    ) var originalName   : String? = null,
        @JsonProperty("poster_path"      ) var posterPath     : String? = null,
        @JsonProperty("backdrop_path"    ) var backdropPath   : String? = null,
        @JsonProperty("logo"             ) var logo           : String? = null,
        @JsonProperty("episode_run_time" ) var episodeRunTime : String? = null,
        @JsonProperty("first_air_date"   ) var firstAirDate   : String? = null,
        @JsonProperty("in_production"    ) var inProduction   : Int?    = null,
        @JsonProperty("last_air_date"    ) var lastAirDate    : String? = null,
        @JsonProperty("overview"         ) var overview       : String? = null,
        @JsonProperty("status"           ) var status         : String? = null,
        @JsonProperty("vote_average"     ) var voteAverage    : Double? = null,
        @JsonProperty("created_at"       ) var createdAt      : String? = null,
        @JsonProperty("updated_at"       ) var updatedAt      : String? = null,
        @JsonProperty("view_count"       ) var viewCount      : Int?    = null,
        @JsonProperty("original_title" ) var originalTitle : String? = null,
        @JsonProperty("title"          ) var title         : String? = null,
        @JsonProperty("release_date"   ) var releaseDate   : String? = null,
        @JsonProperty("runtime"        ) var runtime       : String? = null,
    )
    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "${playhubAPI}search?q=$query"
        val search = ArrayList<SearchResponse>()
        val res = app.get(url, headers = playhubHeaders).parsed<PlayhubSearchMain>()
        res.movies?.map {
            val title = it.title ?: it.originalTitle ?: ""
            val posterinfo = it.posterPath ?: ""
            val poster = getImageUrl(posterinfo)
            val id = it.id
            val href = "$mainUrl/movies/$id"
            search.add(
                newMovieSearchResponse(title, href, TvType.Movie){
                    this.posterUrl = poster
                })
        }

        res.series?.map {
            val title = it.name ?: it.originalName ?: ""
            val posterinfo = it.posterPath ?: ""
            val poster = getImageUrl(posterinfo)
            val id = it.id
            val href = "$mainUrl/series/$id"
            search.add(
                newTvSeriesSearchResponse(title,href, TvType.TvSeries) {
                    this.posterUrl = poster
                })
        }
        return search
    }



    data class PlayhubLoadMain (
        @JsonProperty("id"              ) var id              : Int?                       = null,
        @JsonProperty("original_title"  ) var originalTitle   : String?                    = null,
        @JsonProperty("title"           ) var title           : String?                    = null,
        @JsonProperty("backdrop_path"   ) var backdropPath    : String?                    = null,
        @JsonProperty("logo"            ) var logo            : String?                    = null,
        @JsonProperty("poster_path"     ) var posterPath      : String?                    = null,
        @JsonProperty("overview"        ) var overview        : String?                    = null,
        @JsonProperty("release_date"    ) var releaseDate     : String?                    = null,
        @JsonProperty("runtime"         ) var runtime         : String?                    = null,
        @JsonProperty("status"          ) var status          : String?                    = null,
        @JsonProperty("vote_average"    ) var voteAverage     : Double?                    = null,
        @JsonProperty("created_at"      ) var createdAt       : String?                    = null,
        @JsonProperty("updated_at"      ) var updatedAt       : String?                    = null,
        @JsonProperty("view_count"      ) var viewCount       : Int?                       = null,
        @JsonProperty("recommendations" ) var recommendations : ArrayList<PlayhubRecommendations>? = arrayListOf(),
        @JsonProperty("categories"      ) var categories      : ArrayList<Categories>?      = arrayListOf(),
        @JsonProperty("seasons"          ) var seasons        : ArrayList<Seasons>?   = arrayListOf(),
        @JsonProperty("name"             ) var name           : String?               = null,
        @JsonProperty("original_name"    ) var originalName   : String?               = null,
        @JsonProperty("episode_run_time" ) var episodeRunTime : String?               = null,
        @JsonProperty("first_air_date"   ) var firstAirDate   : String?               = null,
        @JsonProperty("in_production"    ) var inProduction   : Int?                  = null,
        @JsonProperty("last_air_date"    ) var lastAirDate    : String?               = null,
    )

    data class PlayhubRecommendations (

        @JsonProperty("id"            ) var id           : Int?    = null,
        @JsonProperty("title"         ) var title        : String? = null,
        @JsonProperty("poster_path"   ) var posterPath   : String? = null,
        @JsonProperty("backdrop_path" ) var backdropPath : String? = null,

        )

    data class Categories (
        @JsonProperty("id"    ) var id    : Int?    = null,
        @JsonProperty("name"  ) var name  : String? = null,
    )

    data class Seasons (
        @JsonProperty("id"            ) var id           : Int? = null,
        @JsonProperty("serie_id"      ) var serieId      : Int? = null,
        @JsonProperty("season_number" ) var seasonNumber : Int? = null
    )

    data class SeasonsInfo (
        @JsonProperty("id"            ) var id           : Int?                = null,
        @JsonProperty("serie_id"      ) var serieId      : Int?                = null,
        @JsonProperty("season_number" ) var seasonNumber : Int?                = null,
        @JsonProperty("episodes"      ) var episodes     : ArrayList<EpisodesInfo>? = arrayListOf()
    )
    data class EpisodesInfo (
        @JsonProperty("id"             ) var id            : Int?    = null,
        @JsonProperty("serie_id"       ) var serieId       : String? = null,
        @JsonProperty("season_id"      ) var seasonId      : Int?    = null,
        @JsonProperty("episode_number" ) var episodeNumber : Int?    = null,
        @JsonProperty("season_number"  ) var seasonNumber  : Int?    = null,
        @JsonProperty("air_date"       ) var airDate       : String? = null,
        @JsonProperty("name"           ) var name          : String? = null,
        @JsonProperty("overview"       ) var overview      : String? = null,
        @JsonProperty("still_path"     ) var stillPath     : String? = null
    )
    override suspend fun load(url: String): LoadResponse? {
        val type = if (url.contains("movie")) TvType.Movie else TvType.TvSeries
        val id = url.substringAfter("/movies/").substringAfter("/series/")
        val uuu = if (type == TvType.Movie) "${playhubAPI}movies/$id" else "${playhubAPI}series/$id"
        val res = app.get(uuu, headers = playhubHeaders).parsed<PlayhubLoadMain>()
        val title = res.title ?: res.originalTitle ?: res.name ?: res.originalName ?: ""
        val plot = res.overview ?: ""
        val posterinfo = res.posterPath ?: ""
        val backposterinfo = res.backdropPath ?: ""
        val poster = getImageUrl(posterinfo)
        val backposter = getImageUrl(backposterinfo)
        val tags = res.categories?.mapNotNull { it.name }
        val episodes = ArrayList<Episode>()
        val recs = ArrayList<SearchResponse>()

        if (type == TvType.TvSeries) {
            res.seasons?.apmap { mainInfo ->
                val seasonurl = "${playhubAPI}seasons/${mainInfo.serieId}/${mainInfo.seasonNumber}"
                val seasonres = app.get(seasonurl, headers = playhubHeaders).parsed<SeasonsInfo>()
                val seriesID = mainInfo.serieId
                seasonres.episodes?.apmap { ep ->
                    val eptitle = ep.name
                    val epthumb = getImageUrl(ep.stillPath)
                    val epPlot = ep.overview
                    val seasonNum = ep.seasonNumber
                    val epNum = ep.episodeNumber
                    val airDate = ep.airDate
                    val epData = "${playhubAPI}xxx/$seriesID-$seasonNum-$epNum?s=web"
                    episodes.add(
                        newEpisode(epData) {
                            this.name = eptitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = fixUrlNull(epthumb)
                            this.description = epPlot
                            addDate(airDate)
                        })
                }
            }
        }
        if (type == TvType.Movie)  {
            res.recommendations?.map {
                val rectitle = it.title ?: ""
                val recid = it.id
                val recposterinfo = it.posterPath
                val recposter = getImageUrl(recposterinfo)
                recs.add(
                    newMovieSearchResponse(rectitle, "$mainUrl/movies/$recid", type) {
                        this.posterUrl = recposter
                    })
            }
        }

        return when (type) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title,
                    url, type, episodes,){
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backposter
                    this.plot = plot
                    //this.year = year
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, type, "${playhubAPI}xxx/$id?s=web"){
                    this.posterUrl = poster
                    this.plot = plot
                    this.backgroundPosterUrl = backposter
                    //this.year = year
                    this.tags = tags
                    this.recommendations = recs
                }
            }
            else -> null
        }
    }


    data class DataBase (

        @JsonProperty("data" ) var data : String? = null

    )

    data class ServersInfo (
        @JsonProperty("id"         ) var id        : Int?    = null,
        @JsonProperty("vid"        ) var vid       : String? = null,
        @JsonProperty("url"        ) var url       : String? = null,
        @JsonProperty("server"     ) var server    : String? = null,
        @JsonProperty("language"   ) var language  : String? = null,
        @JsonProperty("quality"    ) var quality   : String? = null,
        @JsonProperty("user_id"    ) var userId    : String? = null,
        @JsonProperty("status"     ) var status    : String? = null,
        @JsonProperty("created_at" ) var createdAt : String? = null,
        @JsonProperty("updated_at" ) var updatedAt : String? = null,
        @JsonProperty("type"       ) var type      : Int?    = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rr = app.get(data).parsed<DataBase>()
        val datafix = rr.data?.replace("#", "A")?.replace("!", "B")?.replace("%", "N")?.replace("&", "i")?.replace("/", "l")?.replace("*", "L")?.replace("+", "s")?.replace("((", "j")?.replace("[]", "=")
        if (!datafix.isNullOrEmpty()) {
            val dadatec = base64Decode(datafix)
            val json = parseJson<ArrayList<ServersInfo>>(dadatec)
            json.map {
                val link = it.url?.replace(Regex("(https|http):.*\\/api\\/source\\/"),"https://embedsito.com/v/")
                    ?.replace(Regex("https://sbrity.com|https://sblanh.com"),"https://watchsb.com") ?: ""
                //println("TESTING $link")
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }
}