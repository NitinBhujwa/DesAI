package com.example.render

import com.example.model.Node
import com.example.model.Screen
import com.example.model.ThemeTokens
import com.example.registry.ComponentRegistry
import com.example.registry.HtmlContext
import com.example.registry.HtmlRenderMode
import com.example.registry.HtmlSecurity
import kotlinx.serialization.Serializable

@Serializable
data class NodeRect(
  val id: String,
  val type: String,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
)

object HtmlRenderer {
  const val VIEWPORT_WIDTH_PX = 390
  const val VIEWPORT_HEIGHT_PX = 844
  const val SHARED_FONT_STACK = """system-ui, -apple-system, "Segoe UI", Roboto, sans-serif"""

  /**
   * Pure deterministic function rendering [screen] into a complete, self-contained HTML document.
   */
  fun renderScreen(
    screen: Screen,
    theme: ThemeTokens = ThemeTokens(),
    registry: ComponentRegistry = ComponentRegistry.Default,
    mode: HtmlRenderMode = HtmlRenderMode.Edit,
    selectedNodeId: String? = null,
  ): String {
    val primary = HtmlSecurity.sanitizeColor(theme.primary, "#4F46E5")
    // Item 7: Derive translucent variants in Kotlin from the sanitized theme.primary hex
    val primaryTonal = HtmlSecurity.hexToRgbaCss(primary, 0.12f)
    val primaryFocusRing = HtmlSecurity.hexToRgbaCss(primary, 0.18f)
    val secondary = HtmlSecurity.sanitizeColor(theme.secondary, "#0EA5E9")
    val background = HtmlSecurity.sanitizeColor(theme.background, "#F8FAFC")
    val surface = HtmlSecurity.sanitizeColor(theme.surface, "#FFFFFF")
    val onPrimary = HtmlSecurity.sanitizeColor(theme.onPrimary, "#FFFFFF")
    val cornerRadius = theme.cornerRadius.coerceIn(0, 48)
    val fontScale = theme.fontScale.coerceIn(0.5f, 2.5f)

    val rootContext =
      HtmlContext(
        theme = theme,
        mode = mode,
        selectedNodeId = if (mode == HtmlRenderMode.Edit) selectedNodeId else null,
        parentType = "ScreenViewport",
      )

    val bodyTreeHtml = renderNodeRecursive(screen.root, rootContext, registry)

    // Item 9: In Export mode, html/body allow full window width and #screen-viewport (390px) is centered horizontally via margin: 0 auto.
    val htmlBodyWidthCss =
      if (mode == HtmlRenderMode.Export) {
        "width: 100%; min-width: ${VIEWPORT_WIDTH_PX}px;"
      } else {
        "width: ${VIEWPORT_WIDTH_PX}px;"
      }

    val viewportMarginCss =
      if (mode == HtmlRenderMode.Export) {
        "margin: 0 auto;"
      } else {
        "margin: 0;"
      }

    val viewportContainerCss =
      when {
        !screen.scrollable ->
          """
          width: ${VIEWPORT_WIDTH_PX}px;
          height: ${VIEWPORT_HEIGHT_PX}px;
          overflow: hidden;
          $viewportMarginCss
          """
        mode == HtmlRenderMode.Export ->
          """
          width: ${VIEWPORT_WIDTH_PX}px;
          min-height: ${VIEWPORT_HEIGHT_PX}px;
          height: auto;
          overflow: visible;
          $viewportMarginCss
          """
        else ->
          """
          width: ${VIEWPORT_WIDTH_PX}px;
          height: ${VIEWPORT_HEIGHT_PX}px;
          overflow-y: auto;
          overflow-x: hidden;
          $viewportMarginCss
          """
      }.trimIndent()

    val editStyles =
      if (mode == HtmlRenderMode.Edit) {
        """
        .edit-target {
          position: relative;
          cursor: pointer;
          transition: outline-color 0.12s ease;
        }
        .edit-target:hover {
          outline: 1px dashed rgba(79, 70, 229, 0.55);
          outline-offset: -1px;
        }
        .selected-node {
          outline: 2px solid #4F46E5 !important;
          outline-offset: -2px !important;
          background-image: linear-gradient(rgba(79, 70, 229, 0.04), rgba(79, 70, 229, 0.04));
        }
        .node-selection-badge {
          position: absolute;
          top: 0;
          right: 0;
          z-index: 20;
          background-color: #4F46E5;
          color: #FFFFFF;
          font-size: 10px;
          font-weight: 700;
          font-family: monospace;
          padding: 2px 6px;
          border-bottom-left-radius: 6px;
          pointer-events: none;
          line-height: 1.2;
          white-space: nowrap;
        }
        """
      } else {
        ""
      }

    val editScript =
      if (mode == HtmlRenderMode.Edit) {
        buildEditBridgeScript()
      } else {
        ""
      }

    val safeTitle = HtmlSecurity.escapeHtml(screen.name.ifBlank { "Screen" })

    return buildString {
      append("<!DOCTYPE html>\n")
      append("<html lang=\"en\">\n")
      append("<head>\n")
      append("  <meta charset=\"UTF-8\" />\n")
      append("  <meta name=\"viewport\" content=\"width=$VIEWPORT_WIDTH_PX, initial-scale=1.0\" />\n")
      append("  <title>$safeTitle</title>\n")
      append("  <style>\n")
      append("    :root {\n")
      append("      --color-primary: $primary;\n")
      append("      --color-primary-tonal: $primaryTonal;\n")
      append("      --color-primary-focus: $primaryFocusRing;\n")
      append("      --color-secondary: $secondary;\n")
      append("      --color-background: $background;\n")
      append("      --color-surface: $surface;\n")
      append("      --color-on-primary: $onPrimary;\n")
      append("      --radius-base: ${cornerRadius}px;\n")
      append("      --font-scale: $fontScale;\n")
      append("      --font-sans: $SHARED_FONT_STACK;\n")
      append("    }\n")
      append("    *, *::before, *::after {\n")
      append("      box-sizing: border-box;\n")
      append("    }\n")
      append("    html, body {\n")
      append("      margin: 0;\n")
      append("      padding: 0;\n")
      append("      $htmlBodyWidthCss\n")
      append("      background-color: var(--color-background);\n")
      append("      color: #0F172A;\n")
      append("      font-family: var(--font-sans);\n")
      append("      -webkit-font-smoothing: antialiased;\n")
      append("    }\n")
      append("    #screen-viewport {\n")
      append("      background-color: var(--color-background);\n")
      append("      display: flex;\n")
      append("      flex-direction: column;\n")
      append("      $viewportContainerCss\n")
      append("    }\n")
      // Item 4: The root node always fills #screen-viewport via flex in all three modes (Edit, Preview, Export)
      append("    #screen-viewport > .ui-node {\n")
      append("      flex: 1 0 auto;\n")
      append("      width: 100%;\n")
      append("    }\n")
      append("    .ui-node {\n")
      append("      box-sizing: border-box;\n")
      append("    }\n")
      append("    .ui-textfield-label {\n")
      append("      font-size: calc(12px * var(--font-scale));\n")
      append("      font-weight: 600;\n")
      append("      color: #334155;\n")
      append("    }\n")
      append("    .ui-textfield-input {\n")
      append("      width: 100%;\n")
      append("      min-height: 42px;\n")
      append("      padding: 10px 12px;\n")
      append("      border: 1px solid #CBD5E1;\n")
      append("      border-radius: var(--radius-base);\n")
      append("      background-color: var(--color-surface);\n")
      append("      color: #0F172A;\n")
      append("      font-family: var(--font-sans);\n")
      append("      font-size: calc(14px * var(--font-scale));\n")
      append("      outline: none;\n")
      append("    }\n")
      append("    .ui-textfield-input:focus {\n")
      append("      border-color: var(--color-primary);\n")
      append("      box-shadow: 0 0 0 2px var(--color-primary-focus);\n")
      append("    }\n")
      append("    .ui-image-placeholder {\n")
      append("      display: flex;\n")
      append("      flex-direction: column;\n")
      append("      align-items: center;\n")
      append("      justify-content: center;\n")
      append("      gap: 6px;\n")
      append("      padding: 16px;\n")
      append("      color: #475569;\n")
      append("      font-size: calc(12px * var(--font-scale));\n")
      append("      font-weight: 500;\n")
      append("      text-align: center;\n")
      append("    }\n")
      append("    .ui-unknown-component {\n")
      append("      display: flex;\n")
      append("      align-items: center;\n")
      append("      justify-content: center;\n")
      append("      padding: 12px 16px;\n")
      append("      background-color: #FEF2F2;\n")
      append("      border: 2px dashed #DC2626;\n")
      append("      border-radius: var(--radius-base);\n")
      append("      color: #B91C1C;\n")
      append("      font-weight: 700;\n")
      append("      font-size: 13px;\n")
      append("    }\n")
      if (editStyles.isNotEmpty()) {
        append(editStyles.trimIndent())
        append("\n")
      }
      append("  </style>\n")
      append("</head>\n")
      append("<body>\n")
      append("  <div id=\"screen-viewport\">\n")
      append("    $bodyTreeHtml\n")
      append("  </div>\n")
      if (editScript.isNotEmpty()) {
        append(editScript)
        append("\n")
      }
      append("</body>\n")
      append("</html>")
    }
  }

  private fun renderNodeRecursive(
    node: Node,
    ctx: HtmlContext,
    registry: ComponentRegistry,
  ): String {
    val def = registry.get(node.type)
    val childCtx = ctx.copy(parentType = node.type)
    val childrenHtml =
      if (node.children.isEmpty()) {
        ""
      } else {
        node.children.joinToString(separator = "") { child ->
          renderNodeRecursive(child, childCtx, registry)
        }
      }

    if (def == null) {
      val safeType = ctx.escapeHtml(node.type)
      val attrs = ctx.nodeAttributes(node, "ui-node ui-unknown-component")
      return """<div $attrs>${ctx.selectionBadgeHtml(node)}<span>Unknown component: $safeType</span></div>"""
    }

    return def.toHtml(node, ctx, childrenHtml)
  }

  private fun buildEditBridgeScript(): String {
    return """
  <script>
    (function() {
      var throttleTimer = null;
      var THROTTLE_MS = 50;

      function computeAndReportRects() {
        throttleTimer = null;
        if (typeof Android === 'undefined' || !Android.onRects) return;
        var elements = document.querySelectorAll('[data-node-id]');
        var rects = [];
        for (var i = 0; i < elements.length; i++) {
          var el = elements[i];
          var r = el.getBoundingClientRect();
          rects.push({
            id: el.getAttribute('data-node-id') || '',
            type: el.getAttribute('data-node-type') || '',
            x: Math.round(r.left * 10) / 10,
            y: Math.round(r.top * 10) / 10,
            width: Math.round(r.width * 10) / 10,
            height: Math.round(r.height * 10) / 10
          });
        }
        try {
          Android.onRects(JSON.stringify(rects));
        } catch (err) {}
      }

      function scheduleReportRects() {
        if (throttleTimer !== null) return;
        throttleTimer = setTimeout(function() {
          window.requestAnimationFrame(computeAndReportRects);
        }, THROTTLE_MS);
      }

      document.addEventListener('click', function(e) {
        var target = e.target ? e.target.closest('[data-node-id]') : null;
        if (target) {
          e.preventDefault();
          e.stopPropagation();
          var id = target.getAttribute('data-node-id');
          if (id && typeof Android !== 'undefined' && Android.onNodeSelected) {
            Android.onNodeSelected(id);
          }
        }
      }, true);

      var viewport = document.getElementById('screen-viewport');
      if (viewport) {
        viewport.addEventListener('scroll', scheduleReportRects, { passive: true });
      }
      window.addEventListener('scroll', scheduleReportRects, { passive: true });
      window.addEventListener('resize', scheduleReportRects, { passive: true });
      window.addEventListener('load', function() {
        computeAndReportRects();
        if (document.fonts && document.fonts.ready) {
          document.fonts.ready.then(scheduleReportRects);
        }
      });

      if (typeof ResizeObserver !== 'undefined' && viewport) {
        var ro = new ResizeObserver(scheduleReportRects);
        ro.observe(viewport);
      }

      setTimeout(computeAndReportRects, 0);
    })();
  </script>"""
  }
}
