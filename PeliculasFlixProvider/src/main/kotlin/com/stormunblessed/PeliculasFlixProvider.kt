package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class PeliculasFlixProvider:MainAPI() {
    companion object  {
        private const val peliflixapi = "https://doraflix.fluxcedene.net/api/gql"
        private val mediaType = "application/json; charset=utf-8".toMediaType()
    }

    override var mainUrl = "https://peliculasflix.co"
    override var name = "PeliculasFlix"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
    )



    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }


    data class PeliMain (

        @JsonProperty("data" ) var data : PeliData? = PeliData()

    )

    data class PeliData (
        @JsonProperty("paginationFilm" ) var paginationFilm : PaginationFilm? = PaginationFilm(),
        @JsonProperty("searchFilm" ) var searchFilm : ArrayList<PeliItems>?= arrayListOf(),
        @JsonProperty("detailFilm" ) var detailFilm : DetailFilm? = DetailFilm()
    )

    data class PaginationFilm (
        @JsonProperty("items"      ) var items     : ArrayList<PeliItems>? = arrayListOf(),
        @JsonProperty("__typename" ) var _typename : String?          = null
    )
    data class PeliItems (
        @JsonProperty("_id"          ) var Id          : String?           = null,
        @JsonProperty("title"        ) var title       : String?           = null,
        @JsonProperty("name"         ) var name        : String?           = null,
        @JsonProperty("overview"     ) var overview    : String?           = null,
        @JsonProperty("runtime"      ) var runtime     : Int?              = null,
        @JsonProperty("slug"         ) var slug        : String?           = null,
        @JsonProperty("name_es"      ) var nameEs      : String?           = null,
        @JsonProperty("poster_path"  ) var posterPath  : String?           = null,
        @JsonProperty("poster"       ) var poster      : String?           = null,
        @JsonProperty("languages"    ) var languages   : ArrayList<String> = arrayListOf(),
        @JsonProperty("release_date" ) var releaseDate : String?           = null,
        @JsonProperty("__typename"   ) var _typename   : String?           = null
    )

    data class DetailFilm (
        @JsonProperty("name"          ) var name         : String?                = null,
        @JsonProperty("title"         ) var title        : String?                = null,
        @JsonProperty("name_es"       ) var nameEs       : String?                = null,
        @JsonProperty("overview"      ) var overview     : String?                = null,
        @JsonProperty("languages"     ) var languages    : ArrayList<String>      = arrayListOf(),
        @JsonProperty("popularity"    ) var popularity   : Double?                = null,
        @JsonProperty("poster"        ) var poster       : String?                = null,
        @JsonProperty("poster_path"   ) var posterPath   : String?                = null,
        @JsonProperty("backdrop"      ) var backdrop     : String?                = null,
        @JsonProperty("backdrop_path" ) var backdropPath : String?                = null,
        @JsonProperty("genres"        ) var genres       : ArrayList<GenresAndLabels>?      = arrayListOf(),
        @JsonProperty("labels"        ) var labels       : ArrayList<GenresAndLabels>?      = arrayListOf(),
        @JsonProperty("links_online"  ) var linksOnline  : ArrayList<LinksOnline>? = arrayListOf(),
        @JsonProperty("__typename"    ) var _typename    : String?                = null
    )

    data class GenresAndLabels (
        @JsonProperty("name"       ) var name      : String? = null,
        @JsonProperty("slug"       ) var slug      : String? = null,
        @JsonProperty("__typename" ) var _typename : String? = null
    )

    data class LinksOnline (
        @JsonProperty("_id"        ) var Id        : String? = null,
        @JsonProperty("server"     ) var server    : Int?    = null,
        @JsonProperty("lang"       ) var lang      : String? = null,
        @JsonProperty("link"       ) var link      : String? = null,
        @JsonProperty("__typename" ) var _typename : String? = null
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val bodyJson = "{\"operationName\":\"listMovies\",\"variables\":{\"perPage\":32,\"sort\":\"CREATEDAT_DESC\",\"filter\":{\"isPublish\":true},\"page\":1},\"query\":\"query listMovies(\$page: Int, \$perPage: Int, \$sort: SortFindManyFilmInput, \$filter: FilterFindManyFilmInput) {\\n  paginationFilm(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      title\\n      name\\n      overview\\n      runtime\\n      slug\\n      name_es\\n      poster_path\\n      poster\\n      languages\\n      release_date\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val aaa = app.post(peliflixapi, requestBody = bodyJson.toRequestBody(mediaType)).parsed<PeliMain>()
        val aaaaa = aaa.data?.paginationFilm?.items
        val home =  aaaaa?.map {
            tasa(it)
        }
        items.add(HomePageList("Peliculas", home!!))
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    private fun tasa(
        info: PeliItems
    ): SearchResponse {
        val title = info.name ?: ""
        val slug = info.slug
        val poster = info.posterPath
        val realposter = getImageUrl(poster)
        val id = info.Id
        val typename = info._typename
        val data = "{\"id\":\"$id\",\"slug\":\"$slug\",\"type\":\"$typename\"}"
        return newMovieSearchResponse(title, data, TvType.Movie) {
            this.posterUrl = realposter
        }
    }
    override suspend fun search(query: String): List<SearchResponse>? {
        val bodyjson = "{\"operationName\":\"searchAll\",\"variables\":{\"input\":\"$query\"},\"query\":\"query searchAll(\$input: String!) {\\n  searchFilm(input: \$input, limit: 10) {\\n    _id\\n    slug\\n    title\\n    name\\n    name_es\\n    poster_path\\n    poster\\n    __typename\\n  }\\n}\\n\"}"
        val res = app.post(peliflixapi, requestBody = bodyjson.toRequestBody(mediaType)).parsed<PeliMain>()
        val sss = res.data?.searchFilm
        return sss?.map {
            tasa(it)
        }
    }

    data class PelisInfo (
        @JsonProperty("id"   ) var id   : String? = null,
        @JsonProperty("slug" ) var slug : String? = null,
        @JsonProperty("type" ) var type : String? = null,
    )
    override suspend fun load(url: String): LoadResponse? {
        val fixed = url.replace("$mainUrl/","")
        val json = parseJson<PelisInfo>(fixed)
        val sluginfo = json.slug
        val typename = json.type
        val id = json.id
        val bodyJson = "{\"operationName\":\"detailFilm\",\"variables\":{\"slug\":\"$sluginfo\"},\"query\":\"query detailFilm(\$slug: String!) {\\n  detailFilm(filter: {slug: \$slug}) {\\n    name\\n    title\\n    name_es\\n    overview\\n    languages\\n    popularity\\n  poster\\n poster_path\\n  backdrop\\n backdrop_path\\n  genres {\\n      name\\n      slug\\n      __typename\\n    }\\n    labels {\\n      name\\n      slug\\n      __typename\\n    }\\n     backdrop\\n    links_online {\\n      _id\\n      server\\n      lang\\n      link\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val res = app.post(peliflixapi, requestBody = bodyJson.toRequestBody(mediaType)).parsed<PeliMain>()
        val meta = res.data?.detailFilm
        val title = meta?.title
        val plot = meta?.overview
        val posterinfo = meta?.posterPath ?: meta?.poster ?: ""
        val backposterinfo = meta?.backdropPath ?: meta?.backdrop ?: ""
        val poster = getImageUrl(posterinfo)
        val backposter = getImageUrl(backposterinfo)
        val tags = ArrayList<String>()
        val tags1 = meta?.genres?.map { tags.add(it.name!!) }
        val tags2 = meta?.labels?.map { tags.add(it.name!!) }
        val movieData = meta?.linksOnline?.toJson() ?: ""
        return newMovieLoadResponse(title!!, "{\"id\":\"$id\",\"slug\":\"$sluginfo\",\"type\":\"$typename\"}", TvType.Movie, movieData){
            this.posterUrl = poster
            this.plot = plot
            this.backgroundPosterUrl = backposter
            this.tags = tags.distinct().toList()
            if (movieData.isEmpty()) this.comingSoon = true
        }
    }



    data class VideoInfo (
        @JsonProperty("file" ) var file : String? = null
    )
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val daaa = parseJson<ArrayList<LinksOnline>>(data)
        daaa.apmap {info ->
            val link = info.link
            //println("LINK $link")
            if (link?.contains("player.html#") == true) {
                val videoID = link.substringAfter("player.html#")
                val fi = app.get("https://pelisplus.esplay.io/video/$videoID").parsedSafe<VideoInfo>()
                val check = !fi?.file.isNullOrEmpty()
                val file = fi?.file ?: ""
                if (check) {
                    callback(
                        ExtractorLink(
                            this.name,
                            this.name,
                            file,
                            "",
                            Qualities.Unknown.value,
                            file.contains(".m3u8")
                        )
                    )
                } else {
                    ///nothing
                }
            } else {
                loadExtractor(link!!, subtitleCallback, callback)
            }
        }
        return true
    }
}