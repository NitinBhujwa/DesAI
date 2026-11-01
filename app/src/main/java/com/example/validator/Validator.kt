package com.example.validator

import com.example.model.Node
import com.example.model.Screen
import com.example.registry.ComponentRegistry
import com.example.registry.HtmlSecurity
import com.example.registry.PropKind
import com.example.registry.PropSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class ValidationError(
  val nodeId: String,
  val path: String,
  val message: String,
) {
  override fun toString(): String = "[$nodeId @ $path] $message"
}

object Validator {
  /**
   * Validates [screen] against [registry].
   *
   * @param allowRemoteImages When false (e.g. for AI-originated operations when the user has not enabled remote images),
   *   any `Image` `src` starting with `http://` or `https://` is rejected with a [ValidationError].
   */
  fun validate(
    screen: Screen,
    registry: ComponentRegistry = ComponentRegistry.Default,
    allowRemoteImages: Boolean = true,
  ): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    val seenIds = mutableSetOf<String>()
    validateNodeRecursive(
      node = screen.root,
      path = "root",
      registry = registry,
      seenIds = seenIds,
      allowRemoteImages = allowRemoteImages,
      errors = errors,
    )
    return errors
  }

  private fun validateNodeRecursive(
    node: Node,
    path: String,
    registry: ComponentRegistry,
    seenIds: MutableSet<String>,
    allowRemoteImages: Boolean,
    errors: MutableList<ValidationError>,
  ) {
    if (node.id.isBlank()) {
      errors.add(
        ValidationError(
          nodeId = node.id,
          path = path,
          message = "Node ID must not be blank.",
        )
      )
    } else if (!seenIds.add(node.id)) {
      errors.add(
        ValidationError(
          nodeId = node.id,
          path = path,
          message = "Duplicate node ID '${node.id}' found in screen tree.",
        )
      )
    }

    val def = registry.get(node.type)
    if (def == null) {
      errors.add(
        ValidationError(
          nodeId = node.id,
          path = path,
          message = "Unknown component type '${node.type}'. Registered types: ${registry.all().joinToString { it.type }}.",
        )
      )
    } else {
      if (!def.canHaveChildren && node.children.isNotEmpty()) {
        errors.add(
          ValidationError(
            nodeId = node.id,
            path = "$path.children",
            message = "Component '${node.type}' is a leaf component and cannot have children (found ${node.children.size}).",
          )
        )
      }

      for ((propKey, propValue) in node.props) {
        val spec = def.propMap[propKey]
        if (spec == null) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = "$path.props.$propKey",
              message = "Unknown prop '$propKey' on component '${node.type}'. Allowed props: ${def.props.joinToString { it.name }}.",
            )
          )
        } else {
          validatePropValue(
            node = node,
            path = "$path.props.$propKey",
            spec = spec,
            value = propValue,
            allowRemoteImages = allowRemoteImages,
            errors = errors,
          )
        }
      }
    }

    node.children.forEachIndexed { index, child ->
      validateNodeRecursive(
        node = child,
        path = "$path.children[$index]",
        registry = registry,
        seenIds = seenIds,
        allowRemoteImages = allowRemoteImages,
        errors = errors,
      )
    }
  }

  private fun validatePropValue(
    node: Node,
    path: String,
    spec: PropSpec,
    value: JsonElement,
    allowRemoteImages: Boolean,
    errors: MutableList<ValidationError>,
  ) {
    // Item 11: Reject JSON null props in AddNode / ReplaceScreenRoot / Screen tree validation
    if (value is JsonNull) {
      errors.add(
        ValidationError(
          nodeId = node.id,
          path = path,
          message = "Prop '${spec.name}' cannot be null on a node (null is only valid inside UpdateProps to remove a prop).",
        )
      )
      return
    }

    val primitive = value as? JsonPrimitive
    if (primitive == null) {
      errors.add(
        ValidationError(
          nodeId = node.id,
          path = path,
          message = "Prop '${spec.name}' must be a primitive value of kind ${spec.kind}, but got non-primitive JSON.",
        )
      )
      return
    }

    when (spec.kind) {
      PropKind.String -> {
        if (!primitive.isString) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' expects a String value, got '$primitive'.",
            )
          )
          return
        }
        if (node.type == "Image" && spec.name == "src") {
          val rawUrl = primitive.content.trim()
          // Item 18: Reject remote http/https images when allowRemoteImages is false
          if (
            !allowRemoteImages &&
              (rawUrl.startsWith("http://", ignoreCase = true) ||
                rawUrl.startsWith("https://", ignoreCase = true))
          ) {
            errors.add(
              ValidationError(
                nodeId = node.id,
                path = path,
                message = "Remote Image 'src' ('$rawUrl') is disabled for AI operations unless 'Allow Remote Images' is enabled. Use an empty 'src' placeholder or 'data:image/' URI.",
              )
            )
          } else if (!HtmlSecurity.isValidImageSrc(rawUrl)) {
            errors.add(
              ValidationError(
                nodeId = node.id,
                path = path,
                message = "Image 'src' must be empty or use 'https://' or 'data:image/' scheme, got '$rawUrl'.",
              )
            )
          }
        }
      }

      PropKind.Int -> {
        val intVal = if (primitive.isString) null else primitive.intOrNull
        if (intVal == null) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' expects an Int value, got '$primitive'.",
            )
          )
          return
        }
        checkNumericRange(node.id, path, spec, intVal.toFloat(), errors)
      }

      PropKind.Float -> {
        val rawContent = primitive.content
        val floatVal =
          if (
            primitive.isString ||
              rawContent.contains('e', ignoreCase = true) ||
              rawContent.contains("NaN", ignoreCase = true) ||
              rawContent.contains("Infinity", ignoreCase = true)
          ) {
            null
          } else {
            primitive.floatOrNull?.takeIf { it.isFinite() }
          }
        if (floatVal == null) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' expects a finite plain decimal Float value, got '$primitive'.",
            )
          )
          return
        }
        checkNumericRange(node.id, path, spec, floatVal, errors)
      }

      PropKind.Bool -> {
        if (primitive.isString || primitive.booleanOrNull == null) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' expects a Bool value, got '$primitive'.",
            )
          )
        }
      }

      PropKind.Color -> {
        if (!primitive.isString) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' expects a hex Color string (#RRGGBB), got '$primitive'.",
            )
          )
        } else {
          val content = primitive.content
          if (content.isNotEmpty() && !HtmlSecurity.isValidHexColor(content)) {
            errors.add(
              ValidationError(
                nodeId = node.id,
                path = path,
                message = "Prop '${spec.name}' must be a valid hex color (#RRGGBB or #AARRGGBB), got '$content'.",
              )
            )
          }
        }
      }

      PropKind.Dimension -> {
        // Item 10: Only plain decimal numbers with optional "dp" suffix up to 4000, or "fill"/"wrap"
        val dimString = primitive.content
        if (!HtmlSecurity.isValidDimension(dimString)) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' must be 'fill', 'wrap', or a plain decimal dimension in 0..4000 (e.g. '120' or '120dp'), got '$dimString'.",
            )
          )
        }
      }

      PropKind.Enum -> {
        if (!primitive.isString) {
          errors.add(
            ValidationError(
              nodeId = node.id,
              path = path,
              message = "Prop '${spec.name}' expects an Enum string value, got '$primitive'.",
            )
          )
          return
        }
      }
    }

    val allowed = spec.allowedValues
    if (!allowed.isNullOrEmpty()) {
      val strVal = primitive.content
      if (strVal !in allowed) {
        errors.add(
          ValidationError(
            nodeId = node.id,
            path = path,
            message = "Value '$strVal' for prop '${spec.name}' is not allowed. Allowed values: ${allowed.joinToString(", ")}.",
          )
        )
      }
    }
  }

  private fun checkNumericRange(
    nodeId: String,
    path: String,
    spec: PropSpec,
    value: Float,
    errors: MutableList<ValidationError>,
  ) {
    val min = spec.minFloat
    val max = spec.maxFloat
    if (min != null && value < min) {
      errors.add(
        ValidationError(
          nodeId = nodeId,
          path = path,
          message = "Prop '${spec.name}' value $value is below minimum $min.",
        )
      )
    }
    if (max != null && value > max) {
      errors.add(
        ValidationError(
          nodeId = nodeId,
          path = path,
          message = "Prop '${spec.name}' value $value exceeds maximum $max.",
        )
      )
    }
  }
}
