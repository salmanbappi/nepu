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
            // Re-throw as IOException for OkHttp
            if (e is IOException) throw e else throw IOException(e)
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
        
        // Ensure consistent User-Agent
        val userAgent = request.header("User-Agent") ?: DEFAULT_USER_AGENT
        
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
                userAgentString = userAgent
            }

            webview.addJavascriptInterface(jsInterface, "CloudflareJSI")
            webview.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(POLLING_SCRIPT) {}
                }
            }

            // Standard headers for WebView
            val webViewHeaders = mutableMapOf(
                "User-Agent" to userAgent
            )
            request.header("Referer")?.let { webViewHeaders["Referer"] = it }

            webview.loadUrl(origRequestUrl, webViewHeaders)
        }

        // Parallel polling for cookie changes (often faster than JS)
        val thread = Thread {
            val start = System.currentTimeMillis()
            while (latch.count > 0 && System.currentTimeMillis() - start < 30000) {
                val currentCookie = getClearanceCookie(origRequestUrl)
                if (currentCookie != null && currentCookie != oldCookie) {
                    latch.countDown()
                    break
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

        // Extract and sync all cookies to OkHttp
        val cookieString = cookieManager.getCookie(origRequestUrl)
        if (cookieString != null) {
            val cookies = cookieString.split(";").mapNotNull {
                val parts = it.trim().split("=", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                
                // Use standard OkHttp Cookie parse logic by simulating Set-Cookie
                Cookie.parse(request.url, "${parts[0]}=${parts[1]}; Domain=${request.url.host}")
            }
            
            cookies.forEach {
                client.cookieJar.saveFromResponse(request.url, listOf(it))
            }
        }

        // Build new request. We don't add the Cookie header manually because 
        // OkHttp's CookieJar (which we just updated) will handle it.
        return request.newBuilder()
            .header(BYPASS_HEADER, "true")
            .removeHeader("Cookie") // Let CookieJar provide the new cookies
            .build()
    }

    private fun getClearanceCookie(url: String): String? {
        return CookieManager.getInstance().getCookie(url)
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("=")
    }

    companion object {
        private val ERROR_CODES = listOf(403, 503)
        private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
        private const val BYPASS_HEADER = "X-Cloudflare-Bypass"
        
        // Modern Android Chrome UA
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

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

                    // Try checkbox
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
