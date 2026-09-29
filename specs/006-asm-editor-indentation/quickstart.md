# Quickstart: Validating Assembly-Style Indentation

## Automated

```sh
./gradlew test --tests 'x86sim.SourceLayoutTest' --tests 'x86sim.EditorIndentTest'
./gradlew test          # the full suite must stay green
```

What they cover:

| Test | Proves |
|------|--------|
| `SourceLayoutTest` — classification | Labels, directives, code, comments, and `:`/`;` inside `'…'`, `"…"` and `` `…` `` ([data-model](data-model.md)) |
| `SourceLayoutTest` — examples are fixed points | `format(example) == example` for all 9 examples (SC-002, FR-014) |
| `SourceLayoutTest` — strip and restore | For each example, removing all leading whitespace and formatting restores the original's code columns (SC-003) |
| `SourceLayoutTest` — preserves assembly | `Assembler.assemble` on the original and on the formatted text gives equal programs, and both run to the same output through `Machine` (SC-005, FR-010) |
| `SourceLayoutTest` — idempotent | `format(format(t)) == format(t)` |
| `EditorIndentTest` | Runs each key in [contracts/editor-keys.md](contracts/editor-keys.md) on a headless `AsmEditor` through its `actionMap`. Checks the text and caret afterwards, and that one `undo.undo()` restores the previous text. |
| `EditorIndentTest` — retype example | Feeds `02_loop_sum.asm` in character by character (without leading whitespace) through the typed-key actions, and checks the code columns match the file (SC-001) |

## Manual GUI walkthrough

Run `./gradlew run`, then:

1. **File → New**. Type `section .text`, Enter, `global _start`, Enter. `global` jumps to column 0
   and the caret is indented.
2. Type `_start:`. The line jumps to column 0 when `:` is typed. Press Enter: the caret is indented
   4 spaces.
3. Type `mov rcx, 100` and then `;`. The `;` lands at column 28. Type ` counter`, then Enter. The
   caret is still indented.
4. Select three instruction lines. Press Tab (they move right 4 columns), Shift+Tab (they move
   back), then ⌘Z (the Shift+Tab is undone in one step).
5. On an indented empty line at column 8, press Backspace. The caret goes to column 4.
6. Load **Examples → Fibonacci** and run **Edit → Format Program**. Nothing changes, and the title
   shows no modified marker.
7. Paste an unindented copy of an example into a new file, then run Format Program. The layout
   matches the example. ⌘Z restores the pasted layout in one step. Assemble and Run give the same
   output as before.
8. Repeat step 3 in the other theme and at 200% zoom (the layout is text-only, so nothing should
   differ).
9. Open the **Reference** tab and check the "Code layout" section lists the conventions and keys.
