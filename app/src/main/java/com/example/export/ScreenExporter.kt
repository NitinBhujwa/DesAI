package com.example.export

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import com.example.model.Node
import com.example.model.Screen
import com.example.model.ThemeTokens
import com.example.model.stringProp
import com.example.registry.ComponentRegistry
import com.example.registry.HtmlRenderMode
import com.example.registry.HtmlSecurity
import com.example.render.HtmlRenderer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ExportScale(val factor: Int, val label: String) {
  Scale1x(1, "1x"),
  Scale2x(2, "2x"),
  Scale3x(3, "3x"),
}

data class ExportHtmlResult(
  val html: String,
  val warnings: List<String>,
)

data class ExportFileResult(
  val file: File,
  val mimeType: String,
  val warnings: List<String>,
)

/**
 * Item 15: Named class with `@JavascriptInterface` methods so registration on `WebView` never silently fails.
 */
class ExportJsBridge(
  private val mainHandler: Handler,
  private val onReadyCallback: (Int) -> Unit,
  private val onFrameSettledCallback: () -> Unit,
) {
  @JavascriptInterface
  fun onReady(cssScrollHeight: Int) {
    mainHandler.post { onReadyCallback(cssScrollHeight) }
  }

  @JavascriptInterface
  fun onFrameSettled() {
    mainHandler.post { onFrameSettledCallback() }
  }
}

/**
 * Item 14: Touch-transparent hidden container that never intercepts touches (`dispatchTouchEvent` returns false)
 * and is marked `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`.
 */
class TouchTransparentExportContainer(context: Context) : FrameLayout(context) {
  init {
    alpha = 0f
    isClickable = false
    isFocusable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  }

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false

  override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

  override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

object ScreenExporter {
  // Item 12: Limit by total pixel count (~24 million pixels) at a fixed scale independent of device density
  const val MAX_EXPORT_TOTAL_PIXELS: Long = 24_000_000L
  const val STALE_EXPORT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
  private const val EXPORT_TIMEOUT_MS = 12_000L
  private const val STRIP_HEIGHT_CSS_PX = 1600

  /**
   * Pure function that renders [screen] in [HtmlRenderMode.Export] mode and inspects the tree
   * for any remote (`https://`) image dependencies, returning both the clean HTML and warnings.
   */
  fun exportHtml(
    screen: Screen,
    theme: ThemeTokens = ThemeTokens(),
    registry: ComponentRegistry = ComponentRegistry.Default,
  ): ExportHtmlResult {
    val html =
      HtmlRenderer.renderScreen(
        screen = screen,
        theme = theme,
        registry = registry,
        mode = HtmlRenderMode.Export,
      )
    val remoteCount = countRemoteImages(screen.root)
    val warnings =
      if (remoteCount > 0) {
        val label = if (remoteCount == 1) "1 remote image" else "$remoteCount remote images"
        listOf("$label (https://) referenced — network connection required when viewing offline.")
      } else {
        emptyList()
      }
    return ExportHtmlResult(html = html, warnings = warnings)
  }

  /**
   * Item 12: Validates whether a screen of [cssHeight] at [scale] fits within [MAX_EXPORT_TOTAL_PIXELS].
   * Error message explicitly states the CSS height and scale used.
   */
  fun validateExportDimensions(
    cssHeight: Int,
    scale: ExportScale = ExportScale.Scale2x,
    cssWidth: Int = HtmlRenderer.VIEWPORT_WIDTH_PX,
  ): Result<Pair<Int, Int>> {
    return runCatching {
      val safeCssHeight = cssHeight.coerceAtLeast(HtmlRenderer.VIEWPORT_HEIGHT_PX)
      val outWidthPx = cssWidth * scale.factor
      val outHeightPx = safeCssHeight * scale.factor
      val totalPixels = outWidthPx.toLong() * outHeightPx.toLong()
      if (totalPixels > MAX_EXPORT_TOTAL_PIXELS) {
        throw IllegalStateException(
          "Export PNG size exceeds the maximum pixel budget of $MAX_EXPORT_TOTAL_PIXELS pixels: " +
            "CSS height=${safeCssHeight}px at scale=${scale.label} (${outWidthPx}x${outHeightPx} = $totalPixels pixels)."
        )
      }
      outWidthPx to outHeightPx
    }
  }

  /**
   * Item 16: Builds a collision-resistant base filename combining the sanitized screen name and a short deterministic screen ID suffix.
   */
  fun buildExportBaseFileName(screenName: String, screenId: String): String {
    val sanitizedName =
      screenName
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .ifEmpty { "screen" }
    val sanitizedId =
      screenId
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
    val shortSuffix =
      if (sanitizedId.length >= 6) {
        sanitizedId.takeLast(6)
      } else if (sanitizedId.isNotEmpty()) {
        sanitizedId
      } else {
        Integer.toUnsignedString(screenId.hashCode(), 16).take(6)
      }
    return "${sanitizedName}_${shortSuffix}"
  }

  /**
   * Item 16: Deletes files older than 24 hours from `cache/exports` at export time.
   */
  fun cleanupStaleExports(
    exportDir: File,
    nowMillis: Long = System.currentTimeMillis(),
    maxAgeMs: Long = STALE_EXPORT_MAX_AGE_MS,
  ): Int {
    if (!exportDir.exists() || !exportDir.isDirectory) return 0
    var deletedCount = 0
    val cutoff = nowMillis - maxAgeMs
    val files = exportDir.listFiles() ?: return 0
    for (file in files) {
      if (file.isFile && file.lastModified() in 1 until cutoff) {
        if (file.delete()) {
          deletedCount += 1
        }
      }
    }
    return deletedCount
  }

  /**
   * Item 13: Inspects a rendered export [bitmap] to verify it is neither completely transparent
   * nor a single flat color unlike the page background (e.g., an unrendered blank surface).
   */
  fun verifyRenderedBitmapNotBlank(
    bitmap: Bitmap,
    expectedBackgroundHex: String,
  ): Result<Unit> {
    return runCatching {
      val w = bitmap.width
      val h = bitmap.height
      if (w <= 0 || h <= 0) {
        throw IllegalStateException("Rendered bitmap has invalid dimensions ${w}x${h}.")
      }

      val sampleCols = 6
      val sampleRows = 8
      var nonTransparentCount = 0
      val firstPixel = bitmap.getPixel(w / 2, h / 2)
      var allIdentical = true

      for (row in 0 until sampleRows) {
        val y = ((row + 0.5f) * h / sampleRows).toInt().coerceIn(0, h - 1)
        for (col in 0 until sampleCols) {
          val x = ((col + 0.5f) * w / sampleCols).toInt().coerceIn(0, w - 1)
          val px = bitmap.getPixel(x, y)
          if (Color.alpha(px) > 0) {
            nonTransparentCount += 1
          }
          if (px != firstPixel) {
            allIdentical = false
          }
        }
      }

      if (nonTransparentCount == 0) {
        throw IllegalStateException("Exported PNG bitmap is entirely transparent (blank render detected).")
      }

      if (allIdentical) {
        val expectedColor = parseHexToColorInt(expectedBackgroundHex)
        val colorDistance =
          abs(Color.red(firstPixel) - Color.red(expectedColor)) +
            abs(Color.green(firstPixel) - Color.green(expectedColor)) +
            abs(Color.blue(firstPixel) - Color.blue(expectedColor))
        if (colorDistance > 15) {
          throw IllegalStateException(
            "Exported PNG bitmap is a single flat color (#${Integer.toHexString(firstPixel)}) unlike the page background ($expectedBackgroundHex)."
          )
        }
        throw IllegalStateException(
          "Exported PNG bitmap contains only a flat uniform background with no rendered component pixels."
        )
      }
    }
  }

  private fun parseHexToColorInt(hex: String): Int {
    val clean = HtmlSecurity.sanitizeColor(hex, "#F8FAFC").removePrefix("#")
    val rgb = if (clean.length == 8) clean.substring(2) else clean
    val r = rgb.substring(0, 2).toIntOrNull(16) ?: 248
    val g = rgb.substring(2, 4).toIntOrNull(16) ?: 250
    val b = rgb.substring(4, 6).toIntOrNull(16) ?: 252
    return Color.rgb(r, g, b)
  }

  /**
   * Writes the clean Export-mode HTML to a file in the app's cache `exports/` directory.
   */
  suspend fun exportHtmlFile(
    context: Context,
    screen: Screen,
    theme: ThemeTokens = ThemeTokens(),
    registry: ComponentRegistry = ComponentRegistry.Default,
  ): Result<ExportFileResult> =
    withContext(Dispatchers.IO) {
      runCatching {
        val result = exportHtml(screen, theme, registry)
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        cleanupStaleExports(exportDir)
        val baseName = buildExportBaseFileName(screen.name, screen.id)
        val htmlFile = File(exportDir, "${baseName}.html")
        htmlFile.writeText(result.html, Charsets.UTF_8)
        ExportFileResult(
          file = htmlFile,
          mimeType = "text/html",
          warnings = result.warnings,
        )
      }
    }

  /**
   * Loads the Export-mode HTML into a dedicated hidden/temporary WebView attached to [activity]'s
   * window, waits until page load AND all images and fonts have settled via JS `Promise.all` +
   * `document.fonts.ready`, validates total pixel count at [scale] (independent of device density),
   * renders vertical strips and stitches them onto the output [Bitmap] after `postVisualStateCallback`
   * + `requestAnimationFrame` synchronization, verifies the bitmap is not blank, and saves the `.png`.
   */
  suspend fun exportPng(
    activity: Activity,
    screen: Screen,
    theme: ThemeTokens = ThemeTokens(),
    registry: ComponentRegistry = ComponentRegistry.Default,
    scale: ExportScale = ExportScale.Scale2x,
  ): Result<ExportFileResult> {
    val htmlResult = exportHtml(screen, theme, registry)
    val bitmapResult = renderExportHtmlToBitmap(activity, htmlResult.html, theme.background, scale)
    return bitmapResult.mapCatching { bitmap ->
      withContext(Dispatchers.IO) {
        try {
          val exportDir = File(activity.cacheDir, "exports").apply { mkdirs() }
          cleanupStaleExports(exportDir)
          val baseName = buildExportBaseFileName(screen.name, screen.id)
          val pngFile = File(exportDir, "${baseName}_${scale.label}.png")
          FileOutputStream(pngFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
          }
          ExportFileResult(
            file = pngFile,
            mimeType = "image/png",
            warnings = htmlResult.warnings,
          )
        } finally {
          bitmap.recycle()
        }
      }
    }
  }

  /**
   * Exports both `.html` and full-scroll `.png` packaged inside a `.zip` archive.
   */
  suspend fun exportBundle(
    activity: Activity,
    screen: Screen,
    theme: ThemeTokens = ThemeTokens(),
    registry: ComponentRegistry = ComponentRegistry.Default,
    scale: ExportScale = ExportScale.Scale2x,
  ): Result<ExportFileResult> {
    val htmlResult = exportHtml(screen, theme, registry)
    val pngResult = exportPng(activity, screen, theme, registry, scale)
    return pngResult.mapCatching { pngExport ->
      withContext(Dispatchers.IO) {
        val exportDir = File(activity.cacheDir, "exports").apply { mkdirs() }
        cleanupStaleExports(exportDir)
        val baseName = buildExportBaseFileName(screen.name, screen.id)
        val zipFile = File(exportDir, "${baseName}_${scale.label}_bundle.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
          zos.putNextEntry(ZipEntry("${baseName}.html"))
          zos.write(htmlResult.html.toByteArray(Charsets.UTF_8))
          zos.closeEntry()

          zos.putNextEntry(ZipEntry("${baseName}_${scale.label}.png"))
          pngExport.file.inputStream().use { input -> input.copyTo(zos) }
          zos.closeEntry()
        }
        ExportFileResult(
          file = zipFile,
          mimeType = "application/zip",
          warnings = htmlResult.warnings,
        )
      }
    }
  }

  fun shareExportedFile(context: Context, exportFile: ExportFileResult) {
    val authority = "${context.packageName}.fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, exportFile.file)
    val sendIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = exportFile.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    val chooser =
      Intent.createChooser(sendIntent, "Export ${exportFile.file.name}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(chooser)
  }

  @SuppressLint("SetJavaScriptEnabled")
  private suspend fun renderExportHtmlToBitmap(
    activity: Activity,
    exportHtml: String,
    expectedBackgroundHex: String,
    scale: ExportScale,
  ): Result<Bitmap> =
    withContext(Dispatchers.Main) {
      runCatching {
        val rootViewGroup =
          activity.window?.decorView as? ViewGroup
            ?: throw IllegalStateException("Activity window decorView is not available for export.")

        val density = activity.resources.displayMetrics.density.coerceAtLeast(1f)
        val webViewWidthPx = (HtmlRenderer.VIEWPORT_WIDTH_PX * density).roundToInt()
        val initialHeightPx = (HtmlRenderer.VIEWPORT_HEIGHT_PX * density).roundToInt()

        // Item 14: Use TouchTransparentExportContainer so touches never get swallowed during export
        val hiddenContainer =
          TouchTransparentExportContainer(activity).apply {
            layoutParams = ViewGroup.LayoutParams(webViewWidthPx, initialHeightPx)
          }

        val exportWebView =
          WebView(activity).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            settings.apply {
              javaScriptEnabled = true
              allowFileAccess = false
              allowContentAccess = false
              domStorageEnabled = false
              databaseEnabled = false
              setSupportZoom(false)
            }
            layoutParams = FrameLayout.LayoutParams(webViewWidthPx, initialHeightPx)
          }

        hiddenContainer.addView(exportWebView)
        rootViewGroup.addView(hiddenContainer)

        try {
          val measuredCssHeight =
            withTimeoutOrNull(EXPORT_TIMEOUT_MS) {
              awaitExportWebViewReady(exportWebView, exportHtml)
            } ?: throw IllegalStateException("Timed out waiting for export WebView assets and fonts to load.")

          val targetCssHeight = measuredCssHeight.coerceAtLeast(HtmlRenderer.VIEWPORT_HEIGHT_PX)

          // Item 12: Validate total pixels at the requested fixed export scale (1x/2x/3x), independent of device density
          val (outWidthPx, outHeightPx) =
            validateExportDimensions(targetCssHeight, scale, HtmlRenderer.VIEWPORT_WIDTH_PX).getOrThrow()

          // Render in vertical strips (up to STRIP_HEIGHT_CSS_PX each) and stitch into the final fixed-scale Bitmap
          val finalBitmap = Bitmap.createBitmap(outWidthPx, outHeightPx, Bitmap.Config.ARGB_8888)
          try {
            val finalCanvas = Canvas(finalBitmap)
            val scaleFactor = scale.factor.toFloat()

            var currentCssOffsetY = 0
            var requestId = 1L
            while (currentCssOffsetY < targetCssHeight) {
              val stripCssH = minOf(STRIP_HEIGHT_CSS_PX, targetCssHeight - currentCssOffsetY)
              val stripViewHeightPx = (stripCssH * density).roundToInt().coerceAtLeast(1)

              exportWebView.layoutParams = FrameLayout.LayoutParams(webViewWidthPx, stripViewHeightPx)
              hiddenContainer.layoutParams = ViewGroup.LayoutParams(webViewWidthPx, stripViewHeightPx)

              val widthSpec = View.MeasureSpec.makeMeasureSpec(webViewWidthPx, View.MeasureSpec.EXACTLY)
              val heightSpec = View.MeasureSpec.makeMeasureSpec(stripViewHeightPx, View.MeasureSpec.EXACTLY)
              exportWebView.measure(widthSpec, heightSpec)
              exportWebView.layout(0, 0, webViewWidthPx, stripViewHeightPx)

              // Item 13: Synchronize via JS scroll offset + requestAnimationFrame AND WebView.postVisualStateCallback
              withTimeoutOrNull(EXPORT_TIMEOUT_MS) {
                awaitStripVisualStateReady(exportWebView, currentCssOffsetY, requestId++)
              } ?: throw IllegalStateException(
                "Timed out waiting for visual state callback at CSS height=${targetCssHeight}px, scale=${scale.label}."
              )

              val saveCount = finalCanvas.save()
              finalCanvas.translate(0f, currentCssOffsetY * scaleFactor)
              finalCanvas.scale(scaleFactor / density, scaleFactor / density)
              exportWebView.draw(finalCanvas)
              finalCanvas.restoreToCount(saveCount)

              currentCssOffsetY += stripCssH
            }

            // Item 13: Sample pixels to verify the rendered bitmap is neither transparent nor a flat blank surface
            verifyRenderedBitmapNotBlank(finalBitmap, expectedBackgroundHex).getOrThrow()
            finalBitmap
          } catch (e: Throwable) {
            finalBitmap.recycle()
            throw e
          }
        } finally {
          exportWebView.removeJavascriptInterface("ExportBridge")
          exportWebView.stopLoading()
          hiddenContainer.removeAllViews()
          rootViewGroup.removeView(hiddenContainer)
          exportWebView.destroy()
        }
      }
    }

  private suspend fun awaitExportWebViewReady(
    webView: WebView,
    exportHtml: String,
  ): Int = suspendCancellableCoroutine { cont ->
    val mainHandler = Handler(Looper.getMainLooper())
    var completed = false

    val bridge =
      ExportJsBridge(
        mainHandler = mainHandler,
        onReadyCallback = { cssScrollHeight ->
          if (!completed && cont.isActive) {
            completed = true
            cont.resume(cssScrollHeight)
          }
        },
        onFrameSettledCallback = {},
      )

    webView.addJavascriptInterface(bridge, "ExportBridge")

    webView.webViewClient =
      object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView?,
          request: WebResourceRequest?,
        ): Boolean = true

        @Deprecated("Deprecated in Android WebViewClient")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

        override fun onRenderProcessGone(
          view: WebView?,
          detail: RenderProcessGoneDetail?,
        ): Boolean {
          mainHandler.post {
            if (!completed && cont.isActive) {
              completed = true
              cont.resumeWithException(
                IllegalStateException("WebView renderer process exited during export.")
              )
            }
          }
          return true
        }

        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          val readinessJs =
            """
            (function() {
              var imgs = Array.prototype.slice.call(document.images || []);
              var imgPromises = imgs.map(function(img) {
                if (img.complete) return Promise.resolve();
                return new Promise(function(resolve) {
                  img.addEventListener('load', resolve, { once: true });
                  img.addEventListener('error', resolve, { once: true });
                });
              });
              var fontsPromise = (document.fonts && document.fonts.ready)
                ? document.fonts.ready
                : Promise.resolve();
              Promise.all(imgPromises.concat([fontsPromise])).then(function() {
                requestAnimationFrame(function() {
                  var viewport = document.getElementById('screen-viewport');
                  var h = Math.max(
                    document.documentElement ? document.documentElement.scrollHeight : 0,
                    document.body ? document.body.scrollHeight : 0,
                    viewport ? viewport.scrollHeight : 0,
                    844
                  );
                  if (typeof ExportBridge !== 'undefined' && ExportBridge.onReady) {
                    ExportBridge.onReady(Math.ceil(h));
                  }
                });
              });
            })();
            """.trimIndent()
          view?.evaluateJavascript(readinessJs, null)
        }
      }

    webView.loadDataWithBaseURL(
      "https://export.local/",
      exportHtml,
      "text/html",
      "UTF-8",
      null,
    )
  }

  /**
   * Item 13: Waits for one `requestAnimationFrame` handshake after applying strip scroll offset
   * PLUS `WebView.postVisualStateCallback` before drawing the strip onto the Bitmap.
   */
  private suspend fun awaitStripVisualStateReady(
    webView: WebView,
    cssOffsetY: Int,
    requestId: Long,
  ): Unit = suspendCancellableCoroutine { cont ->
    val mainHandler = Handler(Looper.getMainLooper())
    var completed = false

    fun completeOnce() {
      if (!completed && cont.isActive) {
        completed = true
        cont.resume(Unit)
      }
    }

    val rafJs =
      """
      (function() {
        var vp = document.getElementById('screen-viewport');
        if (vp) {
          vp.style.transform = 'translateY(-${cssOffsetY}px)';
        }
        requestAnimationFrame(function() {
          requestAnimationFrame(function() {
            return 'settled';
          });
        });
      })();
      """.trimIndent()

    webView.evaluateJavascript(rafJs) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        webView.postVisualStateCallback(
          requestId,
          object : WebView.VisualStateCallback() {
            override fun onComplete(completedRequestId: Long) {
              mainHandler.post { completeOnce() }
            }
          },
        )
      } else {
        mainHandler.post { completeOnce() }
      }
    }
  }

  fun countRemoteImages(node: Node): Int {
    var count = 0
    if (node.type == "Image") {
      val src = node.props.stringProp("src", "").trim()
      if (src.startsWith("https://", ignoreCase = true) || src.startsWith("http://", ignoreCase = true)) {
        count += 1
      }
    }
    for (child in node.children) {
      count += countRemoteImages(child)
    }
    return count
  }
}
