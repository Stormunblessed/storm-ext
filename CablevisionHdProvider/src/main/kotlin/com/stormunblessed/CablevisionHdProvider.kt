package com.stormunblessed

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.net.URL

class CablevisionHdProvider : MainAPI() {

    override var mainUrl = "https://www.cablevisionhd.com"
    override var name = "CablevisionHd"
    override var lang = "es"

    override val hasQuickSearch = false
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
            TvType.Live,
    )

    private fun decodeBase64UntilUnchanged(encodedString: String): String {
        var decodedString = encodedString
        var previousDecodedString = ""
        while (decodedString != previousDecodedString) {
            previousDecodedString = decodedString
            decodedString = try {
                val decodedBytes = Base64.decode(decodedString, Base64.DEFAULT)
                String(decodedBytes)
            } catch (e: IllegalArgumentException) {
                // If decoding fails (e.g., not valid base64), break the loop
                break
            }
        }

        return decodedString
    }

    val nowAllowed = setOf("Únete al chat", "Donar con Paypal", "Lizard Premium")

    val deportesCat = setOf(
            "TUDN",
            "WWE",
            "Afizzionados",
            "Gol Perú",
            "Gol TV",
            "TNT SPORTS",
            "Fox Sports Premium",
            "TYC Sports",
            "Movistar Deportes (Perú)",
            "Movistar La Liga",
            "Movistar Liga De Campeones",
            "Dazn F1",
            "Dazn La Liga",
            "Bein La Liga",
            "Bein Sports Extra",
            "Directv Sports",
            "Directv Sports 2",
            "Directv Sports Plus",
            "Espn Deportes",
            "Espn Extra",
            "Espn Premium",
            "Espn",
            "Espn 2",
            "Espn 3",
            "Espn 4",
            "Espn Mexico",
            "Espn 2 Mexico",
            "Espn 3 Mexico",
            "Fox Deportes",
            "Fox Sports",
            "Fox Sports 2",
            "Fox Sports 3",
            "Fox Sports Mexico",
            "Fox Sports 2 Mexico",
            "Fox Sports 3 Mexico",
    )

    val entretenimientoCat = setOf(
            "Telefe",
            "El Trece",
            "Televisión Pública",
            "Telemundo Puerto rico",
            "Univisión",
            "Univisión Tlnovelas",
            "Pasiones",
            "Caracol",
            "RCN",
            "Latina",
            "America TV",
            "Willax TV",
            "ATV",
            "Las Estrellas",
            "Tl Novelas",
            "Galavision",
            "Azteca 7",
            "Azteca Uno",
            "Canal 5",
            "Distrito Comedia",
    )

    val noticiasCat = setOf(
            "Telemundo 51",
    )

    val peliculasCat = setOf(
            "Movistar Accion",
            "Movistar Drama",
            "Universal Channel",
            "TNT",
            "TNT Series",
            "Star Channel",
            "Star Action",
            "Star Series",
            "Cinemax",
            "Space",
            "Syfy",
            "Warner Channel",
            "Warner Channel (México)",
            "Cinecanal",
            "FX",
            "AXN",
            "AMC",
            "Studio Universal",
            "Multipremier",
            "Golden",
            "Golden Plus",
            "Golden Edge",
            "Golden Premier",
            "Golden Premier 2",
            "Sony",
            "DHE",
            "NEXT HD",
    )

    val infantilCat = setOf(
            "Cartoon Network",
            "Tooncast",
            "Cartoonito",
            "Disney Channel",
            "Disney JR",
            "Nick",
    )

    val educacionCat = setOf(
            "Discovery Channel",
            "Discovery World",
            "Discovery Theater",
            "Discovery Science",
            "Discovery Familia",
            "History",
            "History 2",
            "Animal Planet",
            "Nat Geo",
            "Nat Geo Mundo",
    )

    val dos47Cat = setOf(
            "24/7",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
                Pair("Deportes", mainUrl),
                Pair("Entretenimiento", mainUrl),
                Pair("Noticias", mainUrl),
                Pair("Peliculas", mainUrl),
                Pair("Infantil", mainUrl),
                Pair("Educacion", mainUrl),
                Pair("24/7", mainUrl),
                Pair("Todos", mainUrl),
        )
        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").filterNot { element ->
                val text = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                        ?: ""
                nowAllowed.any {
                    text.contains(it, ignoreCase = true)
                } || text.isBlank()
            }.filter {
                val text = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()?.trim()
                        ?: ""
                when (name) {
                    "Deportes" -> {
                        deportesCat.any {
                            text.equals(it, ignoreCase = true)
                        }
                    }

                    "Entretenimiento" -> {
                        entretenimientoCat.any {
                            text.equals(it, ignoreCase = true)
                        }
                    }

                    "Noticias" -> {
                        noticiasCat.any {
                            text.equals(it, ignoreCase = true)
                        }
                    }

                    "Peliculas" -> {
                        peliculasCat.any {
                            text.equals(it, ignoreCase = true)
                        }
                    }

                    "Infantil" -> {
                        infantilCat.any {
                            text.equals(it, ignoreCase = true)
                        }
                    }

                    "Educacion" -> {
                        educacionCat.any {
                            text.equals(it, ignoreCase = true)
                        }
                    }

                    "24/7" -> {
                        dos47Cat.any {
                            text.contains(it, ignoreCase = true)
                        }
                    }

                    "Todos" -> true
                    else -> true
                }
            }.map {
                val title = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                        ?: ""
                val img = it.selectFirst("div.lm-canal.lm-info-block.gray-default a div.container-image img")?.attr("src")
                        ?: ""
                val link = it.selectFirst("div.lm-canal.lm-info-block.gray-default a")?.attr("href")
                        ?: ""
                LiveSearchResponse(
                        title,
                        link,
                        this.name,
                        TvType.Live,
                        fixUrl(img),
                        null,
                        null,
                )
            }
            items.add(HomePageList(name, home, true))
        }

        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = mainUrl
        val doc = app.get(url).document
        return doc.select("div.page-scroll div#page_container.page-container.bg-move-effect div div#canales.row div.canal-item.col-6.col-xs-6.col-sm-6.col-md-3.col-lg-3").filterNot { element ->
            val text = element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                    ?: ""
            nowAllowed.any {
                text.contains(it, ignoreCase = true)
            } || text.isBlank()
        }.filter { element ->
            element.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()?.contains(query, ignoreCase = true)
                    ?: false
        }.map {
            val title = it.selectFirst("div.lm-canal.lm-info-block.gray-default a h4")?.text()
                    ?: ""
            val img = it.selectFirst("div.lm-canal.lm-info-block.gray-default a div.container-image img")?.attr("src")
                    ?: ""
            val link = it.selectFirst("div.lm-canal.lm-info-block.gray-default a")?.attr("href")
                    ?: ""
            LiveSearchResponse(
                    title,
                    link,
                    this.name,
                    TvType.Live,
                    fixUrl(img),
                    null,
                    null,
            )
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val poster = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body img")?.attr("src")?.replace(Regex("\\/p\\/w\\d+.*\\/"), "/p/original/")
                ?: ""
        val title = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title h2")?.text()
                ?: ""
        val desc = doc.selectFirst("div.page-scroll div#page_container.page-container.bg-move-effect div.block-title div.block-title div.section.mt-2 div.card.bg-dark.text-white div.card-body div.info")?.text()
                ?: ""

        return newMovieLoadResponse(
                title,
                url, TvType.Live, url
        ) {
            this.posterUrl = fixUrl(poster)
            this.backgroundPosterUrl = fixUrl(poster)
            this.plot = desc
        }

    }


    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("a.btn.btn-md").forEach {
            val trembedlink = it.attr("href")
            if (trembedlink.contains("/stream")) {
                val tremrequest = app.get(trembedlink, headers = mapOf(
                        "Host" to "www.cablevisionhd.com",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to data,
                        "Alt-Used" to "www.cablevisionhd.com",
                        "Connection" to "keep-alive",
                        "Cookie" to "TawkConnectionTime=0; twk_idm_key=qMfE5UE9JTs3JUBCtVUR1",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                )).document
                val trembedlink2 = tremrequest.selectFirst("iframe")?.attr("src") ?: ""
                val tremrequest2 = app.get(trembedlink2, headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Referer" to mainUrl,
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "cross-site",
                )).document
                val scriptPacked = tremrequest2.select("script").find { it.html().contains("function(p,a,c,k,e,d)") }?.html()
                val script = JsUnpacker(scriptPacked)
                if (script.detect()) {
                    val regex = """MARIOCSCryptOld\("(.*?)"\)""".toRegex()
                    val match = regex.find(script.unpack() ?: "")
                    val hash = match?.groupValues?.get(1) ?: ""
                    val extractedurl = decodeBase64UntilUnchanged(hash)
                    if (extractedurl.isNotBlank()) {
                        callback(
                                ExtractorLink(
                                        it.text() ?: getHostUrl(extractedurl),
                                        it.text() ?: getHostUrl(extractedurl),
                                        extractedurl,
                                        "${getBaseUrl(extractedurl)}/",
                                        getQualityFromName(""),
                                        extractedurl.contains("m3u8")
                                )
                        )
                    }
                }
            }
        }
        return true
    }

    fun getBaseUrl(urlString: String): String {
        val url = URL(urlString)
        return "${url.protocol}://${url.host}"
    }

    fun getHostUrl(urlString: String): String {
        val url = URL(urlString)
        return url.host
    }
}