package com.lagradost.cloudstream3.movieproviders

import android.annotation.TargetApi
import android.os.Build
import android.util.Base64
import android.util.Log
import android.webkit.URLUtil
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PelisplusHDProvider : MainAPI() {
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
        return if (isMovie) {
            MovieSearchResponse(
                title,
                href,
                name,
                TvType.Movie,
                posterUrl,
                null
            )
        } else {
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

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
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
            val name = li.selectFirst(".btn-primary.btn-block")?.text()
                ?.replace(Regex("(T(\\d+).*E(\\d+):)"), "")?.trim()
            val seasoninfo = href?.substringAfter("temporada/")?.replace("/capitulo/", "-")
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
            .map { it?.text()?.trim().toString().replace(", ", "") }

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
        app.get(data).document.select("script")
            .firstOrNull { it.html().contains("var video = [];") }?.html().let { script ->
                fetchUrls(
                    script
                )
                    .apmap { frameLink ->
                        if (frameLink.startsWith("https://embed69.org/")) {
                            val linkRegex = """"link":"(.*?)"""".toRegex()
                            val links = app.get(frameLink).document.select("script")
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
                            val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
                            regex.findAll(app.get(frameLink).document.html()).toList().apmap {
                                val current = it?.groupValues?.get(2) ?: ""
                                var link: String? = null
                                if (URLUtil.isValidUrl(current)) {
                                    link = fixUrl(current)
                                } else {
                                    try {
                                        link =
                                            base64Decode(
                                                it?.groupValues?.get(1) ?: ""
                                            )
                                    } catch (e: Throwable) {
                                    }
                                }
                                if (!link.isNullOrBlank()) {
                                    if (link.contains("https://api.mycdn.moe/video/") || link.contains(
                                            "https://api.mycdn.moe/embed.php?customid"
                                        )
                                    ) {
                                        val doc = app.get(link).document
                                        doc.select("div.ODDIV li").apmap {
                                            val linkencoded = it.attr("data-r")
                                            val linkdecoded = base64Decode(linkencoded)
                                                .replace(
                                                    Regex("https://owodeuwu.xyz|https://sypl.xyz"),
                                                    "https://embedsito.com"
                                                )
                                                .replace(Regex(".poster.*"), "")
                                            val secondlink =
                                                it.attr("onclick").substringAfter("go_to_player('")
                                                    .substringBefore("',")
                                            loadExtractor(
                                                linkdecoded,
                                                link,
                                                subtitleCallback,
                                                callback
                                            )
                                            val restwo = app.get(
                                                "https://api.mycdn.moe/player/?id=$secondlink",
                                                allowRedirects = false
                                            ).document
                                            val thirdlink =
                                                restwo.selectFirst("body > iframe")?.attr("src")
                                                    ?.replace(
                                                        Regex("https://owodeuwu.xyz|https://sypl.xyz"),
                                                        "https://embedsito.com"
                                                    )
                                                    ?.replace(Regex(".poster.*"), "")
                                            loadExtractor(
                                                thirdlink!!,
                                                link,
                                                subtitleCallback,
                                                callback
                                            )

                                        }
                                    } else {
                                        loadExtractor(link, data, subtitleCallback, callback)
                                    }
                                }
                            }
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
