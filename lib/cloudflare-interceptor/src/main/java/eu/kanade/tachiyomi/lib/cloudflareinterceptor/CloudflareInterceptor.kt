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
import okhttp3.HttpUrl.Companion.toHttpUrl
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

        // Check if we are facing a Cloudflare challenge
        val isChallenge = response.code in 403..503 && 
            response.header("Server")?.contains("cloudflare", ignoreCase = true) == true

        if (!isChallenge || request.header(BYPASS_HEADER) != null) {
            return response
        }

        response.close()
        
        // Solve the challenge and get a new request with updated cookies/headers
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
                useWideViewPort = true
                loadWithOverviewMode = false
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

            // Load the URL to trigger the challenge
            wv.loadUrl(origUrl)
        }

        // Wait for solution: Either JS interface signals 'done' or cookies change
        val start = System.currentTimeMillis()
        val oldCookie = cookieManager.getCookie(origUrl) ?: ""
        
        while (latch.count > 0 && System.currentTimeMillis() - start < 30000) {
            val currentCookie = cookieManager.getCookie(origUrl) ?: ""
            // Success if we get a NEW cf_clearance cookie
            if (currentCookie != oldCookie && currentCookie.contains("cf_clearance")) {
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

        // --- THE BRIDGE: Sync WebView Cookies to OkHttp Client ---
        val finalCookieString = cookieManager.getCookie(origUrl) ?: ""
        if (finalCookieString.isNotEmpty()) {
            val cookies = finalCookieString.split(";").mapNotNull {
                val part = it.trim()
                if (part.isEmpty()) return@mapNotNull null
                // Parse the cookie string into an OkHttp Cookie object
                Cookie.parse(request.url, "$part; Domain=${request.url.host}")
            }
            
            // Save to the OkHttp client's shared CookieJar
            client.cookieJar.saveFromResponse(request.url, cookies)
        }

        // Return new request with the bypass header and synchronized User-Agent
        return request.newBuilder()
            .header(BYPASS_HEADER, "1")
            .header("User-Agent", userAgent)
            .build()
    }

    companion object {
        private const val BYPASS_HEADER = "X-Cloudflare-Bypass"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

        // Script to detect when the challenge is gone and click Turnstile if needed
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

                    // Attempt to click Turnstile checkbox
                    const turnstile = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                    if (turnstile) {
                        try {
                            const btn = turnstile.contentWindow.document.querySelector('input[type="checkbox"]');
                            if (btn) btn.click();
                        } catch(e) {}
                    }
                    
                    // Attempt to click simple "Verify" button
                    const btn = document.querySelector('#challenge-stage input[type="button"]');
                    if (btn) btn.click();

                }, 2000);
            })();
        """.trimIndent()
    }
}
