package com.example.registry

import com.example.model.Node
import com.example.model.ThemeTokens
import com.example.model.boolProp
import com.example.model.floatProp
import com.example.model.intProp
import com.example.model.stringProp
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class ComponentCategory {
  Layout,
  Buttons,
  Inputs,
  Labels,
  Navigation,
  Data,
  Feedback,
}

@Serializable
enum class PropKind {
  String,
  Int,
  Float,
  Bool,
  Color,
  Enum,
  Dimension,
}

@Serializable
data class PropSpec(
  val name: kotlin.String,
  val kind: PropKind,
  val default: JsonElement? = null,
  val allowedValues: List<kotlin.String>? = null,
  val minFloat: Float? = null,
  val maxFloat: Float? = null,
  val description: kotlin.String,
)

enum class HtmlRenderMode {
  Edit,
  Preview,
  Export,
}

data class HtmlContext(
  val theme: ThemeTokens,
  val mode: HtmlRenderMode,
  val selectedNodeId: kotlin.String? = null,
  val parentType: kotlin.String? = null,
) {
  fun escapeHtml(raw: kotlin.String): kotlin.String = HtmlSecurity.escapeHtml(raw)

  fun sanitizeColor(raw: kotlin.String?, fallback: kotlin.String): kotlin.String =
    HtmlSecurity.sanitizeColor(raw, fallback)

  fun sanitizeImageUrl(raw: kotlin.String?): kotlin.String? =
    HtmlSecurity.sanitizeImageUrl(raw)

  /**
   * Builds the HTML attributes (`data-node-id`, `data-node-type`, `class`, and `style`)
   * for any component element.
   *
   * Ordering rule (Item 1): [defaultDeclarations] are emitted FIRST, and user-prop declarations
   * (from common layout props and [userPropDeclarations]) are emitted AFTER so user props always override defaults.
   */
  fun nodeAttributes(
    node: Node,
    baseClasses: kotlin.String = "ui-node",
    defaultDeclarations: List<kotlin.String> = emptyList(),
    userPropDeclarations: List<kotlin.String> = emptyList(),
  ): kotlin.String {
    val isSelected = mode == HtmlRenderMode.Edit && selectedNodeId == node.id
    val classes = buildString {
      append(baseClasses)
      if (mode == HtmlRenderMode.Edit) {
        append(" edit-target")
        if (isSelected) append(" selected-node")
      }
    }

    val declarations = mutableListOf<kotlin.String>()
    declarations.addAll(defaultDeclarations)
    declarations.addAll(buildCommonLayoutCss(node))
    declarations.addAll(userPropDeclarations)
    val styleAttr = declarations.filter { it.isNotBlank() }.joinToString("; ")

    val dataAttrs =
      if (mode == HtmlRenderMode.Edit) {
        val safeId = escapeHtml(node.id)
        val safeType = escapeHtml(node.type)
        """ data-node-id="$safeId" data-node-type="$safeType""""
      } else {
        ""
      }

    val styleSegment = if (styleAttr.isNotEmpty()) """ style="${escapeHtml(styleAttr)}"""" else ""
    return """class="${escapeHtml(classes)}"$dataAttrs$styleSegment"""
  }

  /**
   * Renders the floating selection badge in Edit mode when this node is selected.
   */
  fun selectionBadgeHtml(node: Node): kotlin.String {
    if (mode != HtmlRenderMode.Edit || selectedNodeId != node.id) return ""
    val label = "${escapeHtml(node.type)}#${escapeHtml(node.id)}"
    return """<span class="node-selection-badge" aria-hidden="true">$label</span>"""
  }

  private fun buildCommonLayoutCss(node: Node): List<kotlin.String> {
    val css = mutableListOf<kotlin.String>()
    val props = node.props

    // Item 3: width "wrap" maps to fit-content (so inside a Column with align-items: stretch it wraps its content)
    val widthRaw = props.stringProp("width", "")
    if (widthRaw.isNotEmpty()) {
      when (val w = parseWidthToCss(widthRaw)) {
        "fill" -> {
          if (parentType == "Row") {
            css.add("flex: 1 1 0%")
          } else {
            css.add("width: 100%")
            css.add("align-self: stretch")
          }
        }
        "fit-content" -> {
          css.add("width: fit-content")
          if (parentType == "Column" || parentType == "Card" || parentType == "Box") {
            css.add("align-self: flex-start")
          }
        }
        else -> if (w != null) css.add("width: $w")
      }
    }

    // Item 4: height "fill" uses flex-based fill instead of percentage height so Edit and Export behave identically
    val heightRaw = props.stringProp("height", "")
    if (heightRaw.isNotEmpty()) {
      when (val h = parseHeightToCss(heightRaw)) {
        "fill" -> {
          when (parentType) {
            "Row" -> css.add("align-self: stretch")
            else -> css.add("flex: 1 1 0%")
          }
        }
        "auto" -> css.add("height: auto")
        else -> if (h != null) css.add("height: $h")
      }
    }

    if (props.containsKey("padding")) {
      val pad = props.intProp("padding", 0).coerceIn(0, 4000)
      css.add("padding: ${pad}px")
    }

    if (props.containsKey("spacing")) {
      val gap = props.intProp("spacing", 0).coerceIn(0, 4000)
      css.add("gap: ${gap}px")
    }

    if (props.containsKey("weight")) {
      val weight = props.floatProp("weight", 0f)
      if (weight > 0f && weight.isFinite()) {
        css.add("flex: $weight 1 0%")
      }
    }

    // Item 5: mainAxis and crossAxis on containers (Column, Row, Box, Card)
    val mainAxis = props.stringProp("mainAxis", "")
    if (mainAxis.isNotEmpty()) {
      val justifyCss =
        when (mainAxis) {
          "start" -> "flex-start"
          "center" -> "center"
          "end" -> "flex-end"
          "space_between" -> "space-between"
          "space_around" -> "space-around"
          else -> null
        }
      if (justifyCss != null) {
        css.add("justify-content: $justifyCss")
      }
    }

    val crossAxis = props.stringProp("crossAxis", "")
    if (crossAxis.isNotEmpty()) {
      val alignCss =
        when (crossAxis) {
          "start" -> "flex-start"
          "center" -> "center"
          "end" -> "flex-end"
          "stretch" -> "stretch"
          else -> null
        }
      if (alignCss != null) {
        css.add("align-items: $alignCss")
      }
    }

    return css
  }

  companion object {
    const val MAX_DIMENSION_PX = 4000f
    // Item 10: Plain decimal numbers only (no NaN, Infinity, or exponent notation like 1e10), optional "dp" suffix
    private val PLAIN_DECIMAL_DIMENSION_REGEX =
      Regex("^\\d+(?:\\.\\d+)?(?:dp)?$", RegexOption.IGNORE_CASE)

    fun parseDimensionNumber(raw: kotlin.String): Float? {
      val trimmed = raw.trim()
      if (!PLAIN_DECIMAL_DIMENSION_REGEX.matches(trimmed)) return null
      val numericPart =
        if (trimmed.endsWith("dp", ignoreCase = true)) {
          trimmed.dropLast(2)
        } else {
          trimmed
        }
      val value = numericPart.toFloatOrNull() ?: return null
      if (!value.isFinite() || value < 0f || value > MAX_DIMENSION_PX) return null
      return value
    }

    fun formatPx(value: Float): kotlin.String {
      val formatted = if (value % 1f == 0f) value.toInt().toString() else value.toString()
      return "${formatted}px"
    }

    fun parseWidthToCss(raw: kotlin.String): kotlin.String? {
      val trimmed = raw.trim()
      if (trimmed.equals("fill", ignoreCase = true)) return "fill"
      if (trimmed.equals("wrap", ignoreCase = true)) return "fit-content"
      val num = parseDimensionNumber(trimmed) ?: return null
      return formatPx(num)
    }

    fun parseHeightToCss(raw: kotlin.String): kotlin.String? {
      val trimmed = raw.trim()
      if (trimmed.equals("fill", ignoreCase = true)) return "fill"
      if (trimmed.equals("wrap", ignoreCase = true)) return "auto"
      val num = parseDimensionNumber(trimmed) ?: return null
      return formatPx(num)
    }
  }
}

object HtmlSecurity {
  private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

  fun escapeHtml(input: kotlin.String): kotlin.String {
    if (input.isEmpty()) return ""
    val sb = StringBuilder(input.length + 16)
    for (ch in input) {
      when (ch) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        '\'' -> sb.append("&#39;")
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  fun isValidHexColor(input: kotlin.String): Boolean = HEX_COLOR_REGEX.matches(input.trim())

  fun sanitizeColor(input: kotlin.String?, fallback: kotlin.String): kotlin.String {
    if (input.isNullOrBlank()) return fallback
    val trimmed = input.trim()
    return if (isValidHexColor(trimmed)) trimmed else fallback
  }

  /**
   * Item 7: Derives an `rgba(r, g, b, alpha)` CSS string directly in Kotlin from a sanitized `#RRGGBB` or `#AARRGGBB` hex color.
   */
  fun hexToRgbaCss(hexInput: kotlin.String, alpha: Float, fallbackHex: kotlin.String = "#4F46E5"): kotlin.String {
    val safeHex = sanitizeColor(hexInput, fallbackHex).removePrefix("#")
    val rgbHex = if (safeHex.length == 8) safeHex.substring(2) else safeHex
    val r = rgbHex.substring(0, 2).toIntOrNull(16) ?: 79
    val g = rgbHex.substring(2, 4).toIntOrNull(16) ?: 70
    val b = rgbHex.substring(4, 6).toIntOrNull(16) ?: 229
    val clampedAlpha = alpha.coerceIn(0f, 1f)
    val formattedAlpha = String.format(Locale.US, "%.2f", clampedAlpha)
    return "rgba($r, $g, $b, $formattedAlpha)"
  }

  /**
   * Item 10: Validates plain decimal dimension with optional "dp" suffix in range [0, 4000], or "fill" / "wrap".
   */
  fun isValidDimension(input: kotlin.String): Boolean {
    val trimmed = input.trim()
    if (trimmed.equals("fill", ignoreCase = true) || trimmed.equals("wrap", ignoreCase = true)) {
      return true
    }
    return HtmlContext.parseDimensionNumber(trimmed) != null
  }

  /**
   * Image `src` allows ONLY `https:` or `data:image/` URLs, or empty string for placeholder.
   */
  fun isValidImageSrc(input: kotlin.String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith("https://", ignoreCase = true) && trimmed.length > 8) {
      return !trimmed.any { it.isWhitespace() || it == '<' || it == '>' || it == '"' || it == '\'' }
    }
    if (trimmed.startsWith("data:image/", ignoreCase = true) && trimmed.contains(",")) {
      return !trimmed.any { it.isWhitespace() || it == '<' || it == '>' || it == '"' || it == '\'' }
    }
    return false
  }

  fun sanitizeImageUrl(input: kotlin.String?): kotlin.String? {
    if (input.isNullOrBlank()) return null
    val trimmed = input.trim()
    return if (isValidImageSrc(trimmed)) trimmed else null
  }
}

class ComponentDefinition(
  val type: kotlin.String,
  val category: ComponentCategory,
  val displayName: kotlin.String,
  val canHaveChildren: Boolean,
  val props: List<PropSpec>,
  val toHtml: (node: Node, ctx: HtmlContext, childrenHtml: kotlin.String) -> kotlin.String,
) {
  val propMap: Map<kotlin.String, PropSpec> = props.associateBy { it.name }
}

object CommonProps {
  val MAIN_AXIS_VALUES = listOf("start", "center", "end", "space_between", "space_around")
  val CROSS_AXIS_VALUES = listOf("start", "center", "end", "stretch")

  val BASE_ALL: List<PropSpec> =
    listOf(
      PropSpec(
        name = "width",
        kind = PropKind.Dimension,
        default = JsonPrimitive("wrap"),
        description = "Component width: 'fill', 'wrap' (maps to fit-content), or plain decimal number/dp up to 4000 (e.g. '120' or '120dp'). An unset width stretches in a default Column.",
      ),
      PropSpec(
        name = "height",
        kind = PropKind.Dimension,
        default = JsonPrimitive("wrap"),
        description = "Component height: 'fill' (flex-based fill), 'wrap' (auto), or plain decimal number/dp up to 4000 (e.g. '48' or '48dp').",
      ),
      PropSpec(
        name = "padding",
        kind = PropKind.Int,
        default = JsonPrimitive(0),
        minFloat = 0f,
        maxFloat = 4000f,
        description = "Inner padding in pixels/dp (integer 0..4000).",
      ),
      PropSpec(
        name = "spacing",
        kind = PropKind.Int,
        default = JsonPrimitive(0),
        minFloat = 0f,
        maxFloat = 4000f,
        description = "Gap between child components in pixels/dp (integer 0..4000).",
      ),
      PropSpec(
        name = "weight",
        kind = PropKind.Float,
        default = JsonPrimitive(0f),
        minFloat = 0f,
        maxFloat = 100f,
        description = "Optional flex grow weight inside a Column or Row (0..100).",
      ),
    )

  fun containerAxisProps(defaultCrossAxis: kotlin.String): List<PropSpec> =
    listOf(
      PropSpec(
        name = "mainAxis",
        kind = PropKind.Enum,
        default = JsonPrimitive("start"),
        allowedValues = MAIN_AXIS_VALUES,
        description = "Main-axis distribution (justify-content): start, center, end, space_between, space_around.",
      ),
      PropSpec(
        name = "crossAxis",
        kind = PropKind.Enum,
        default = JsonPrimitive(defaultCrossAxis),
        allowedValues = CROSS_AXIS_VALUES,
        description = "Cross-axis alignment (align-items): start, center, end, stretch.",
      ),
    )

  fun forContainer(defaultCrossAxis: kotlin.String, vararg specific: PropSpec): List<PropSpec> {
    val combined = BASE_ALL + containerAxisProps(defaultCrossAxis)
    val specificNames = specific.map { it.name }.toSet()
    return combined.filterNot { it.name in specificNames } + specific.toList()
  }

  fun forLeaf(vararg specific: PropSpec): List<PropSpec> {
    val specificNames = specific.map { it.name }.toSet()
    return BASE_ALL.filterNot { it.name in specificNames } + specific.toList()
  }
}

object BuiltInIcons {
  val ALLOWED_ICONS =
    listOf(
      "lock",
      "mail",
      "user",
      "check",
      "star",
      "home",
      "settings",
      "search",
      "arrow_right",
      "info",
      "heart",
      "bell",
    )

  fun renderSvg(name: kotlin.String, sizePx: Int, colorHex: kotlin.String): kotlin.String {
    val safeSize = sizePx.coerceIn(12, 128)
    val safeColor = HtmlSecurity.sanitizeColor(colorHex, "#4F46E5")
    val pathContent =
      when (name.lowercase()) {
        "lock" ->
          """<rect x="5" y="11" width="14" height="10" rx="2" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M8 11V7a4 4 0 0 1 8 0v4" stroke="$safeColor" stroke-width="2" fill="none"/>"""
        "mail" ->
          """<rect x="3" y="5" width="18" height="14" rx="2" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M3 7l9 6 9-6" stroke="$safeColor" stroke-width="2" fill="none"/>"""
        "user" ->
          """<circle cx="12" cy="8" r="4" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M4 20c0-4 4-6 8-6s8 2 8 6" stroke="$safeColor" stroke-width="2" fill="none"/>"""
        "check" ->
          """<path d="M5 13l4 4L19 7" stroke="$safeColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>"""
        "star" ->
          """<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" stroke="$safeColor" stroke-width="2" fill="none"/>"""
        "home" ->
          """<path d="M3 10l9-7 9 7v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V10z" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M9 22V12h6v10" stroke="$safeColor" stroke-width="2" fill="none"/>"""
        "settings" ->
          """<circle cx="12" cy="12" r="3" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M12 2v3m0 14v3M4.93 4.93l2.12 2.12m9.9 9.9l2.12 2.12M2 12h3m14 0h3M4.93 19.07l2.12-2.12m9.9-9.9l2.12-2.12" stroke="$safeColor" stroke-width="2" stroke-linecap="round"/>"""
        "search" ->
          """<circle cx="11" cy="11" r="7" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M20 20l-3.5-3.5" stroke="$safeColor" stroke-width="2" stroke-linecap="round"/>"""
        "arrow_right" ->
          """<path d="M5 12h14M13 6l6 6-6 6" stroke="$safeColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>"""
        "heart" ->
          """<path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" stroke="$safeColor" stroke-width="2" fill="none"/>"""
        "bell" ->
          """<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0" stroke="$safeColor" stroke-width="2" stroke-linecap="round" fill="none"/>"""
        else ->
          """<circle cx="12" cy="12" r="9" stroke="$safeColor" stroke-width="2" fill="none"/><path d="M12 16v-4M12 8h.01" stroke="$safeColor" stroke-width="2" stroke-linecap="round"/>"""
      }
    return """<svg width="$safeSize" height="$safeSize" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" style="display:block;flex-shrink:0;">$pathContent</svg>"""
  }
}

class ComponentRegistry(initialDefinitions: List<ComponentDefinition> = defaultDefinitions()) {
  private val definitions = LinkedHashMap<kotlin.String, ComponentDefinition>()

  init {
    initialDefinitions.forEach { register(it) }
  }

  fun register(definition: ComponentDefinition) {
    definitions[definition.type] = definition
  }

  fun get(type: kotlin.String): ComponentDefinition? = definitions[type]

  fun all(): List<ComponentDefinition> = definitions.values.toList()

  companion object {
    val Default: ComponentRegistry by lazy { ComponentRegistry() }

    fun defaultDefinitions(): List<ComponentDefinition> =
      listOf(
        // 1. Column (Layout) — default mainAxis=start, crossAxis=stretch
        ComponentDefinition(
          type = "Column",
          category = ComponentCategory.Layout,
          displayName = "Column",
          canHaveChildren = true,
          props =
            CommonProps.forContainer(
              defaultCrossAxis = "stretch",
              PropSpec(
                name = "backgroundColor",
                kind = PropKind.Color,
                default = JsonPrimitive(""),
                description = "Optional hex background color (#RRGGBB).",
              ),
            ),
          toHtml = { node, ctx, childrenHtml ->
            val defaults =
              listOf(
                "display: flex",
                "flex-direction: column",
                "justify-content: flex-start",
                "align-items: stretch",
              )
            val userProps = mutableListOf<kotlin.String>()
            val bg = node.props.stringProp("backgroundColor", "")
            if (bg.isNotEmpty() && HtmlSecurity.isValidHexColor(bg)) {
              userProps.add("background-color: $bg")
            }
            val attrs = ctx.nodeAttributes(node, "ui-node ui-column", defaults, userProps)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$childrenHtml</div>"""
          },
        ),
        // 2. Row (Layout) — default mainAxis=start, crossAxis=center
        ComponentDefinition(
          type = "Row",
          category = ComponentCategory.Layout,
          displayName = "Row",
          canHaveChildren = true,
          props =
            CommonProps.forContainer(
              defaultCrossAxis = "center",
              PropSpec(
                name = "backgroundColor",
                kind = PropKind.Color,
                default = JsonPrimitive(""),
                description = "Optional hex background color (#RRGGBB).",
              ),
            ),
          toHtml = { node, ctx, childrenHtml ->
            val defaults =
              listOf(
                "display: flex",
                "flex-direction: row",
                "justify-content: flex-start",
                "align-items: center",
              )
            val userProps = mutableListOf<kotlin.String>()
            val bg = node.props.stringProp("backgroundColor", "")
            if (bg.isNotEmpty() && HtmlSecurity.isValidHexColor(bg)) {
              userProps.add("background-color: $bg")
            }
            val attrs = ctx.nodeAttributes(node, "ui-node ui-row", defaults, userProps)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$childrenHtml</div>"""
          },
        ),
        // 3. Box (Layout) — default mainAxis=start, crossAxis=stretch
        ComponentDefinition(
          type = "Box",
          category = ComponentCategory.Layout,
          displayName = "Box",
          canHaveChildren = true,
          props =
            CommonProps.forContainer(
              defaultCrossAxis = "stretch",
              PropSpec(
                name = "backgroundColor",
                kind = PropKind.Color,
                default = JsonPrimitive(""),
                description = "Optional hex background color (#RRGGBB).",
              ),
            ),
          toHtml = { node, ctx, childrenHtml ->
            val defaults =
              listOf(
                "display: flex",
                "flex-direction: column",
                "position: relative",
                "justify-content: flex-start",
                "align-items: stretch",
              )
            val userProps = mutableListOf<kotlin.String>()
            val bg = node.props.stringProp("backgroundColor", "")
            if (bg.isNotEmpty() && HtmlSecurity.isValidHexColor(bg)) {
              userProps.add("background-color: $bg")
            }
            val attrs = ctx.nodeAttributes(node, "ui-node ui-box", defaults, userProps)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$childrenHtml</div>"""
          },
        ),
        // 4. Card (Layout) — default mainAxis=start, crossAxis=stretch
        ComponentDefinition(
          type = "Card",
          category = ComponentCategory.Layout,
          displayName = "Card",
          canHaveChildren = true,
          props =
            CommonProps.forContainer(
              defaultCrossAxis = "stretch",
              PropSpec(
                name = "elevation",
                kind = PropKind.Int,
                default = JsonPrimitive(2),
                minFloat = 0f,
                maxFloat = 24f,
                description = "Card shadow elevation level (integer 0..24).",
              ),
              PropSpec(
                name = "backgroundColor",
                kind = PropKind.Color,
                default = JsonPrimitive(""),
                description = "Optional hex surface color (#RRGGBB).",
              ),
            ),
          toHtml = { node, ctx, childrenHtml ->
            val elevation = node.props.intProp("elevation", 2).coerceIn(0, 24)
            val shadow =
              if (elevation == 0) {
                "none"
              } else {
                "0 ${elevation * 2}px ${elevation * 5}px rgba(15, 23, 42, 0.08)"
              }
            val defaults =
              listOf(
                "display: flex",
                "flex-direction: column",
                "justify-content: flex-start",
                "align-items: stretch",
                "padding: 16px",
                "background-color: var(--color-surface)",
                "border-radius: var(--radius-base)",
                "border: 1px solid rgba(148, 163, 184, 0.25)",
                "box-shadow: $shadow",
              )
            val userProps = mutableListOf<kotlin.String>()
            val bg = node.props.stringProp("backgroundColor", "")
            if (bg.isNotEmpty() && HtmlSecurity.isValidHexColor(bg)) {
              userProps.add("background-color: $bg")
            }
            val attrs = ctx.nodeAttributes(node, "ui-node ui-card", defaults, userProps)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$childrenHtml</div>"""
          },
        ),
        // 5. Button (Buttons)
        ComponentDefinition(
          type = "Button",
          category = ComponentCategory.Buttons,
          displayName = "Button",
          canHaveChildren = false,
          props =
            CommonProps.forLeaf(
              PropSpec(
                name = "text",
                kind = PropKind.String,
                default = JsonPrimitive("Button"),
                description = "Button label text.",
              ),
              PropSpec(
                name = "variant",
                kind = PropKind.Enum,
                default = JsonPrimitive("filled"),
                allowedValues = listOf("filled", "outlined", "tonal"),
                description = "Visual button style variant.",
              ),
              PropSpec(
                name = "enabled",
                kind = PropKind.Bool,
                default = JsonPrimitive(true),
                description = "Whether the button is interactive.",
              ),
            ),
          toHtml = { node, ctx, _ ->
            val text = ctx.escapeHtml(node.props.stringProp("text", "Button"))
            val variant = node.props.stringProp("variant", "filled")
            val enabled = node.props.boolProp("enabled", true)
            val defaults =
              mutableListOf(
                "display: inline-flex",
                "align-items: center",
                "justify-content: center",
                "min-height: 44px",
                "padding: 10px 18px",
                "border-radius: var(--radius-base)",
                "font-weight: 600",
                "font-size: calc(14px * var(--font-scale))",
                "cursor: ${if (enabled) "pointer" else "not-allowed"}",
                "opacity: ${if (enabled) "1" else "0.55"}",
                "transition: transform 0.1s ease, opacity 0.15s ease",
              )
            when (variant) {
              "outlined" -> {
                defaults.add("background-color: transparent")
                defaults.add("color: var(--color-primary)")
                defaults.add("border: 1.5px solid var(--color-primary)")
              }
              "tonal" -> {
                // Item 7: Use CSS variable derived from theme.primary in Kotlin
                defaults.add("background-color: var(--color-primary-tonal)")
                defaults.add("color: var(--color-primary)")
                defaults.add("border: none")
              }
              else -> {
                defaults.add("background-color: var(--color-primary)")
                defaults.add("color: var(--color-on-primary)")
                defaults.add("border: none")
              }
            }
            // Item 2: In Edit mode never emit the HTML disabled attribute so disabled buttons remain selectable via click
            val disabledAttrs =
              if (!enabled) {
                if (ctx.mode == HtmlRenderMode.Edit) {
                  """ aria-disabled="true""""
                } else {
                  """ disabled aria-disabled="true""""
                }
              } else {
                ""
              }
            val attrs = ctx.nodeAttributes(node, "ui-node ui-button", defaults)
            """<button type="button" $attrs$disabledAttrs>${ctx.selectionBadgeHtml(node)}<span>$text</span></button>"""
          },
        ),
        // 6. TextField (Inputs)
        ComponentDefinition(
          type = "TextField",
          category = ComponentCategory.Inputs,
          displayName = "TextField",
          canHaveChildren = false,
          props =
            CommonProps.forLeaf(
              PropSpec(
                name = "label",
                kind = PropKind.String,
                default = JsonPrimitive(""),
                description = "Field label displayed above the input.",
              ),
              PropSpec(
                name = "placeholder",
                kind = PropKind.String,
                default = JsonPrimitive("Enter text..."),
                description = "Placeholder hint inside the input.",
              ),
              PropSpec(
                name = "value",
                kind = PropKind.String,
                default = JsonPrimitive(""),
                description = "Initial text value.",
              ),
              PropSpec(
                name = "inputType",
                kind = PropKind.Enum,
                default = JsonPrimitive("text"),
                allowedValues = listOf("text", "email", "password", "number"),
                description = "HTML input type.",
              ),
            ),
          toHtml = { node, ctx, _ ->
            val label = ctx.escapeHtml(node.props.stringProp("label", ""))
            val placeholder = ctx.escapeHtml(node.props.stringProp("placeholder", ""))
            val value = ctx.escapeHtml(node.props.stringProp("value", ""))
            val rawType = node.props.stringProp("inputType", "text")
            val inputType =
              if (rawType in listOf("text", "email", "password", "number")) rawType else "text"
            val defaults = listOf("display: flex", "flex-direction: column", "gap: 6px")
            val attrs = ctx.nodeAttributes(node, "ui-node ui-textfield-wrapper", defaults)
            val labelHtml =
              if (label.isNotEmpty()) {
                """<label class="ui-textfield-label">$label</label>"""
              } else {
                ""
              }
            val readOnlyAttr = if (ctx.mode == HtmlRenderMode.Edit) " readonly" else ""
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$labelHtml<input class="ui-textfield-input" type="$inputType" placeholder="$placeholder" value="$value"$readOnlyAttr /></div>"""
          },
        ),
        // 7. Text (Labels)
        ComponentDefinition(
          type = "Text",
          category = ComponentCategory.Labels,
          displayName = "Text",
          canHaveChildren = false,
          props =
            CommonProps.forLeaf(
              PropSpec(
                name = "text",
                kind = PropKind.String,
                default = JsonPrimitive("Text"),
                description = "Displayed text content.",
              ),
              PropSpec(
                name = "fontSize",
                kind = PropKind.Int,
                default = JsonPrimitive(16),
                minFloat = 8f,
                maxFloat = 96f,
                description = "Font size in pixels/sp (integer 8..96).",
              ),
              PropSpec(
                name = "fontWeight",
                kind = PropKind.Enum,
                default = JsonPrimitive("normal"),
                allowedValues = listOf("normal", "medium", "semibold", "bold"),
                description = "Font weight style.",
              ),
              PropSpec(
                name = "color",
                kind = PropKind.Color,
                default = JsonPrimitive("#0F172A"),
                description = "Hex text color (#RRGGBB).",
              ),
              PropSpec(
                name = "textAlign",
                kind = PropKind.Enum,
                default = JsonPrimitive("left"),
                allowedValues = listOf("left", "center", "right"),
                description = "Horizontal text alignment.",
              ),
            ),
          toHtml = { node, ctx, _ ->
            val text = ctx.escapeHtml(node.props.stringProp("text", "Text"))
            val fontSize = node.props.intProp("fontSize", 16).coerceIn(8, 96)
            val weightCss =
              when (node.props.stringProp("fontWeight", "normal")) {
                "bold" -> "700"
                "semibold" -> "600"
                "medium" -> "500"
                else -> "400"
              }
            val color = ctx.sanitizeColor(node.props.stringProp("color", ""), "#0F172A")
            val textAlign =
              when (node.props.stringProp("textAlign", "left")) {
                "center" -> "center"
                "right" -> "right"
                else -> "left"
              }
            val defaults = listOf("line-height: 1.4", "margin: 0")
            val userProps =
              listOf(
                "font-size: calc(${fontSize}px * var(--font-scale))",
                "font-weight: $weightCss",
                "color: $color",
                "text-align: $textAlign",
              )
            val attrs = ctx.nodeAttributes(node, "ui-node ui-text", defaults, userProps)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}<span>$text</span></div>"""
          },
        ),
        // 8. Image (Data / Layout)
        ComponentDefinition(
          type = "Image",
          category = ComponentCategory.Data,
          displayName = "Image",
          canHaveChildren = false,
          props =
            CommonProps.forLeaf(
              PropSpec(
                name = "src",
                kind = PropKind.String,
                default = JsonPrimitive(""),
                description = "Optional image URL (https:// or data:image/ only). Empty renders a placeholder box.",
              ),
              PropSpec(
                name = "label",
                kind = PropKind.String,
                default = JsonPrimitive("Image Placeholder"),
                description = "Alt text or placeholder caption.",
              ),
              PropSpec(
                name = "aspectRatio",
                kind = PropKind.Float,
                default = JsonPrimitive(1.77f),
                minFloat = 0.1f,
                maxFloat = 10f,
                description = "Width-to-height aspect ratio (0.1..10.0) applied when no explicit height is set.",
              ),
            ),
          toHtml = { node, ctx, _ ->
            val rawSrc = node.props.stringProp("src", "")
            val validSrc = ctx.sanitizeImageUrl(rawSrc)
            val label = ctx.escapeHtml(node.props.stringProp("label", "Image Placeholder"))
            val aspectRatio = node.props.floatProp("aspectRatio", 1.77f).coerceIn(0.1f, 10f)
            val hasExplicitHeight = node.props.stringProp("height", "").isNotBlank()

            val defaults =
              mutableListOf(
                "display: flex",
                "align-items: center",
                "justify-content: center",
                "overflow: hidden",
                "border-radius: var(--radius-base)",
                "background-color: #E2E8F0",
                "min-height: 96px",
              )
            // Item 6: Apply aspectRatio as container aspect-ratio when no explicit height is set
            val userProps = mutableListOf<kotlin.String>()
            if (!hasExplicitHeight) {
              userProps.add("aspect-ratio: $aspectRatio")
            }
            val attrs = ctx.nodeAttributes(node, "ui-node ui-image", defaults, userProps)
            val innerHtml =
              if (validSrc != null) {
                val escapedSrc = ctx.escapeHtml(validSrc)
                """<img src="$escapedSrc" alt="$label" style="width:100%;height:100%;object-fit:cover;display:block;" />"""
              } else {
                """<div class="ui-image-placeholder">""" +
                  """<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#64748B" stroke-width="1.8"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>""" +
                  """<span>$label</span></div>"""
              }
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$innerHtml</div>"""
          },
        ),
        // 9. Icon (Data / Layout)
        ComponentDefinition(
          type = "Icon",
          category = ComponentCategory.Data,
          displayName = "Icon",
          canHaveChildren = false,
          props =
            CommonProps.forLeaf(
              PropSpec(
                name = "name",
                kind = PropKind.Enum,
                default = JsonPrimitive("star"),
                allowedValues = BuiltInIcons.ALLOWED_ICONS,
                description = "Built-in inline SVG icon name.",
              ),
              PropSpec(
                name = "size",
                kind = PropKind.Int,
                default = JsonPrimitive(24),
                minFloat = 12f,
                maxFloat = 128f,
                description = "Icon size in pixels (integer 12..128).",
              ),
              PropSpec(
                name = "tint",
                kind = PropKind.Color,
                default = JsonPrimitive("#4F46E5"),
                description = "Icon stroke hex color (#RRGGBB).",
              ),
            ),
          toHtml = { node, ctx, _ ->
            val iconName = node.props.stringProp("name", "star")
            val size = node.props.intProp("size", 24).coerceIn(12, 128)
            val tint = ctx.sanitizeColor(node.props.stringProp("tint", ""), ctx.theme.primary)
            val defaults =
              listOf(
                "display: inline-flex",
                "align-items: center",
                "justify-content: center",
              )
            val attrs = ctx.nodeAttributes(node, "ui-node ui-icon", defaults)
            val svg = BuiltInIcons.renderSvg(iconName, size, tint)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}$svg</div>"""
          },
        ),
        // 10. Spacer (Layout)
        ComponentDefinition(
          type = "Spacer",
          category = ComponentCategory.Layout,
          displayName = "Spacer",
          canHaveChildren = false,
          props =
            CommonProps.forLeaf(
              PropSpec(
                name = "size",
                kind = PropKind.Int,
                default = JsonPrimitive(16),
                minFloat = 0f,
                maxFloat = 4000f,
                description = "Spacer dimension in pixels/dp along the parent's main axis (integer 0..4000).",
              )
            ),
          toHtml = { node, ctx, _ ->
            val size = node.props.intProp("size", 16).coerceIn(0, 4000)
            // Item 8: Apply size on parent's main axis only (Row -> width, otherwise height)
            val axisDecl =
              if (ctx.parentType == "Row") {
                "width: ${size}px; min-width: ${size}px"
              } else {
                "height: ${size}px; min-height: ${size}px"
              }
            val defaults = listOf(axisDecl, "flex-shrink: 0")
            val attrs = ctx.nodeAttributes(node, "ui-node ui-spacer", defaults)
            """<div $attrs>${ctx.selectionBadgeHtml(node)}</div>"""
          },
        ),
      )
  }
}
