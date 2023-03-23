package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class DoramasFlixProvider:MainAPI() {
    companion object  {
        private const val doraflixapi = "https://doraflix.fluxcedene.net/api/gql"
        private val mediaType = "application/json; charset=utf-8".toMediaType()
    }

    override var mainUrl = "https://doramasflix.co"
    override var name = "Doramasflix"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
    )

    data class MainDoramas (
        @JsonProperty("data" ) var data : DataDoramas? = DataDoramas()
    )
    data class DataDoramas (
        @JsonProperty("listDoramas" ) var listDoramas : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("searchDorama" ) var searchDorama : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("searchMovie"  ) var searchMovie  : ArrayList<ListDoramas>?  = arrayListOf(),
        @JsonProperty("listSeasons" ) var listSeasons : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("detailDorama" ) var detailDorama : DetailDoramaandDoramaMeta? = DetailDoramaandDoramaMeta(),
        @JsonProperty("detailMovie" ) var detailMovie : DetailDoramaandDoramaMeta? = DetailDoramaandDoramaMeta(),
        @JsonProperty("paginationEpisode" ) var paginationEpisode : PaginationEpisode? = PaginationEpisode(),
        @JsonProperty("detailEpisode" ) var detailEpisode : DetailDoramaandDoramaMeta? = DetailDoramaandDoramaMeta(),
        @JsonProperty("carrouselMovies" ) var carrouselMovies : ArrayList<ListDoramas>? = arrayListOf(),
        @JsonProperty("paginationDorama" ) var paginationDorama : ListDoramas? = ListDoramas(),
        @JsonProperty("paginationMovie" ) var paginationMovie : ListDoramas? = ListDoramas()
    )

    data class ListDoramas (
        @JsonProperty("_id"         ) var Id         : String?  = null,
        @JsonProperty("name"        ) var name       : String?  = null,
        @JsonProperty("name_es"     ) var nameEs     : String?  = null,
        @JsonProperty("slug"        ) var slug       : String?  = null,
        @JsonProperty("poster_path" ) var posterPath : String?  = null,
        @JsonProperty("isTVShow"    ) var isTVShow   : Boolean? = null,
        @JsonProperty("poster"      ) var poster     : String?  = null,
        @JsonProperty("__typename"  ) var _typename  : String?  = null,
        @JsonProperty("season_number" ) var seasonNumber : Int?    = null,
        @JsonProperty("items"      ) var items     : ArrayList<ListDoramas>? = arrayListOf(),
    )

    data class DetailDoramaandDoramaMeta (
        @JsonProperty("_id"              ) var Id             : String?           = null,
        @JsonProperty("name"             ) var name           : String?           = null,
        @JsonProperty("slug"             ) var slug           : String?           = null,
        @JsonProperty("names"            ) var names          : String?           = null,
        @JsonProperty("name_es"          ) var nameEs         : String?           = null,
        @JsonProperty("overview"         ) var overview       : String?           = null,
        @JsonProperty("languages"        ) var languages      : ArrayList<String>? = arrayListOf(),
        @JsonProperty("poster_path"      ) var posterPath     : String?           = null,
        @JsonProperty("backdrop_path"    ) var backdropPath   : String?           = null,
        @JsonProperty("first_air_date"   ) var firstAirDate   : String?           = null,
        @JsonProperty("episode_run_time" ) var episodeRunTime : ArrayList<Int>?    = arrayListOf(),
        @JsonProperty("isTVShow"         ) var isTVShow       : Boolean?          = null,
        @JsonProperty("premiere"         ) var premiere       : Boolean?          = null,
        @JsonProperty("poster"           ) var poster         : String?           = null,
        @JsonProperty("trailer"          ) var trailer        : String?           = null,
        @JsonProperty("videos"           ) var videos         : ArrayList<String>? = arrayListOf(),
        @JsonProperty("backdrop"         ) var backdrop       : String?           = null,
        @JsonProperty("genres"           ) var genres         : ArrayList<GenresAndLabels>? = arrayListOf(),
        @JsonProperty("labels"           ) var labels         : ArrayList<GenresAndLabels>? = arrayListOf(),
        @JsonProperty("__typename"       ) var _typename      : String?           = null,
        @JsonProperty("links_online"  ) var linksOnline  : ArrayList<LinksOnline>? = arrayListOf(),
        @JsonProperty("still_path"     ) var stillPath     : String? = null,
        @JsonProperty("episode_number" ) var episodeNumber : Int?    = null,
        @JsonProperty("season_number"  ) var seasonNumber  : Int?    = null,
        @JsonProperty("air_date"       ) var airDate       : String? = null,
        @JsonProperty("serie_id"       ) var serieId       : String? = null,
        @JsonProperty("season_poster"  ) var seasonPoster  : String? = null,
        @JsonProperty("serie_poster"   ) var seriePoster   : String? = null,
    )


    data class LinksOnline (
        @JsonProperty("page"   ) var page   : String? = null,
        @JsonProperty("server" ) var server : String? = null,
        @JsonProperty("link"   ) var link   : String? = null,
        @JsonProperty("lang"   ) var lang   : String? = null
    )

    data class GenresAndLabels (
        @JsonProperty("name"       ) var name      : String? = null,
        @JsonProperty("slug"       ) var slug      : String? = null,
        @JsonProperty("__typename" ) var _typename : String? = null
    )


    data class DoramasInfo (
        @JsonProperty("id"   ) var id   : String? = null,
        @JsonProperty("slug" ) var slug : String? = null,
        @JsonProperty("type" ) var type : String? = null,
        @JsonProperty("isTV" ) var isTV : Boolean? = null
    )

    data class PaginationEpisode (
        @JsonProperty("items"      ) var items     : ArrayList<DetailDoramaandDoramaMeta> = arrayListOf(),
        @JsonProperty("__typename" ) var _typename : String?          = null
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w1280/$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val doramasBody = "{\"operationName\":\"listDoramasMobile\",\"variables\":{\"filter\":{\"isTVShow\":false},\"limit\":32,\"sort\":\"_ID_DESC\"},\"query\":\"query listDoramasMobile(\$limit: Int, \$skip: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  listDoramas(limit: \$limit, skip: \$skip, sort: \$sort, filter: \$filter) {\\n    _id\\n    name\\n    name_es\\n    slug\\n    poster_path\\n    isTVShow\\n    poster\\n    __typename\\n  }\\n}\\n\"}"
        val peliculasBody = "{\"operationName\":\"paginationMovie\",\"variables\":{\"perPage\":32,\"sort\":\"CREATEDAT_DESC\",\"filter\":{},\"page\":1},\"query\":\"query paginationMovie(\$page: Int, \$perPage: Int, \$sort: SortFindManyMovieInput, \$filter: FilterFindManyMovieInput) {\\n  paginationMovie(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      names\\n      poster_path\\n      poster\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val variedadesBody = "{\"operationName\":\"paginationDorama\",\"variables\":{\"perPage\":32,\"sort\":\"CREATEDAT_DESC\",\"filter\":{\"isTVShow\":true},\"page\":1},\"query\":\"query paginationDorama(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  paginationDorama(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      names\\n      poster_path\\n      backdrop_path\\n      isTVShow\\n      poster\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val doraresponse = app.post(doraflixapi, requestBody = doramasBody.toRequestBody(mediaType)).parsed<MainDoramas>()
        val pelisrresponse = app.post(doraflixapi, requestBody = peliculasBody.toRequestBody(mediaType)).parsed<MainDoramas>()
        val variedadesresponse = app.post(doraflixapi, requestBody = variedadesBody.toRequestBody(mediaType)).parsed<MainDoramas>()
        val listdoramas = doraresponse.data?.listDoramas
        val pelis = pelisrresponse.data?.paginationMovie?.items
        val vari = variedadesresponse.data?.paginationDorama?.items
        val home1 = listdoramas?.map { info ->
            tasa(info)
        }
        val home2 = pelis?.map { info ->
            tasa(info)
        }
        val home3 = vari?.map {info ->
            tasa(info)
        }

        items.add(HomePageList("Doramas", home1!!))
        items.add(HomePageList("Peliculas", home2!!))
        items.add(HomePageList("Doramas 2", home3!!))
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    private fun tasa(
        info: ListDoramas
    ): SearchResponse {
        val title = info.name
        val slug = info.slug
        val poster = info.posterPath
        val realposter = getImageUrl(poster)
        val id = info.Id
        val typename = info._typename
        val istvShow = info.isTVShow
        val data = "{\"id\":\"$id\",\"slug\":\"$slug\",\"type\":\"$typename\",\"isTV\":$istvShow}"

        return TvSeriesSearchResponse(
            title!!,
            data,
            name,
            TvType.AsianDrama,
            realposter,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val search = ArrayList<SearchResponse>()
        val bodyjson = "{\"operationName\":\"searchAll\",\"variables\":{\"input\":\"$query\"},\"query\":\"query searchAll(\$input: String!) {\\n  searchDorama(input: \$input, limit: 5) {\\n    _id\\n    slug\\n    name\\n    name_es\\n    poster_path\\n  isTVShow\\n  poster\\n    __typename\\n  }\\n  searchMovie(input: \$input, limit: 5) {\\n    _id\\n    name\\n    name_es\\n    slug\\n    poster_path\\n    poster\\n    __typename\\n  }\\n}\\n\"}"
        val response = app.post(doraflixapi, requestBody = bodyjson.toRequestBody(mediaType)).parsed<MainDoramas>()
        val searchDorama = response.data?.searchDorama
        val searchMovie = response.data?.searchMovie
        if (searchDorama!!.isNotEmpty() || searchMovie!!.isNotEmpty())  {
            searchDorama.map { info->
                search.add(tasa(info))
            }
            searchMovie?.map {info ->
                search.add(tasa(info))
            }
        }
        return search
    }

    override suspend fun load(url: String): LoadResponse? {
        //There's ton of shit here
        val fixed = url.substringAfter("https://www.comamosramen.com/")
        val parse = parseJson<DoramasInfo>(fixed)
        val type = parse.type
        val tvType = if (type!!.contains("Dorama")) TvType.AsianDrama else TvType.Movie
        val sluginfo = parse.slug
        val isMovie = tvType == TvType.Movie
        val id = parse.id
        val detailMovieBody = "{\"operationName\":\"detailMovieExtra\",\"variables\":{\"slug\":\"$sluginfo\"},\"query\":\"query detailMovieExtra(\$slug: String!) {\\n  detailMovie(filter: {slug: \$slug}) {\\n    name\\n    name_es\\n    overview\\n    languages\\n    popularity\\n  poster_path\\n poster\\n  backdrop_path\\n    backdrop\\n    links_online\\n    __typename\\n genres {\\n      name\\n      slug\\n      __typename\\n    }\\n labels {\\n      name\\n      slug\\n      __typename\\n    }\\n  }\\n}\\n\"}"
        val detailDoramaRequestbody = "{\"operationName\":\"detailDorama\",\"variables\":{\"slug\":\"$sluginfo\"},\"query\":\"query detailDorama(\$slug: String!) {\\n  detailDorama(filter: {slug: \$slug}) {\\n    _id\\n    name\\n    slug\\n    cast\\n    names\\n    name_es\\n    overview\\n    languages\\n    poster_path\\n    backdrop_path\\n    first_air_date\\n    episode_run_time\\n    isTVShow\\n    premiere\\n    poster\\n    trailer\\n    videos\\n    backdrop\\n    genres {\\n      name\\n      slug\\n      __typename\\n    }\\n    labels {\\n      name\\n      slug\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
        val metadataRequestBody = if (!isMovie) detailDoramaRequestbody.toRequestBody(mediaType) else detailMovieBody.toRequestBody(mediaType)
        val metadatarequest = app.post(doraflixapi, requestBody = metadataRequestBody).parsed<MainDoramas>()
        val metaInfo = if (isMovie) metadatarequest.data?.detailMovie else metadatarequest.data?.detailDorama
        val title = metaInfo?.name
        val plot = metaInfo?.overview
        val posterinfo = metaInfo?.poster ?: metaInfo?.posterPath ?: ""
        val poster = getImageUrl(posterinfo)
        val backgroundPosterinfo = metaInfo?.backdrop ?: metaInfo?.backdropPath ?: ""
        val bgposter = getImageUrl(backgroundPosterinfo)
        val tags = ArrayList<String>()
        val tags1 = metaInfo?.genres?.map { tags.add(it.name!!) }
        val tags2 = metaInfo?.labels?.map { tags.add(it.name!!) }
        val episodes = ArrayList<Episode>()
        var movieData: String? = ""
        val datatwo = "{\"id\":\"${parse.id}\",\"slug\":\"${parse.slug}\",\"type\":\"${parse.type}\",\"isTV\":${parse.isTV}}"

        if (!isMovie) {
            val listSeasonsbody = "{\"operationName\":\"listSeasons\",\"variables\":{\"serie_id\":\"$id\"},\"query\":\"query listSeasons(\$serie_id: MongoID!) {\\n  listSeasons(sort: NUMBER_ASC, filter: {serie_id: \$serie_id}) {\\n    slug\\n    season_number\\n    poster_path\\n    air_date\\n    serie_name\\n    poster\\n    backdrop\\n    __typename\\n  }\\n}\\n\"}"
            val response = app.post(doraflixapi, requestBody = listSeasonsbody.toRequestBody(mediaType)).parsed<MainDoramas>()
            response.data?.listSeasons?.map {
                val seasonNum = it.seasonNumber
                val paginationepisodesBody = "{\"operationName\":\"listEpisodesPagination\",\"variables\":{\"serie_id\":\"$id\",\"season_number\":$seasonNum,\"page\":1},\"query\":\"query listEpisodesPagination(\$page: Int!, \$serie_id: MongoID!, \$season_number: Float!) {\\n  paginationEpisode(\\n    page: \$page\\n    perPage: 1000\\n    sort: NUMBER_ASC\\n    filter: {type_serie: \\\"dorama\\\", serie_id: \$serie_id, season_number: \$season_number}\\n  ) {\\n       items {\\n      _id\\n      name\\n      still_path\\n   overview\\n   episode_number\\n      season_number\\n      air_date\\n      slug\\n      serie_id\\n   season_poster\\n      serie_poster\\n      poster\\n      backdrop\\n      __typename\\n    }\\n    pageInfo {\\n      hasNextPage\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
                val episodesReq = app.post(doraflixapi, requestBody = paginationepisodesBody.toRequestBody(mediaType)).parsed<MainDoramas>()
                episodesReq.data?.paginationEpisode?.items?.map {
                    val season = it.seasonNumber
                    val epnum = it.episodeNumber
                    val epSlug = it.slug
                    val epthumb = getImageUrl(it.stillPath)
                    val name = it.name
                    episodes.add(
                        Episode(
                            epSlug!!,
                            name,
                            season,
                            epnum,
                            epthumb
                        ))
                }
            }
        } else if (isMovie) {
            val linksJson = metaInfo?.linksOnline?.toJson()
            movieData = linksJson
        }
        return when (tvType) {
            TvType.AsianDrama -> {
                newTvSeriesLoadResponse(title!!,
                    datatwo, tvType, episodes,){
                    this.posterUrl = poster
                    this.backgroundPosterUrl = bgposter
                    this.plot = plot
                    this.tags = tags.distinct().toList()
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title!!, datatwo, tvType, movieData){
                    this.posterUrl = poster
                    this.plot = plot
                    this.backgroundPosterUrl = bgposter
                    this.tags = tags.distinct().toList()
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
        if (data.contains("link")) {
            val parse = parseJson<List<LinksOnline>>(data)
            parse.map {
                val link = it.link
                loadExtractor(link!!, data, subtitleCallback, callback)
            }
        } else {
            val episodeslinkRequestbody = "{\"operationName\":\"detailEpisodeLinks\",\"variables\":{\"episode_slug\":\"$data\"},\"query\":\"query detailEpisodeLinks(\$episode_slug: String!) {\\n  detailEpisode(filter: {slug: \$episode_slug, type_serie: \\\"dorama\\\"}) {\\n    links_online\\n   }\\n}\\n\"}"
            val request = app.post(doraflixapi, requestBody = episodeslinkRequestbody.toRequestBody(mediaType)).parsedSafe<MainDoramas>()
            request?.data?.detailEpisode?.linksOnline?.map {
                val link = it.link
                loadExtractor(link!!, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
