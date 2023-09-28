package com.stormunblessed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests.Companion.await
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import android.util.Log
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
//import com.lagradost.cloudstream3.animeproviders.ZoroProvider
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.system.measureTimeMillis


private const val OPTIONS = "OPTIONS"

class AniwatchProvider : MainAPI() {
    override var mainUrl = "https://aniwatch.to"
    override var name = "Aniwatch"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val usesWebView = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )


    val epRegex = Regex("Ep (\\d+)/")
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrl(this.select("a").attr("href"))
        val title = this.select("h3.film-name").text()
        val dubSub = this.select(".film-poster > .tick.ltr").text()
        //val episodes = this.selectFirst(".film-poster > .tick-eps")?.text()?.toIntOrNull()

        val dubExist = dubSub.contains("dub", ignoreCase = true)
        val subExist = dubSub.contains("sub", ignoreCase = true)
        val episodes =
            this.selectFirst(".film-poster > .tick.rtl > .tick-eps")?.text()?.let { eps ->
                //println("REGEX:::: $eps")
                // current episode / max episode
                //Regex("Ep (\\d+)/(\\d+)")
                epRegex.find(eps)?.groupValues?.get(1)?.toIntOrNull()
            }
        if (href.contains("/news/") || title.trim().equals("News", ignoreCase = true)) return null
        val posterUrl = fixUrl(this.select("img").attr("data-src"))
        val type = getType(this.select("div.fd-infor > span.fdi-item").text())

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist, subExist, episodes, episodes)
        }
    }

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val html = app.get("$mainUrl/home").text
        val document = Jsoup.parse(html)

        val homePageList = ArrayList<HomePageList>()

        document.select("div.anif-block").forEach { block ->
            val header = block.select("div.anif-block-header").text().trim()
            val animes = block.select("li").mapNotNull {
                it.toSearchResult()
            }
            if (animes.isNotEmpty()) homePageList.add(HomePageList(header, animes))
        }

        document.select("section.block_area.block_area_home").forEach { block ->
            val header = block.select("h2.cat-heading").text().trim()
            val animes = block.select("div.flw-item").mapNotNull {
                it.toSearchResult()
            }
            if (animes.isNotEmpty()) homePageList.add(HomePageList(header, animes))
        }

        return HomePageResponse(homePageList)
    }

    private data class Response(
        @JsonProperty("status") val status: Boolean,
        @JsonProperty("html") val html: String
    )

//    override suspend fun quickSearch(query: String): List<SearchResponse> {
//        val url = "$mainUrl/ajax/search/suggest?keyword=${query}"
//        val html = mapper.readValue<Response>(khttp.get(url).text).html
//        val document = Jsoup.parse(html)
//
//        return document.select("a.nav-item").map {
//            val title = it.selectFirst(".film-name")?.text().toString()
//            val href = fixUrl(it.attr("href"))
//            val year = it.selectFirst(".film-infor > span")?.text()?.split(",")?.get(1)?.trim()?.toIntOrNull()
//            val image = it.select("img").attr("data-src")
//
//            AnimeSearchResponse(
//                title,
//                href,
//                this.name,
//                TvType.TvSeries,
//                image,
//                year,
//                null,
//                EnumSet.of(DubStatus.Subbed),
//                null,
//                null
//            )
//
//        }
//    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/search?keyword=$query"
        val html = app.get(link).text
        val document = Jsoup.parse(html)

        return document.select(".flw-item").map {
            val title = it.selectFirst(".film-detail > .film-name > a")?.attr("title").toString()
            val filmPoster = it.selectFirst(".film-poster")
            val poster = filmPoster!!.selectFirst("img")?.attr("data-src")

            val episodes = filmPoster.selectFirst("div.rtl > div.tick-eps")?.text()?.let { eps ->
                // current episode / max episode
                val epRegex = Regex("Ep (\\d+)/")//Regex("Ep (\\d+)/(\\d+)")
                epRegex.find(eps)?.groupValues?.get(1)?.toIntOrNull()
            }
            val dubsub = filmPoster.selectFirst("div.ltr")?.text()
            val dubExist = dubsub?.contains("DUB") ?: false
            val subExist = dubsub?.contains("SUB") ?: false || dubsub?.contains("RAW") ?: false

            val tvType =
                getType(it.selectFirst(".film-detail > .fd-infor > .fdi-item")?.text().toString())
            val href = fixUrl(it.selectFirst(".film-name a")!!.attr("href"))

            newAnimeSearchResponse(title, href, tvType) {
                this.posterUrl = poster
                addDubStatus(dubExist, subExist, episodes, episodes)
            }
        }
    }

    private fun Element?.getActor(): Actor? {
        val image =
            fixUrlNull(this?.selectFirst(".pi-avatar > img")?.attr("data-src")) ?: return null
        val name = this?.selectFirst(".pi-detail > .pi-name")?.text() ?: return null
        return Actor(name = name, image = image)
    }

    data class ZoroSyncData(
        @JsonProperty("mal_id") val malId: String?,
        @JsonProperty("anilist_id") val aniListId: String?,
    )

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val document = Jsoup.parse(html)
        val syncData = AppUtils.tryParseJson<ZoroSyncData>(document.selectFirst("#syncData")?.data())
        val title = document.selectFirst(".anisc-detail > .film-name")?.text().toString()
        val poster = document.selectFirst(".anisc-poster img")?.attr("src")
        val tags = document.select(".anisc-info a[href*=\"/genre/\"]").map { it.text() }
        val subEpisodes = ArrayList<Episode>()
        val dubEpisodes = ArrayList<Episode>()
        var year: Int? = null
        var japaneseTitle: String? = null
        var status: ShowStatus? = null

        for (info in document.select(".anisc-info > .item.item-title")) {
            val text = info?.text().toString()
            when {
                (year != null && japaneseTitle != null && status != null) -> break
                text.contains("Premiered") && year == null ->
                    year =
                        info.selectFirst(".name")?.text().toString().split(" ").last().toIntOrNull()

                text.contains("Japanese") && japaneseTitle == null ->
                    japaneseTitle = info.selectFirst(".name")?.text().toString()

                text.contains("Status") && status == null ->
                    status = getStatus(info.selectFirst(".name")?.text().toString())
            }
        }

        val description = document.selectFirst(".film-description.m-hide > .text")?.text()
        val animeId = URI(url).path.split("-").last()

        val episodes = Jsoup.parse(
            parseJson<Response>(
                app.get(
                    "$mainUrl/ajax/v2/episode/list/$animeId"
                ).text
            ).html
        ).select(".ss-list > a[href].ssl-item.ep-item").map { uno ->
            val episodeID = uno.attr("href").split("=")[1]

            val servers: List<Pair<String, String>> = Jsoup.parse(
                app.get("$mainUrl/ajax/v2/episode/servers?episodeId=$episodeID")
                    .parsed<Response>().html
            ).select(".server-item[data-type][data-id]").map {
                Pair(
                    it.attr("data-type"),
                    it.attr("data-id")
                )
            }

            val assa = servers.map {
            val serverId = it.second
            val dubstat  = it.first
            val dataeps = "{\"server\":\"$serverId\",\"dubs\":\"$dubstat\"}"

                if (dubstat.toString() == "sub") {
                    subEpisodes.add(
                        newEpisode(dataeps) {
                            this.name = uno?.attr("title")
                            this.episode = uno.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                        }
                    )
                }

                if (dubstat.toString() == "dub") {
                    dubEpisodes.add(
                        newEpisode(dataeps) {
                            this.name = uno?.attr("title")
                            this.episode = uno.selectFirst(".ssli-order")?.text()?.toIntOrNull()
                        }
                    )
                }
        }

        }

        val actors = document.select("div.block-actors-content > div.bac-list-wrap > div.bac-item")
            .mapNotNull { head ->
                val subItems = head.select(".per-info") ?: return@mapNotNull null
                if (subItems.isEmpty()) return@mapNotNull null
                var role: ActorRole? = null
                val mainActor = subItems.first()?.let {
                    role = when (it.selectFirst(".pi-detail > .pi-cast")?.text()?.trim()) {
                        "Supporting" -> ActorRole.Supporting
                        "Main" -> ActorRole.Main
                        else -> null
                    }
                    it.getActor()
                } ?: return@mapNotNull null
                val voiceActor = if (subItems.size >= 2) subItems[1]?.getActor() else null
                ActorData(actor = mainActor, role = role, voiceActor = voiceActor)
            }

        val recommendations =
            document.select("#main-content > section > .tab-content > div > .film_list-wrap > .flw-item")
                .mapNotNull { head ->
                    val filmPoster = head?.selectFirst(".film-poster")
                    val epPoster = filmPoster?.selectFirst("img")?.attr("data-src")
                    val a = head?.selectFirst(".film-detail > .film-name > a")
                    val epHref = a?.attr("href")
                    val epTitle = a?.attr("title")
                    if (epHref == null || epTitle == null || epPoster == null) {
                        null
                    } else {
                        AnimeSearchResponse(
                            epTitle,
                            fixUrl(epHref),
                            this.name,
                            TvType.Anime,
                            epPoster,
                            dubStatus = null
                        )
                    }
                }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            japName = japaneseTitle
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Dubbed, dubEpisodes)
            addEpisodes(DubStatus.Subbed, subEpisodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            this.actors = actors
            addMalId(syncData?.malId?.toIntOrNull())
            addAniListId(syncData?.aniListId?.toIntOrNull())
        }
    }

    private data class RapidCloudResponse(
        @JsonProperty("link") val link: String
    )

    /** Url hashcode to sid */
    var sid: HashMap<Int, String?> = hashMapOf()

    /**
     * Makes an identical Options request before .ts request
     * Adds an SID header to the .ts request.
     * */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        // Needs to be object instead of lambda to make it compile correctly
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                if (request.url.toString().endsWith(".ts")
                    && request.method != OPTIONS
                    // No option requests on VidCloud
                    && !request.url.toString().contains("betterstream")
                ) {
                    val newRequest =
                        chain.request()
                            .newBuilder().apply {
                                sid[extractorLink.url.hashCode()]?.let { sid ->
                                    addHeader("SID", sid)
                                }
                            }
                            .build()
                    val options = request.newBuilder().method(OPTIONS, request.body).build()
                    ioSafe { app.baseClient.newCall(options).await() }

                    return chain.proceed(newRequest)
                } else {
                    return chain.proceed(chain.request())
                }
            }
        }
    }

    private suspend fun getKey(): String {
        return app.get("https://raw.githubusercontent.com/enimax-anime/key/e6/key.txt").text
    }

    data class Tracks(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class Sources(
        @JsonProperty("file") val file: String?,
        @JsonProperty("type") val type: String?,
        @JsonProperty("label") val label: String?
    )

    data class SourceObject(
        @JsonProperty("sources") val sources: List<Sources?>? = null,
        @JsonProperty("sources_1") val sources1: List<Sources?>? = null,
        @JsonProperty("sources_2") val sources2: List<Sources?>? = null,
        @JsonProperty("sourcesBackup") val sourcesBackup: List<Sources?>? = null,
        @JsonProperty("tracks") val tracks: List<Tracks?>? = null
    )

    data class SourceObjectEncrypted(
        @JsonProperty("sources") val sources: String?,
        @JsonProperty("encrypted") val encrypted: Boolean?,
        @JsonProperty("sources_1") val sources1: String?,
        @JsonProperty("sources_2") val sources2: String?,
        @JsonProperty("sourcesBackup") val sourcesBackup: String?,
        @JsonProperty("tracks") val tracks: List<Tracks?>?
    )

    data class IframeJson(
//        @JsonProperty("type") val type: String? = null,
        @JsonProperty("link") val link: String? = null,
//        @JsonProperty("sources") val sources: ArrayList<String> = arrayListOf(),
//        @JsonProperty("tracks") val tracks: ArrayList<String> = arrayListOf(),
//        @JsonProperty("title") val title: String? = null
    )

    data class SubDubInfo (
        @JsonProperty("server"   ) val server   : String,
        @JsonProperty("dubs" ) val dubs : String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parseData = AppUtils.parseJson<SubDubInfo>(data)
        val serverstwo = listOf(
            Pair(parseData.dubs, parseData.server)
        )
//        val extractorData =
//            "https://ws1.rapid-cloud.ru/socket.io/?EIO=4&transport=polling"

        // Prevent duplicates
        serverstwo.distinctBy { it.second }.apmap {
            val link =
                "$mainUrl/ajax/v2/episode/sources?id=${it.second}"
            val extractorLink = app.get(
                link,
            ).parsed<RapidCloudResponse>().link
            val hasLoadedExtractorLink =
                loadExtractor(extractorLink, "https://rapid-cloud.ru/", subtitleCallback, callback)
            if (!hasLoadedExtractorLink) {
                extractRabbitStream(
                    extractorLink,
                    subtitleCallback,
                    // Blacklist VidCloud for now
                    { videoLink -> if (!videoLink.url.contains("betterstream")) callback(videoLink) },
                    false,
                    null,
                    decryptKey = getKey()
                ) { sourceName ->
                    sourceName + " - ${it.first}"
                }
            }
        }

        return true
    }

    companion object {
        data class PollingData(
            @JsonProperty("sid") val sid: String? = null,
            @JsonProperty("upgrades") val upgrades: ArrayList<String> = arrayListOf(),
            @JsonProperty("pingInterval") val pingInterval: Int? = null,
            @JsonProperty("pingTimeout") val pingTimeout: Int? = null
        )

        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

        /*
        # python code to figure out the time offset based on code if necessary
        chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        code = "Nxa_-bM"
        total = 0
        for i, char in enumerate(code[::-1]):
            index = chars.index(char)
            value = index * 64**i
            total += value
        print(f"total {total}")
        */
        private fun generateTimeStamp(): String {
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
            var code = ""
            var time = unixTimeMS
            while (time > 0) {
                code += chars[(time % (chars.length)).toInt()]
                time /= chars.length
            }
            return code.reversed()
        }

        suspend fun getKey(): String? {
            return app.get("https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt")
                .text
        }

        /**
         * Generates a session
         * 1 Get request.
         * */
        private suspend fun negotiateNewSid(baseUrl: String): PollingData? {
            // Tries multiple times
            for (i in 1..5) {
                val jsonText =
                    app.get("$baseUrl&t=${generateTimeStamp()}").text.replaceBefore("{", "")
//            println("Negotiated sid $jsonText")
                parseJson<PollingData?>(jsonText)?.let { return it }
                delay(1000L * i)
            }
            return null
        }

        /**
         * Generates a new session if the request fails
         * @return the data and if it is new.
         * */
        private suspend fun getUpdatedData(
            response: NiceResponse,
            data: PollingData,
            baseUrl: String
        ): Pair<PollingData, Boolean> {
            if (!response.okhttpResponse.isSuccessful) {
                return negotiateNewSid(baseUrl)?.let {
                    it to true
                } ?: (data to false)
            }
            return data to false
        }


        private suspend fun initPolling(
            extractorData: String,
            referer: String
        ): Pair<PollingData?, String?> {
            val headers = mapOf(
                "Referer" to referer // "https://rabbitstream.net/"
            )

            val data = negotiateNewSid(extractorData) ?: return null to null
            app.post(
                "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                requestBody = "40".toRequestBody(),
                headers = headers
            )

            // This makes the second get request work, and re-connect work.
            val reconnectSid =
                parseJson<PollingData>(
                    app.get(
                        "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                        headers = headers
                    )
//                    .also { println("First get ${it.text}") }
                        .text.replaceBefore("{", "")
                ).sid

            // This response is used in the post requests. Same contents in all it seems.
            val authInt =
                app.get(
                    "$extractorData&t=${generateTimeStamp()}&sid=${data.sid}",
                    timeout = 60,
                    headers = headers
                ).text
                    //.also { println("Second get ${it}") }
                    // Dunno if it's actually generated like this, just guessing.
                    .toIntOrNull()?.plus(1) ?: 3

            return data to reconnectSid
        }

        suspend fun runSflixExtractorVerifierJob(
            api: MainAPI,
            extractorData: String?,
            referer: String
        ) {
            if (extractorData == null) return
            val headers = mapOf(
                "Referer" to referer // "https://rabbitstream.net/"
            )

            lateinit var data: PollingData
            var reconnectSid = ""

            initPolling(extractorData, referer)
                .also {
                    data = it.first ?: throw RuntimeException("Data Null")
                    reconnectSid = it.second ?: throw RuntimeException("ReconnectSid Null")
                }

            // Prevents them from fucking us over with doing a while(true){} loop
            val interval = maxOf(data.pingInterval?.toLong()?.plus(2000) ?: return, 10000L)
            var reconnect = false
            var newAuth = false


            while (true) {
                val authData =
                    when {
                        newAuth -> "40"
                        reconnect -> """42["_reconnect", "$reconnectSid"]"""
                        else -> "3"
                    }

                val url = "${extractorData}&t=${generateTimeStamp()}&sid=${data.sid}"

                getUpdatedData(
                    app.post(url, json = authData, headers = headers),
                    data,
                    extractorData
                ).also {
                    newAuth = it.second
                    data = it.first
                }

                //.also { println("Sflix post job ${it.text}") }
                Log.d(api.name, "Running ${api.name} job $url")

                val time = measureTimeMillis {
                    // This acts as a timeout
                    val getResponse = app.get(
                        url,
                        timeout = interval / 1000,
                        headers = headers
                    )
//                    .also { println("Sflix get job ${it.text}") }
                    reconnect = getResponse.text.contains("sid")
                }
                // Always waits even if the get response is instant, to prevent a while true loop.
                if (time < interval - 4000)
                    delay(4000)
            }
        }

        // Only scrape servers with these names
        fun String?.isValidServer(): Boolean {
            val list = listOf("upcloud", "vidcloud", "streamlare")
            return list.contains(this?.lowercase(Locale.ROOT))
        }

        // For re-use in Zoro
        private suspend fun Sources.toExtractorLink(
            caller: MainAPI,
            name: String,
            extractorData: String? = null,
        ): List<ExtractorLink>? {
            return this.file?.let { file ->
                //println("FILE::: $file")
                val isM3u8 = URI(this.file).path.endsWith(".m3u8") || this.type.equals(
                    "hls",
                    ignoreCase = true
                )
                return if (isM3u8) {
                    suspendSafeApiCall {
                        M3u8Helper().m3u8Generation(
                            M3u8Helper.M3u8Stream(
                                this.file,
                                null,
                                mapOf("Referer" to "https://mzzcloud.life/")
                            ), false
                        )
                            .map { stream ->
                                ExtractorLink(
                                    caller.name,
                                    "${caller.name} $name",
                                    stream.streamUrl,
                                    caller.mainUrl,
                                    getQualityFromName(stream.quality?.toString()),
                                    true,
                                    extractorData = extractorData
                                )
                            }
                    }.takeIf { !it.isNullOrEmpty() } ?: listOf(
                        // Fallback if m3u8 extractor fails
                        ExtractorLink(
                            caller.name,
                            "${caller.name} $name",
                            this.file,
                            caller.mainUrl,
                            getQualityFromName(this.label),
                            isM3u8,
                            extractorData = extractorData
                        )
                    )
                } else {
                    listOf(
                        ExtractorLink(
                            caller.name,
                            caller.name,
                            file,
                            caller.mainUrl,
                            getQualityFromName(this.label),
                            false,
                            extractorData = extractorData
                        )
                    )
                }
            }
        }

        private fun Tracks.toSubtitleFile(): SubtitleFile? {
            return this.file?.let {
                SubtitleFile(
                    this.label ?: "Unknown",
                    it
                )
            }
        }

        private fun md5(input: ByteArray): ByteArray {
            return MessageDigest.getInstance("MD5").digest(input)
        }

        private fun generateKey(salt: ByteArray, secret: ByteArray): ByteArray {
            var key = md5(secret + salt)
            var currentKey = key
            while (currentKey.size < 48) {
                key = md5(key + secret + salt)
                currentKey += key
            }
            return currentKey
        }

        private fun decryptSourceUrl(decryptionKey: ByteArray, sourceUrl: String): String {
            val cipherData = base64DecodeArray(sourceUrl)
            val encrypted = cipherData.copyOfRange(16, cipherData.size)
            val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")

            Objects.requireNonNull(aesCBC).init(
                Cipher.DECRYPT_MODE, SecretKeySpec(
                    decryptionKey.copyOfRange(0, 32),
                    "AES"
                ),
                IvParameterSpec(decryptionKey.copyOfRange(32, decryptionKey.size))
            )
            val decryptedData = aesCBC!!.doFinal(encrypted)
            return String(decryptedData, StandardCharsets.UTF_8)
        }

        private inline fun <reified T> decryptMapped(input: String, key: String): T? {
            return tryParseJson(decrypt(input, key))
        }

        private fun decrypt(input: String, key: String): String {
            return decryptSourceUrl(
                generateKey(
                    base64DecodeArray(input).copyOfRange(8, 16),
                    key.toByteArray()
                ), input
            )
        }

        suspend fun MainAPI.extractRabbitStream(
            url: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            useSidAuthentication: Boolean,
            /** Used for extractorLink name, input: Source name */
            extractorData: String? = null,
            decryptKey: String? = null,
            nameTransformer: (String) -> String,
        ) = suspendSafeApiCall {
            // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> https://rapid-cloud.ru/embed-6
            val mainIframeUrl =
                url.substringBeforeLast("/")
            val mainIframeId = url.substringAfterLast("/")
                .substringBefore("?") // https://rapid-cloud.ru/embed-6/dcPOVRE57YOT?z= -> dcPOVRE57YOT
//            val iframe = app.get(url, referer = mainUrl)
//            val iframeKey =
//                iframe.document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
//                    .attr("src").substringAfter("render=")
//            val iframeToken = getCaptchaToken(url, iframeKey)
//            val number =
//                Regex("""recaptchaNumber = '(.*?)'""").find(iframe.text)?.groupValues?.get(1)

            var sid: String? = null
            if (useSidAuthentication && extractorData != null) {
                negotiateNewSid(extractorData)?.also { pollingData ->
                    app.post(
                        "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                        requestBody = "40".toRequestBody(),
                        timeout = 60
                    )
                    val text = app.get(
                        "$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}",
                        timeout = 60
                    ).text.replaceBefore("{", "")

                    sid = parseJson<PollingData>(text).sid
                    ioSafe { app.get("$extractorData&t=${generateTimeStamp()}&sid=${pollingData.sid}") }
                }
            }
            val getSourcesUrl = "${
                mainIframeUrl.replace(
                    "/embed",
                    "/ajax/embed"
                )
            }/getSources?id=$mainIframeId${sid?.let { "$&sId=$it" } ?: ""}"
            val response = app.get(
                getSourcesUrl,
                referer = mainUrl,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "Connection" to "keep-alive",
                    "TE" to "trailers"
                )
            )

            val sourceObject = if (decryptKey != null) {
                val encryptedMap = response.parsedSafe<SourceObjectEncrypted>()
                val sources = encryptedMap?.sources
                if (sources == null || encryptedMap.encrypted == false) {
                    response.parsedSafe()
                } else {
                    val decrypted = decryptMapped<List<Sources>>(sources, decryptKey)
                    SourceObject(
                        sources = decrypted,
                        tracks = encryptedMap.tracks
                    )
                }
            } else {
                response.parsedSafe()
            } ?: return@suspendSafeApiCall

            sourceObject.tracks?.forEach { track ->
                track?.toSubtitleFile()?.let { subtitleFile ->
                    subtitleCallback.invoke(subtitleFile)
                }
            }

            val list = listOf(
                sourceObject.sources to "source 1",
                sourceObject.sources1 to "source 2",
                sourceObject.sources2 to "source 3",
                sourceObject.sourcesBackup to "source backup"
            )

            list.forEach { subList ->
                subList.first?.forEach { source ->
                    source?.toExtractorLink(
                        this,
                        nameTransformer(subList.second),
                        extractorData,
                    )
                        ?.forEach {
                            // Sets Zoro SID used for video loading
//                            (this as? ZoroProvider)?.sid?.set(it.url.hashCode(), sid)
                            callback(it)
                        }
                }
            }
        }
    }
}
