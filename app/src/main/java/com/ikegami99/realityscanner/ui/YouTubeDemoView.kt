package com.ikegami99.realityscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class YouTubeDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var onVideoLoaded: ((String) -> Unit)? = null
    var onVideoError: ((String) -> Unit)? = null

    private var currentVideoId: String? = null
    private var directFallbackActive = false

    init {
        configure()
        visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configure() {
        setBackgroundColor(Color.BLACK)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(PlayerBridge(), "RealityScanner")
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (directFallbackActive && url?.contains("youtube.com/embed/") == true) {
                    currentVideoId?.let { id -> onVideoLoaded?.invoke("$id // direct-fallback") }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    onVideoError?.invoke(
                        "YouTube page load failed: ${error?.errorCode} ${error?.description ?: "unknown"}"
                    )
                }
            }
        }
    }

    fun play(input: String): Boolean {
        val videoId = extractVideoId(input)
        if (videoId == null) {
            onVideoError?.invoke("YouTube URL / video ID could not be parsed")
            return false
        }

        currentVideoId = videoId
        directFallbackActive = false
        scaleX = 1f
        scaleY = 1f
        visibility = View.VISIBLE
        loadDataWithBaseURL(
            APP_ORIGIN,
            buildHtml(videoId),
            "text/html",
            "UTF-8",
            null
        )
        return true
    }

    fun stopPlayback() {
        currentVideoId = null
        directFallbackActive = false
        scaleX = 1f
        scaleY = 1f
        runCatching { evaluateJavascript("if(window.player){try{player.stopVideo();}catch(e){}}", null) }
        runCatching { loadUrl("about:blank") }
        visibility = View.GONE
    }

    fun release() {
        stopLoading()
        loadUrl("about:blank")
        clearHistory()
        removeAllViews()
        destroy()
    }

    private fun startDirectFallback(videoId: String) {
        if (directFallbackActive) return
        directFallbackActive = true

        // The direct embed gets a real HTTPS document URL plus an explicit Referer header.
        // Zooming the whole WebView by 16:9 keeps the video in square cover mode while the
        // SquareFrameLayout clips the extra left/right area.
        scaleX = COVER_SCALE
        scaleY = COVER_SCALE
        pivotX = width / 2f
        pivotY = height / 2f

        val url = "https://www.youtube.com/embed/$videoId" +
            "?autoplay=1&playsinline=1&rel=0&controls=1"
        loadUrl(
            url,
            mapOf(
                "Referer" to "https://www.youtube.com/"
            )
        )
    }

    private inner class PlayerBridge {
        @JavascriptInterface
        fun ready() {
            val id = currentVideoId ?: return
            post { onVideoLoaded?.invoke(id) }
        }

        @JavascriptInterface
        fun error(code: Int) {
            val id = currentVideoId ?: return
            val detail = when (code) {
                2 -> "invalid video ID / parameter"
                5 -> "HTML5 player error"
                100 -> "video not found, removed, or private"
                101, 150 -> "video owner disabled embedded playback"
                153 -> "YouTube rejected embed identity / HTTP Referer"
                else -> "unknown player error"
            }

            post {
                if (code == 153 && !directFallbackActive) {
                    onVideoError?.invoke(
                        "YouTube player error 153 // retrying direct embed with explicit Referer"
                    )
                    startDirectFallback(id)
                } else {
                    onVideoError?.invoke("YouTube player error $code // $detail")
                }
            }
        }

        @JavascriptInterface
        fun autoplayBlocked() {
            post {
                onVideoError?.invoke(
                    "YouTube autoplay blocked // tap the player once to start playback"
                )
            }
        }
    }

    private fun buildHtml(videoId: String): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
          <meta name="referrer" content="origin" />
          <style>
            html, body {
              margin: 0;
              padding: 0;
              width: 100%;
              height: 100%;
              overflow: hidden;
              background: #000;
            }
            #crop {
              position: fixed;
              inset: 0;
              overflow: hidden;
              background: #000;
            }
            #player {
              position: absolute;
              top: 50%;
              left: 50%;
              width: 177.777778%;
              height: 100%;
              border: 0;
              transform: translate(-50%, -50%);
            }
          </style>
        </head>
        <body>
          <div id="crop">
            <iframe
              id="player"
              src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&controls=1&enablejsapi=1&origin=$APP_ORIGIN_ENCODED"
              title="YouTube demo"
              referrerpolicy="origin"
              allow="autoplay; encrypted-media; picture-in-picture"
              allowfullscreen>
            </iframe>
          </div>
          <script src="https://www.youtube.com/iframe_api"></script>
          <script>
            var player = null;
            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                events: {
                  'onReady': function(e) {
                    if (window.RealityScanner) RealityScanner.ready();
                  },
                  'onError': function(e) {
                    if (window.RealityScanner) RealityScanner.error(Number(e.data));
                  },
                  'onAutoplayBlocked': function() {
                    if (window.RealityScanner) RealityScanner.autoplayBlocked();
                  }
                }
              });
            }
          </script>
        </body>
        </html>
    """.trimIndent()

    private fun extractVideoId(rawInput: String): String? {
        val input = rawInput.trim()
        if (VIDEO_ID.matches(input)) return input

        val normalized = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else {
            "https://$input"
        }

        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty().removePrefix("www.").removePrefix("m.")
        val segments = uri.pathSegments

        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            host.endsWith("youtube.com") && uri.getQueryParameter("v") != null -> uri.getQueryParameter("v")
            host.endsWith("youtube.com") && segments.firstOrNull() in setOf("shorts", "embed", "live") -> segments.getOrNull(1)
            else -> null
        }?.substringBefore('?')?.substringBefore('&')

        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }

    companion object {
        private const val APP_ORIGIN = "https://reality-scanner.app/"
        private const val APP_ORIGIN_ENCODED = "https%3A%2F%2Freality-scanner.app"
        private const val COVER_SCALE = 1.7777778f
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
