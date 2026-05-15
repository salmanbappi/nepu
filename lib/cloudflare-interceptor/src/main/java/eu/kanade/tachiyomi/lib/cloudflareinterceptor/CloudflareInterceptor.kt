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

    @Synchronized
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalResponse = chain.proceed(originalRequest)

        // Check if Cloudflare anti-bot is active
        if (!(originalResponse.code in ERROR_CODES && originalResponse.header("Server") in SERVER_CHECK)) {
            return originalResponse
        }

        return try {
            originalResponse.close()
            val request = resolveWithWebView(originalRequest)
            chain.proceed(request)
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
        val oldCookie = cookieManager.getCookie(origRequestUrl)
            ?.split(";")
            ?.mapNotNull { it.trim().split("=").getOrNull(0) }
            ?.firstOrNull { it == "cf_clearance" }

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
                    // Start polling for completion
                    view?.evaluateJavascript(POLLING_SCRIPT) {}
                }
            }

            webview.loadUrl(origRequestUrl, headers)
        }

        // Poll for cookie change in parallel to JS interface
        val thread = Thread {
            val start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 30000) {
                val currentCookies = cookieManager.getCookie(origRequestUrl)
                if (currentCookies != null && currentCookies.contains("cf_clearance")) {
                    if (oldCookie == null || !currentCookies.contains("cf_clearance=$oldCookie")) {
                        latch.countDown()
                        break
                    }
                }
                Thread.sleep(1000)
            }
        }.apply { start() }

        latch.await(35, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val cookieString = cookieManager.getCookie(origRequestUrl)
        val cookies = cookieString?.split(";")?.mapNotNull { 
            Cookie.parse(request.url, it.trim()) 
        } ?: emptyList()

        // Sync with OkHttp client
        cookies.forEach {
            client.cookieJar.saveFromResponse(request.url, listOf(it))
        }

        return createRequestWithCookies(request, cookies)
    }

    private fun createRequestWithCookies(request: Request, cookies: List<Cookie>): Request {
        val filteredCookies = cookies.filter { it.matches(request.url) }
        if (filteredCookies.isEmpty()) return request

        val cookieHeader = filteredCookies.joinToString("; ") { "${it.name}=${it.value}" }
        return request.newBuilder()
            .header("Cookie", cookieHeader)
            .build()
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")

        private val POLLING_SCRIPT = """
            (function() {
                const interval = setInterval(() => {
                    const isPassed = () => {
                        // If these elements are gone, we likely passed
                        return !document.querySelector('#challenge-form') && 
                               !document.querySelector('#challenge-stage') &&
                               !document.querySelector('#cf-challenge-running');
                    };

                    if (isPassed()) {
                        CloudflareJSI.leave();
                        clearInterval(interval);
                        return;
                    }

                    // Try to click Turnstile checkbox if visible
                    const turnstile = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                    if (turnstile) {
                        try {
                            const btn = turnstile.contentWindow.document.querySelector('input[type="checkbox"]');
                            if (btn) btn.click();
                        } catch(e) {}
                    }

                    // Try simple button
                    const btn = document.querySelector('#challenge-stage input[type="button"]');
                    if (btn) btn.click();

                }, 2000);
            })();
        """.trimIndent()
    }
}
