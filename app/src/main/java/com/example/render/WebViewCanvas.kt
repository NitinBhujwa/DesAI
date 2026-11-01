package com.example.render

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.AppJson

private class CanvasJsBridge(
  private val mainHandler: Handler,
  private val onNodeSelectedCallback: (String) -> Unit,
  private val onRectsUpdatedCallback: (List<NodeRect>) -> Unit,
) {
  @JavascriptInterface
  fun onNodeSelected(nodeId: String) {
    if (nodeId.isBlank()) return
    mainHandler.post { onNodeSelectedCallback(nodeId) }
  }

  @JavascriptInterface
  fun onRects(rectsJson: String) {
    val parsed =
      runCatching { AppJson.compact.decodeFromString<List<NodeRect>>(rectsJson) }
        .getOrDefault(emptyList())
    mainHandler.post { onRectsUpdatedCallback(parsed) }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewCanvas(
  html: String,
  onNodeSelected: (String) -> Unit,
  onRectsUpdated: (List<NodeRect>) -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentOnNodeSelected by rememberUpdatedState(onNodeSelected)
  val currentOnRectsUpdated by rememberUpdatedState(onRectsUpdated)
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  var lastLoadedHtml by remember { mutableStateOf<String?>(null) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var recreationKey by remember { mutableIntStateOf(0) }

  DisposableEffect(recreationKey) {
    onDispose {
      webViewRef?.apply {
        removeJavascriptInterface("Android")
        stopLoading()
        destroy()
      }
      webViewRef = null
      lastLoadedHtml = null
    }
  }

  androidx.compose.runtime.key(recreationKey) {
    AndroidView(
      modifier = modifier,
      factory = { context ->
        WebView(context).apply {
          webViewRef = this
          // Use software layer rendering to avoid Chromium GPU/renderer process crashes in streaming emulators
          setLayerType(View.LAYER_TYPE_SOFTWARE, null)

          settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
          }

          webViewClient =
            object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
              ): Boolean {
                // Block all navigation away from the loaded canvas content
                return true
              }

              @Deprecated("Deprecated in Android WebViewClient")
              override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return true
              }

              override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
              ): Boolean {
                // Handle renderer process crash gracefully instead of crashing the host app
                mainHandler.post {
                  recreationKey += 1
                }
                return true
              }
            }

          addJavascriptInterface(
            CanvasJsBridge(
              mainHandler = mainHandler,
              onNodeSelectedCallback = { id -> currentOnNodeSelected(id) },
              onRectsUpdatedCallback = { rects -> currentOnRectsUpdated(rects) },
            ),
            "Android",
          )
        }
      },
      update = { webView ->
        if (lastLoadedHtml != html) {
          lastLoadedHtml = html
          webView.loadDataWithBaseURL(
            "https://canvas.local/",
            html,
            "text/html",
            "UTF-8",
            null,
          )
        }
      },
    )
  }
}
