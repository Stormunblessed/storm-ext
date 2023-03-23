package com.stormunblessed


import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AcraApplication.Companion.context
import com.lagradost.cloudstream3.utils.Coroutines
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.nicehttp.requestCreator
import com.stormunblessed.BflixProviderPlugin.Companion.postFunction
import kotlinx.coroutines.runBlocking
import okhttp3.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

//Credits https://github.com/jmir1/aniyomi-extensions/blob/master/src/en/nineanime/src/eu/kanade/tachiyomi/animeextension/en/nineanime/JsInterceptor.kt

class JsInterceptor(private val serverid: String) : Interceptor {

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    class JsObject(var payload: String = "") {
        @JavascriptInterface
        fun passPayload(passedPayload: String) {
            payload = passedPayload
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runBlocking {
            val fixedRequest = resolveWithWebView(request)
            return@runBlocking chain.proceed(fixedRequest ?: request)
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request? {
        val latch = CountDownLatch(1)

        var webView: WebView? = null

        val origRequestUrl = request.url.toString()

        val jsinterface = JsObject()

        fun destroyWebView() {
            Coroutines.main {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                println("Destroyed webview")
            }
        }

        // JavaSrcipt gets the Dub or Sub link of vidstream
        val jsScript = """
                (function() {
                  var click = document.createEvent('MouseEvents');
                  click.initMouseEvent('click', true, true);
                  document.querySelector('div.server[data-id="$serverid"]').dispatchEvent(click);
                })();
        """

        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

        var newRequest: Request? = null

        handler.postFunction {
            val webview = WebView(context!!)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:106.0) Gecko/20100101 Firefox/106.0"
                webview.addJavascriptInterface(jsinterface, "android")
                webview.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {

                        if (serverid == "41") {
                            if (!request?.url.toString().contains("vidstream") &&
                                !request?.url.toString().contains("vizcloud")
                            ) return null
                        }
                        if (serverid == "28") {
                            if (!request?.url.toString().contains("mcloud")
                            ) return null
                        }

                        if (request?.url.toString().contains(Regex("list.m3u8|/simple/"))) {
                            newRequest = requestCreator("GET", request?.url.toString(), headers = mapOf("referer" to "/orp.maertsdiv//:sptth".reversed()))
                            latch.countDown()
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(jsScript) {}
                    }
                }
                webView?.loadUrl(origRequestUrl, headers)
            }
        }

        latch.await(45, TimeUnit.SECONDS)

        handler.postFunction {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
           // context.let { Toast.makeText(it, "Success!", Toast.LENGTH_SHORT).show()}
        }
        var loop = 0
        val totalTime = 60000L

        val delayTime = 100L

        while (loop < totalTime / delayTime) {
            if (newRequest != null) return newRequest
            loop += 1
        }
        println("Web-view timeout after ${totalTime / 1000}s")
        destroyWebView()
        return newRequest
    }
}