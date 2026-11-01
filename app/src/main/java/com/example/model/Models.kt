package com.example.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class Project(
  val id: String,
  val name: String,
  val screens: List<Screen>,
  val theme: ThemeTokens = ThemeTokens(),
  val createdAt: Long,
  val updatedAt: Long,
)

@Serializable
data class Screen(
  val id: String,
  val name: String,
  val root: Node,
  val scrollable: Boolean = true,
)

@Serializable
data class Node(
  val id: String,
  val type: String,
  val props: JsonObject = JsonObject(emptyMap()),
  val children: List<Node> = emptyList(),
)

@Serializable
data class ThemeTokens(
  val primary: String = "#4F46E5",
  val secondary: String = "#0EA5E9",
  val background: String = "#F8FAFC",
  val surface: String = "#FFFFFF",
  val onPrimary: String = "#FFFFFF",
  val cornerRadius: Int = 10,
  val fontScale: Float = 1.0f,
)

object AppJson {
  val instance: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = false
    encodeDefaults = true
    classDiscriminator = "op"
  }

  val compact: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = false
    encodeDefaults = true
    classDiscriminator = "op"
  }
}

fun JsonObject.stringProp(key: String, default: String = ""): String {
  val el: JsonElement = this[key] ?: return default
  return (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default
}

fun JsonObject.intProp(key: String, default: Int = 0): Int {
  val el: JsonElement = this[key] ?: return default
  return (el as? JsonPrimitive)?.intOrNull ?: default
}

fun JsonObject.floatProp(key: String, default: Float = 0f): Float {
  val el: JsonElement = this[key] ?: return default
  return (el as? JsonPrimitive)?.floatOrNull ?: default
}

fun JsonObject.boolProp(key: String, default: Boolean = false): Boolean {
  val el: JsonElement = this[key] ?: return default
  return (el as? JsonPrimitive)?.booleanOrNull ?: default
}
