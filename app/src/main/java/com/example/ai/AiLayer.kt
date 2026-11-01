package com.example.ai

import com.example.model.AppJson
import com.example.model.Node
import com.example.model.Screen
import com.example.ops.EditOp
import com.example.registry.ComponentRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface AiProviderConfig {
  data class Gemini(
    val apiKey: String,
    val model: String = "gemini-2.5-flash",
  ) : AiProviderConfig {
    override fun toString(): String = "Gemini(model=$model, apiKey=***REDACTED***)"
  }

  data class OpenAiCompatible(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
  ) : AiProviderConfig {
    override fun toString(): String =
      "OpenAiCompatible(baseUrl=$baseUrl, model=$model, apiKey=***REDACTED***)"
  }
}

data class AiRequest(
  val userPrompt: String,
  val currentScreen: Screen,
  val selectedNodeId: String? = null,
)

sealed interface AiResult {
  data class Success(
    val ops: List<EditOp>,
    val sourceDescription: String = "",
  ) : AiResult

  data class Failure(
    val message: String,
  ) : AiResult
}

interface AiProvider {
  suspend fun generateOps(request: AiRequest): AiResult
}

/**
 * Neutral, non-recursive function-calling tool schema.
 * Never uses `$ref` or `$defs`.
 */
@Serializable
data class NeutralPropertySchema(
  val type: String,
  val description: String,
  val enumValues: List<String>? = null,
)

@Serializable
data class NeutralToolDefinition(
  val name: String,
  val description: String,
  val isReadOnly: Boolean,
  val properties: Map<String, NeutralPropertySchema>,
  val required: List<String>,
)

@Serializable
data class AiToolCall(
  val name: String,
  val arguments: JsonObject,
)

object PromptBuilder {
  /**
   * Builds the neutral, non-recursive tool definitions from [registry].
   *
   * Rules enforced:
   * - `add_node` takes ONE flat node specification (`parentId`, `index`, `id`, `type`, `props`) with NO `children`.
   *   Multi-level trees are constructed with sequential `add_node` calls (parent first, then children).
   * - `replace_screen_root` is NOT exposed as a function-calling tool (only available in the text-fallback JSON path).
   * - `props` is kept as a free-form `object` validated by `Validator`.
   */
  fun buildNeutralTools(
    registry: ComponentRegistry = ComponentRegistry.Default,
  ): List<NeutralToolDefinition> {
    val registeredTypes = registry.all().map { it.type }
    val containerTypes = registry.all().filter { it.canHaveChildren }.map { it.type }

    return listOf(
      NeutralToolDefinition(
        name = "add_node",
        description =
          "Adds a single UI component node (without children) into an existing container (${containerTypes.joinToString(", ")}). To build a multi-level subtree, call add_node sequentially: create the parent container first, then add its children referencing the new parent's id.",
        isReadOnly = false,
        properties =
          linkedMapOf(
            "parentId" to
              NeutralPropertySchema(
                type = "string",
                description = "Existing container node ID where the new node will be inserted.",
              ),
            "index" to
              NeutralPropertySchema(
                type = "integer",
                description = "0-based insertion index inside parentId's children (-1 to append at end).",
              ),
            "id" to
              NeutralPropertySchema(
                type = "string",
                description = "Unique string identifier for the new node.",
              ),
            "type" to
              NeutralPropertySchema(
                type = "string",
                description = "Registered component type to create.",
                enumValues = registeredTypes,
              ),
            "props" to
              NeutralPropertySchema(
                type = "object",
                description = "Flat key-value map of component and layout properties (width, height, padding, spacing, weight, and mainAxis/crossAxis on containers).",
              ),
          ),
        required = listOf("parentId", "index", "id", "type", "props"),
      ),
      NeutralToolDefinition(
        name = "update_props",
        description = "Patches one or more properties on an existing node by nodeId. Passing a JSON null value for a property removes it and resets it to its default.",
        isReadOnly = false,
        properties =
          linkedMapOf(
            "nodeId" to
              NeutralPropertySchema(
                type = "string",
                description = "ID of the existing node to update.",
              ),
            "props" to
              NeutralPropertySchema(
                type = "object",
                description = "Key-value property patch to merge into the node's existing props (use null to remove a prop and reset to default).",
              ),
          ),
        required = listOf("nodeId", "props"),
      ),
      NeutralToolDefinition(
        name = "move_node",
        description = "Moves an existing non-root node to a new parent container at the specified index. Cannot move a node into itself or its own descendant.",
        isReadOnly = false,
        properties =
          linkedMapOf(
            "nodeId" to
              NeutralPropertySchema(
                type = "string",
                description = "ID of the existing non-root node to move.",
              ),
            "newParentId" to
              NeutralPropertySchema(
                type = "string",
                description = "ID of the destination container node.",
              ),
            "index" to
              NeutralPropertySchema(
                type = "integer",
                description = "0-based target index in newParentId (-1 to append).",
              ),
          ),
        required = listOf("nodeId", "newParentId", "index"),
      ),
      NeutralToolDefinition(
        name = "delete_node",
        description = "Deletes an existing non-root node and its subtree by nodeId. Never delete the screen root.",
        isReadOnly = false,
        properties =
          linkedMapOf(
            "nodeId" to
              NeutralPropertySchema(
                type = "string",
                description = "ID of the existing non-root node to delete.",
              ),
          ),
        required = listOf("nodeId"),
      ),
      NeutralToolDefinition(
        name = "get_screen",
        description = "Read-only tool that returns the current Screen JSON tree and selectedNodeId.",
        isReadOnly = true,
        properties = emptyMap(),
        required = emptyList(),
      ),
      NeutralToolDefinition(
        name = "get_component_catalog",
        description = "Read-only tool that returns all registered component types, their categories, and allowed PropSpecs.",
        isReadOnly = true,
        properties = emptyMap(),
        required = emptyList(),
      ),
      NeutralToolDefinition(
        name = "validate_screen",
        description = "Read-only tool that validates the current Screen against the ComponentRegistry and returns any ValidationErrors.",
        isReadOnly = true,
        properties = emptyMap(),
        required = emptyList(),
      ),
    )
  }

  /**
   * Pure function that builds the system prompt from [registry].
   */
  fun buildSystemPrompt(
    registry: ComponentRegistry = ComponentRegistry.Default,
  ): String {
    val definitions = registry.all()
    return buildString {
      appendLine("You are the AI UI Designer Engine for a mobile screen builder (390x844 viewport).")
      appendLine("A serializable Screen component tree is the single source of truth.")
      appendLine()
      appendLine("STRICT RULES:")
      appendLine("1. Use ONLY registered component types and their documented properties.")
      appendLine("2. Never invent IDs when referencing existing nodes (parentId, nodeId, newParentId). Always use exact IDs from the current screen.")
      appendLine("3. When adding new nodes via the `add_node` tool, each call creates ONE node with NO children. To build nested hierarchies, emit multiple `add_node` calls in parent-first order.")
      appendLine("4. Never delete the root node with `delete_node` and never move a node into its own descendant.")
      appendLine("5. Layout & Axis Alignment: Container components (Column, Row, Box, Card) use `mainAxis` (start, center, end, space_between, space_around) and `crossAxis` (start, center, end, stretch). Do not use `alignment`.")
      appendLine("6. Security & Remote Images: Never output raw HTML, CSS, or <script> tags in props. Colors must be 6- or 8-digit hex (#RRGGBB). Do NOT invent remote `http://` or `https://` Image `src` URLs unless the user has explicitly enabled 'Allow Remote Images' (default off); use an empty `src` string for a placeholder box or a self-contained `data:image/` URI.")
      appendLine("7. To remove a property on an existing node and reset it to its default, pass `null` as that property's value in `update_props` (null props are forbidden in `add_node` and `replace_screen_root`).")
      appendLine("8. If function calling is unavailable on the endpoint, respond ONLY with a valid JSON array of EditOp objects using the `\"op\"` discriminator (`add_node`, `update_props`, `move_node`, `delete_node`, `replace_screen_root`).")
      appendLine()
      appendLine("COMPONENT CATALOG (${definitions.size} registered components):")
      for (def in definitions) {
        val childMode = if (def.canHaveChildren) "Container (canHaveChildren=true)" else "Leaf (canHaveChildren=false)"
        appendLine("- ${def.type} [Category: ${def.category}] — $childMode")
        for (prop in def.props) {
          val allowed =
            if (!prop.allowedValues.isNullOrEmpty()) {
              " allowed=${prop.allowedValues}"
            } else {
              ""
            }
          val rangeInfo =
            if (prop.minFloat != null || prop.maxFloat != null) {
              " range=[${prop.minFloat ?: "-inf"}..${prop.maxFloat ?: "+inf"}]"
            } else {
              ""
            }
          val defVal = prop.default?.toString() ?: "none"
          appendLine("    • ${prop.name}: ${prop.kind} (default=$defVal$allowed$rangeInfo) — ${prop.description}")
        }
      }
      appendLine()
      appendLine("TEXT-FALLBACK JSON FORMAT (when tool calling is disabled):")
      appendLine("""[{"op":"add_node","parentId":"root_column","index":-1,"node":{"id":"new_btn","type":"Button","props":{"text":"Continue","variant":"filled"},"children":[]}},{"op":"update_props","nodeId":"title_text","props":{"text":"Updated Title"}}]""")
    }.trimEnd()
  }
}

/**
 * Pure adapter that converts neutral tool definitions into the Gemini `functionDeclarations` JSON format.
 * Strictly non-recursive: never emits `$ref` or `$defs`.
 */
object GeminiToolAdapter {
  fun toFunctionDeclarationsJson(tools: List<NeutralToolDefinition>): JsonObject {
    return buildJsonObject {
      put(
        "functionDeclarations",
        buildJsonArray {
          for (tool in tools) {
            add(
              buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put(
                  "parameters",
                  buildJsonObject {
                    put("type", JsonPrimitive("OBJECT"))
                    put(
                      "properties",
                      buildJsonObject {
                        for ((propName, schema) in tool.properties) {
                          put(
                            propName,
                            buildJsonObject {
                              put("type", JsonPrimitive(schema.type.uppercase()))
                              put("description", JsonPrimitive(schema.description))
                              if (!schema.enumValues.isNullOrEmpty()) {
                                put(
                                  "enum",
                                  buildJsonArray {
                                    schema.enumValues.forEach { add(JsonPrimitive(it)) }
                                  },
                                )
                              }
                            },
                          )
                        }
                      },
                    )
                    if (tool.required.isNotEmpty()) {
                      put(
                        "required",
                        buildJsonArray {
                          tool.required.forEach { add(JsonPrimitive(it)) }
                        },
                      )
                    }
                  },
                )
              }
            )
          }
        },
      )
    }
  }
}

/**
 * Pure adapter that converts neutral tool definitions into the OpenAI-compatible `tools` JSON array format.
 * Strictly non-recursive: never emits `$ref` or `$defs`.
 */
object OpenAiToolAdapter {
  fun toToolsJson(tools: List<NeutralToolDefinition>): JsonArray {
    return buildJsonArray {
      for (tool in tools) {
        add(
          buildJsonObject {
            put("type", JsonPrimitive("function"))
            put(
              "function",
              buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put(
                  "parameters",
                  buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                      "properties",
                      buildJsonObject {
                        for ((propName, schema) in tool.properties) {
                          put(
                            propName,
                            buildJsonObject {
                              put("type", JsonPrimitive(schema.type.lowercase()))
                              put("description", JsonPrimitive(schema.description))
                              if (!schema.enumValues.isNullOrEmpty()) {
                                put(
                                  "enum",
                                  buildJsonArray {
                                    schema.enumValues.forEach { add(JsonPrimitive(it)) }
                                  },
                                )
                              }
                            },
                          )
                        }
                      },
                    )
                    put(
                      "required",
                      buildJsonArray {
                        tool.required.forEach { add(JsonPrimitive(it)) }
                      },
                    )
                    put("additionalProperties", JsonPrimitive(false))
                  },
                )
              },
            )
          }
        )
      }
    }
  }
}

/**
 * Converts either structured function-calling [AiToolCall]s OR a fallback plain-text JSON array of [EditOp]s
 * into a list of [EditOp] domain objects ready for `applyOps` + `Validator`.
 */
object AiResponseParser {
  fun parseToolCalls(toolCalls: List<AiToolCall>): Result<List<EditOp>> {
    return runCatching {
      val ops = mutableListOf<EditOp>()
      for (call in toolCalls) {
        val args = call.arguments
        when (call.name) {
          "add_node" -> {
            if (args.containsKey("children")) {
              throw IllegalArgumentException(
                "Tool 'add_node' is non-recursive and must not contain 'children'. Emit multiple 'add_node' calls instead."
              )
            }
            val parentId =
              args["parentId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("add_node missing 'parentId'")
            val index = args["index"]?.jsonPrimitive?.intOrNull ?: -1
            val id =
              args["id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("add_node missing 'id'")
            val type =
              args["type"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("add_node missing 'type'")
            val props = args["props"]?.jsonObject ?: JsonObject(emptyMap())
            ops.add(
              EditOp.AddNode(
                parentId = parentId,
                index = index,
                node = Node(id = id, type = type, props = props, children = emptyList()),
              )
            )
          }

          "update_props" -> {
            val nodeId =
              args["nodeId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("update_props missing 'nodeId'")
            val props =
              args["props"]?.jsonObject
                ?: throw IllegalArgumentException("update_props missing 'props'")
            ops.add(EditOp.UpdateProps(nodeId = nodeId, props = props))
          }

          "move_node" -> {
            val nodeId =
              args["nodeId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("move_node missing 'nodeId'")
            val newParentId =
              args["newParentId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("move_node missing 'newParentId'")
            val index = args["index"]?.jsonPrimitive?.intOrNull ?: -1
            ops.add(EditOp.MoveNode(nodeId = nodeId, newParentId = newParentId, index = index))
          }

          "delete_node" -> {
            val nodeId =
              args["nodeId"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("delete_node missing 'nodeId'")
            ops.add(EditOp.DeleteNode(nodeId = nodeId))
          }

          "get_screen", "get_component_catalog", "validate_screen" -> {
            // Read-only inspection tools do not emit mutation EditOps
          }

          "replace_screen_root" -> {
            throw IllegalArgumentException(
              "'replace_screen_root' is not a valid function-calling tool; use the JSON text-fallback path."
            )
          }

          else -> throw IllegalArgumentException("Unknown AI tool call '${call.name}'.")
        }
      }
      ops
    }
  }

  /**
   * Fallback parser for endpoints without tool calling where the model replies with a JSON array of [EditOp]s.
   */
  fun parseTextFallbackJson(rawText: String): Result<List<EditOp>> {
    return runCatching {
      val trimmed = rawText.trim()
      val jsonSlice =
        if (trimmed.startsWith("```")) {
          trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        } else {
          trimmed
        }
      val element: JsonElement = AppJson.instance.parseToJsonElement(jsonSlice)
      val array = element.jsonArray
      array.map { item -> AppJson.instance.decodeFromJsonElement(EditOp.serializer(), item) }
    }
  }
}

/**
 * Fake AI Provider that deterministically exercises:
 * 1. Function-calling path: builds a 3-level component subtree (`Card` -> `Column` -> `Row`/`Text`/`Button`)
 *    through sequential non-recursive `add_node` tool calls.
 * 2. Text-fallback path: parses a raw JSON array of `EditOp`s.
 * 3. Remote-image test path: emits an `Image` with an `https://` URL to test AI remote-image gating (Item 18).
 */
class FakeAiProvider(
  var mode: Mode = Mode.FunctionCallingThreeLevelTree,
) : AiProvider {

  enum class Mode {
    FunctionCallingThreeLevelTree,
    TextFallbackJsonArray,
    RemoteImageAttempt,
  }

  private var callCounter = 0

  override suspend fun generateOps(request: AiRequest): AiResult {
    callCounter += 1
    val suffix = callCounter.toString()
    val targetParentId = request.currentScreen.root.id

    return when (mode) {
      Mode.FunctionCallingThreeLevelTree -> {
        val cardId = "ai_card_$suffix"
        val innerColId = "ai_card_col_$suffix"
        val badgeRowId = "ai_badge_row_$suffix"
        val iconId = "ai_icon_$suffix"
        val headingId = "ai_heading_$suffix"
        val actionBtnId = "ai_action_btn_$suffix"

        val toolCalls =
          listOf(
            // Level 1: Card added to root container
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(targetParentId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive(cardId))
                  put("type", JsonPrimitive("Card"))
                  put(
                    "props",
                    buildJsonObject {
                      put("width", JsonPrimitive("fill"))
                      put("padding", JsonPrimitive(16))
                      put("elevation", JsonPrimitive(3))
                    },
                  )
                },
            ),
            // Level 2: Column added inside Card
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(cardId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive(innerColId))
                  put("type", JsonPrimitive("Column"))
                  put(
                    "props",
                    buildJsonObject {
                      put("width", JsonPrimitive("fill"))
                      put("spacing", JsonPrimitive(10))
                      put("mainAxis", JsonPrimitive("start"))
                      put("crossAxis", JsonPrimitive("stretch"))
                    },
                  )
                },
            ),
            // Level 3 (a): Heading Text added inside Level 2 Column
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(innerColId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive(headingId))
                  put("type", JsonPrimitive("Text"))
                  put(
                    "props",
                    buildJsonObject {
                      put("text", JsonPrimitive("SSO Quick Access (#$suffix)"))
                      put("fontSize", JsonPrimitive(16))
                      put("fontWeight", JsonPrimitive("semibold"))
                      put("color", JsonPrimitive("#1E293B"))
                    },
                  )
                },
            ),
            // Level 3 (b): Row added inside Level 2 Column
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(innerColId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive(badgeRowId))
                  put("type", JsonPrimitive("Row"))
                  put(
                    "props",
                    buildJsonObject {
                      put("width", JsonPrimitive("fill"))
                      put("spacing", JsonPrimitive(8))
                      put("mainAxis", JsonPrimitive("start"))
                      put("crossAxis", JsonPrimitive("center"))
                    },
                  )
                },
            ),
            // Child inside Row: Icon
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(badgeRowId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive(iconId))
                  put("type", JsonPrimitive("Icon"))
                  put(
                    "props",
                    buildJsonObject {
                      put("name", JsonPrimitive("check"))
                      put("size", JsonPrimitive(20))
                      put("tint", JsonPrimitive("#10B981"))
                    },
                  )
                },
            ),
            // Level 3 (c): Action Button added inside Level 2 Column
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(innerColId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive(actionBtnId))
                  put("type", JsonPrimitive("Button"))
                  put(
                    "props",
                    buildJsonObject {
                      put("width", JsonPrimitive("fill"))
                      put("text", JsonPrimitive("Continue with Passkey"))
                      put("variant", JsonPrimitive("tonal"))
                    },
                  )
                },
            ),
          )

        AiResponseParser.parseToolCalls(toolCalls).fold(
          onSuccess = { ops ->
            AiResult.Success(
              ops = ops,
              sourceDescription = "Function-Calling: 6 sequential add_node calls building a 3-level Card -> Column -> Text/Row/Button tree",
            )
          },
          onFailure = { err ->
            AiResult.Failure(err.message ?: "Failed to parse tool calls")
          },
        )
      }

      Mode.TextFallbackJsonArray -> {
        val rawFallbackText =
          """
          [
            {
              "op": "update_props",
              "nodeId": "${request.selectedNodeId ?: "title_text"}",
              "props": {
                "text": "Enterprise Portal (AI JSON #$suffix)"
              }
            },
            {
              "op": "add_node",
              "parentId": "$targetParentId",
              "index": -1,
              "node": {
                "id": "fallback_btn_$suffix",
                "type": "Button",
                "props": {
                  "width": "fill",
                  "text": "Request Magic Link (#$suffix)",
                  "variant": "outlined"
                },
                "children": []
              }
            }
          ]
          """.trimIndent()

        AiResponseParser.parseTextFallbackJson(rawFallbackText).fold(
          onSuccess = { ops ->
            AiResult.Success(
              ops = ops,
              sourceDescription = "Text-Fallback JSON Array: parsed ${ops.size} EditOps from raw text response",
            )
          },
          onFailure = { err ->
            AiResult.Failure(err.message ?: "Failed to parse fallback JSON")
          },
        )
      }

      Mode.RemoteImageAttempt -> {
        val toolCalls =
          listOf(
            AiToolCall(
              name = "add_node",
              arguments =
                buildJsonObject {
                  put("parentId", JsonPrimitive(targetParentId))
                  put("index", JsonPrimitive(-1))
                  put("id", JsonPrimitive("ai_remote_img_$suffix"))
                  put("type", JsonPrimitive("Image"))
                  put(
                    "props",
                    buildJsonObject {
                      put("width", JsonPrimitive("fill"))
                      put("src", JsonPrimitive("https://example.com/invented_ai_banner.png"))
                      put("label", JsonPrimitive("AI Invented Remote Banner"))
                    },
                  )
                },
            )
          )
        AiResponseParser.parseToolCalls(toolCalls).fold(
          onSuccess = { ops ->
            AiResult.Success(
              ops = ops,
              sourceDescription = "Added AI remote image (allowed because 'Allow Remote Images' is ON).",
            )
          },
          onFailure = { err -> AiResult.Failure(err.message ?: "Failed") },
        )
      }
    }
  }
}
