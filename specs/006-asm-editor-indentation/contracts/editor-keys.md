# Contract: Editor Keys and Commands

This contract covers the source editor (`AsmEditor`) only. Every action below counts as **one
Undo step** together with the character it typed (FR-011). Layout changes happen only on these
keys. Paste, Open, loading an example and loading a disassembly never re-lay out text.

Notation: `·` is a space, `|` is the caret, and the cell shows the line before and after the key.

| Key | Condition | Effect | Example |
|-----|-----------|--------|---------|
| Enter | caret line is Label or Directive | If the line is indented, move it to column 0 first. The new line gets 4 spaces. | `····section .bss\|` → `section .bss` / `····\|` |
| Enter | caret line is Code | The new line gets the same indent as the caret line. | `····mov rcx, 100\|` → … / `····\|` |
| Enter | caret line is whitespace-only | Empty that line; the new line gets the same indent. | `····\|` → `` / `····\|` |
| Enter | caret mid-line | Split the line. The text after the caret is placed by the rules above (a label goes to column 0, code to the computed indent). | `····dec rcx\|.l: nop` → `····dec rcx` / `.l: nop` |
| `:` typed | the text before the caret on this line is `indent + name` and is not inside a literal | Insert `:` and move the line to column 0. The caret stays after `:`. | `····.again\|` → `.again:\|` |
| `:` typed | anything else | Insert `:` only. | `····mov al, 'a\|` → `····mov al, 'a:\|` |
| `;` typed | caret is after code, outside a literal | Pad with spaces so `;` lands on the run's comment column (at least 1 space after the code). | `····inc rax\|` → `····inc rax·…·;\|` at column 28 |
| `;` typed | caret is in leading whitespace, on an empty line, or inside a literal | Insert `;` only. | |
| Tab | no selection | Insert spaces up to the next multiple of 4. | column 2 → column 4 |
| Tab | selection spans ≥ 2 lines, or a whole line | Add 4 spaces to the start of every line the selection touches (empty lines are skipped). The selection is kept, expanded to whole lines. | |
| Shift+Tab | no selection | Remove up to 4 leading spaces (or 1 leading tab) from the caret line. | |
| Shift+Tab | selection | The same, for every line the selection touches. Lines at column 0 are left alone. | |
| Backspace | no selection, only spaces before the caret, caret column > 0 | Delete back to the previous multiple of 4. | column 8 → column 4 |
| Backspace | anything else | The normal single-character delete. | |
| ⇧⌘F / Ctrl+Shift+F, or Edit → Format Program | always | Apply `SourceLayout.format` as whitespace-only per-line edits. If nothing changes: no edit, and the document is not marked modified. | |

## Layout rules used by Enter, `:` and Format Program

| Line kind | Column of first code character |
|-----------|--------------------------------|
| Label (with or without code after it) | 0 |
| Directive: `section`, `segment`, `global`, `extern`, `default`, `bits` | 0 |
| Code: instructions, prefixes, `db`…`dq`, `resb`…`resq`, `equ`, `times`, `align`, `name db …` | 4 |
| Full-line comment (Format Program only) | the column of the next code line (0 if there is none) |
| Blank (Format Program only) | the line is emptied |

Trailing comments: see research R6 for the column rule. Format Program also changes the gap
before `;` to spaces and removes trailing whitespace. It never changes `code` or `comment` text.

## Menu

`Edit` menu, after Redo: a separator, then **Format Program** (⇧⌘F).
