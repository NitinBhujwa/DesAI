package com.example.debug

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ai.AiRequest
import com.example.ai.AiResult
import com.example.ai.FakeAiProvider
import com.example.ai.GeminiToolAdapter
import com.example.ai.OpenAiToolAdapter
import com.example.ai.PromptBuilder
import com.example.export.ExportScale
import com.example.export.ScreenExporter
import com.example.model.AppJson
import com.example.model.Node
import com.example.model.Project
import com.example.model.Screen
import com.example.ops.EditOp
import com.example.ops.applyOps
import com.example.ops.findNode
import com.example.registry.ComponentRegistry
import com.example.registry.HtmlRenderMode
import com.example.render.HtmlRenderer
import com.example.render.NodeRect
import com.example.storage.FileProjectRepository
import com.example.storage.SampleProjectFactory
import com.example.undo.UndoStack
import com.example.validator.ValidationError
import com.example.validator.Validator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

enum class WorkbenchTab {
  Canvas,
  Html,
  RawJson,
  PromptAndTools,
}

data class DebugUiState(
  val project: Project = SampleProjectFactory.createLoginSampleProject(),
  val currentScreen: Screen = SampleProjectFactory.createLoginScreen(),
  val renderMode: HtmlRenderMode = HtmlRenderMode.Edit,
  val activeTab: WorkbenchTab = WorkbenchTab.Canvas,
  val selectedNodeId: String? = "login_button",
  val nodeRects: List<NodeRect> = emptyList(),
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val undoCount: Int = 0,
  val redoCount: Int = 0,
  val validationErrors: List<ValidationError> = emptyList(),
  val statusMessage: String = "Loaded Starter Workspace ('Login' screen).",
  val isStatusError: Boolean = false,
  val exportWarnings: List<String> = emptyList(),
  val isExporting: Boolean = false,
  val exportScale: ExportScale = ExportScale.Scale2x,
  val allowAiRemoteImages: Boolean = false,
  val canvasHtml: String = "",
  val exportHtmlPreview: String = "",
  val rawScreenJson: String = "",
  val systemPrompt: String = "",
  val geminiToolsJson: String = "",
  val openAiToolsJson: String = "",
)

class DebugViewModel(
  private val repository: FileProjectRepository,
  private val registry: ComponentRegistry = ComponentRegistry.Default,
  private val autoLoadFromRepository: Boolean = true,
) : ViewModel() {

  private val undoStack = UndoStack<Screen>(maxEntries = 50)
  private val fakeAiProvider = FakeAiProvider()
  private var sampleOpCounter = 1
  private var activeTransactionSnapshot: Screen? = null

  private val _uiState = MutableStateFlow(createInitialState())
  val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

  init {
    if (autoLoadFromRepository) {
      viewModelScope.launch {
        val savedProject = repository.ensureSampleProject()
        val firstScreen = savedProject.screens.firstOrNull() ?: SampleProjectFactory.createLoginScreen()
        refreshDerivedState(
          project = savedProject,
          screen = firstScreen,
          statusMessage = "Loaded '${firstScreen.name}' from internal JSON storage.",
          isError = false,
        )
      }
    }
  }

  private fun createInitialState(): DebugUiState {
    val initialProject = SampleProjectFactory.createLoginSampleProject()
    val initialScreen = initialProject.screens.first()
    val neutralTools = PromptBuilder.buildNeutralTools(registry)
    val sysPrompt = PromptBuilder.buildSystemPrompt(registry)
    val geminiJson =
      AppJson.instance.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        GeminiToolAdapter.toFunctionDeclarationsJson(neutralTools),
      )
    val openAiJson =
      AppJson.instance.encodeToString(
        kotlinx.serialization.json.JsonArray.serializer(),
        OpenAiToolAdapter.toToolsJson(neutralTools),
      )
    val exportRes = ScreenExporter.exportHtml(initialScreen, initialProject.theme, registry)
    val canvasHtml =
      HtmlRenderer.renderScreen(
        screen = initialScreen,
        theme = initialProject.theme,
        registry = registry,
        mode = HtmlRenderMode.Edit,
        selectedNodeId = "login_button",
      )
    val rawJson = AppJson.instance.encodeToString(Screen.serializer(), initialScreen)

    return DebugUiState(
      project = initialProject,
      currentScreen = initialScreen,
      canvasHtml = canvasHtml,
      exportHtmlPreview = exportRes.html,
      exportWarnings = exportRes.warnings,
      rawScreenJson = rawJson,
      systemPrompt = sysPrompt,
      geminiToolsJson = geminiJson,
      openAiToolsJson = openAiJson,
    )
  }

  fun selectTab(tab: WorkbenchTab) {
    _uiState.update { it.copy(activeTab = tab) }
  }

  fun setRenderMode(mode: HtmlRenderMode) {
    val current = _uiState.value
    val updatedCanvasHtml =
      HtmlRenderer.renderScreen(
        screen = current.currentScreen,
        theme = current.project.theme,
        registry = registry,
        mode = mode,
        selectedNodeId = current.selectedNodeId,
      )
    _uiState.update {
      it.copy(
        renderMode = mode,
        canvasHtml = updatedCanvasHtml,
        statusMessage = "Switched canvas to ${mode.name} mode.",
        isStatusError = false,
      )
    }
  }

  fun setExportScale(scale: ExportScale) {
    _uiState.update {
      it.copy(
        exportScale = scale,
        statusMessage = "Set PNG export scale to ${scale.label} (${390 * scale.factor}px width).",
        isStatusError = false,
      )
    }
  }

  fun toggleAllowAiRemoteImages() {
    val next = !_uiState.value.allowAiRemoteImages
    _uiState.update {
      it.copy(
        allowAiRemoteImages = next,
        statusMessage = "AI Allow Remote Images set to $next.",
        isStatusError = false,
      )
    }
  }

  fun toggleScrollable() {
    val current = _uiState.value
    val nextScrollable = !current.currentScreen.scrollable
    undoStack.push(current.currentScreen)
    val updatedScreen = current.currentScreen.copy(scrollable = nextScrollable)
    commitScreenUpdate(
      updatedScreen = updatedScreen,
      statusMessage = "Screen scrollable set to $nextScrollable.",
      isError = false,
    )
  }

  fun onNodeSelected(nodeId: String) {
    val current = _uiState.value
    val updatedCanvasHtml =
      HtmlRenderer.renderScreen(
        screen = current.currentScreen,
        theme = current.project.theme,
        registry = registry,
        mode = current.renderMode,
        selectedNodeId = nodeId,
      )
    _uiState.update {
      it.copy(
        selectedNodeId = nodeId,
        canvasHtml = updatedCanvasHtml,
        statusMessage = "Selected node '$nodeId'.",
        isStatusError = false,
      )
    }
  }

  fun onRectsUpdated(rects: List<NodeRect>) {
    _uiState.update { it.copy(nodeRects = rects) }
  }

  /**
   * Item 17: Transaction grouping for multi-step turns (such as an AI turn executing multiple sequential tool calls).
   * Snapshots the screen once at turn start so a single `undo()` reverts the entire turn.
   */
  fun beginUndoTransaction() {
    if (activeTransactionSnapshot == null) {
      activeTransactionSnapshot = _uiState.value.currentScreen
    }
  }

  fun commitUndoTransaction() {
    val snapshot = activeTransactionSnapshot ?: return
    activeTransactionSnapshot = null
    if (snapshot != _uiState.value.currentScreen) {
      undoStack.push(snapshot)
      _uiState.update {
        it.copy(
          canUndo = undoStack.canUndo,
          canRedo = undoStack.canRedo,
          undoCount = undoStack.undoCount,
          redoCount = undoStack.redoCount,
        )
      }
    }
  }

  fun rollbackUndoTransaction(reason: String) {
    val snapshot = activeTransactionSnapshot ?: return
    activeTransactionSnapshot = null
    commitScreenUpdate(
      updatedScreen = snapshot,
      statusMessage = "Rolled back transaction: $reason",
      isError = true,
    )
  }

  /**
   * Executes an AI turn consisting of one or more [EditOp] batches (e.g. sequential `add_node` tool calls)
   * grouped into a SINGLE undo entry, enforcing the AI remote-image policy (`allowAiRemoteImages`, default false).
   */
  fun executeAiTurnTransaction(
    opBatches: List<List<EditOp>>,
    description: String,
  ): Result<Screen> {
    val current = _uiState.value
    val allowRemote = current.allowAiRemoteImages
    beginUndoTransaction()
    var workingScreen = current.currentScreen
    for (batch in opBatches) {
      val stepRes = applyOps(workingScreen, batch, registry, allowRemoteImages = allowRemote)
      if (stepRes.isFailure) {
        val errMessage = stepRes.exceptionOrNull()?.message ?: "AI op failed"
        activeTransactionSnapshot = null
        _uiState.update {
          it.copy(
            statusMessage = "AI Turn Rejected (screen unchanged): $errMessage",
            isStatusError = true,
          )
        }
        return Result.failure(stepRes.exceptionOrNull() ?: IllegalArgumentException(errMessage))
      }
      workingScreen = stepRes.getOrThrow()
    }
    val initialSnapshot = activeTransactionSnapshot
    activeTransactionSnapshot = null
    if (initialSnapshot != null && initialSnapshot != workingScreen) {
      undoStack.push(initialSnapshot)
    }
    commitScreenUpdate(
      updatedScreen = workingScreen,
      statusMessage = description,
      isError = false,
    )
    return Result.success(workingScreen)
  }

  /**
   * Sample Op 1: Add a Button to the selected container (or root Column).
   */
  fun applySampleAddButton() {
    val current = _uiState.value
    val screen = current.currentScreen
    val targetParentId = resolveTargetContainerId(screen, current.selectedNodeId)
    val newId = "sample_btn_${sampleOpCounter++}"
    val op =
      EditOp.AddNode(
        parentId = targetParentId,
        index = -1,
        node =
          Node(
            id = newId,
            type = "Button",
            props =
              buildJsonObject {
                put("width", JsonPrimitive("fill"))
                put("text", JsonPrimitive("Action Button #$sampleOpCounter"))
                put("variant", JsonPrimitive(if (sampleOpCounter % 2 == 0) "tonal" else "outlined"))
                put("enabled", JsonPrimitive(true))
              },
          ),
      )
    executeUserAtomicOps(listOf(op), "Added Button '$newId' into '$targetParentId'.")
  }

  /**
   * Sample Op 2: Update the title Text node's content and styling.
   */
  fun applySampleUpdateText() {
    val current = _uiState.value
    val screen = current.currentScreen
    val targetId =
      current.selectedNodeId?.takeIf { findNode(screen.root, it)?.type == "Text" }
        ?: findFirstNodeByType(screen.root, "Text")?.id
        ?: "title_text"

    val nextCount = sampleOpCounter++
    val op =
      EditOp.UpdateProps(
        nodeId = targetId,
        props =
          buildJsonObject {
            put("text", JsonPrimitive("Workspace Sign-In (Rev #$nextCount)"))
            put("color", JsonPrimitive(if (nextCount % 2 == 0) "#4F46E5" else "#0F172A"))
          },
      )
    executeUserAtomicOps(listOf(op), "Updated Text props on '$targetId'.")
  }

  /**
   * Sample Op 3: Delete the currently selected node (or last leaf node).
   */
  fun applySampleDeleteNode() {
    val current = _uiState.value
    val screen = current.currentScreen
    val targetId =
      current.selectedNodeId
        ?: screen.root.children.lastOrNull()?.id
        ?: screen.root.id

    val op = EditOp.DeleteNode(nodeId = targetId)
    executeUserAtomicOps(listOf(op), "Deleted node '$targetId'.")
  }

  /**
   * Adds tall scrollable content + a user-originated remote https image warning example.
   */
  fun applySampleTallScrollContent() {
    val current = _uiState.value
    val rootId = current.currentScreen.root.id
    val batchId = sampleOpCounter++
    val cardId = "tall_card_$batchId"
    val ops =
      listOf(
        EditOp.AddNode(
          parentId = rootId,
          index = -1,
          node =
            Node(
              id = cardId,
              type = "Card",
              props =
                buildJsonObject {
                  put("width", JsonPrimitive("fill"))
                  put("padding", JsonPrimitive(16))
                  put("spacing", JsonPrimitive(12))
                  put("elevation", JsonPrimitive(3))
                  put("mainAxis", JsonPrimitive("start"))
                  put("crossAxis", JsonPrimitive("stretch"))
                },
              children =
                listOf(
                  Node(
                    id = "tall_title_$batchId",
                    type = "Text",
                    props =
                      buildJsonObject {
                        put("text", JsonPrimitive("Scrollable Section #$batchId"))
                        put("fontSize", JsonPrimitive(18))
                        put("fontWeight", JsonPrimitive("bold"))
                      },
                  ),
                  Node(
                    id = "tall_img_$batchId",
                    type = "Image",
                    props =
                      buildJsonObject {
                        put("width", JsonPrimitive("fill"))
                        put("height", JsonPrimitive("140dp"))
                        put("src", JsonPrimitive("https://picsum.photos/390/140"))
                        put("label", JsonPrimitive("Remote Hero Banner #$batchId"))
                      },
                  ),
                  Node(
                    id = "tall_desc_$batchId",
                    type = "Text",
                    props =
                      buildJsonObject {
                        put(
                          "text",
                          JsonPrimitive(
                            "This block extends the vertical height beyond 844px so you can verify full-scroll PNG capture and remote image warnings."
                          ),
                        )
                        put("fontSize", JsonPrimitive(13))
                        put("color", JsonPrimitive("#475569"))
                      },
                  ),
                  Node(
                    id = "tall_spacer_$batchId",
                    type = "Spacer",
                    props = buildJsonObject { put("size", JsonPrimitive(120)) },
                  ),
                  Node(
                    id = "tall_btn_$batchId",
                    type = "Button",
                    props =
                      buildJsonObject {
                        put("width", JsonPrimitive("fill"))
                        put("text", JsonPrimitive("Bottom Action #$batchId"))
                        put("variant", JsonPrimitive("filled"))
                      },
                  ),
                ),
            ),
        )
      )
    executeUserAtomicOps(ops, "Added tall scrollable section '$cardId' (with 1 remote https image).")
  }

  /**
   * Exercises FakeAiProvider in Function-Calling mode (builds a 3-level subtree via 6 sequential `add_node` calls)
   * grouped into ONE single Undo transaction entry (Item 17).
   */
  fun runFakeAiThreeLevelTree() {
    viewModelScope.launch {
      val current = _uiState.value
      fakeAiProvider.mode = FakeAiProvider.Mode.FunctionCallingThreeLevelTree
      val result =
        fakeAiProvider.generateOps(
          AiRequest(
            userPrompt = "Add a 3-level SSO quick access card with icon, heading, and button",
            currentScreen = current.currentScreen,
            selectedNodeId = current.selectedNodeId,
          )
        )
      when (result) {
        is AiResult.Success -> {
          // Execute each tool call sequentially within a single Undo transaction
          val sequentialBatches = result.ops.map { listOf(it) }
          executeAiTurnTransaction(sequentialBatches, result.sourceDescription)
        }
        is AiResult.Failure ->
          _uiState.update {
            it.copy(statusMessage = "AI Provider Error: ${result.message}", isStatusError = true)
          }
      }
    }
  }

  /**
   * Exercises FakeAiProvider in Text-Fallback JSON array mode within a single Undo transaction.
   */
  fun runFakeAiTextFallback() {
    viewModelScope.launch {
      val current = _uiState.value
      fakeAiProvider.mode = FakeAiProvider.Mode.TextFallbackJsonArray
      val result =
        fakeAiProvider.generateOps(
          AiRequest(
            userPrompt = "Update the title text and add a magic link button",
            currentScreen = current.currentScreen,
            selectedNodeId = current.selectedNodeId,
          )
        )
      when (result) {
        is AiResult.Success ->
          executeAiTurnTransaction(listOf(result.ops), result.sourceDescription)
        is AiResult.Failure ->
          _uiState.update {
            it.copy(statusMessage = "AI Fallback Error: ${result.message}", isStatusError = true)
          }
      }
    }
  }

  /**
   * Injects an unknown component node directly into the preview state (bypassing applyOps gate)
   * when the user explicitly clicks "Test Unknown Type".
   */
  fun injectUnknownComponentForDiagnostic() {
    val current = _uiState.value
    undoStack.push(current.currentScreen)
    val unknownNode =
      Node(
        id = "unknown_widget_${sampleOpCounter++}",
        type = "ChartCarousel3D",
        props = buildJsonObject {},
      )
    val updatedRoot =
      current.currentScreen.root.copy(children = current.currentScreen.root.children + unknownNode)
    val updatedScreen = current.currentScreen.copy(root = updatedRoot)
    val errors = Validator.validate(updatedScreen, registry)
    commitScreenUpdate(
      updatedScreen = updatedScreen,
      statusMessage =
        "Injected unvalidated 'ChartCarousel3D' node: rendered red placeholder safely & caught ${errors.size} validation error(s).",
      isError = true,
    )
  }

  fun runValidationCheck() {
    val current = _uiState.value
    val errors = Validator.validate(current.currentScreen, registry)
    val msg =
      if (errors.isEmpty()) {
        "Validation passed: 0 errors across all nodes and props."
      } else {
        "Validation found ${errors.size} error(s): ${errors.first()}"
      }
    _uiState.update {
      it.copy(
        validationErrors = errors,
        statusMessage = msg,
        isStatusError = errors.isNotEmpty(),
      )
    }
  }

  fun undo() {
    val current = _uiState.value
    val previous = undoStack.undo(current.currentScreen)
    if (previous != null) {
      commitScreenUpdate(
        updatedScreen = previous,
        statusMessage = "Undo applied (${undoStack.undoCount} undo / ${undoStack.redoCount} redo available).",
        isError = false,
      )
    }
  }

  fun redo() {
    val current = _uiState.value
    val next = undoStack.redo(current.currentScreen)
    if (next != null) {
      commitScreenUpdate(
        updatedScreen = next,
        statusMessage = "Redo applied (${undoStack.undoCount} undo / ${undoStack.redoCount} redo available).",
        isError = false,
      )
    }
  }

  fun resetToSampleLogin() {
    val current = _uiState.value
    undoStack.push(current.currentScreen)
    val fresh = SampleProjectFactory.createLoginScreen()
    commitScreenUpdate(
      updatedScreen = fresh,
      statusMessage = "Reset screen to default 'Login' sample.",
      isError = false,
    )
  }

  fun exportHtmlAndShare(activity: Activity) {
    viewModelScope.launch {
      _uiState.update { it.copy(isExporting = true) }
      val current = _uiState.value
      val result =
        ScreenExporter.exportHtmlFile(
          context = activity,
          screen = current.currentScreen,
          theme = current.project.theme,
          registry = registry,
        )
      result.fold(
        onSuccess = { exportFile ->
          val warnNote =
            if (exportFile.warnings.isNotEmpty()) {
              " (${exportFile.warnings.joinToString()})"
            } else {
              ""
            }
          _uiState.update {
            it.copy(
              isExporting = false,
              exportWarnings = exportFile.warnings,
              statusMessage = "Exported ${exportFile.file.name}$warnNote",
              isStatusError = false,
            )
          }
          ScreenExporter.shareExportedFile(activity, exportFile)
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isExporting = false,
              statusMessage = "Export HTML failed: ${err.message}",
              isStatusError = true,
            )
          }
        },
      )
    }
  }

  fun exportPngAndShare(activity: Activity) {
    viewModelScope.launch {
      val current = _uiState.value
      val scale = current.exportScale
      _uiState.update {
        it.copy(
          isExporting = true,
          statusMessage = "Rendering clean Export-mode HTML in dedicated WebView at ${scale.label} scale...",
          isStatusError = false,
        )
      }
      val result =
        ScreenExporter.exportPng(
          activity = activity,
          screen = current.currentScreen,
          theme = current.project.theme,
          registry = registry,
          scale = scale,
        )
      result.fold(
        onSuccess = { exportFile ->
          _uiState.update {
            it.copy(
              isExporting = false,
              exportWarnings = exportFile.warnings,
              statusMessage = "Exported full-scroll PNG at ${scale.label} (${exportFile.file.name}, ${exportFile.file.length() / 1024} KB).",
              isStatusError = false,
            )
          }
          ScreenExporter.shareExportedFile(activity, exportFile)
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isExporting = false,
              statusMessage = "Export PNG failed: ${err.message}",
              isStatusError = true,
            )
          }
        },
      )
    }
  }

  fun exportBundleAndShare(activity: Activity) {
    viewModelScope.launch {
      val current = _uiState.value
      val scale = current.exportScale
      _uiState.update {
        it.copy(
          isExporting = true,
          statusMessage = "Packaging clean .html and ${scale.label} full-height .png into .zip bundle...",
          isStatusError = false,
        )
      }
      val result =
        ScreenExporter.exportBundle(
          activity = activity,
          screen = current.currentScreen,
          theme = current.project.theme,
          registry = registry,
          scale = scale,
        )
      result.fold(
        onSuccess = { exportFile ->
          _uiState.update {
            it.copy(
              isExporting = false,
              exportWarnings = exportFile.warnings,
              statusMessage = "Exported ZIP bundle (${exportFile.file.name}, ${exportFile.file.length() / 1024} KB).",
              isStatusError = false,
            )
          }
          ScreenExporter.shareExportedFile(activity, exportFile)
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isExporting = false,
              statusMessage = "Export Bundle failed: ${err.message}",
              isStatusError = true,
            )
          }
        },
      )
    }
  }

  /**
   * User-originated edits always allow remote `https://` images (`allowRemoteImages = true`).
   */
  private fun executeUserAtomicOps(ops: List<EditOp>, successDescription: String) {
    val current = _uiState.value
    val result = applyOps(current.currentScreen, ops, registry, allowRemoteImages = true)
    result.fold(
      onSuccess = { newScreen ->
        if (activeTransactionSnapshot == null) {
          undoStack.push(current.currentScreen)
        }
        commitScreenUpdate(
          updatedScreen = newScreen,
          statusMessage = successDescription,
          isError = false,
        )
      },
      onFailure = { err ->
        _uiState.update {
          it.copy(
            statusMessage = "Atomic Reject (screen unchanged): ${err.message}",
            isStatusError = true,
          )
        }
      },
    )
  }

  private fun commitScreenUpdate(
    updatedScreen: Screen,
    statusMessage: String,
    isError: Boolean,
  ) {
    val current = _uiState.value
    val updatedProject =
      current.project.copy(
        screens = listOf(updatedScreen),
        updatedAt = System.currentTimeMillis(),
      )
    if (autoLoadFromRepository) {
      viewModelScope.launch {
        repository.save(updatedProject)
      }
    }
    refreshDerivedState(
      project = updatedProject,
      screen = updatedScreen,
      statusMessage = statusMessage,
      isError = isError,
    )
  }

  private fun refreshDerivedState(
    project: Project,
    screen: Screen,
    statusMessage: String,
    isError: Boolean,
  ) {
    val current = _uiState.value
    val selectedId =
      current.selectedNodeId?.takeIf { findNode(screen.root, it) != null } ?: screen.root.id
    val canvasHtml =
      HtmlRenderer.renderScreen(
        screen = screen,
        theme = project.theme,
        registry = registry,
        mode = current.renderMode,
        selectedNodeId = selectedId,
      )
    val exportRes = ScreenExporter.exportHtml(screen, project.theme, registry)
    val rawJson = AppJson.instance.encodeToString(Screen.serializer(), screen)
    val errors = Validator.validate(screen, registry)

    _uiState.update {
      it.copy(
        project = project,
        currentScreen = screen,
        selectedNodeId = selectedId,
        canUndo = undoStack.canUndo,
        canRedo = undoStack.canRedo,
        undoCount = undoStack.undoCount,
        redoCount = undoStack.redoCount,
        validationErrors = errors,
        canvasHtml = canvasHtml,
        exportHtmlPreview = exportRes.html,
        exportWarnings = exportRes.warnings,
        rawScreenJson = rawJson,
        statusMessage = statusMessage,
        isStatusError = isError,
      )
    }
  }

  private fun resolveTargetContainerId(screen: Screen, selectedId: String?): String {
    if (selectedId != null) {
      val selectedNode = findNode(screen.root, selectedId)
      if (selectedNode != null && registry.get(selectedNode.type)?.canHaveChildren == true) {
        return selectedNode.id
      }
    }
    return screen.root.id
  }

  private fun findFirstNodeByType(node: Node, type: String): Node? {
    if (node.type == type) return node
    for (child in node.children) {
      val found = findFirstNodeByType(child, type)
      if (found != null) return found
    }
    return null
  }

  class Factory(private val repository: FileProjectRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return DebugViewModel(repository) as T
    }
  }
}
