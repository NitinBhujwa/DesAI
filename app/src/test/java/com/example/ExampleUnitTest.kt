package com.example

import android.webkit.JavascriptInterface
import com.example.ai.AiRequest
import com.example.ai.AiResponseParser
import com.example.ai.AiResult
import com.example.ai.FakeAiProvider
import com.example.ai.GeminiToolAdapter
import com.example.ai.OpenAiToolAdapter
import com.example.ai.PromptBuilder
import com.example.debug.DebugViewModel
import com.example.export.ExportJsBridge
import com.example.export.ExportScale
import com.example.export.ScreenExporter
import com.example.model.AppJson
import com.example.model.Node
import com.example.model.Project
import com.example.model.Screen
import com.example.model.ThemeTokens
import com.example.ops.EditOp
import com.example.ops.applyOps
import com.example.ops.findNode
import com.example.registry.ComponentRegistry
import com.example.registry.HtmlRenderMode
import com.example.render.HtmlRenderer
import com.example.storage.FileProjectRepository
import com.example.storage.SampleProjectFactory
import com.example.undo.UndoStack
import com.example.validator.Validator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase0FoundationTest {

  private val registry = ComponentRegistry.Default

  // =========================================================================
  // Existing Phase 0 Core Tests
  // =========================================================================

  @Test
  fun `models and EditOps survive JSON round trip`() {
    val sampleProject = SampleProjectFactory.createLoginSampleProject()
    val encodedProject = AppJson.instance.encodeToString(Project.serializer(), sampleProject)
    val decodedProject = AppJson.instance.decodeFromString(Project.serializer(), encodedProject)
    assertEquals(sampleProject, decodedProject)
    assertTrue(decodedProject.screens.first().scrollable)

    val ops: List<EditOp> =
      listOf(
        EditOp.AddNode(
          parentId = "root_column",
          index = 0,
          node =
            Node(
              id = "added_text",
              type = "Text",
              props = buildJsonObject { put("text", JsonPrimitive("Hello")) },
            ),
        ),
        EditOp.UpdateProps(
          nodeId = "title_text",
          props = buildJsonObject { put("fontSize", JsonPrimitive(28)) },
        ),
        EditOp.MoveNode(nodeId = "login_button", newParentId = "header_row", index = 1),
        EditOp.DeleteNode(nodeId = "form_spacer"),
        EditOp.ReplaceScreenRoot(
          node = Node(id = "new_root", type = "Column", props = buildJsonObject {})
        ),
      )

    val encodedOps = AppJson.instance.encodeToString(ListSerializer(EditOp.serializer()), ops)
    assertTrue(encodedOps.contains("\"op\": \"add_node\""))
    assertTrue(encodedOps.contains("\"op\": \"update_props\""))
    assertTrue(encodedOps.contains("\"op\": \"move_node\""))
    assertTrue(encodedOps.contains("\"op\": \"delete_node\""))
    assertTrue(encodedOps.contains("\"op\": \"replace_screen_root\""))

    val decodedOps = AppJson.instance.decodeFromString(ListSerializer(EditOp.serializer()), encodedOps)
    assertEquals(ops, decodedOps)
  }

  @Test
  fun `applyOps succeeds on valid batch of operations`() {
    val initialScreen = SampleProjectFactory.createLoginScreen()
    val ops =
      listOf(
        EditOp.AddNode(
          parentId = "root_column",
          index = -1,
          node =
            Node(
              id = "help_btn",
              type = "Button",
              props =
                buildJsonObject {
                  put("text", JsonPrimitive("Need Help?"))
                  put("variant", JsonPrimitive("outlined"))
                },
            ),
        ),
        EditOp.UpdateProps(
          nodeId = "title_text",
          props = buildJsonObject { put("text", JsonPrimitive("Updated Sign In")) },
        ),
        EditOp.DeleteNode(nodeId = "form_spacer"),
      )

    val result = applyOps(initialScreen, ops, registry)
    assertTrue(result.isSuccess)
    val updated = result.getOrThrow()
    assertNotNull(findNode(updated.root, "help_btn"))
    assertNull(findNode(updated.root, "form_spacer"))
    assertEquals(
      "Updated Sign In",
      findNode(updated.root, "title_text")?.props?.get("text")?.let { (it as JsonPrimitive).content },
    )
  }

  @Test
  fun `applyOps is atomic and leaves screen unchanged when a later op fails`() {
    val initialScreen = SampleProjectFactory.createLoginScreen()
    val snapshotBefore = AppJson.instance.encodeToString(Screen.serializer(), initialScreen)

    val ops =
      listOf(
        EditOp.UpdateProps(
          nodeId = "title_text",
          props = buildJsonObject { put("text", JsonPrimitive("Mutated First")) },
        ),
        EditOp.DeleteNode(nodeId = "non_existent_node_999"),
      )

    val result = applyOps(initialScreen, ops, registry)
    assertTrue(result.isFailure)
    val errorMsg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue(errorMsg.contains("non_existent_node_999"))

    val snapshotAfter = AppJson.instance.encodeToString(Screen.serializer(), initialScreen)
    assertEquals(snapshotBefore, snapshotAfter)
  }

  @Test
  fun `applyOps rejects moving a node into itself or its own descendant`() {
    val initialScreen = SampleProjectFactory.createLoginScreen()

    val withNestedBox =
      applyOps(
          initialScreen,
          listOf(
            EditOp.AddNode(
              parentId = "header_row",
              index = -1,
              node = Node(id = "nested_box", type = "Box"),
            )
          ),
          registry,
        )
        .getOrThrow()

    val moveResult =
      applyOps(
        withNestedBox,
        listOf(EditOp.MoveNode(nodeId = "header_row", newParentId = "nested_box", index = 0)),
        registry,
      )

    assertTrue(moveResult.isFailure)
    assertTrue(
      moveResult.exceptionOrNull()?.message.orEmpty().contains("descendant", ignoreCase = true)
    )
  }

  @Test
  fun `applyOps rejects deleting the root node`() {
    val initialScreen = SampleProjectFactory.createLoginScreen()
    val result =
      applyOps(
        initialScreen,
        listOf(EditOp.DeleteNode(nodeId = initialScreen.root.id)),
        registry,
      )
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("root", ignoreCase = true))
  }

  @Test
  fun `validator catches unknown type, unknown prop, wrong prop type, disallowed value, leaf children, and duplicate IDs`() {
    val invalidRoot =
      Node(
        id = "root_col",
        type = "Column",
        props =
          buildJsonObject {
            put("unknownPropKey", JsonPrimitive("bad"))
            put("padding", JsonPrimitive("not_an_int"))
            put("mainAxis", JsonPrimitive("diagonal_invalid"))
          },
        children =
          listOf(
            Node(id = "dup_id", type = "FancyHologram", props = buildJsonObject {}),
            Node(
              id = "dup_id",
              type = "Button",
              props = buildJsonObject { put("text", JsonPrimitive("Click")) },
              children =
                listOf(
                  Node(
                    id = "leaf_child",
                    type = "Text",
                    props = buildJsonObject { put("color", JsonPrimitive("javascript:alert(1)")) },
                  )
                ),
            ),
            Node(
              id = "bad_img",
              type = "Image",
              props = buildJsonObject { put("src", JsonPrimitive("http://insecure.example.com/a.png")) },
            ),
          ),
      )

    val screen = Screen(id = "s1", name = "Invalid", root = invalidRoot)
    val errors = Validator.validate(screen, registry)

    val messages = errors.joinToString("\n") { it.toString() }
    assertTrue("Should catch unknown prop", messages.contains("Unknown prop 'unknownPropKey'"))
    assertTrue("Should catch wrong prop type", messages.contains("expects an Int value"))
    assertTrue("Should catch disallowed enum value", messages.contains("not allowed"))
    assertTrue("Should catch unknown component type", messages.contains("Unknown component type 'FancyHologram'"))
    assertTrue("Should catch duplicate ID", messages.contains("Duplicate node ID 'dup_id'"))
    assertTrue("Should catch children on leaf component", messages.contains("leaf component and cannot have children"))
    assertTrue("Should catch invalid hex color", messages.contains("valid hex color"))
    assertTrue("Should catch invalid Image src scheme", messages.contains("Image 'src' must be empty or use 'https://' or 'data:image/'"))
  }

  @Test
  fun `UndoStack enforces max 50 entries and supports undo and redo`() {
    val stack = UndoStack<Int>(maxEntries = 50)
    assertFalse(stack.canUndo)
    assertFalse(stack.canRedo)

    var current = 0
    for (step in 1..60) {
      stack.push(current)
      current = step
    }

    assertEquals(50, stack.undoCount)
    assertTrue(stack.canUndo)
    assertFalse(stack.canRedo)

    val prev = stack.undo(current)
    assertEquals(59, prev)
    current = prev!!
    assertTrue(stack.canRedo)

    val redone = stack.redo(current)
    assertEquals(60, redone)
  }

  @Test
  fun `script tag inside Text prop and attributes is strictly HTML-escaped`() {
    val maliciousPayload = """<script>alert("xss")</script><img src=x onerror=alert(1)>"""
    val screen =
      Screen(
        id = "xss_screen",
        name = """<script>evilTitle()</script>""",
        root =
          Node(
            id = "root_col",
            type = "Column",
            children =
              listOf(
                Node(
                  id = "xss_text",
                  type = "Text",
                  props = buildJsonObject { put("text", JsonPrimitive(maliciousPayload)) },
                ),
                Node(
                  id = "xss_btn",
                  type = "Button",
                  props = buildJsonObject { put("text", JsonPrimitive(maliciousPayload)) },
                ),
              ),
          ),
      )

    val exportHtml =
      HtmlRenderer.renderScreen(
        screen = screen,
        theme = ThemeTokens(),
        registry = registry,
        mode = HtmlRenderMode.Export,
      )

    assertFalse("Raw <script> must never appear in Export HTML", exportHtml.contains("<script>"))
    assertTrue(
      "Escaped &lt;script&gt; must appear in rendered output",
      exportHtml.contains("&lt;script&gt;alert(&quot;xss&quot;)&lt;/script&gt;"),
    )
  }

  @Test
  fun `every registered component renders in Edit, Preview, and Export modes`() {
    val allComponentsNodes =
      listOf(
        Node(id = "c_row", type = "Row"),
        Node(id = "c_box", type = "Box"),
        Node(id = "c_card", type = "Card"),
        Node(
          id = "c_btn",
          type = "Button",
          props = buildJsonObject { put("text", JsonPrimitive("Tap")) },
        ),
        Node(
          id = "c_tf",
          type = "TextField",
          props = buildJsonObject { put("label", JsonPrimitive("Name")) },
        ),
        Node(
          id = "c_txt",
          type = "Text",
          props = buildJsonObject { put("text", JsonPrimitive("Label")) },
        ),
        Node(
          id = "c_img_empty",
          type = "Image",
          props = buildJsonObject { put("label", JsonPrimitive("Placeholder")) },
        ),
        Node(
          id = "c_icon",
          type = "Icon",
          props = buildJsonObject { put("name", JsonPrimitive("star")) },
        ),
        Node(
          id = "c_spacer",
          type = "Spacer",
          props = buildJsonObject { put("size", JsonPrimitive(12)) },
        ),
      )

    val screen =
      Screen(
        id = "all_comps",
        name = "All 10 Components",
        root = Node(id = "c_col", type = "Column", children = allComponentsNodes),
        scrollable = true,
      )

    assertEquals(10, registry.all().size)
    assertTrue(Validator.validate(screen, registry).isEmpty())

    for (mode in HtmlRenderMode.entries) {
      val html =
        HtmlRenderer.renderScreen(
          screen = screen,
          theme = ThemeTokens(),
          registry = registry,
          mode = mode,
          selectedNodeId = "c_btn",
        )
      assertTrue("HTML in $mode must contain doctype", html.startsWith("<!DOCTYPE html>"))
      assertTrue("HTML in $mode must include shared font stack", html.contains(HtmlRenderer.SHARED_FONT_STACK))
      assertTrue("HTML in $mode must render button", html.contains("ui-button"))
      assertTrue("HTML in $mode must render textfield", html.contains("ui-textfield-input"))
      assertTrue("HTML in $mode must render inline svg icon", html.contains("<svg"))
    }
  }

  @Test
  fun `Export mode contains no script tag and no data-node attributes and uses natural scroll height`() {
    val screen = SampleProjectFactory.createLoginScreen()
    val editHtml =
      HtmlRenderer.renderScreen(
        screen = screen,
        theme = ThemeTokens(),
        registry = registry,
        mode = HtmlRenderMode.Edit,
        selectedNodeId = "login_button",
      )
    val exportHtml =
      HtmlRenderer.renderScreen(
        screen = screen,
        theme = ThemeTokens(),
        registry = registry,
        mode = HtmlRenderMode.Export,
      )

    assertTrue(editHtml.contains("<script>"))
    assertTrue(editHtml.contains("data-node-id="))
    assertTrue(editHtml.contains("overflow-y: auto;"))

    assertFalse(exportHtml.contains("<script"))
    assertFalse(exportHtml.contains("data-node-id"))
    assertFalse(exportHtml.contains("data-node-type"))
    assertTrue(exportHtml.contains("min-height: 844px;"))
    assertTrue(exportHtml.contains("overflow: visible;"))
  }

  @Test
  fun `same tree always produces identical HTML deterministically`() {
    val screen = SampleProjectFactory.createLoginScreen()
    val theme = ThemeTokens()

    val html1 = HtmlRenderer.renderScreen(screen, theme, registry, HtmlRenderMode.Export)
    val html2 = HtmlRenderer.renderScreen(screen, theme, registry, HtmlRenderMode.Export)
    assertEquals(html1, html2)

    val edit1 = HtmlRenderer.renderScreen(screen, theme, registry, HtmlRenderMode.Edit, "title_text")
    val edit2 = HtmlRenderer.renderScreen(screen, theme, registry, HtmlRenderMode.Edit, "title_text")
    assertEquals(edit1, edit2)
  }

  @Test
  fun `exportHtml reports warnings for remote https images and zero warnings for data URIs`() {
    val baseScreen = SampleProjectFactory.createLoginScreen()
    val cleanRes = ScreenExporter.exportHtml(baseScreen, ThemeTokens(), registry)
    assertTrue(cleanRes.warnings.isEmpty())

    val withRemoteAndDataImages =
      applyOps(
          baseScreen,
          listOf(
            EditOp.AddNode(
              parentId = "root_column",
              index = -1,
              node =
                Node(
                  id = "remote_img",
                  type = "Image",
                  props = buildJsonObject { put("src", JsonPrimitive("https://example.com/banner.png")) },
                ),
            ),
            EditOp.AddNode(
              parentId = "root_column",
              index = -1,
              node =
                Node(
                  id = "data_img",
                  type = "Image",
                  props =
                    buildJsonObject {
                      put(
                        "src",
                        JsonPrimitive(
                          "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                        ),
                      )
                    },
                ),
            ),
          ),
          registry,
        )
        .getOrThrow()

    val warnRes = ScreenExporter.exportHtml(withRemoteAndDataImages, ThemeTokens(), registry)
    assertEquals(1, warnRes.warnings.size)
    assertTrue(warnRes.warnings.first().contains("1 remote image"))
  }

  @Test
  fun `AI tool adapters are non-recursive, omit ref and defs, and exclude replace_screen_root`() {
    val tools = PromptBuilder.buildNeutralTools(registry)
    val toolNames = tools.map { it.name }
    assertTrue(toolNames.containsAll(listOf("add_node", "update_props", "move_node", "delete_node")))
    assertTrue(toolNames.containsAll(listOf("get_screen", "get_component_catalog", "validate_screen")))
    assertFalse("replace_screen_root must not be a function-calling tool", toolNames.contains("replace_screen_root"))

    val addNodeTool = tools.first { it.name == "add_node" }
    assertFalse("add_node must not have children property", addNodeTool.properties.containsKey("children"))

    val geminiJsonStr = GeminiToolAdapter.toFunctionDeclarationsJson(tools).toString()
    val openAiJsonStr = OpenAiToolAdapter.toToolsJson(tools).toString()

    assertFalse(geminiJsonStr.contains("\$ref"))
    assertFalse(geminiJsonStr.contains("\$defs"))
    assertFalse(openAiJsonStr.contains("\$ref"))
    assertFalse(openAiJsonStr.contains("\$defs"))
  }

  @Test
  fun `FakeAiProvider builds 3-level tree via sequential add_node calls and supports text fallback`() =
    runTest {
      val initialScreen = SampleProjectFactory.createLoginScreen()
      val provider = FakeAiProvider(FakeAiProvider.Mode.FunctionCallingThreeLevelTree)

      val treeRes = provider.generateOps(AiRequest("Add 3-level card", initialScreen))
      assertTrue(treeRes is AiResult.Success)
      val treeOps = (treeRes as AiResult.Success).ops
      assertEquals(6, treeOps.size)

      val afterTreeScreen = applyOps(initialScreen, treeOps, registry).getOrThrow()
      val cardNode = findNode(afterTreeScreen.root, "ai_card_1")
      assertNotNull(cardNode)
      val innerCol = findNode(cardNode!!, "ai_card_col_1")
      assertNotNull(innerCol)
      val badgeRow = findNode(innerCol!!, "ai_badge_row_1")
      assertNotNull(badgeRow)
      val iconNode = findNode(badgeRow!!, "ai_icon_1")
      assertNotNull(iconNode)

      provider.mode = FakeAiProvider.Mode.TextFallbackJsonArray
      val fallbackRes = provider.generateOps(AiRequest("Fallback update", afterTreeScreen, "title_text"))
      assertTrue(fallbackRes is AiResult.Success)
      val fallbackOps = (fallbackRes as AiResult.Success).ops
      val afterFallbackScreen = applyOps(afterTreeScreen, fallbackOps, registry).getOrThrow()
      assertNotNull(findNode(afterFallbackScreen.root, "fallback_btn_2"))
    }

  @Test
  fun `FileProjectRepository saves, lists, gets, and deletes projects in JSON storage`() = runTest {
    val tempDir = Files.createTempDirectory("repo_test").toFile()
    try {
      val repo = FileProjectRepository(tempDir)
      val seeded = repo.ensureSampleProject()
      assertEquals(SampleProjectFactory.SAMPLE_PROJECT_ID, seeded.id)

      val fetched = repo.get(seeded.id)
      assertEquals(seeded, fetched)
      assertEquals(1, repo.list().size)

      assertTrue(repo.delete(seeded.id))
      assertNull(repo.get(seeded.id))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // =========================================================================
  // 18 Code Review Regression Tests (Items 1 through 18)
  // =========================================================================

  // Item 1 Regression Test
  @Test
  fun `regression_item_1_component_defaults_precede_user_props_so_row_crossAxis_and_card_padding_override_defaults`() {
    val rowNode =
      Node(
        id = "test_row",
        type = "Row",
        props =
          buildJsonObject {
            put("crossAxis", JsonPrimitive("end"))
            put("mainAxis", JsonPrimitive("space_between"))
          },
      )
    val cardNode =
      Node(
        id = "test_card",
        type = "Card",
        props = buildJsonObject { put("padding", JsonPrimitive(32)) },
      )
    val screen =
      Screen(
        id = "s_order",
        name = "Order",
        root = Node(id = "root", type = "Column", children = listOf(rowNode, cardNode)),
      )
    val html = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)

    // In Row, default "align-items: center" must come BEFORE user "align-items: flex-end"
    val defaultAlignIdx = html.indexOf("align-items: center")
    val userAlignIdx = html.indexOf("align-items: flex-end")
    assertTrue("Row default align-items must be present", defaultAlignIdx >= 0)
    assertTrue("User crossAxis align-items: flex-end must come AFTER default", userAlignIdx > defaultAlignIdx)

    // In Card, default "padding: 16px" must come BEFORE user "padding: 32px"
    val defaultPadIdx = html.indexOf("padding: 16px")
    val userPadIdx = html.indexOf("padding: 32px")
    assertTrue("Card default padding must be present", defaultPadIdx >= 0)
    assertTrue("User padding: 32px must come AFTER default padding: 16px", userPadIdx > defaultPadIdx)
  }

  // Item 2 Regression Test
  @Test
  fun `regression_item_2_disabled_button_omits_disabled_attribute_in_Edit_mode_and_keeps_it_in_Preview_and_Export`() {
    val screen =
      Screen(
        id = "s_btn",
        name = "DisabledBtn",
        root =
          Node(
            id = "root",
            type = "Column",
            children =
              listOf(
                Node(
                  id = "disabled_btn",
                  type = "Button",
                  props =
                    buildJsonObject {
                      put("text", JsonPrimitive("Cannot Submit"))
                      put("enabled", JsonPrimitive(false))
                    },
                )
              ),
          ),
      )

    val editHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Edit)
    val previewHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Preview)
    val exportHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)

    // Edit mode must NOT emit the native HTML `disabled` attribute, but shows disabled look & aria-disabled="true"
    assertFalse("Edit mode must not emit HTML disabled attribute", editHtml.contains(" disabled "))
    assertTrue("Edit mode must emit aria-disabled", editHtml.contains("""aria-disabled="true""""))
    assertTrue("Edit mode must keep disabled opacity", editHtml.contains("opacity: 0.55"))

    // Preview and Export modes MUST emit the real `disabled` attribute
    assertTrue("Preview mode must emit disabled attribute", previewHtml.contains(""" disabled aria-disabled="true""""))
    assertTrue("Export mode must emit disabled attribute", exportHtml.contains(""" disabled aria-disabled="true""""))
  }

  // Item 3 Regression Test
  @Test
  fun `regression_item_3_width_wrap_maps_to_fit_content_and_does_not_stretch_in_column`() {
    val screen =
      Screen(
        id = "s_wrap",
        name = "WrapBtn",
        root =
          Node(
            id = "root",
            type = "Column",
            children =
              listOf(
                Node(
                  id = "wrap_btn",
                  type = "Button",
                  props =
                    buildJsonObject {
                      put("width", JsonPrimitive("wrap"))
                      put("text", JsonPrimitive("Compact"))
                    },
                )
              ),
          ),
      )

    val html = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)
    assertTrue("width='wrap' must map to width: fit-content", html.contains("width: fit-content"))
    assertTrue("width='wrap' inside Column must set align-self: flex-start", html.contains("align-self: flex-start"))
  }

  // Item 4 Regression Test
  @Test
  fun `regression_item_4_height_fill_uses_flex_and_behaves_identically_in_Edit_and_Export_modes`() {
    val screen =
      Screen(
        id = "s_fill",
        name = "FillScreen",
        scrollable = true,
        root =
          Node(
            id = "root_col",
            type = "Column",
            props = buildJsonObject { put("height", JsonPrimitive("fill")) },
            children =
              listOf(
                Node(
                  id = "child_col",
                  type = "Column",
                  props = buildJsonObject { put("height", JsonPrimitive("fill")) },
                ),
                Node(
                  id = "child_row",
                  type = "Row",
                  children =
                    listOf(
                      Node(
                        id = "row_item",
                        type = "Box",
                        props = buildJsonObject { put("height", JsonPrimitive("fill")) },
                      )
                    ),
                ),
              ),
          ),
      )

    val editHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Edit)
    val exportHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)

    // Neither Edit nor Export uses broken height: 100% for fill; both use flex-based fill
    assertFalse("Must not use percentage height: 100%", editHtml.contains("height: 100%"))
    assertFalse("Must not use percentage height: 100%", exportHtml.contains("height: 100%"))
    assertTrue(editHtml.contains("#screen-viewport > .ui-node {\n      flex: 1 0 auto;"))
    assertTrue(exportHtml.contains("#screen-viewport > .ui-node {\n      flex: 1 0 auto;"))
    assertTrue(editHtml.contains("flex: 1 1 0%"))
    assertTrue(exportHtml.contains("flex: 1 1 0%"))
    assertTrue(editHtml.contains("align-self: stretch"))
    assertTrue(exportHtml.contains("align-self: stretch"))
  }

  // Item 5 Regression Test
  @Test
  fun `regression_item_5_mainAxis_and_crossAxis_replace_alignment_and_validator_rejects_legacy_alignment`() {
    val centeredScreen =
      Screen(
        id = "s_axes",
        name = "Axes",
        root =
          Node(
            id = "root_col",
            type = "Column",
            props =
              buildJsonObject {
                put("mainAxis", JsonPrimitive("center"))
                put("crossAxis", JsonPrimitive("center"))
              },
          ),
      )

    assertTrue("mainAxis and crossAxis must validate cleanly", Validator.validate(centeredScreen, registry).isEmpty())
    val html = HtmlRenderer.renderScreen(centeredScreen, ThemeTokens(), registry, HtmlRenderMode.Export)
    assertTrue(html.contains("justify-content: center"))
    assertTrue(html.contains("align-items: center"))

    // Legacy `alignment` prop must be rejected as an unknown prop by Validator
    val legacyScreen =
      Screen(
        id = "s_legacy",
        name = "Legacy",
        root =
          Node(
            id = "root_col",
            type = "Column",
            props = buildJsonObject { put("alignment", JsonPrimitive("center")) },
          ),
      )
    val errors = Validator.validate(legacyScreen, registry)
    assertTrue(errors.any { it.message.contains("Unknown prop 'alignment'") })
  }

  // Item 6 Regression Test
  @Test
  fun `regression_item_6_image_aspectRatio_is_applied_when_no_explicit_height_is_set`() {
    val screen =
      Screen(
        id = "s_img",
        name = "ImageAspect",
        root =
          Node(
            id = "root",
            type = "Column",
            children =
              listOf(
                Node(
                  id = "img_auto_h",
                  type = "Image",
                  props = buildJsonObject { put("aspectRatio", JsonPrimitive(2.5f)) },
                ),
                Node(
                  id = "img_fixed_h",
                  type = "Image",
                  props =
                    buildJsonObject {
                      put("height", JsonPrimitive("200dp"))
                      put("aspectRatio", JsonPrimitive(2.5f))
                    },
                ),
              ),
          ),
      )

    val html = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)
    assertTrue("Image without explicit height must include aspect-ratio: 2.5", html.contains("aspect-ratio: 2.5"))
    assertTrue("Image with explicit height must use height: 200px", html.contains("height: 200px"))
  }

  // Item 7 Regression Test
  @Test
  fun `regression_item_7_changing_theme_primary_updates_derived_tonal_and_focus_rgba_values`() {
    val screen =
      Screen(
        id = "s_theme",
        name = "ThemeTest",
        root =
          Node(
            id = "root",
            type = "Column",
            children =
              listOf(
                Node(
                  id = "tonal_btn",
                  type = "Button",
                  props = buildJsonObject { put("variant", JsonPrimitive("tonal")) },
                )
              ),
          ),
      )

    // #FF5722 -> RGB(255, 87, 34)
    val customTheme = ThemeTokens(primary = "#FF5722")
    val html = HtmlRenderer.renderScreen(screen, customTheme, registry, HtmlRenderMode.Export)

    assertTrue("Tonal color variable must derive from #FF5722 -> rgba(255, 87, 34, 0.12)", html.contains("--color-primary-tonal: rgba(255, 87, 34, 0.12);"))
    assertTrue("Focus ring variable must derive from #FF5722 -> rgba(255, 87, 34, 0.18)", html.contains("--color-primary-focus: rgba(255, 87, 34, 0.18);"))
    assertFalse("Export HTML must not contain hardcoded indigo rgba(79, 70, 229", html.contains("rgba(79, 70, 229"))
  }

  // Item 8 Regression Test
  @Test
  fun `regression_item_8_spacer_applies_size_on_parent_main_axis_only`() {
    val screen =
      Screen(
        id = "s_spacer",
        name = "SpacerAxis",
        root =
          Node(
            id = "col_parent",
            type = "Column",
            children =
              listOf(
                Node(
                  id = "col_spacer",
                  type = "Spacer",
                  props = buildJsonObject { put("size", JsonPrimitive(24)) },
                ),
                Node(
                  id = "row_parent",
                  type = "Row",
                  children =
                    listOf(
                      Node(
                        id = "row_spacer",
                        type = "Spacer",
                        props = buildJsonObject { put("size", JsonPrimitive(36)) },
                      )
                    ),
                ),
              ),
          ),
      )

    val html = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)
    assertTrue("Spacer in Column sets height: 24px; min-height: 24px", html.contains("height: 24px; min-height: 24px; flex-shrink: 0"))
    assertTrue("Spacer in Row sets width: 36px; min-width: 36px", html.contains("width: 36px; min-width: 36px; flex-shrink: 0"))
  }

  // Item 9 Regression Test
  @Test
  fun `regression_item_9_export_mode_centers_390px_viewport_horizontally`() {
    val screen = SampleProjectFactory.createLoginScreen()
    val exportHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Export)
    val editHtml = HtmlRenderer.renderScreen(screen, ThemeTokens(), registry, HtmlRenderMode.Edit)

    assertTrue("Export mode #screen-viewport must use margin: 0 auto;", exportHtml.contains("margin: 0 auto;"))
    assertTrue("Export mode html, body must allow 100% width", exportHtml.contains("width: 100%; min-width: 390px;"))
    assertFalse("Edit mode must not center with margin: 0 auto;", editHtml.contains("margin: 0 auto;"))
  }

  // Item 10 Regression Test
  @Test
  fun `regression_item_10_validator_rejects_exponent_NaN_Infinity_and_out_of_range_dimensions_and_numeric_props`() {
    val badNodes =
      listOf(
        Node(
          id = "n_exp",
          type = "Box",
          props = buildJsonObject { put("width", JsonPrimitive("1e10")) },
        ),
        Node(
          id = "n_nan",
          type = "Box",
          props = buildJsonObject { put("height", JsonPrimitive("NaN")) },
        ),
        Node(
          id = "n_inf",
          type = "Box",
          props = buildJsonObject { put("width", JsonPrimitive("Infinity")) },
        ),
        Node(
          id = "n_huge_dim",
          type = "Box",
          props = buildJsonObject { put("width", JsonPrimitive("5000dp")) },
        ),
        Node(
          id = "n_bad_pad",
          type = "Column",
          props =
            buildJsonObject {
              put("padding", JsonPrimitive(4500))
              put("spacing", JsonPrimitive(-5))
              put("weight", JsonPrimitive(250f))
            },
        ),
        Node(
          id = "n_bad_card",
          type = "Card",
          props = buildJsonObject { put("elevation", JsonPrimitive(99)) },
        ),
        Node(
          id = "n_bad_text",
          type = "Text",
          props = buildJsonObject { put("fontSize", JsonPrimitive(200)) },
        ),
        Node(
          id = "n_bad_spacer",
          type = "Spacer",
          props = buildJsonObject { put("size", JsonPrimitive(9000)) },
        ),
      )

    val screen =
      Screen(
        id = "s_bounds",
        name = "Bounds",
        root = Node(id = "root", type = "Column", children = badNodes),
      )
    val errors = Validator.validate(screen, registry)
    val errorNodeIds = errors.map { it.nodeId }.toSet()

    assertTrue(errorNodeIds.containsAll(listOf("n_exp", "n_nan", "n_inf", "n_huge_dim", "n_bad_pad", "n_bad_card", "n_bad_text", "n_bad_spacer")))
  }

  // Item 11 Regression Test
  @Test
  fun `regression_item_11_UpdateProps_removes_prop_when_value_is_null_while_AddNode_and_ReplaceScreenRoot_reject_null_props`() {
    val initialScreen = SampleProjectFactory.createLoginScreen()
    assertTrue(findNode(initialScreen.root, "root_column")!!.props.containsKey("padding"))

    // 1. UpdateProps with JsonNull removes 'padding'
    val afterRemove =
      applyOps(
          initialScreen,
          listOf(
            EditOp.UpdateProps(
              nodeId = "root_column",
              props = buildJsonObject { put("padding", JsonNull) },
            )
          ),
          registry,
        )
        .getOrThrow()

    assertFalse(
      "UpdateProps with null must remove the property key",
      findNode(afterRemove.root, "root_column")!!.props.containsKey("padding"),
    )

    // 2. AddNode with JsonNull prop must be rejected by Validator
    val addNullRes =
      applyOps(
        afterRemove,
        listOf(
          EditOp.AddNode(
            parentId = "root_column",
            index = -1,
            node =
              Node(
                id = "null_prop_btn",
                type = "Button",
                props = buildJsonObject { put("text", JsonNull) },
              ),
          )
        ),
        registry,
      )
    assertTrue("AddNode with null prop must fail validation", addNullRes.isFailure)

    // 3. ReplaceScreenRoot with JsonNull prop must be rejected by Validator
    val replaceNullRes =
      applyOps(
        afterRemove,
        listOf(
          EditOp.ReplaceScreenRoot(
            node =
              Node(
                id = "new_root",
                type = "Column",
                props = buildJsonObject { put("padding", JsonNull) },
              )
          )
        ),
        registry,
      )
    assertTrue("ReplaceScreenRoot with null prop must fail validation", replaceNullRes.isFailure)
  }

  // Item 12 Regression Test
  @Test
  fun `regression_item_12_validateExportDimensions_uses_fixed_scale_and_reports_css_height_and_scale_on_overflow`() {
    // At 2x scale: 390*2 = 780px width. CSS height 5000 -> 5000*2 = 10,000px height -> 7.8M pixels (< 24M limit)
    val ok2x = ScreenExporter.validateExportDimensions(cssHeight = 5000, scale = ExportScale.Scale2x)
    assertTrue(ok2x.isSuccess)
    assertEquals(780 to 10000, ok2x.getOrThrow())

    // At 3x scale: 390*3 = 1170px width. CSS height 9000 -> 9000*3 = 27,000px height -> 31.59M pixels (> 24M limit)
    val overflow3x = ScreenExporter.validateExportDimensions(cssHeight = 9000, scale = ExportScale.Scale3x)
    assertTrue(overflow3x.isFailure)
    val msg = overflow3x.exceptionOrNull()?.message.orEmpty()
    assertTrue("Error must state CSS height", msg.contains("CSS height=9000px"))
    assertTrue("Error must state scale used", msg.contains("scale=3x"))
  }

  // Item 15 Regression Test
  @Test
  fun `regression_item_15_ExportJsBridge_is_named_public_class_with_JavascriptInterface_methods`() {
    val clazz = ExportJsBridge::class.java
    assertFalse("ExportJsBridge must not be an anonymous class", clazz.isAnonymousClass)
    val onReadyMethod = clazz.getMethod("onReady", Int::class.javaPrimitiveType)
    val onFrameSettledMethod = clazz.getMethod("onFrameSettled")
    assertNotNull(onReadyMethod.getAnnotation(JavascriptInterface::class.java))
    assertNotNull(onFrameSettledMethod.getAnnotation(JavascriptInterface::class.java))
  }

  // Item 16 Regression Test
  @Test
  fun `regression_item_16_export_filenames_include_screen_id_suffix_and_stale_files_older_than_24h_are_deleted`() {
    // Non-Latin screen names sanitize to "screen" but have distinct screen IDs -> distinct filenames
    val nameA = ScreenExporter.buildExportBaseFileName("로그인 화면", "screen_alpha1")
    val nameB = ScreenExporter.buildExportBaseFileName("ログイン画面", "screen_beta22")
    assertTrue(nameA.startsWith("screen_"))
    assertTrue(nameB.startsWith("screen_"))
    assertFalse("Distinct screen IDs must never collide in export filenames", nameA == nameB)

    // Verify 24h stale export cleanup
    val tempDir = Files.createTempDirectory("exports_cleanup_test").toFile()
    try {
      val now = 1_800_000_000_000L
      val staleFile = File(tempDir, "old.png").apply {
        writeText("old")
        setLastModified(now - ScreenExporter.STALE_EXPORT_MAX_AGE_MS - 60_000L)
      }
      val freshFile = File(tempDir, "fresh.png").apply {
        writeText("fresh")
        setLastModified(now - 60_000L)
      }

      val deleted = ScreenExporter.cleanupStaleExports(tempDir, nowMillis = now)
      assertEquals(1, deleted)
      assertFalse(staleFile.exists())
      assertTrue(freshFile.exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // Item 17 Regression Test
  @Test
  fun `regression_item_17_multi_step_AI_turn_is_grouped_into_a_single_undo_entry`() = runTest {
    val tempDir = Files.createTempDirectory("vm_undo_test").toFile()
    try {
      val repo = FileProjectRepository(tempDir)
      val vm = DebugViewModel(repo, registry, autoLoadFromRepository = false)
      val initialScreen = vm.uiState.value.currentScreen
      val provider = FakeAiProvider(FakeAiProvider.Mode.FunctionCallingThreeLevelTree)
      val aiRes = provider.generateOps(AiRequest("Build 3-level tree", initialScreen)) as AiResult.Success

      // 6 sequential add_node tool calls executed as 6 batches in one AI turn transaction
      val batches = aiRes.ops.map { listOf(it) }
      val turnResult = vm.executeAiTurnTransaction(batches, "AI 3-level turn")
      assertTrue(turnResult.isSuccess)

      // Undo count must be 1 (not 6), and a single undo() reverts all 6 added nodes back to initialScreen
      assertEquals(1, vm.uiState.value.undoCount)
      assertNotNull(findNode(vm.uiState.value.currentScreen.root, "ai_card_1"))

      vm.undo()
      assertEquals(0, vm.uiState.value.undoCount)
      assertNull(findNode(vm.uiState.value.currentScreen.root, "ai_card_1"))
      assertEquals(initialScreen.root, vm.uiState.value.currentScreen.root)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // Item 18 Regression Test
  @Test
  fun `regression_item_18_AI_remote_images_are_rejected_by_default_and_allowed_when_setting_is_enabled`() =
    runTest {
      val tempDir = Files.createTempDirectory("vm_ai_img_test").toFile()
      try {
        val repo = FileProjectRepository(tempDir)
        val vm = DebugViewModel(repo, registry, autoLoadFromRepository = false)
        val provider = FakeAiProvider(FakeAiProvider.Mode.RemoteImageAttempt)
        val aiRes =
          provider.generateOps(AiRequest("Add remote image", vm.uiState.value.currentScreen))
            as AiResult.Success

        // Default allowAiRemoteImages is false -> AI remote https:// image must be rejected
        assertFalse(vm.uiState.value.allowAiRemoteImages)
        val rejected = vm.executeAiTurnTransaction(listOf(aiRes.ops), "Try AI remote image")
        assertTrue("AI remote image must be rejected when allowAiRemoteImages=false", rejected.isFailure)
        assertTrue(
          rejected.exceptionOrNull()?.message.orEmpty().contains("Allow Remote Images", ignoreCase = true)
        )

        // Enable allowAiRemoteImages -> same AI remote image op now succeeds
        vm.toggleAllowAiRemoteImages()
        assertTrue(vm.uiState.value.allowAiRemoteImages)
        val accepted = vm.executeAiTurnTransaction(listOf(aiRes.ops), "Try AI remote image enabled")
        assertTrue("AI remote image must succeed when allowAiRemoteImages=true", accepted.isSuccess)
      } finally {
        tempDir.deleteRecursively()
      }
    }
}
