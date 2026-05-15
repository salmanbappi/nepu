package eu.kanade.tachiyomi.lib.cloudflareinterceptor

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflareInterceptor(private val client: OkHttpClient) : Interceptor {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if Cloudflare anti-bot is active
        if (!(response.code in ERROR_CODES && response.header("Server") in SERVER_CHECK)) {
            return response
        }

        // Avoid infinite loops
        if (request.header(BYPASS_HEADER) != null) {
            return response
        }

        return try {
            response.close()
            val newRequest = resolveWithWebView(request)
            chain.proceed(newRequest)
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    class CloudflareJSI(private val latch: CountDownLatch) {
        @JavascriptInterface
        fun leave() {
            latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request {
        val latch = CountDownLatch(1)
        val jsInterface = CloudflareJSI(latch)
        val origRequestUrl = request.url.toString()
        val headers = request.headers.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()
        
        val cookieManager = CookieManager.getInstance()
        val oldCookie = getClearanceCookie(origRequestUrl)

        var webView: WebView? = null

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = false
                userAgentString = request.header("User-Agent")
            }

            webview.addJavascriptInterface(jsInterface, "CloudflareJSI")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(POLLING_SCRIPT) {}
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        // Parallel polling for cookie changes
        val thread = Thread {
            val start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 30000) {
                val currentCookie = getClearanceCookie(origRequestUrl)
                if (currentCookie != null && currentCookie != oldCookie) {
                    latch.countDown()
                    break
                }
                Thread.sleep(1500)
            }
        }.apply { start() }

        latch.await(35, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        // Extract and sync all cookies
        val cookieString = cookieManager.getCookie(origRequestUrl)
        val cookies = parseCookies(request.url, cookieString)

        cookies.forEach {
            client.cookieJar.saveFromResponse(request.url, listOf(it))
        }

        return request.newBuilder()
            .header(BYPASS_HEADER, "true")
            .header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    private fun getClearanceCookie(url: String): String? {
        return CookieManager.getInstance().getCookie(url)
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("=")
    }

    private fun parseCookies(url: HttpUrl, cookieString: String?): List<Cookie> {
        if (cookieString == null) return emptyList()
        return cookieString.split(";").mapNotNull {
            val parts = it.trim().split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            Cookie.Builder()
                .name(parts[0])
                .value(parts[1])
                .domain(url.host)
                .build()
        }
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        private const val BYPASS_HEADER = "X-Cloudflare-Bypass"

        private val POLLING_SCRIPT = """
            (function() {
                const interval = setInterval(() => {
                    const isPassed = () => {
                        return !document.querySelector('#challenge-form') && 
                               !document.querySelector('#challenge-stage') &&
                               !document.querySelector('#cf-challenge-running');
                    };

                    if (isPassed()) {
                        CloudflareJSI.leave();
                        clearInterval(interval);
                        return;
                    }

                    const turnstile = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                    if (turnstile) {
                        try {
                            const btn = turnstile.contentWindow.document.querySelector('input[type="checkbox"]');
                            if (btn) btn.click();
                        } catch(e) {}
                    }

                    const btn = document.querySelector('#challenge-stage input[type="button"]');
                    if (btn) btn.click();

                }, 2000);
            })();
        """.trimIndent()
    }
}
