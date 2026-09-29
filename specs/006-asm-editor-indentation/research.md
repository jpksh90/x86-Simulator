# Research: Assembly-Style Indentation in the Editor

No open questions were left in the Technical Context. The items below record the design decisions
and the facts checked in the codebase.

## R1. Where the layout rules live

- **Decision**: A new headless file, `src/main/kotlin/x86sim/asm/SourceLayout.kt`, holds pure
  functions over strings: classify a line, find the indent for Enter, find the comment column, and
  format a whole program. `AsmEditor` only maps key presses to those functions and applies the
  resulting edits.
- **Rationale**: Principle IV says features must get their information from APIs that can be
  unit-tested without a display. Putting it in `asm/` lets it reuse `Assembler.DIRECTIVES`,
  `DATA_DIRECTIVES` and the assembler's label regex, so the editor and the assembler agree on what
  a label is. It also lets tests check that formatting never changes the assembled `Program`.
- **Alternatives considered**: Logic inside `AsmEditor` (can only be tested through Swing, which
  makes it slower and more fragile). A new `x86sim.edit` package (adds a package for one file).

## R2. Classifying a line

- **Decision**: Split each line into `indent`, `code` and `comment` by scanning for the first `;`
  outside `'…'`, `"…"` and `` `…` `` literals. This is the same quote-aware scan as
  `Assembler.stripComment`, pulled out into a shared helper. Then classify by the first word of
  `code`:
  - empty code and no comment → **Blank**; empty code with a comment → **Comment**
  - matches `^name\s*:` (the assembler's `labelColon` regex) → **Label**, which may be followed by
    code on the same line
  - first word (lower-cased) in `section segment global extern default bits` → **Directive**
  - anything else (instruction, prefix, `db`/`resb`/`equ`/`times`/`align`, `name db …`, unknown
    words) → **Code**
- **Rationale**: This matches the edge cases in the spec: literals never trigger, a colon-less label
  is plain code, and `len equ $ - msg` is code. Unknown text is indented like code, which is the
  safe default for a line the learner is still typing.
- **Alternatives considered**: Reusing the assembler's tokenizer (it throws on incomplete lines,
  which happen on every keystroke). Reusing the highlighter's regex (it isn't quote-aware for `;`
  in every case).

## R3. Hooking keys in the editor

- **Decision**: Bind actions in the editor's `inputMap` and `actionMap`, the way the existing
  `insert-4-spaces` Tab binding works:
  - `ENTER` replaces `insert-break`
  - `TAB` and `shift TAB` handle indent and unindent, with or without a selection
  - `BACK_SPACE` replaces `delete-previous` only when the caret has nothing but spaces before it and
    there is no selection; otherwise it defers to the original action
  - `typed :` and `typed ;` insert the character and then apply label dedent or comment alignment
- **Rationale**: Key bindings react only to the learner's own typing. Pasting, opening a file or
  loading an example goes through `setText` or `replaceSelection` and never triggers layout, which
  is what the spec requires. A `DocumentFilter` would also fire on paste and on `setSource`.
- **Checked**: `JEditorPane` uses Ctrl+Tab and Ctrl+Shift+Tab for focus traversal, so Tab and
  Shift+Tab reach the input map. The existing Tab binding already proves this for Tab. A test
  will confirm the `shift TAB` binding is found.

## R4. One Undo per keystroke

- **Decision**: Add `AsmEditor.compoundEdit { … }`. While the block runs, the undoable-edit
  listener sends edits into a `javax.swing.undo.CompoundEdit` instead of `undo`. When the block
  ends, the compound edit is closed and added to `undo` only if it holds anything. Every
  layout-aware action (Enter, Tab, Shift+Tab, Backspace-by-level, `:`, `;`, Format Program) runs
  inside it.
- **Rationale**: FR-011 needs the typed character and its re-indent to undo together. The existing
  listener already filters out style changes, and the compound edit goes through the same filter.
- **Alternatives considered**: Merging edits by time (fragile), or replacing the whole text
  (loses the caret and scroll position, and wipes the undo granularity).

## R5. Applying Format Program

- **Decision**: `SourceLayout.format(text)` returns the new text. The editor compares it line by
  line and, inside one `compoundEdit`, replaces only the whitespace ranges that changed, working
  from the last line to the first. If nothing changed, no edit is made.
- **Rationale**: The line count never changes, so breakpoints (kept by line number) and the caret
  line survive. No edit means `onEdited` never fires, so the document isn't marked modified
  (Story 4, scenario 2).

## R6. Comment column

- **Decision**:
  - A **run** is a stretch of consecutive lines that have code (Label, Directive or Code). A run
    ends at a Blank or Comment line.
  - **While typing `;`**: the target is the most common trailing-comment column among the *other*
    lines of the caret's run, or the default of 28. It is raised to `codeEnd + 1` if the code is
    longer.
  - **Format Program**: measure where each trailing comment lands once its code is re-indented
    and its gap is kept, so comments move with their code. If they all land on the same column
    `c` and `c ≥ longest commented code + 1`, keep `c`. Otherwise use
    `max(28, longest commented code + 1)`. Code lines without a comment don't push the column.
- **Checked**: The bundled examples use comment columns 24, 28, 30, 34 and 42, and every run
  already shares one column except run 4 of `05_fibonacci.asm` (28 and 35). Its code reaches
  column 31, so 28 can't hold it. The touch-up moves the single comment at 28 (`mov rcx, 2`) to 35,
  which matches the other three. This is whitespace only, so FR-014 and SC-002 hold.
- **Alternatives considered**: One column for the whole file (would rewrite most examples), or
  the column fixed at 24 (the examples use 28 more often).

## R7. Tabs and blank lines

- **Decision**:
  - Visual columns count a tab as advancing to the next multiple of 4.
  - Live editing leaves existing tab characters alone. Format Program replaces leading whitespace
    and the whitespace before a trailing comment with spaces, and removes trailing whitespace.
  - When Enter is pressed on a line that holds only whitespace, that whitespace is removed before
    the new line is created. So pressing Enter twice leaves a truly empty line, while the new line
    still gets its indent.
- **Rationale**: This keeps the spec's "blank lines stay blank" rule in the common case without
  watching caret movement. Format Program cleans up anything left.
- **Scope note**: If a learner indents an empty line and then clicks elsewhere, the spaces stay
  until Format Program runs. This is a slightly narrower reading of the blank-line edge case in
  the spec.

## R8. Format Program shortcut

- **Decision**: **Edit → Format Program**, shortcut ⇧⌘F (Ctrl+Shift+F on Linux and Windows).
- **Checked**: `MainWindow` uses no `VK_F` binding. ⇧⌘F is the reformat shortcut in several
  editors, and it doesn't clash with macOS full-screen (⌃⌘F).
