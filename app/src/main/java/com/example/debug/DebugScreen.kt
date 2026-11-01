package com.example.debug

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.export.ExportScale
import com.example.registry.HtmlRenderMode
import com.example.render.NodeRect
import com.example.render.WebViewCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
  viewModel: DebugViewModel,
  modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val activity = context as? Activity

  Scaffold(
    modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    topBar = {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
      ) {
        // Header bar: Title + Mode Switcher + Scrollable toggle
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.debug_workbench_title),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text =
                "Screen: ${state.currentScreen.name} • Selected: ${state.selectedNodeId ?: "none"} • Rects: ${state.nodeRects.size}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            FilterChip(
              selected = state.renderMode == HtmlRenderMode.Edit,
              onClick = { viewModel.setRenderMode(HtmlRenderMode.Edit) },
              label = { Text(stringResource(R.string.mode_edit)) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.Edit,
                  contentDescription = "Edit mode",
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.testTag("mode_edit_chip"),
            )
            FilterChip(
              selected = state.renderMode == HtmlRenderMode.Preview,
              onClick = { viewModel.setRenderMode(HtmlRenderMode.Preview) },
              label = { Text(stringResource(R.string.mode_preview)) },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.Visibility,
                  contentDescription = "Preview mode",
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.testTag("mode_preview_chip"),
            )
            FilterChip(
              selected = state.currentScreen.scrollable,
              onClick = { viewModel.toggleScrollable() },
              label = { Text(stringResource(R.string.toggle_scrollable)) },
              modifier = Modifier.testTag("toggle_scrollable_chip"),
            )
          }
        }

        // Row 1: Sample EditOps, AI Tool/Fallback, Undo, Redo, Validate
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          ActionButton(
            text = stringResource(R.string.btn_add_button),
            icon = Icons.Default.AddCircleOutline,
            tag = "btn_add_button",
            onClick = { viewModel.applySampleAddButton() },
          )
          ActionButton(
            text = stringResource(R.string.btn_update_text),
            icon = Icons.Default.Edit,
            tag = "btn_update_text",
            onClick = { viewModel.applySampleUpdateText() },
          )
          ActionButton(
            text = stringResource(R.string.btn_delete_node),
            icon = Icons.Default.DeleteOutline,
            tag = "btn_delete_node",
            onClick = { viewModel.applySampleDeleteNode() },
          )
          ActionButton(
            text = stringResource(R.string.btn_add_tall_content),
            icon = Icons.Default.UnfoldMore,
            tag = "btn_add_tall_content",
            onClick = { viewModel.applySampleTallScrollContent() },
          )
          ActionButton(
            text = stringResource(R.string.btn_ai_tree),
            icon = Icons.Default.AutoAwesome,
            tag = "btn_ai_tree",
            onClick = { viewModel.runFakeAiThreeLevelTree() },
          )
          ActionButton(
            text = stringResource(R.string.btn_ai_fallback),
            icon = Icons.Default.SmartToy,
            tag = "btn_ai_fallback",
            onClick = { viewModel.runFakeAiTextFallback() },
          )
          FilterChip(
            selected = state.allowAiRemoteImages,
            onClick = { viewModel.toggleAllowAiRemoteImages() },
            label = { Text("AI Remote Img", fontSize = 11.sp) },
            modifier = Modifier.testTag("toggle_ai_remote_images_chip"),
          )
          OutlinedButton(
            onClick = { viewModel.undo() },
            enabled = state.canUndo,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("btn_undo"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Undo,
              contentDescription = "Undo",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("${stringResource(R.string.btn_undo)} (${state.undoCount})", fontSize = 12.sp)
          }
          OutlinedButton(
            onClick = { viewModel.redo() },
            enabled = state.canRedo,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("btn_redo"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.Redo,
              contentDescription = "Redo",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("${stringResource(R.string.btn_redo)} (${state.redoCount})", fontSize = 12.sp)
          }
          ActionButton(
            text = stringResource(R.string.btn_validate),
            icon = Icons.Default.Verified,
            tag = "btn_validate",
            onClick = { viewModel.runValidationCheck() },
          )
          ActionButton(
            text = stringResource(R.string.btn_test_invalid),
            icon = Icons.Default.BugReport,
            tag = "btn_test_invalid",
            onClick = { viewModel.injectUnknownComponentForDiagnostic() },
          )
          ActionButton(
            text = stringResource(R.string.btn_reset_sample),
            icon = Icons.Default.Refresh,
            tag = "btn_reset_sample",
            onClick = { viewModel.resetToSampleLogin() },
          )
        }

        // Row 2: Export Buttons + Export Scale Selector (1x / 2x / 3x)
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            onClick = { activity?.let { viewModel.exportHtmlAndShare(it) } },
            enabled = !state.isExporting && activity != null,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("btn_export_html"),
          ) {
            Icon(
              imageVector = Icons.Default.Html,
              contentDescription = "Export HTML",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.btn_export_html), fontSize = 12.sp)
          }

          Button(
            onClick = { activity?.let { viewModel.exportPngAndShare(it) } },
            enabled = !state.isExporting && activity != null,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("btn_export_png"),
          ) {
            Icon(
              imageVector = Icons.Default.Image,
              contentDescription = "Export PNG",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("${stringResource(R.string.btn_export_png)} (${state.exportScale.label})", fontSize = 12.sp)
          }

          Button(
            onClick = { activity?.let { viewModel.exportBundleAndShare(it) } },
            enabled = !state.isExporting && activity != null,
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
              ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("btn_export_both"),
          ) {
            Icon(
              imageVector = Icons.Default.FolderZip,
              contentDescription = "Export Both ZIP",
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.btn_export_both), fontSize = 12.sp)
          }

          ExportScale.entries.forEach { scaleOption ->
            FilterChip(
              selected = state.exportScale == scaleOption,
              onClick = { viewModel.setExportScale(scaleOption) },
              label = { Text(scaleOption.label, fontSize = 11.sp) },
              modifier = Modifier.testTag("export_scale_${scaleOption.label}"),
            )
          }
        }

        if (state.isExporting) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Status & Warnings Banner
        StatusBanner(
          message = state.statusMessage,
          isError = state.isStatusError,
          warnings = state.exportWarnings,
        )

        // 4 Tabs: Canvas, HTML, Raw JSON, Prompt & Tools
        PrimaryScrollableTabRow(
          selectedTabIndex = state.activeTab.ordinal,
          edgePadding = 12.dp,
        ) {
          Tab(
            selected = state.activeTab == WorkbenchTab.Canvas,
            onClick = { viewModel.selectTab(WorkbenchTab.Canvas) },
            text = { Text(stringResource(R.string.tab_canvas)) },
            icon = {
              Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = "Canvas tab",
                modifier = Modifier.size(18.dp),
              )
            },
            modifier = Modifier.testTag("tab_canvas"),
          )
          Tab(
            selected = state.activeTab == WorkbenchTab.Html,
            onClick = { viewModel.selectTab(WorkbenchTab.Html) },
            text = { Text(stringResource(R.string.tab_html)) },
            icon = {
              Icon(
                imageVector = Icons.Default.Code,
                contentDescription = "HTML tab",
                modifier = Modifier.size(18.dp),
              )
            },
            modifier = Modifier.testTag("tab_html"),
          )
          Tab(
            selected = state.activeTab == WorkbenchTab.RawJson,
            onClick = { viewModel.selectTab(WorkbenchTab.RawJson) },
            text = { Text(stringResource(R.string.tab_json)) },
            icon = {
              Icon(
                imageVector = Icons.Default.DataObject,
                contentDescription = "Raw JSON tab",
                modifier = Modifier.size(18.dp),
              )
            },
            modifier = Modifier.testTag("tab_json"),
          )
          Tab(
            selected = state.activeTab == WorkbenchTab.PromptAndTools,
            onClick = { viewModel.selectTab(WorkbenchTab.PromptAndTools) },
            text = { Text(stringResource(R.string.tab_prompt_tools)) },
            icon = {
              Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "Prompt and Tools tab",
                modifier = Modifier.size(18.dp),
              )
            },
            modifier = Modifier.testTag("tab_prompt_tools"),
          )
        }
        HorizontalDivider()
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .background(Color(0xFF0F172A))
    ) {
      when (state.activeTab) {
        WorkbenchTab.Canvas -> {
          CanvasTabContent(
            state = state,
            onNodeSelected = viewModel::onNodeSelected,
            onRectsUpdated = viewModel::onRectsUpdated,
          )
        }
        WorkbenchTab.Html -> {
          HtmlTabContent(state = state)
        }
        WorkbenchTab.RawJson -> {
          MonospaceViewerCard(
            title = "Screen Tree (@Serializable JSON — Single Source of Truth)",
            subtitle = "Screen ID: ${state.currentScreen.id} • Scrollable: ${state.currentScreen.scrollable}",
            code = state.rawScreenJson,
            testTag = "raw_json_viewer",
          )
        }
        WorkbenchTab.PromptAndTools -> {
          PromptAndToolsTabContent(state = state)
        }
      }
    }
  }
}

@Composable
private fun ActionButton(
  text: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  tag: String,
  onClick: () -> Unit,
) {
  OutlinedButton(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    modifier = Modifier.testTag(tag),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = text,
      modifier = Modifier.size(16.dp),
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(text = text, fontSize = 12.sp)
  }
}

@Composable
private fun StatusBanner(
  message: String,
  isError: Boolean,
  warnings: List<String>,
) {
  val bgColor =
    if (isError) {
      Color(0xFFFEF2F2)
    } else {
      Color(0xFFEEF2FF)
    }
  val contentColor =
    if (isError) {
      Color(0xFFB91C1C)
    } else {
      Color(0xFF312E81)
    }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .background(bgColor)
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag("status_banner")
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        imageVector = if (isError) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
        contentDescription = "Status",
        tint = contentColor,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = message,
        color = contentColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
      )
    }

    warnings.forEach { warning ->
      Row(
        modifier = Modifier.padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.WarningAmber,
          contentDescription = "Export Warning",
          tint = Color(0xFFB45309),
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = "Export Warning: $warning",
          color = Color(0xFFB45309),
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun CanvasTabContent(
  state: DebugUiState,
  onNodeSelected: (String) -> Unit,
  onRectsUpdated: (List<NodeRect>) -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Device frame header bar
    Surface(
      color = Color(0xFF1E293B),
      shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
      modifier = Modifier.width(390.dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "390×844 CSS px (${state.renderMode.name})",
          color = Color(0xFFE2E8F0),
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
        )
        val selectedRect = state.nodeRects.find { it.id == state.selectedNodeId }
        val rectLabel =
          if (selectedRect != null) {
            "x:${selectedRect.x.toInt()} y:${selectedRect.y.toInt()} ${selectedRect.width.toInt()}×${selectedRect.height.toInt()}"
          } else {
            "tap node to inspect"
          }
        Text(
          text = rectLabel,
          color = Color(0xFF94A3B8),
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
        )
      }
    }

    // WebView Host inside a 390dp wide device viewport frame
    Box(
      modifier =
        Modifier.width(390.dp)
          .weight(1f)
          .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
          .border(
            BorderStroke(2.dp, Color(0xFF334155)),
            RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
          )
          .background(Color.White)
          .testTag("webview_canvas_container")
    ) {
      WebViewCanvas(
        html = state.canvasHtml,
        onNodeSelected = onNodeSelected,
        onRectsUpdated = onRectsUpdated,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun HtmlTabContent(state: DebugUiState) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (state.exportWarnings.isNotEmpty()) {
      Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
        modifier = Modifier.fillMaxWidth().testTag("html_export_warnings_card"),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            text = "Export HTML Warnings (${state.exportWarnings.size})",
            fontWeight = FontWeight.Bold,
            color = Color(0xFF92400E),
            fontSize = 13.sp,
          )
          state.exportWarnings.forEach { warning ->
            Text(
              text = "• $warning",
              color = Color(0xFF92400E),
              fontSize = 12.sp,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
      }
    }

    CodeBlockSection(
      title = "Export-Mode HTML (Clean, Centered 390px, No Scripts, No data-node-* Attributes)",
      subtitle = "Identical HTML/CSS structure to canvas • Natural flow (min-height: 844px) when scrollable=true",
      code = state.exportHtmlPreview,
      testTag = "export_html_code",
    )

    CodeBlockSection(
      title = "Active Canvas HTML (${state.renderMode.name} Mode)",
      subtitle = "Currently loaded in WebViewCanvas",
      code = state.canvasHtml,
      testTag = "canvas_html_code",
    )
  }
}

@Composable
private fun PromptAndToolsTabContent(state: DebugUiState) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Validator section
    Card(
      colors =
        CardDefaults.cardColors(
          containerColor =
            if (state.validationErrors.isEmpty()) Color(0xFF064E3B) else Color(0xFF7F1D1D)
        ),
      modifier = Modifier.fillMaxWidth().testTag("validator_diagnostics_card"),
    ) {
      Column(modifier = Modifier.padding(12.dp)) {
        Text(
          text =
            if (state.validationErrors.isEmpty()) {
              "Validator Status: PASS (0 errors)"
            } else {
              "Validator Status: ${state.validationErrors.size} Error(s) Detected"
            },
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
        )
        if (state.validationErrors.isEmpty()) {
          Text(
            text = "All nodes, component types, leaf constraints, IDs, hex colors, and prop specs are valid.",
            color = Color(0xFFD1FAE5),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
          )
        } else {
          state.validationErrors.forEach { err ->
            Text(
              text = "• $err",
              color = Color(0xFFFECACA),
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
      }
    }

    CodeBlockSection(
      title = "AI System Prompt (Generated by PromptBuilder)",
      subtitle = "Derived dynamically from ComponentRegistry (${state.nodeRects.size} active DOM rects)",
      code = state.systemPrompt,
      testTag = "system_prompt_code",
    )

    CodeBlockSection(
      title = "Gemini Function Declarations (Non-Recursive, No \$ref/\$defs)",
      subtitle = "add_node (flat, no children), update_props, move_node, delete_node + 3 read-only tools",
      code = state.geminiToolsJson,
      testTag = "gemini_tools_code",
    )

    CodeBlockSection(
      title = "OpenAI-Compatible Tools Schema (Non-Recursive, No \$ref/\$defs)",
      subtitle = "Converted from neutral tool definitions by OpenAiToolAdapter",
      code = state.openAiToolsJson,
      testTag = "openai_tools_code",
    )
  }
}

@Composable
private fun MonospaceViewerCard(
  title: String,
  subtitle: String,
  code: String,
  testTag: String,
) {
  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(12.dp)
  ) {
    CodeBlockSection(
      title = title,
      subtitle = subtitle,
      code = code,
      testTag = testTag,
    )
  }
}

@Composable
private fun CodeBlockSection(
  title: String,
  subtitle: String,
  code: String,
  testTag: String,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
    modifier = Modifier.fillMaxWidth().testTag(testTag),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = title,
        color = Color(0xFFF8FAFC),
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
      )
      Text(
        text = subtitle,
        color = Color(0xFF94A3B8),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
      )
      HorizontalDivider(color = Color(0xFF334155))
      SelectionContainer {
        Text(
          text = code,
          color = Color(0xFFE2E8F0),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          lineHeight = 16.sp,
          modifier =
            Modifier.fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(top = 8.dp),
        )
      }
    }
  }
}
