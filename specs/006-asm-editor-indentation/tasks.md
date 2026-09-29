---

description: "Task list for Assembly-Style Indentation in the Editor"
---

# Tasks: Assembly-Style Indentation in the Editor

**Input**: Design documents from `specs/006-asm-editor-indentation/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/editor-keys.md](./contracts/editor-keys.md),
[quickstart.md](./quickstart.md)

**Tests**: **Included, and written first.** Constitution Principle III requires them. Within each
story, write the test task first, run it and watch it fail, then implement.

**Organization**: Tasks are grouped by user story from spec.md:
- **US1** (P1): lines land in the right column as you type (Enter, `:`)
- **US2** (P2): Tab, Shift+Tab and Backspace move by indentation levels
- **US3** (P3): trailing comments line up (`;`)
- **US4** (P3): Edit → Format Program

## Format: `[ID] [P?] [Story] Description`

- **[P]**: The task can run in parallel with others (different files, no dependency on incomplete
  tasks).
- Paths are relative to the repo root `/Users/jyp/x86-Simulator`. Production code is in
  `src/main/kotlin/x86sim/`, and tests are in `src/test/kotlin/x86sim/`.
- Terms used below:
  - **Contract** means [contracts/editor-keys.md](./contracts/editor-keys.md).
  - `|` in test strings marks the caret. `·` in the contract means a space; use real spaces in
    tests.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Let `SourceLayout` reuse the assembler's comment and label rules, with no change in
behavior.

- [X] T001 In `src/main/kotlin/x86sim/asm/Assembler.kt`:
  - Move the quote-aware scan in `stripComment` into an `internal` companion function
    `commentStart(line: String): Int`. It returns the index of the first `;` outside `'…'`, `"…"`
    and `` `…` `` literals, or -1.
  - Make `stripComment` call it.
  - Move the label regex from `labelColon` into an `internal val LABEL_RE`
    (`^\s*([A-Za-z_.?@$][\w.?@$#]*)\s*:`) and have `labelColon` use it.
  - Run `./gradlew test` and confirm it still passes.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The line model and the editor's undo grouping, which every story needs.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T002 [P] Create `src/test/kotlin/x86sim/SourceLayoutTest.kt` with failing tests for
  `SourceLayout.split`, `kind` and `visualWidth` (see [data-model.md](./data-model.md)). Cover:
  - `split("    mov rax, 1    ; hi")` gives indent `"    "`, code `"mov rax, 1"`, gap `"    "`
    and comment `"; hi"`.
  - `;` inside literals is not a comment: `msg db "a;b", 10` and `mov al, ';'`.
  - Invariant: `indent + code + gap + (comment ?: "")` equals the original line, apart from
    trailing whitespace when there is no comment.
  - `kind` returns:
    - `Blank` for `""` and `"   "`
    - `Comment` for `"  ; x"`
    - `Label` for `_start:`, `.again:` and `.l: dec rcx`
    - `Directive` for `section .text`, `SEGMENT .data`, `global _start`, `extern x`,
      `default rel` and `bits 64`
    - `Code` for `mov rcx, 100`, `msg db "x:y", 10`, `len equ $ - msg`, `times 4 db 0`,
      `align 8`, `rep movsb`, `_start` with no colon, and unknown text such as `foo bar`
  - `visualWidth("\tx") == 5` and `visualWidth("  \tx") == 5`.
- [X] T003 Create `src/main/kotlin/x86sim/asm/SourceLayout.kt`: an `object SourceLayout` with no
  Swing imports.
  - Add constants: `INDENT = 4`, `DEFAULT_COMMENT_COLUMN = 28`, and `LEVEL_ZERO_DIRECTIVES` (the
    set `section segment global extern default bits`).
  - Add `data class LineParts(indent, code, gap, comment: String?)`.
  - Add `enum class LineKind { Blank, Comment, Label, Directive, Code }`.
  - Add `split(line)` using `Assembler.commentStart`, `kind(line)` using `Assembler.LABEL_RE` and
    the first word lower-cased, and `visualWidth(s)`, where a tab advances to the next multiple
    of 4.
  - Make T002 pass.
- [X] T004 [P] Create `src/test/kotlin/x86sim/EditorIndentTest.kt` with the harness and a failing
  undo-grouping test.
  - Harness setup: `System.setProperty("java.awt.headless", "true")` and
    `System.setProperty("x86sim.noprefs", "true")`. Create an `AsmEditor`.
  - Helper `given("…|…")`: sets the text through `setSource`, places the caret at `|`, and removes
    the marker.
  - Helper `press(actionName)`: calls `editor.actionMap[actionName].actionPerformed(null)`.
  - Helper `state()`: returns the text with `|` put back at the caret.
  - Test: two insertions inside one `editor.compoundEdit { … }` are undone by a single
    `editor.undo.undo()`.
- [X] T005 In `src/main/kotlin/x86sim/ui/AsmEditor.kt`:
  - Add `fun compoundEdit(block: () -> Unit)` (research R4). While `block` runs, the
    undoable-edit listener adds text edits to a `javax.swing.undo.CompoundEdit` instead of `undo`,
    still skipping edits whose `presentationName` is "style change". Afterwards, `end()` it and
    add it to `undo` only if it is significant.
  - Add action-name constants in a companion: `ENTER = "asm-enter"`, `INDENT = "asm-indent"`,
    `UNINDENT = "asm-unindent"`, `BACKSPACE = "asm-backspace"`, `COLON = "asm-colon"`,
    `SEMICOLON = "asm-semicolon"`.
  - Add a private helper `lineText(line)` / `replaceRange(start, end, text)` that the actions use.
  - Make T004 pass.

**Checkpoint**: Line model and undo grouping ready. User stories can start.

---

## Phase 3: User Story 1 - Lines land in the right column as I type (Priority: P1) 🎯 MVP

**Goal**:
- Enter indents the new line by the layout rules.
- Typing `:` after a label name moves the line to column 0.
- Enter moves an indented directive to column 0.

**Independent Test**: Using only Enter and ordinary characters, retype the code lines of
`02_loop_sum.asm` into an empty editor. Every label, directive and instruction lands in the same
column as in the file (SC-001).

### Tests for User Story 1 ⚠️

- [X] T006 [P] [US1] In `src/test/kotlin/x86sim/SourceLayoutTest.kt`, add failing tests for
  `indentAfter(line)` and `placed(line)`.
  - `indentAfter` returns:
    - 4 after `_start:`, `.l: dec rcx`, `section .text` and `    global _start`
    - the line's own indent width after `    mov rcx, 100`, after `        nop` (8) and after
      `mov rax, 1` at column 0 (0)
    - the whitespace width for a whitespace-only line
  - `placed(line, codeIndent)` returns the line with its indent replaced:
    - column 0 for Label and Directive lines
    - `codeIndent` for Code lines
    - unchanged for Blank and Comment lines
- [X] T007 [P] [US1] In `src/test/kotlin/x86sim/EditorIndentTest.kt`, add failing tests for every
  Enter and `:` row of the Contract, using `press(AsmEditor.ENTER)` and
  `press(AsmEditor.COLON)`:
  - `_start:|` → `_start:\n    |`
  - `    mov rcx, 100|` → `    mov rcx, 100\n    |`
  - `    section .bss|` → `section .bss\n    |`
  - `    |` → `\n    |` (the whitespace-only line is emptied)
  - `    dec rcx|.l: nop` → `    dec rcx\n.l: nop` with the caret before `.l`
  - `    .again|` + `:` → `.again:|`
  - `    mov al, 'a|` + `:` → `    mov al, 'a:|`
  - `    mov|` + `:` → `mov:|` at column 0. Like the assembler, the editor treats any `name:` that
    matches `LABEL_RE` as a label.
  - Each of these is reverted by exactly one `undo.undo()`.
  - The `TAB`, `shift TAB`, `ENTER`, `BACK_SPACE`, `typed :` and `typed ;` keystrokes in
    `editor.inputMap` map to the new action names.
- [X] T008 [US1] In `src/test/kotlin/x86sim/EditorIndentTest.kt`, add the SC-001 retype test.
  - Load `Examples.load("02_loop_sum")`.
  - Keep only lines whose kind is Label, Directive or Code, with trailing comments removed and
    leading whitespace stripped. Also keep blank lines as blank.
  - Feed each character through `replaceSelection`, except `:`, which goes through
    `press(COLON)`. Call `press(ENTER)` between lines.
  - Assert that each code line's indent width equals the original line's.

### Implementation for User Story 1

- [X] T009 [US1] In `src/main/kotlin/x86sim/asm/SourceLayout.kt`, implement `indentAfter(line)`
  and `placed(line, codeIndent)`, following the Contract's "Layout rules" table. Make T006 pass.
- [X] T010 [US1] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, bind `ENTER` in `inputMap` to the
  `AsmEditor.ENTER` action. Inside `compoundEdit`:
  1. Delete any selection.
  2. Split the caret line into `before` and `after`.
  3. If `before` is whitespace-only, empty it. Otherwise replace the line's head with
     `SourceLayout.placed(before, currentIndent)`.
  4. Insert `"\n"` + the new indent (`indentAfter(before)`).
  5. Place `after`, trimmed of leading spaces, via `placed` (a label goes to column 0).
  6. Leave the caret before `after`.
  Make the Enter tests in T007 pass.
- [X] T011 [US1] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, bind `KeyStroke.getKeyStroke("typed
  :")` to the `COLON` action. Inside `compoundEdit`, insert `:`. Then, if
  `SourceLayout.kind(line text up to and including the caret)` is `Label` and the caret is not
  inside a literal (`Assembler.commentStart` and the quote state of the text before the caret),
  remove the line's leading whitespace and keep the caret right after `:`. Make the remaining T007
  tests and T008 pass.

**Checkpoint**: US1 works on its own. Typing new code produces the conventional layout.

---

## Phase 4: User Story 2 - Tab and Shift+Tab move by indentation levels (Priority: P2)

**Goal**:
- Tab and Shift+Tab indent and unindent the caret line or every selected line by one level.
- Tab with no selection goes to the next 4-column stop.
- Backspace in leading spaces removes a whole level.

**Independent Test**: Select five instruction lines of an example, then press Tab and Shift+Tab.
They move 4 columns right and back with nothing else changed, and one Undo reverts each step.

### Tests for User Story 2 ⚠️

- [X] T012 [US2] In `src/test/kotlin/x86sim/EditorIndentTest.kt`, add failing tests for the Tab,
  Shift+Tab and Backspace rows of the Contract:
  - No selection: `ab|` (column 2) + Tab → `ab  |`, and column 4 + Tab → column 8.
  - Selecting 3 lines (one of them empty) + Tab: each non-empty line gains 4 spaces, and the
    selection still covers the same lines, from the start of the first to the end of the last.
  - Shift+Tab on a selection with a line at column 0, a line at 2 and a line at 8 gives columns 0,
    0 and 4.
  - Shift+Tab with no selection on `\tmov` removes the tab.
  - `        |mov` + Backspace → `    |mov`, and `    x|` + Backspace deletes only `x`.
  - A selection + Backspace deletes the selection.
  - One `undo.undo()` reverts each multi-line Tab or Shift+Tab.

### Implementation for User Story 2

- [X] T013 [US2] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, replace the `insert-4-spaces`
  binding and its comment: map `TAB` to the `INDENT` action.
  - No selection, or a selection within one line that isn't the whole line: replace the selection
    with spaces up to the next multiple of `SourceLayout.INDENT`, using the caret's
    `visualWidth` column.
  - Otherwise: inside `compoundEdit`, insert 4 spaces at the start of each non-empty line the
    selection touches, working from the last line to the first. Then reselect from the start of
    the first line to the end of the last.
- [X] T014 [US2] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, map `shift TAB` to the `UNINDENT`
  action. Inside `compoundEdit`, for the caret line or each selected line, remove one leading
  `\t`, or else up to 4 leading spaces. Keep the selection expanded to whole lines.
- [X] T015 [US2] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, map `BACK_SPACE` to the `BACKSPACE`
  action. With no selection, the caret column > 0 and only spaces before the caret on its line,
  delete back to the previous multiple of 4. Otherwise call the original `delete-previous` action,
  which you save from `actionMap` before rebinding. Make T012 pass.

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 - Trailing comments line up (Priority: P3)

**Goal**: Typing `;` after code places it on the comment column of the surrounding run.

**Independent Test**: In `02_loop_sum.asm`, add `    inc rax` below `mov rcx, 100` and type
`;`. It lands in column 24, like the comments above it.

### Tests for User Story 3 ⚠️

- [X] T016 [P] [US3] In `src/test/kotlin/x86sim/SourceLayoutTest.kt`, add failing tests for
  `commentColumnFor(lines, index)` (research R6):
  - A run whose other lines have comments at 24, 24 and 30 → 24 (the most common). A tie goes to
    the smaller column.
  - A run with no trailing comments → 28.
  - The caret line's code is 30 wide → 31.
  - A blank line and a full-line `;` comment both end the run, so comments beyond them are
    ignored.
- [X] T017 [P] [US3] In `src/test/kotlin/x86sim/EditorIndentTest.kt`, add failing tests for the
  `;` rows of the Contract:
  - Inside the `02_loop_sum` run, `    inc rax|` + `;` → the `;` at column 24.
  - Alone in a file, `    inc rax|` + `;` → the `;` at column 28.
  - A 40-character code line → exactly one space before `;`.
  - `|` at column 0 + `;` → `;|`, and `    |` + `;` → `    ;|`.
  - `    msg db "a|` + `;` → inserted literally.
  - One `undo.undo()` removes both the padding and the `;`.

### Implementation for User Story 3

- [X] T018 [US3] In `src/main/kotlin/x86sim/asm/SourceLayout.kt`, implement
  `commentColumnFor(lines: List<String>, index: Int): Int`:
  - Find the run around `index`: Label, Directive and Code lines, bounded by Blank or Comment
    lines.
  - Take the most common `visualWidth(indent + code + gap)` among the other run lines that have a
    comment (ties go to the smaller column), or `DEFAULT_COMMENT_COLUMN` if there are none.
  - Return the maximum of that and `visualWidth(indent + code of line index) + 1`.
  - Make T016 pass.
- [X] T019 [US3] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, bind `KeyStroke.getKeyStroke("typed
  ;")` to the `SEMICOLON` action. Inside `compoundEdit`:
  - If the text before the caret on its line has non-blank code and no open literal, remove the
    whitespace just before the caret. Then pad with spaces to
    `SourceLayout.commentColumnFor(lines, caretLine)` and insert `;`.
  - Otherwise insert `;` only.
  - Make T017 pass.

**Checkpoint**: US1, US2 and US3 all work independently.

---

## Phase 6: User Story 4 - Reformat the whole program (Priority: P3)

**Goal**: **Edit → Format Program** (⇧⌘F) puts every line in its conventional column and lines up
trailing comments per run. It only changes whitespace, and it is one Undo step.

**Independent Test**: Strip all leading whitespace from an example and run Format Program. The code
columns match the original, and the program assembles and runs identically.

### Tests for User Story 4 ⚠️

- [X] T020 [P] [US4] In `src/test/kotlin/x86sim/SourceLayoutTest.kt`, add failing tests for
  `format(text)`:
  - **Fixed points**: `format(Examples.load(id)) == Examples.load(id)` for every id in
    `Examples.names` (SC-002). This fails for `05_fibonacci` until T023.
  - **Strip and restore**: for every example, `format(stripped)` restores each Label, Directive
    and Code line's indent width (SC-003).
  - **Idempotence**: `format(format(t)) == format(t)` on a messy sample.
  - **Full-line comments**: a full-line comment takes the indent of the next code line (column 0
    before `print_uint:`, 4 before `mov`, 0 at the end of the file).
  - **Blank lines**: `"   "` becomes `""`.
  - **Tabs**: leading tabs and a tab gap before `;` become spaces.
  - **Literals**: `msg db "a: b; c", 10` is only re-indented.
  - **Comment column** per research R6:
    - a run sharing column 34 keeps 34
    - a run mixing 28 and 35 goes to 28
    - a run whose code is 40 wide goes to 41
  - **Unchanged text**: `code` and `comment` text is unchanged on every line.
- [X] T021 [P] [US4] In `src/test/kotlin/x86sim/SourceLayoutTest.kt`, add the assembly-preservation
  test (FR-010, SC-005).
  - For every example, and for a stripped copy of it, run the original and `format(…)` of it
    through a local `run(src, input)` helper copied from `SimulatorTest.run`:
    `Machine()` → `load(Assembler.assemble(src))` → `runToEnd`, providing `"Ada\n"` when the
    state is `WAITING_INPUT`.
  - Assert equal output and exit state.
  - Also assert that `Assembler.assemble` gives the same instruction count and the same bytes in
    each data section.

### Implementation for User Story 4

- [X] T022 [US4] In `src/main/kotlin/x86sim/asm/SourceLayout.kt`, implement `format(text: String):
  String`.
  1. Split on `\n`, keeping a trailing `\r` out of the line body.
  2. Place each line with `placed(line, 4)`. Blank lines become `""`. A Comment line takes the
     indent of the next non-blank Label, Directive or Code line (0 if there is none).
  3. Group lines into runs. For each run, pick the comment column: keep the shared column `c`
     when every comment in the run has the same `c` and `c ≥ codeEnd + 1`. Otherwise use
     `max(28, codeEnd + 1)`.
  4. Rebuild each line as `indent + code + spaces + comment`, with no trailing whitespace.
  5. Rejoin with the original line endings and keep a final newline if the input had one.
  Make T020 and T021 pass, except the `05_fibonacci` fixed point.
- [X] T023 [US4] Edit `src/main/resources/examples/05_fibonacci.asm`: change whitespace only, so
  the run that mixes comment columns 28 and 35 uses 35. (Its code reaches column 31, so 28 can't
  hold it; only the `mov rcx, 2` comment moves.) Check with
  `git diff --ignore-all-space` (it should show no changes). Make the T020 fixed-point test pass
  for every example.
- [X] T024 [US4] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, add `fun formatProgram()` and test
  it in `src/test/kotlin/x86sim/EditorIndentTest.kt`.
  - Implementation: compute `SourceLayout.format(text)`. If it equals the text, return without
    editing. Otherwise, inside `compoundEdit`, go from the last line to the first and replace each
    changed line's content (not its newline). Then restore the caret to the same line, at its
    column clamped to the line length.
  - Tests:
    - An already-formatted example makes no document edit (a `DocumentListener` counts zero
      events).
    - A stripped example is formatted, and one `undo.undo()` restores the stripped text.
    - The line count is unchanged.
- [X] T025 [US4] In `src/main/kotlin/x86sim/ui/MainWindow.kt`, in the `Edit` menu after Redo:
  - Add `addSeparator()`.
  - Add `item("Format Program", KeyEvent.VK_F, InputEvent.SHIFT_DOWN_MASK) {
    editor.formatProgram() }`, which gives ⇧⌘F, or Ctrl+Shift+F off macOS.

**Checkpoint**: All four stories work independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T026 [P] In `src/main/kotlin/x86sim/ui/Docs.kt` `referenceHtml()`, add an
  `<h2>Code layout</h2>` section after "Assembler syntax (NASM)" (FR-013). It should cover:
  - Labels, `section`, `global`, `extern`, `default` and `bits` in column 0.
  - Instructions and data indented 4 spaces.
  - Trailing comments lined up.
  - What Enter, `:`, `;`, Tab, Shift+Tab and Backspace do, and **Edit → Format Program (⇧⌘F)**.
- [X] T027 [P] In `README.md`, add a Features bullet covering assembly-style indentation as you
  type, block indent and unindent, comment alignment and **Edit → Format Program**, plus the
  keyboard-shortcut entry if README lists shortcuts.
- [X] T028 [P] In `src/test/kotlin/x86sim/SourceLayoutTest.kt`, add a performance guard. Build a
  10,000-line program by repeating the body of `06_bubble_sort.asm` with unique labels, and assert
  that `format` finishes in under 1 s.
- [X] T029 Run `./gradlew test` and confirm the whole suite passes (quality gate 1).
- [ ] T030 Run the manual GUI walkthrough in [quickstart.md](./quickstart.md), steps 1–9,
  including both themes and 200% zoom, and the headless `x86sim run --example 05_fibonacci`
  check that the output is unchanged (quality gates 2–4). Record any deviations.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)**: No dependencies.
- **Foundational (T002–T005)**: Depends on T001. Blocks every story.
- **US1 (T006–T011)**: Depends on Phase 2.
- **US2 (T012–T015)**: Depends on Phase 2 only. It is independent of US1, but its tasks edit
  `AsmEditor.kt` and `EditorIndentTest.kt`, so run it after US1 or merge carefully.
- **US3 (T016–T019)**: Depends on Phase 2 only.
- **US4 (T020–T025)**: Depends on Phase 2 and on T009 (`placed`), which it reuses.
- **Polish (T026–T030)**: T026–T028 can start once the stories they describe exist. T029–T030 come
  last.

### Within Each Story

Write the tests first and watch them fail. Then do the `SourceLayout` function, then the
`AsmEditor` action, then the menu wiring.

### Shared-file notes

- `SourceLayout.kt` is edited by T003, T009, T018 and T022.
- `AsmEditor.kt` is edited by T005, T010, T011, T013–T015, T019 and T024.
- `SourceLayoutTest.kt` and `EditorIndentTest.kt` each collect tests from several stories.

Tasks touching the same file are never marked [P] relative to each other within a phase.

## Parallel Opportunities

- **Phase 2**: T002 (`SourceLayoutTest.kt`) and T004 (`EditorIndentTest.kt`) can be written in
  parallel.
- **US1**: T006 and T007 in parallel (different test files).
- **US3**: T016 and T017 in parallel.
- **US4**: T020 and T021 are both in `SourceLayoutTest.kt`. They are marked [P] against the editor
  work, but write them one after the other.
- **Polish**: T026, T027 and T028 in parallel (different files).
- **Across stories**: The pure `SourceLayout` work for US3 (T016, T018) and US4 (T020–T023) can run
  alongside US1 and US2 editor work, because they touch different files.

```text
# Example: US1 kick-off
Task: T006 SourceLayoutTest — indentAfter/placed tests
Task: T007 EditorIndentTest — Enter and ':' contract rows
```

## Implementation Strategy

### MVP (US1 only)

1. T001 (Setup), then T002–T005 (Foundational).
2. T006–T011 (US1).
3. **Stop and validate**: the retype test (T008) and quickstart steps 1–3 (without the `;`
   alignment).

### Incremental delivery

1. MVP (US1): typing new code lands in the right columns.
2. US2: block indent and unindent, and level-wise Backspace.
3. US3: comment alignment while typing.
4. US4: Format Program for pasted or messy code, plus the example touch-up.
5. Polish: the Reference tab, README, performance guard and the full walkthrough.

Each increment keeps `./gradlew test` green and can be committed on its own.
