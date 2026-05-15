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

        // Check for Cloudflare challenge
        val isCloudflare = response.code in 403..503 && 
                response.header("Server")?.contains("cloudflare", ignoreCase = true) == true

        if (!isCloudflare || request.header(BYPASS_HEADER) != null) {
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
        val cookieManager = CookieManager.getInstance()

        handler.post {
            val wv = WebView(context)
            webView = wv
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = userAgent
            }

            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun done() { latch.countDown() }
            }, "CloudflareJSI")

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(POLLING_SCRIPT) {}
                }
            }

            wv.loadUrl(origUrl)
        }

        // Wait for challenge resolution
        val start = System.currentTimeMillis()
        val oldClearance = getClearance(origUrl)
        
        while (latch.count > 0 && System.currentTimeMillis() - start < 30000) {
            val currentClearance = getClearance(origUrl)
            if (currentClearance != null && currentClearance != oldClearance) {
                latch.countDown()
                break
            }
            try { Thread.sleep(1500) } catch (e: Exception) {}
        }

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        // Extract and sync all cookies
        val finalCookieString = cookieManager.getCookie(origUrl) ?: ""
        val cookies = finalCookieString.split(";").mapNotNull {
            val part = it.trim()
            if (part.isEmpty()) return@mapNotNull null
            Cookie.parse(request.url, "$part; Domain=${request.url.host}")
        }
        
        // Push to OkHttp CookieJar
        cookies.forEach {
            client.cookieJar.saveFromResponse(request.url, listOf(it))
        }

        // Build new request with all required headers for modern Cloudflare
        val newRequestBuilder = request.newBuilder()
            .header(BYPASS_HEADER, "1")
            .header("User-Agent", userAgent)
            // Manual cookie injection for immediate consistency
            .header("Cookie", finalCookieString)
            // Add Client Hints (Sec-CH-UA) to match Chrome 121
            .header("Sec-CH-UA", "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"")
            .header("Sec-CH-UA-Mobile", "?1")
            .header("Sec-CH-UA-Platform", "\"Android\"")

        return newRequestBuilder.build()
    }

    private fun getClearance(url: String): String? {
        return CookieManager.getInstance().getCookie(url)
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("cf_clearance=") }
            ?.substringAfter("=")
    }

    companion object {
        private const val BYPASS_HEADER = "X-Cloudflare-Bypass"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

        private val POLLING_SCRIPT = """
            (function() {
                const interval = setInterval(() => {
                    const challengeGone = !document.querySelector('#challenge-form') && 
                                          !document.querySelector('#challenge-stage') &&
                                          !document.querySelector('#cf-challenge-running');
                    
                    if (challengeGone) {
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
