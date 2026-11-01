# Phase 0 Foundation — AI-Assisted UI Designer (Final Revised Plan)

This plan establishes the Phase 0 architecture and core runtime engine for a native Android AI-assisted UI designer built with Kotlin, Jetpack Compose, Material 3, WebView, and `kotlinx.serialization`. A serializable `Project -> Screen -> Node` tree serves as the single source of truth, and a single deterministic `HtmlRenderer` transforms the component tree into HTML/CSS for both the live WebView canvas (`Edit` and `Preview` modes) and standalone `Export` artifacts (`.html` with warnings, full-document `.png` via a dedicated export WebView, and `.zip` bundle).

## User Review & Critical Decisions

> [!IMPORTANT]
> All 7 amendments have been incorporated into this specification:

1. **Dedicated Export WebView for Clean Full-Height PNGs**: `exportPng` never captures the live canvas WebView. Instead, it renders the `Export`-mode HTML into a dedicated hidden/temporary `WebView` attached to the window, waits for page load plus a JS readiness signal (`Promise.all` across all `<img>` load/error events + `document.fonts.ready`) that reports the true document scroll height, enforces a safe maximum bitmap height cap (`MAX_EXPORT_HEIGHT_PX = 16384`, returning a clear human-readable error if exceeded), sizes the export WebView to the full content height, and draws to a `Bitmap`.
2. **Mode-Aware Scroll & Viewport Model (390×844)**:
   - **`Edit` / `Preview` mode (`scrollable = true`)**: The 390px device container has a fixed height of `844px` and scrolls vertically inside it (`overflow-y: auto`).
   - **`Export` mode (`scrollable = true`)**: No inner scroll container and no fixed height; the document flows naturally with `min-height: 844px` so `documentheight == contentHeight`, allowing exported HTML to scroll naturally in any browser and PNG export to capture the full height.
   - **`scrollable = false` (any mode)**: Fixed `390px × 844px` with `overflow: hidden`.
3. **Inline Assets & Remote Image Warnings**: CSS, layout rules, and built-in SVG icons are 100% inline (no CDNs, no web fonts, no scripts in Export). Because `Image` `src` allows `https:` URLs in addition to offline `data:image/` URIs, `exportHtml(screen, theme, registry)` returns an `ExportHtmlResult(html: String, warnings: List<String>)` (e.g., `"1 remote image (https) requires network access when viewing offline"`), and warnings are displayed in the Debug UI.
4. **Non-Recursive AI Tool Schemas**:
   - Function-calling tools are strictly non-recursive with zero `$ref` or `$defs` in `GeminiToolAdapter` and `OpenAiToolAdapter`.
   - `add_node` accepts a single flat node definition (`id` optional/generated or specified, `parentId`, `index`, `type`, and flat `props` object) with **no `children` field**. Multi-level trees are constructed by calling `add_node` sequentially (parent first, then children).
   - `replace_screen_root` is excluded from function-calling tool definitions and is supported exclusively in the text-fallback JSON array path.
   - `props` is typed as a free-form JSON object in tool schemas and validated strictly by `Validator`.
   - `FakeAiProvider` includes a scenario that builds a 3-level component tree (`Card` -> `Column` -> `Text` + `Button`) via sequential `add_node` tool calls, plus a scenario exercising the text-fallback JSON path.
5. **Consistent HTML/CSS Structure & Shared Font Stack**: The canvas and exported HTML share the exact same HTML/CSS structure (differing only in `Edit`-mode selection attributes/chrome and the scroll container rule above) and use a single explicit font stack everywhere: `system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`.
6. **Throttled `Android.onRects` Bridge on Scroll & Resize**: In `Edit` mode, the injected JS script reports `Android.onRects(json)` on initial render and re-sends throttled updates (`requestAnimationFrame` / ~50ms throttle) whenever the inner device container scrolls, the window resizes, or DOM mutations/Readiness complete.
7. **JVM Unit Tests + Manual Device Checklist**: Preserves all deterministic JVM unit tests and includes a final manual device verification checklist for tall scrollable PNG export and desktop browser HTML rendering.

---

## 1. Overview & Core Concept

- **What It Does**: Provides a deterministic UI design engine where `Screen(id, name, root: Node, scrollable: Boolean = true)` is mutated exclusively through validated, atomic `EditOp` commands and rendered into a 390px-wide HTML document displayed inside a locked-down Android `WebView`.
- **Target Audience / Persona**: Mobile product designers and developers inspecting component mutations, HTML/CSS flexbox output, live JS bridge bounding rects, non-recursive AI tool schemas, and full-height visual exports in Phase 0.
- **Key Value**: Guarantees identical HTML/CSS component markup between the interactive canvas and exported artifacts while keeping component registration to a single entry in `ComponentRegistry`.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Initial Launch & Sample Project Load**: On startup (`MainActivity`), `WebView.enableSlowWholeDocumentDraw()` is invoked before any `WebView` is created. Internal JSON storage initializes with a sample Project containing a scrollable `"Login"` screen (`Column` root with heading `Text`, two `TextField`s, `Button`, `Icon`, and `Spacer`) and renders it inside the WebView Canvas in `Edit` mode.
  2. **WebView Canvas Inspection (`Edit` vs `Preview` Mode)**:
     - In `Edit` mode (`390×844` scrollable viewport), every rendered HTML element carries `data-node-id` and `data-node-type`. Tapping any element intercepts default browser actions (`preventDefault`), highlights the element with a selection outline and `id / type` badge, calls `Android.onNodeSelected(id)`, and continuously updates element bounding rects via throttled `Android.onRects(json)` on layout, resize, and inner container scroll.
     - In `Preview` mode (`390×844` viewport), editor chrome and scripts are stripped so HTML inputs, buttons, and scrolling behave natively.
  3. **Atomic Sample Operations, 3-Level AI Tree Build & Undo/Redo**: Toolbar buttons trigger sample `EditOp`s (Add Button, Update Text, Delete Node, Add Tall Content for scroll testing, Fake AI 3-Level Tree via sequential `add_node` tool calls, and Fake AI Text Fallback), validate the resulting tree atomically, push snapshots to `UndoStack<Screen>` (max 50), and update the canvas.
  4. **Clean Export (`Export HTML`, `Export PNG`, `Export Both`)**:
     - **Export HTML**: Generates `Export`-mode HTML (`min-height: 844px`, natural document flow when `scrollable = true`), surfaces any remote `https:` image warnings in the workbench banner, saves the `.html` file, and opens the Android share/save sheet.
     - **Export PNG**: Spins up a dedicated hidden export `WebView` loaded with the clean `Export`-mode HTML (never capturing the live Edit canvas), waits for the JS readiness callback confirming all images and `document.fonts.ready` have settled, verifies the full scroll height is within `MAX_EXPORT_HEIGHT_PX` (16,384px), renders the full height to a `Bitmap`, writes the `.png`, and triggers the share sheet.
     - **Export Both**: Packages both the clean `.html` and full-height `.png` into a `.zip` bundle and opens the share sheet.
  5. **4-Tab Workbench Inspection**: Users switch between **Canvas**, **HTML** (with export warnings), **Raw JSON**, and **Prompt & Tools** (system prompt, non-recursive Gemini & OpenAI tool schemas, and validator diagnostics).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Mode-Aware CSS Viewport & Scroll Rules**
  - *Chosen Approach*:
    - Shared CSS font stack: `font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;`
    - When `scrollable == false` (all modes): `.screen-root { width: 390px; height: 844px; overflow: hidden; }`
    - When `scrollable == true` in `Edit` or `Preview` mode: `.screen-root { width: 390px; height: 844px; overflow-y: auto; overflow-x: hidden; }`
    - When `scrollable == true` in `Export` mode: `.screen-root { width: 390px; min-height: 844px; height: auto; overflow: visible; }`
  - *Why*: Gives designers an accurate 390×844 scrollable mobile viewport during editing/previewing while ensuring exported HTML documents scroll naturally at the page level and full-document PNG captures never clip inside an inner scroll container.

- **Decision 2: Dedicated Export WebView with JS Readiness Handshake & Height Cap**
  - *Chosen Approach*: `PngExporter` attaches an offscreen/0-alpha `WebView` to the activity's decor/content view group, loads the `Export`-mode HTML, and registers a minimal JS bridge (`ExportBridge.onReady(scrollHeight)`) triggered only after `window` load, `document.fonts.ready`, and `Promise.all` over all `Array.from(document.images)` complete. If `scrollHeight * density > 16384`, it aborts cleanly with a descriptive error (`Result.failure`). Otherwise, it measures/layouts the export WebView at `(390 * density, scrollHeight * density)`, draws it onto a `Bitmap.Config.ARGB_8888`, saves the PNG, and detaches/destroys the temporary WebView.
  - *Why*: Guarantees zero editor selection chrome in exported PNGs, waits deterministically for images/fonts, and protects against OOM crashes on excessively tall screens.

- **Decision 3: Non-Recursive Function-Calling Tool Schemas**
  - *Chosen Approach*:
    - Function-calling tools exposed to Gemini and OpenAI: `add_node` (`parentId`, `index`, `type`, `id` optional, `props` free-form object — **no `children`**), `update_props` (`nodeId`, `props`), `move_node` (`nodeId`, `newParentId`, `index`), `delete_node` (`nodeId`), `get_screen`, `get_component_catalog`, `validate_screen`.
    - When `AiResponseParser` converts a flat `add_node` tool call into an `EditOp.AddNode`, it constructs `Node(id = id, type = type, props = props, children = emptyList())`.
    - `replace_screen_root` remains part of `sealed interface EditOp` and is parsed in the text-fallback JSON array path.
  - *Why*: LLM function-calling endpoints (especially strict schema validators in Gemini and OpenAI) reject recursive `$ref`/`$defs` schemas. Building trees via ordered `add_node` tool calls in a single multi-tool turn is universally compatible and validated atomically by `applyOps`.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                        DebugScreen (Jetpack Compose Host)                      │
│  Toolbar: [Sample Ops] [Fake AI: 3-Level Tree / Text JSON] [Undo] [Redo]       │
│           [Validate] [Export HTML] [Export PNG] [Export Both (.zip)]           │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────────────────┐ │
│  │  Canvas Tab  │ │   HTML Tab   │ │ Raw JSON Tab │ │   Prompt & Tools Tab   │ │
│  │ (Edit/Preview│ │ (Export HTML │ │ (Serialized  │ │ (Non-Recursive Gemini  │ │
│  │  390x844 WV) │ │  + Warnings) │ │ Screen Tree) │ │  & OpenAI Tool Schemas)│ │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └───────────┬────────────┘ │
└─────────┼────────────────┼────────────────┼─────────────────────┼──────────────┘
          │                │                │                     │
          ▼                ▼                ▼                     ▼
┌────────────────────────────────────────────────────────────────────────────────┐
│                        DebugViewModel (StateFlow / MVVM)                       │
│  • currentScreen: Screen (scrollable)     • undoStack: UndoStack<Screen> (50)  │
│  • renderMode: HtmlRenderMode             • nodeRects: Map<String, NodeRect>   │
│  • selectedNodeId: String?                • exportWarnings: List<String>       │
└────┬───────────────────┬──────────────────────┬───────────────────────┬────────┘
     │                   │                      │                       │
     ▼                   ▼                      ▼                       ▼
┌──────────────┐  ┌──────────────────┐  ┌───────────────────┐  ┌─────────────────┐
│  EditOps &   │  │   HtmlRenderer   │  │   Export Engine   │  │    AI Layer     │
│  Validator   │  │   & Registry     │  │  (package export) │  │  (package ai)   │
│              │  │                  │  │                   │  │                 │
│ • applyOps() │  │ • 10 Components  │  │ • exportHtml() +  │  │ • 7 Flat Tools  │
│ • validate() │  │ • 390x844 Scroll │  │   remote warnings │  │ • No $ref/$defs │
│   (Atomic)   │  │ • Throttled Rect │  │ • Hidden ExportWV │  │ • 3-Level Tree  │
│              │  │   JS Bridge      │  │   + Height Cap    │  │   FakeProvider  │
└──────────────┘  └──────────────────┘  └───────────────────┘  └─────────────────┘
```

### Verification Plan (JVM Unit Tests + Manual Device Checklist)

1. **Automated JVM Tests (`gradle :app:testDebugUnitTest`)**:
   - Models & `EditOp` JSON round-trip serialization (including `Screen.scrollable`).
   - `applyOps` atomic execution: single & multi-op success (including 3-level sequential `AddNode`), atomic rollback on invalid op or post-op validation error, move-into-descendant rejection, delete-root rejection.
   - `Validator` rules: unknown component type, unknown prop key, wrong prop type, value not in `allowedValues`, invalid hex color, invalid `Image` `src` scheme, children on leaf component, duplicate IDs.
   - `UndoStack`: max 50 capacity, `undo`, `redo`, `canUndo`, `canRedo`.
   - `HtmlRenderer`, Security & Export Warnings:
     - `<script>` payload inside a `Text` prop or attribute is strictly HTML-escaped in rendered output.
     - All 10 registered components render valid HTML in `Edit`, `Preview`, and `Export` modes.
     - `Export` mode output contains zero `<script>` tags, zero `data-node-*` attributes, and uses `min-height: 844px` without an inner scroll lock when `scrollable = true`.
     - Same `Screen` + `ThemeTokens` always produces identical HTML output (deterministic).
     - `exportHtml` detects `https://` images and returns accurate remote-image warnings while `data:image/` produces zero warnings.
   - AI Tool Adapters & Parser:
     - `GeminiToolAdapter` and `OpenAiToolAdapter` output contains no `$ref` or `$defs`, excludes `replace_screen_root`, and keeps `add_node` flat without `children`.
     - `FakeAiProvider` 3-level tree tool call sequence and text-fallback JSON array both parse and apply cleanly to a `Screen`.
2. **Manual Device Checklist (included in final summary)**:
   - Export PNG of a tall scrollable screen captures the full scrollable document height with zero selection outlines or editor badges.
   - Exported `.html` file opens cleanly in a desktop/mobile browser with natural page scrolling and identical component styling.
