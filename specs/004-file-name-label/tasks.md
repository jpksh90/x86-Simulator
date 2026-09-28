---

description: "Task list for Replace Examples Dropdown with File Name Label"
---

# Tasks: Replace Examples Dropdown with File Name Label

**Input**: Design documents from `specs/004-file-name-label/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/toolbar-label.md](./contracts/toolbar-label.md),
[quickstart.md](./quickstart.md)

**Tests**: **Included, and written first.** Constitution Principle III requires tests that assert on
concrete values. The label rules are pure functions, so every row of the contract table gets a
headless unit test.

**Organization**: grouped by user story. US1 = the label shows which program is open (P1). US2 =
the leading `*` for unsaved edits (P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on incomplete tasks)
- Paths are relative to the repo root `/Users/jyp/x86-Simulator`. Production code is in
  `src/main/kotlin/x86sim/`, and tests are in `src/test/kotlin/x86sim/`.

## Conventions for every task

- Run `./gradlew test` after each task that touches production code. Existing tests must stay green.
- Match the existing style: terse KDoc one-liners, `when` dispatch, and themed values set inside
  `Theme.onChange { … }`.
- `ui/DocumentLabel.kt` MUST NOT import anything from `javax.swing` or `java.awt`. Only
  `java.io.File` is allowed.
- Label and tooltip strings must match
  [contracts/toolbar-label.md](./contracts/toolbar-label.md) character for character, including
  `*New File`, `New File (not saved yet)` and `Example: <name>`.

---

## Phase 1: Setup

- [X] T001 Create and switch to branch `004-file-name-label` from the current HEAD (`git checkout -b 004-file-name-label`). The spec folder `specs/004-file-name-label/` already exists.
- [X] T002 Run `./gradlew test` and confirm the baseline is green before any change. Note any pre-existing failures in the PR description instead of fixing them here.

---

## Phase 2: Foundational (blocks all user stories)

**Purpose**: replace the loose `docName`/`file` pair in `MainWindow` with the `Document` value from
[data-model.md](./data-model.md). There is no visible change yet: the window title must read exactly
as before.

- [X] T003 Create `src/main/kotlin/x86sim/ui/DocumentLabel.kt` (package `x86sim.ui`). Add `/** The program in the editor: where it came from and what to call it. */ sealed interface Document` with three cases: `data object New : Document`, `data class Example(val displayName: String) : Document` and `data class Opened(val file: File) : Document`. Add an extension `val Document.titleName: String`: `New` → `"untitled"`, `Example` → `displayName`, `Opened` → `file.name`. This keeps today's window-title text (research R5).
- [X] T004 Refactor `src/main/kotlin/x86sim/ui/MainWindow.kt` to use `Document` (depends on T003):
  - Replace `private var file: File? = null` and `private var docName = "untitled"` (≈ lines 79–80) with `private var document: Document = Document.New` and `private val file: File? get() = (document as? Document.Opened)?.file`.
  - File → New (≈ line 347): replace `file = null; docName = "untitled"` with `document = Document.New`.
  - `loadExample` (≈ line 446): `document = Document.Example(Examples.names.first { it.first == id }.second)`.
  - `open()`: `document = Document.Opened(f)`.
  - `save()`: after writing, `document = Document.Opened(f)`. Inside the `FileDialog.apply { … }` block, `this@MainWindow.file?.name` must still resolve to the getter.
  - `updateTitle()`: use `document.titleName` in place of `docName`.
  - Remove every other use of `docName`. `grep -n docName` must return nothing.
  - Run `./gradlew test`, then `./gradlew run`, and check that the title reads `x86Learn — Hello, world` at startup and `x86Learn — untitled` after New.

**Checkpoint**: builds, tests are green, and the behavior is identical to before.

---

## Phase 3: User Story 1 - See which program is open (Priority: P1) 🎯 MVP

**Goal**: the dropdown is gone. A label at the left of the toolbar shows `*New File`, the example
name, or the file name, with a tooltip.

**Independent Test**: start the app and use New, Open, Save As and the Examples menu. After each
step the label matches the contract rows with `unsaved = false`, and no dropdown is present.

### Tests first

- [X] T005 [US1] Create `src/test/kotlin/x86sim/DocumentLabelTest.kt` (package `x86sim`, `kotlin.test`, style like `RenameTest.kt`) with one test per contract row where `unsaved = false`:
  - `new document label` checks that `labelText(Document.New, false) == "*New File"` and `tooltipText(Document.New) == "New File (not saved yet)"`.
  - `example label` checks that `labelText(Document.Example("Hello, world"), false) == "Hello, world"` and the tooltip is `"Example: Hello, world"`.
  - `opened file label shows name only` uses `File("/home/u/loop.asm")`. The label must be `"loop.asm"` and the tooltip `File("/home/u/loop.asm").absolutePath`.
  - `title name unchanged` checks the `titleName` values from T003.

  The functions don't exist yet, so this test should fail to compile.

### Implementation

- [X] T006 [US1] In `src/main/kotlin/x86sim/ui/DocumentLabel.kt`, add top-level functions `fun labelText(doc: Document, unsaved: Boolean): String` and `fun tooltipText(doc: Document): String`, following [contracts/toolbar-label.md](./contracts/toolbar-label.md). For now `labelText` ignores `unsaved`: `New` → `"*New File"`, `Example` → `displayName`, `Opened` → `file.name` ("shows `file.name` only, with no directory"). `tooltipText`: `New` → `"New File (not saved yet)"`, `Example` → `"Example: $displayName"`, `Opened` → `file.absolutePath`. T005 must pass.
- [X] T007 [US1] In `src/main/kotlin/x86sim/ui/MainWindow.kt` `buildLayout()` (≈ lines 194–202), delete the `val examples = JComboBox(...)` block and `add(examples)`. In the same place (before `addSeparator(Dimension(14, 0))`), add `add(docLabel)`. Declare `private val docLabel = JLabel()` next to `stepsLabel` (≈ line 75), configured with `Theme.onChange { font = Theme.ui(13f, Font.BOLD); foreground = Theme.text; maximumSize = Dimension(Theme.z(220), Theme.z(30)); preferredSize = null }`. Keep the label read-only, with no mouse listener (research R4). Remove the now-unused `import javax.swing.JComboBox` only if nothing else in the file uses it.
- [X] T008 [US1] In `src/main/kotlin/x86sim/ui/MainWindow.kt`, extend `updateTitle()` so that, after setting `title`, it sets `docLabel.text = labelText(document, unsaved)` and `docLabel.toolTipText = tooltipText(document)`. Don't add any other call sites. `updateTitle()` already runs after `setSource` (New, Open, example), `save` and `editor.onEdited` (research R3). Because `docLabel` is declared before `loadExample` runs in `init`, the startup label is `Hello, world`.
- [X] T009 [US1] Check that the text fits: if the label is wider than 220 × zoom (a long file name), it must be shortened with `…` and must not push other buttons off-screen. If `JToolBar`'s `BoxLayout` ignores `maximumSize` for the label's width, also set `preferredSize = Dimension(minOf(ui preferred width, Theme.z(220)), …)` inside `updateTitle()` after setting the text. Check with a file named `a_very_long_program_name_used_for_testing_truncation_x86.asm`.

**Checkpoint**: US1 works end to end. Every row of the quickstart walkthrough that doesn't involve
editing passes.

---

## Phase 4: User Story 2 - See when the program has unsaved changes (Priority: P2)

**Goal**: a leading `*` appears on the first edit and disappears on save, and there is never a double `*`.

**Independent Test**: open a file, type a character, and check the label reads `*loop.asm`. Save and
check it reads `loop.asm`. After New, edit and check it reads `*New File`.

### Tests first

- [X] T010 [US2] In `src/test/kotlin/x86sim/DocumentLabelTest.kt`, add the `unsaved = true` rows:
  - `unsaved example gets a star` expects `"*Hello, world"`.
  - `unsaved file gets a star` expects `"*loop.asm"`.
  - `new file never gets a double star` expects `labelText(Document.New, true) == "*New File"`.
  - `tooltip ignores unsaved`: the tooltips equal the `unsaved = false` ones.
  - `at most one leading star` checks that, for every document and both values of `unsaved`, `labelText(...).takeWhile { it == '*' }.length <= 1` (data-model rule "At most one leading `*`") and the label is not blank ("The label is never empty").

  The two star tests should fail until T011.

### Implementation

- [X] T011 [US2] In `src/main/kotlin/x86sim/ui/DocumentLabel.kt`, update `labelText`: `New` stays `"*New File"` whatever `unsaved` is. Otherwise, the result is `(if (unsaved) "*" else "") + name`. All tests in `DocumentLabelTest` must pass. `MainWindow` needs no change, because `editor.onEdited` already sets `unsaved = true` and then calls `updateTitle()`, and `save` clears it.

**Checkpoint**: both stories work, and every row of [contracts/toolbar-label.md](./contracts/toolbar-label.md) is covered by a test.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T012 [P] In `README.md`, add one line to the Features list (next to the "9 example programs" bullet, ≈ line 71): the toolbar shows the name of the open file (`*` = unsaved changes), and examples are loaded from **File → Examples**. Don't mention the removed dropdown anywhere.
- [X] T013 Run `./gradlew test` (full suite) and `./gradlew installDist`, then `build/install/x86learn/bin/x86learn run --example 01_hello`. CLI output must be unchanged (spec Assumptions and the "CLI: unchanged" contract row).
- [X] T014 Visual check (Principle II, SC-004): build `./gradlew testClasses installDist` and render with `UiSnapshot` as in [quickstart.md](./quickstart.md). Render `01_hello 0 0 dark 0 0`, `01_hello 0 0 light 0 0` and `06_bubble_sort 0 0 dark 8 0` (250% zoom). The classpath recipe is in the project's UiSnapshot memory note: every jar in `build/install/x86learn/lib` except `x86Learn-*.jar`, plus `build/classes/kotlin/test`, `build/classes/kotlin/main` and `build/resources/main`. Read the PNGs and confirm the label is bold and readable in both themes, there is no dropdown, and every toolbar button up to the Speed slider is visible at 250%.
- [X] T015 Walk through the manual table in [quickstart.md](./quickstart.md) with `./gradlew run`, including the cancelled-Open and long-file-name rows. Fix any mismatch before committing. Done by hand (`./gradlew run`) on 2026-09-28; label rules also covered by `DocumentLabelTest` and `UiSnapshot` renders.
- [X] T016 Commit on `004-file-name-label` with an imperative summary, for example "Replace examples dropdown with file name label". Include the spec folder.

---

## Dependencies & Execution Order

```text
T001 → T002 → T003 → T004 ─┬─► US1: T005 → T006 → T007 → T008 → T009
                           │                                   │
                           └─► US2: T010 → T011  (needs T006)  │
                                                               ▼
                                   Polish: T012 [P], T013 → T014 → T015 → T016
```

- **Setup → Foundational**: T004 needs the `Document` type from T003.
- **US1** depends on Foundational. T007 and T008 both edit `MainWindow.kt`, so run them in order.
- **US2** needs `labelText` from T006. It changes only `DocumentLabel.kt` and its test, so it can
  run alongside T007–T009 once T006 is done.
- **Polish**: T012 can start anytime. T013–T016 need both stories.

## Parallel Opportunities

- After T006: **T007–T009** (`MainWindow.kt`) and **T010–T011** (`DocumentLabel.kt` +
  `DocumentLabelTest.kt`) touch different files.
- **T012** (`README.md`) is independent of all code tasks.

```text
# Example parallel batch after T006:
Task A: T007 → T008 → T009  (MainWindow.kt)
Task B: T010 → T011         (DocumentLabel.kt, DocumentLabelTest.kt)
Task C: T012                (README.md)
```

## Implementation Strategy

- **MVP = Phases 1–3 (US1)**. The dropdown is gone, and the label names the program. This is
  shippable on its own because the window title already shows unsaved state with `•`.
- **Increment 2 = Phase 4 (US2)**, a two-task change to one pure function.
- **Finish with Phase 5**: README, CLI check, theme and zoom snapshots, the manual walkthrough and
  the commit.
