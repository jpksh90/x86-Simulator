# Feature Specification: Assembly-Style Indentation in the Editor

**Feature Branch**: `006-asm-editor-indentation`

**Created**: 2026-09-29

**Status**: Draft

**Input**: User description: "I want the indentation to follow the conventions of assembly language programs" (clarified: "in the editor")

## Background

Assembly source is laid out in columns, not nested blocks. The bundled examples already follow the
usual NASM layout:

```
section .text                 ; directives start in column 0
global _start
_start:                       ; labels start in column 0
    mov rcx, 100              ; instructions are indented one level
.again:                       ; local labels also start in column 0
    add rax, rcx              ; trailing comments line up in a column
    loop .again
section .data
    msg db "Hi", 10           ; data definitions are indented like instructions
```

Today the editor only turns the Tab key into four spaces at the caret. Pressing Enter returns the
caret to column 0, typing a label leaves it wherever the caret happened to be, and there is no way
to indent or unindent several lines at once. A learner has to lay out every line by hand, and a
program typed from scratch rarely ends up looking like the examples.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lines land in the right column as I type (Priority: P1)

A learner types a program from scratch. After pressing Enter at the end of a label, a `section`
line or an instruction, the caret is already where the next line conventionally goes: indented one
level for instructions and data definitions. When the learner types a label (text ending in `:`)
or a directive such as `section` or `global` on an indented line, that line moves back to column 0
by itself.

**Why this priority**: This is the core of the request. It makes every newly typed line follow
assembly conventions without the learner having to think about it, and it is useful on its own
with no other story implemented.

**Independent Test**: Start with File → New, type the body of `02_loop_sum.asm` line by line
using only Enter (no Tab, Space or Backspace used for indentation), and compare the column of each
label, directive and instruction with the bundled example.

**Acceptance Scenarios**:

1. **Given** the caret is at the end of `_start:`, **When** the learner presses Enter, **Then**
   the new line starts indented one level (four spaces).
2. **Given** the caret is at the end of an indented instruction such as `    mov rcx, 100`,
   **When** the learner presses Enter, **Then** the new line keeps the same indentation.
3. **Given** the caret is at the end of `section .text` or `section .data`, **When** the learner
   presses Enter, **Then** the new line starts indented one level.
4. **Given** the learner is on an indented new line, **When** they type `.again:`, **Then** the
   line is moved to column 0 as soon as the `:` is typed, and the caret stays right after the `:`.
5. **Given** the learner is on an indented new line, **When** they type `section .bss` or
   `global _start` and press Enter, **Then** that line is moved to column 0 and the next line
   starts indented one level.
6. **Given** the caret is on an indented line, **When** the learner types `mov` (an instruction,
   not a label or directive), **Then** the line's indentation does not change.
7. **Given** the caret is in the middle of a line (text after the caret), **When** the learner
   presses Enter, **Then** the text after the caret moves to the new line at the indentation the
   rules above give it (e.g. a label moved down goes to column 0).

---

### User Story 2 - Tab and Shift+Tab move by indentation levels (Priority: P2)

A learner fixes the layout of existing code. Tab on a selection of several lines indents all of
them one level; Shift+Tab unindents them. With no selection, Tab still inserts spaces up to the
next four-column stop, and Shift+Tab removes one level from the current line. Backspace in leading
whitespace removes a whole level rather than a single space.

**Why this priority**: Programs pasted in from elsewhere, or edited heavily, need fixing by hand.
This makes those fixes quick, but typing new code (Story 1) matters more.

**Independent Test**: Open any example, select five instruction lines, press Tab and then
Shift+Tab, and check the lines move right by four columns and back again with nothing else
changed.

**Acceptance Scenarios**:

1. **Given** several lines are selected, **When** the learner presses Tab, **Then** each selected
   line gains one indentation level and the selection still covers the same lines.
2. **Given** several lines are selected, **When** the learner presses Shift+Tab, **Then** each
   selected line loses up to one level (lines already at column 0 are left alone).
3. **Given** no selection and the caret at column 2, **When** the learner presses Tab, **Then**
   spaces are inserted up to column 4 (the next stop), not four more spaces.
4. **Given** the caret is right after only leading spaces (e.g. column 8 of `        mov`),
   **When** the learner presses Backspace, **Then** the caret moves back to column 4.
5. **Given** an indent or unindent of several lines, **When** the learner presses Undo once,
   **Then** all those lines return to their previous layout together.

---

### User Story 3 - Trailing comments line up (Priority: P3)

A learner types `;` after an instruction to add a comment. The `;` lands in the comment column used
by the surrounding code, so comments form a straight column like in the examples.

**Why this priority**: It is a visible part of assembly style and of the examples, but programs
work and read fine without it.

**Independent Test**: In `02_loop_sum.asm`, add a new instruction below `mov rcx, 100`, type ` ;`
after it, and check the `;` lines up with the comments above.

**Acceptance Scenarios**:

1. **Given** the caret is after the code on an instruction line and nearby lines have trailing
   comments starting at the same column, **When** the learner types `;`, **Then** spaces are
   inserted so the `;` lands in that column.
2. **Given** no nearby line has a trailing comment, **When** the learner types `;` after code,
   **Then** the `;` lands at the default comment column.
3. **Given** the code already reaches past the comment column, **When** the learner types `;`,
   **Then** exactly one space separates the code from the `;`.
4. **Given** the caret is at the start of a line or in leading whitespace, **When** the learner
   types `;`, **Then** nothing is moved (a full-line comment stays where it is typed).

---

### User Story 4 - Reformat the whole program (Priority: P3)

A learner has pasted in or typed a messy program. They choose an **Edit → Format Program** command
(with a keyboard shortcut) and every line is put in its conventional column: labels and
section-level directives at column 0, instructions and data definitions at one level. Trailing
comments in each run of consecutive code lines (a run ends at a blank line or a full-line comment)
share one column. A run whose comments already share a column that clears its code keeps that
column. Otherwise the run uses the default comment column, or one space past its longest code if
that is further right.

**Why this priority**: It fixes existing code in one go, but it is only needed for code that did
not get typed with Stories 1–3 active.

**Independent Test**: Take an example, strip all leading whitespace from every line, run Format
Program, and compare with the original example.

**Acceptance Scenarios**:

1. **Given** a program with inconsistent indentation, **When** the learner runs Format Program,
   **Then** each line is placed according to the column rules and the program assembles to exactly
   the same bytes as before.
2. **Given** a program that is already formatted, **When** Format Program runs, **Then** nothing
   changes and the document is not marked as modified.
3. **Given** a reformat has just happened, **When** the learner presses Undo once, **Then** the
   whole program returns to its previous layout.
4. **Given** a line with a string literal containing `;` or `:` (e.g. `msg db "a: b; c", 10`),
   **When** Format Program runs, **Then** the line is treated as a data definition, not a label or
   comment, and the string is unchanged.

---

### Edge Cases

- A label and an instruction on the same line (`.loop: dec rcx`) stays at column 0 as a label
  line, and Enter after it indents the next line one level.
- A label without a colon (`_start` alone, which NASM allows) is not recognized as a label while
  typing; it is left where the learner put it.
- `:` or `;` inside a string or character literal (`'a:'`, `"x;y"`) never triggers label or
  comment handling.
- Memory operands with segment-style colons or `$`-expressions (`len equ $ - msg`) are not labels.
- `equ` lines (`len equ $ - msg`) and data definitions (`db`, `dw`, `dd`, `dq`, `resb`…`resq`,
  `times`) are indented like instructions, matching the examples.
- Full-line comments are left at whatever column they are in by live typing; Format Program puts
  a full-line comment at the indentation of the next code line (column 0 before a label or
  directive, one level before an instruction).
- Blank lines stay blank: no trailing spaces are left on them when the learner moves away or when
  Format Program runs.
- Existing tab characters in opened files are treated as advancing to the next four-column stop
  and are not rewritten unless the learner runs Format Program.
- Indentation behavior applies only while the editor is editable; it never changes a file on open
  or on example load.
- Auto-indent and alignment each count as part of the keystroke that caused them, so one Undo
  removes both the typed character and the layout change.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The editor MUST use one indentation level of four spaces for instructions and data
  definitions and column 0 for labels and section-level directives (`section`, `segment`,
  `global`, `extern`, `default`, `bits`).
- **FR-002**: Pressing Enter MUST start the new line at column 0 + one level after a label or
  section-level directive line, and at the previous line's indentation after an instruction or data
  line.
- **FR-003**: Typing `:` that completes a label at the start of a line's code MUST move that line
  to column 0, keeping the caret after the `:`.
- **FR-004**: Completing a section-level directive on an indented line (on Enter) MUST move that
  line to column 0.
- **FR-005**: Tab with a multi-line selection MUST indent every selected line by one level;
  Shift+Tab MUST unindent every selected line by up to one level.
- **FR-006**: Tab with no selection MUST insert spaces up to the next four-column stop; Shift+Tab
  with no selection MUST unindent the current line by up to one level.
- **FR-007**: Backspace when only spaces precede the caret on its line MUST remove back to the
  previous four-column stop.
- **FR-008**: Typing `;` after code on a line MUST align it to the comment column of the
  surrounding trailing comments, or to a default column (column 28, the one the examples use most)
  when there are none, and MUST leave at least one space before it.
- **FR-009**: The editor MUST provide an Edit-menu **Format Program** command with a keyboard
  shortcut that applies the column rules to every line.
- **FR-010**: Format Program MUST NOT change what the program assembles to: code, operands, string
  contents and comment text are preserved; only whitespace before code and before trailing
  comments changes.
- **FR-011**: Each automatic indentation, alignment or reformat MUST be undoable together with the
  edit that triggered it, in one Undo step.
- **FR-012**: Characters inside string and character literals MUST NOT trigger label or comment
  handling.
- **FR-013**: The Reference tab or help MUST describe the layout conventions and the Tab,
  Shift+Tab and Format Program keys.
- **FR-014**: The bundled examples MUST satisfy the column rules, so Format Program leaves each of
  them unchanged. Examples whose trailing comments are not aligned within a run MAY be touched up
  (whitespace only) to meet this.

### Key Entities

- **Line kind**: how a line is classified for layout — blank, full-line comment, label (optionally
  followed by code), section-level directive, or instruction/data line.
- **Indentation level**: a multiple of four columns; assembly code uses only level 0 and level 1.
- **Comment column**: the column at which trailing `;` comments start. It comes from nearby lines
  or the default (28), and is shared within a run of consecutive code lines.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Retyping any bundled example line by line using only Enter and ordinary characters
  (no manual indentation) reproduces the example's label, directive and instruction columns on
  100% of code lines.
- **SC-002**: Running Format Program on each bundled example changes zero characters.
- **SC-003**: Running Format Program on each bundled example with all leading whitespace removed
  restores the original's columns on 100% of code lines, and the program produces the same output
  when run.
- **SC-004**: Indenting or unindenting a block of 20 lines takes one keystroke and one Undo reverts
  it.
- **SC-005**: No automatic layout change ever alters a program's assembled bytes or its run output.

## Assumptions

- "Indentation" means the source editor in the GUI; disassembly listings and the CLI are not
  changed.
- The conventions to follow are the NASM layout already used by the bundled examples: four-space
  indentation, labels and section-level directives in column 0, data definitions indented like
  instructions.
- The editor keeps inserting spaces rather than tab characters, as it does today.
- The indentation width (four) and default comment column are fixed; making them user settings is
  out of scope.
- Live behavior (Stories 1–3) only reacts to the learner's own typing; opening a file or loading
  an example never rewrites its layout.
- Rules apply the same way in dark and light themes and at every zoom level, since they affect
  text only.
