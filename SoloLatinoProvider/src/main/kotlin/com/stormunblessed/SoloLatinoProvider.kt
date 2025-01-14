package com.stormunblessed

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SoloLatinoProvider : MainAPI() {
    override var mainUrl = "https://sololatino.net"
    override var name = "SoloLatino"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Animes", "$mainUrl/animes"),
            Pair("Cartoons", "$mainUrl/genre_series/toons"),
        )

        urls.apmap { (name, url) ->
            val tvType = when (name) {
                "Peliculas" -> TvType.Movie
                "Series" -> TvType.TvSeries
                "Animes" -> TvType.Anime
                "Cartoons" -> TvType.Cartoon
                else -> TvType.Others
            }
            val doc = app.get(url).document
            val home = doc.select("div.items article.item").map {
                val title = it.selectFirst("a div.data h3")?.text()
                val link = it.selectFirst("a")?.attr("href")
                val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
                TvSeriesSearchResponse(
                    title!!,
                    link!!,
                    this.name,
                    tvType,
                    img,
                )
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = app.get(url).document
        return doc.select("div.items article.item").map {
            val title = it.selectFirst("a div.data h3")?.text()
            val link = it.selectFirst("a")?.attr("href")
            val img = it.selectFirst("div.poster img.lazyload")?.attr("data-srcset")
            TvSeriesSearchResponse(
                title!!,
                link!!,
                this.name,
                TvType.TvSeries,
                img,
            )
        }
    }

    class MainTemporada(elements: Map<String, List<MainTemporadaElement>>) :
        HashMap<String, List<MainTemporadaElement>>(elements)

    data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("peliculas")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst("div.data h1")?.text() ?: ""
//        val backimage = doc.selectFirst("head meta[property=og:image]")!!.attr("content")
        val poster = doc.selectFirst("div.poster img")!!.attr("src")
        val description = doc.selectFirst("div.wp-content")!!.text()
        val tags = doc.select("div.sgeneros a").map { it.text() }
        var episodes = if (tvType == TvType.TvSeries) {
            doc.select("div#seasons div.se-c").flatMap { season ->
                season.select("ul.episodios li").map {
                    val epurl = fixUrl(it.selectFirst("a")?.attr("href") ?: "")
                    val epTitle = it.selectFirst("div.episodiotitle div.epst")!!.text()
                    val seasonEpisodeNumber =
                        it.selectFirst("div.episodiotitle div.numerando")?.text()?.split("-")?.map {
                            it.trim().toIntOrNull()
                        }
                    val realimg = it.selectFirst("div.imagen img")?.attr("src")
                    Episode(
                        epurl,
                        epTitle,
                        seasonEpisodeNumber?.getOrNull(0),
                        seasonEpisodeNumber?.getOrNull(1),
                        realimg,
                    )
                }
            }
        } else listOf()

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(
                    title,
                    url, tvType, episodes,
                ) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
                }
            }

            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = poster
                    this.plot = description
                    this.tags = tags
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
        val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
        app.get(data).document.selectFirst("iframe")?.attr("src")?.let { frameUrl ->
            if (frameUrl.startsWith("https://embed69.org/")) {
                val linkRegex = """"link":"(.*?)"""".toRegex()
                val links = app.get(frameUrl).document.select("script")
                    .firstOrNull { it.html().contains("const dataLink = [") }?.html()
                    ?.substringAfter("const dataLink = ")
                    ?.substringBefore(";")?.let {
                        linkRegex.findAll(it).map { it.groupValues[1] }.map {
                            decryptCryptoJsAES(
                                it,
                                "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE"
                            )
                        }
                    }?.toList();
                links?.apmap {
                    loadExtractor(it!!, data, subtitleCallback, callback)
                }
            } else {
                regex.findAll(app.get(frameUrl).document.html()).map { it.groupValues.get(2) }
                    .toList().apmap {
                        loadExtractor(it, data, subtitleCallback, callback)
                    }
            }
        }
        return true
    }

    fun generateKeyAndIV(
        keyLength: Int,
        ivLength: Int,
        iterations: Int,
        salt: ByteArray,
        password: ByteArray,
        md: MessageDigest
    ): Array<ByteArray> {
        val digestLength = md.digestLength
        val requiredLength = (keyLength + ivLength + digestLength - 1) / digestLength * digestLength
        val generatedData = ByteArray(requiredLength)
        var generatedLength = 0

        try {
            md.reset()

            while (generatedLength < keyLength + ivLength) {
                if (generatedLength > 0)
                    md.update(generatedData, generatedLength - digestLength, digestLength)
                md.update(password)
                if (salt != null)
                    md.update(salt, 0, 8)
                md.digest(generatedData, generatedLength, digestLength)

                for (i in 1 until iterations) {
                    md.update(generatedData, generatedLength, digestLength)
                    md.digest(generatedData, generatedLength, digestLength)
                }

                generatedLength += digestLength
            }

            val result = Array(2) { ByteArray(0) }
            result[0] = Arrays.copyOfRange(generatedData, 0, keyLength)
            if (ivLength > 0)
                result[1] = Arrays.copyOfRange(generatedData, keyLength, keyLength + ivLength)

            return result
        } catch (e: Exception) {
            throw RuntimeException(e)
        } finally {
            Arrays.fill(generatedData, 0.toByte())
        }
    }

    fun decryptCryptoJsAES(encryptedData: String, key: String): String {
        val cipherData = Base64.decode(encryptedData, Base64.DEFAULT)
        val saltData = Arrays.copyOfRange(cipherData, 8, 16)

        val md5 = MessageDigest.getInstance("MD5")
        val keyAndIV = generateKeyAndIV(32, 16, 1, saltData, key.toByteArray(Charsets.UTF_8), md5)
        val secretKey = SecretKeySpec(keyAndIV[0], "AES")
        val iv = IvParameterSpec(keyAndIV[1])

        val encrypted = Arrays.copyOfRange(cipherData, 16, cipherData.size)
        val aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aesCBC.init(Cipher.DECRYPT_MODE, secretKey, iv)
        val decryptedData = aesCBC.doFinal(encrypted)
        val decryptedText = String(decryptedData, Charsets.UTF_8)

        return decryptedText
    }

}