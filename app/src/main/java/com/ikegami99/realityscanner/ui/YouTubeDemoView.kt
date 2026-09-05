package com.ikegami99.realityscanner.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

class YouTubeDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var onVideoLoaded: ((String) -> Unit)? = null
    var onVideoError: ((String) -> Unit)? = null

    init {
        configure()
        visibility = View.GONE
    }

    @SuppressLint("SetJavaScriptEnabled")
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

        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
    }

    fun play(input: String): Boolean {
        val videoId = extractVideoId(input)
        if (videoId == null) {
            onVideoError?.invoke("YouTube URL / video ID could not be parsed")
            return false
        }

        val html = buildHtml(videoId)
        visibility = View.VISIBLE
        loadDataWithBaseURL(
            "https://www.youtube.com/",
            html,
            "text/html",
            "UTF-8",
            null
        )
        onVideoLoaded?.invoke(videoId)
        return true
    }

    fun stopPlayback() {
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

    private fun buildHtml(videoId: String): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
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
              src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1&controls=1&enablejsapi=1"
              title="YouTube demo"
              allow="autoplay; encrypted-media; picture-in-picture"
              allowfullscreen>
            </iframe>
          </div>
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
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
