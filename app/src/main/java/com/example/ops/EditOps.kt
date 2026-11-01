package com.example.ops

import com.example.model.Node
import com.example.model.Screen
import com.example.registry.ComponentRegistry
import com.example.validator.Validator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("op")
sealed interface EditOp {
  @Serializable
  @SerialName("add_node")
  data class AddNode(
    val parentId: String,
    val index: Int = -1,
    val node: Node,
  ) : EditOp

  @Serializable
  @SerialName("update_props")
  data class UpdateProps(
    val nodeId: String,
    val props: JsonObject,
  ) : EditOp

  @Serializable
  @SerialName("move_node")
  data class MoveNode(
    val nodeId: String,
    val newParentId: String,
    val index: Int = -1,
  ) : EditOp

  @Serializable
  @SerialName("delete_node")
  data class DeleteNode(
    val nodeId: String,
  ) : EditOp

  @Serializable
  @SerialName("replace_screen_root")
  data class ReplaceScreenRoot(
    val node: Node,
  ) : EditOp
}

class EditOpException(message: String) : IllegalArgumentException(message)

/**
 * Pure, atomic function that applies a list of [EditOp]s to [screen] and validates the resulting screen.
 * If ANY operation fails or post-op validation produces any errors, returns [Result.failure] with a
 * human-readable message and leaves the original [screen] untouched.
 *
 * @param allowRemoteImages Passed to [Validator.validate]; when false (for AI-originated ops by default),
 *   rejects any `http://` or `https://` Image `src`.
 */
fun applyOps(
  screen: Screen,
  ops: List<EditOp>,
  registry: ComponentRegistry = ComponentRegistry.Default,
  allowRemoteImages: Boolean = true,
): Result<Screen> {
  return runCatching {
    var workingRoot = screen.root
    ops.forEachIndexed { stepIndex, op ->
      workingRoot =
        try {
          applySingleOp(workingRoot, op, registry)
        } catch (e: EditOpException) {
          throw EditOpException("Op #${stepIndex + 1} (${op::class.simpleName}) failed: ${e.message}")
        }
    }

    val candidateScreen = screen.copy(root = workingRoot)
    val validationErrors = Validator.validate(candidateScreen, registry, allowRemoteImages)
    if (validationErrors.isNotEmpty()) {
      val joined = validationErrors.joinToString("; ") { it.toString() }
      throw EditOpException("Validation failed after applying ops: $joined")
    }
    candidateScreen
  }
}

private fun applySingleOp(
  root: Node,
  op: EditOp,
  registry: ComponentRegistry,
): Node {
  return when (op) {
    is EditOp.AddNode -> {
      if (findNode(root, op.node.id) != null) {
        throw EditOpException("Node with id '${op.node.id}' already exists in screen.")
      }
      val parent =
        findNode(root, op.parentId)
          ?: throw EditOpException("Target parent node '${op.parentId}' not found.")
      val insertIdx =
        if (op.index < 0) {
          parent.children.size
        } else if (op.index <= parent.children.size) {
          op.index
        } else {
          throw EditOpException(
            "Insert index ${op.index} is out of bounds for parent '${op.parentId}' with ${parent.children.size} children."
          )
        }
      replaceNodeById(root, op.parentId) { targetParent ->
        val updatedChildren = targetParent.children.toMutableList()
        updatedChildren.add(insertIdx, op.node)
        targetParent.copy(children = updatedChildren)
      }
    }

    is EditOp.UpdateProps -> {
      val existingNode =
        findNode(root, op.nodeId)
          ?: throw EditOpException("Target node '${op.nodeId}' not found for UpdateProps.")
      val def = registry.get(existingNode.type)

      // Item 11: Treat JSON null value in UpdateProps as "remove this prop (reset to default)".
      // Also validate that any removed prop name is actually a known prop on the component.
      replaceNodeById(root, op.nodeId) { target ->
        val merged = target.props.toMutableMap()
        for ((k, v) in op.props) {
          if (v is JsonNull) {
            if (def != null && !def.propMap.containsKey(k)) {
              throw EditOpException("Unknown prop '$k' on component '${target.type}' in UpdateProps.")
            }
            merged.remove(k)
          } else {
            merged[k] = v
          }
        }
        target.copy(props = JsonObject(merged))
      }
    }

    is EditOp.DeleteNode -> {
      if (root.id == op.nodeId) {
        throw EditOpException("Cannot delete the root node '${root.id}'. Use ReplaceScreenRoot instead.")
      }
      if (findNode(root, op.nodeId) == null) {
        throw EditOpException("Target node '${op.nodeId}' not found for DeleteNode.")
      }
      removeNodeById(root, op.nodeId)
    }

    is EditOp.MoveNode -> {
      if (root.id == op.nodeId) {
        throw EditOpException("Cannot move the root node '${root.id}'.")
      }
      val movingNode =
        findNode(root, op.nodeId)
          ?: throw EditOpException("Node '${op.nodeId}' to move was not found.")
      if (op.nodeId == op.newParentId || findNode(movingNode, op.newParentId) != null) {
        throw EditOpException(
          "Cannot move node '${op.nodeId}' into itself or its own descendant '${op.newParentId}'."
        )
      }
      if (findNode(root, op.newParentId) == null) {
        throw EditOpException("Destination parent node '${op.newParentId}' not found.")
      }

      val treeWithoutNode = removeNodeById(root, op.nodeId)
      val updatedParent =
        findNode(treeWithoutNode, op.newParentId)
          ?: throw EditOpException("Destination parent '${op.newParentId}' unavailable after removal.")

      val targetIndex =
        if (op.index < 0) {
          updatedParent.children.size
        } else if (op.index <= updatedParent.children.size) {
          op.index
        } else {
          throw EditOpException(
            "Move index ${op.index} is out of bounds for parent '${op.newParentId}' (size ${updatedParent.children.size})."
          )
        }

      replaceNodeById(treeWithoutNode, op.newParentId) { parent ->
        val newChildren = parent.children.toMutableList()
        newChildren.add(targetIndex, movingNode)
        parent.copy(children = newChildren)
      }
    }

    is EditOp.ReplaceScreenRoot -> op.node
  }
}

fun findNode(current: Node, targetId: String): Node? {
  if (current.id == targetId) return current
  for (child in current.children) {
    val found = findNode(child, targetId)
    if (found != null) return found
  }
  return null
}

private fun replaceNodeById(current: Node, targetId: String, transform: (Node) -> Node): Node {
  if (current.id == targetId) {
    return transform(current)
  }
  if (current.children.isEmpty()) return current
  return current.copy(
    children = current.children.map { child -> replaceNodeById(child, targetId, transform) }
  )
}

private fun removeNodeById(current: Node, targetId: String): Node {
  if (current.children.isEmpty()) return current
  val filtered = current.children.filterNot { it.id == targetId }
  return current.copy(
    children = filtered.map { child -> removeNodeById(child, targetId) }
  )
}
