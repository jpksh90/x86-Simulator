# Data Model: Assembly-Style Indentation

All types live in `x86sim.asm.SourceLayout` (headless). None are saved to disk.

## LineParts

A single source line split for layout.

| Field | Type | Meaning |
|-------|------|---------|
| `indent` | String | Leading whitespace (spaces and/or tabs) |
| `code` | String | Text after the indent and before the comment, with trailing whitespace trimmed; may be empty |
| `gap` | String | Whitespace between `code` and `comment` |
| `comment` | String? | From the first `;` outside a string or character literal to the end of the line; null if none |

Invariant: `indent + code + gap + (comment ?: "")` equals the original line, apart from trailing
whitespace when there is no comment.

## LineKind

| Kind | Rule (see research R2) | Target indent |
|------|------------------------|---------------|
| `Blank` | No code, no comment | none (the line is emptied by Format Program) |
| `Comment` | No code, has comment | Format Program: indent of the next non-blank code line; live typing: unchanged |
| `Label` | `code` starts with `name:` | 0 |
| `Directive` | first word ∈ {section, segment, global, extern, default, bits} | 0 |
| `Code` | everything else | 4 (one level) |

## Run

A maximal stretch of consecutive `Label`, `Directive` or `Code` lines. It is used only to find the
comment column (research R6).

| Derived value | Definition |
|---------------|------------|
| `codeEnd` | Longest `visualWidth(indent + code)` over the run's lines that have a trailing comment, after re-indenting |
| `commentColumn` | The column where every comment lands after re-indenting with its gap kept, if they all agree and it clears `codeEnd`; otherwise `max(DEFAULT_COMMENT_COLUMN, codeEnd + 1)` |

## Constants

| Name | Value | Source |
|------|-------|--------|
| `INDENT` | 4 | FR-001; existing Tab behavior |
| `DEFAULT_COMMENT_COLUMN` | 28 | FR-008; the column the examples use most |
| `LEVEL_ZERO_DIRECTIVES` | section, segment, global, extern, default, bits | FR-001 |

## Operations (pure)

| Function | Input | Output |
|----------|-------|--------|
| `split(line)` | a line | `LineParts` |
| `kind(line)` | a line | `LineKind` |
| `visualWidth(s)` | text | its column width, with tabs advancing to the next multiple of 4 |
| `indentAfter(line)` | the line Enter is pressed on | the indent for the new line: 4 after Label or Directive, otherwise the line's own indent width |
| `commentColumnFor(lines, index)` | the document's lines and the caret line | the target `;` column for live typing |
| `format(text)` | the whole program | the formatted text; returning the input unchanged when it is already formatted |

`format` must be idempotent (`format(format(t)) == format(t)`) and must preserve assembly: for
every `t` that assembles, `Assembler.assemble(format(t))` produces the same sections, bytes,
symbols and instruction list as `Assembler.assemble(t)`.
