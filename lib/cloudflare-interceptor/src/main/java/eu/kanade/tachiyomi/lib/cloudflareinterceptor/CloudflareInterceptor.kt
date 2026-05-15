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

        // Check for Cloudflare challenge (403 or 503 with Cloudflare server header)
        val isCloudflare = response.code in 403..503 && 
                response.header("Server")?.contains("cloudflare", ignoreCase = true) == true

        if (!isCloudflare || request.header("X-Cloudflare-Bypass") != null) {
            return response
        }

        response.close()
        val solvedRequest = resolveWithWebView(request)
        return chain.proceed(solvedRequest)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resolveWithWebView(request: Request): Request {
        val latch = CountDownLatch(1)
        val origUrl = request.url.toString()
        val userAgent = request.header("User-Agent") ?: DEFAULT_USER_AGENT
        
        var webView: WebView? = null

        handler.post {
            val wv = WebView(context)
            webView = wv
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = userAgent
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(POLLING_SCRIPT) {}
                }
            }

            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun done() { latch.countDown() }
            }, "CloudflareJSI")

            val headers = mutableMapOf("User-Agent" to userAgent)
            request.header("Referer")?.let { headers["Referer"] = it }
            wv.loadUrl(origUrl, headers)
        }

        // Parallel wait: Latch (JS) or Cookie Polling
        val start = System.currentTimeMillis()
        val cookieManager = CookieManager.getInstance()
        val oldCookie = cookieManager.getCookie(origUrl)
        
        while (latch.count > 0 && System.currentTimeMillis() - start < 30000) {
            val currentCookie = cookieManager.getCookie(origUrl)
            if (currentCookie != null && currentCookie != oldCookie && currentCookie.contains("cf_clearance")) {
                latch.countDown()
                break
            }
            try { Thread.sleep(1000) } catch (e: Exception) {}
        }

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val finalCookies = cookieManager.getCookie(origUrl) ?: ""
        
        // Sync to OkHttp CookieJar
        finalCookies.split(";").forEach {
            val cookiePart = it.trim()
            if (cookiePart.isNotEmpty()) {
                Cookie.parse(request.url, "$cookiePart; Domain=${request.url.host}")?.let { cookie ->
                    client.cookieJar.saveFromResponse(request.url, listOf(cookie))
                }
            }
        }

        return request.newBuilder()
            .header("X-Cloudflare-Bypass", "1")
            .header("Cookie", finalCookies)
            .build()
    }

    companion object {
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
                        window.CloudflareJSI.done();
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
