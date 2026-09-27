# Contract: User-Facing Text for x86Learn

Every string below is built from `AppInfo` (see [data-model.md](../data-model.md)), not repeated
as a literal.

## T1. Window title (FR-001)

| State | Title |
|---|---|
| Just started, before any document is shown | `x86Learn` |
| Document `<name>` open, saved | `x86Learn — <name>` |
| Document `<name>` open, unsaved edits | `x86Learn — <name> •` |

The separator is ` — ` (em dash with spaces) and the unsaved marker is ` •`. Both are unchanged
from today.

## T2. macOS application name (FR-002)

`apple.awt.application.name` = `x86Learn`.

## T3. CLI usage (FR-003, FR-008)

Printed for `-h`, `--help` or bad arguments:

```text
x86Learn: learn x86-64 assembly by stepping through it

Usage:
  x86learn                          open the visual simulator
  x86learn run <file.asm> [--trace] assemble and run in the terminal
  x86learn run --example <name>     run a built-in example (e.g. 01_hello)
```

Exit codes are unchanged: 0 for `-h`/`--help`, 2 for bad arguments.

## T4. Help → About (FR-005, FR-006, FR-007)

- **Dialog title**: `About x86Learn`
- **Body** (`AppInfo.aboutHtml()`), rendered as HTML in a label about 360px wide, with no
  hard-coded colors. It MUST contain, in this order:

| # | Item | Text (wording may be polished; a test asserts the key phrase) | Key phrase |
|---|---|---|---|
| 1 | Name | **x86Learn** (as the heading) | `x86Learn` |
| 2 | Version | `Version <VERSION>` | `Version 1.0.0` (the current build) |
| 3 | Description | "An educational, visual simulator for learning x86-64 assembly." | `x86-64 assembly` |
| 4 | Supports | "Write NASM-syntax programs that talk to a simulated Linux through system calls." | `NASM`, `Linux` |
| 5 | Features | "Step forward and back one instruction at a time, set breakpoints, and watch the registers, flags, stack and memory change." | `Step`, `breakpoints`, `registers`, `flags`, `stack`, `memory` |
| 6 | Help | "Help → Instruction Reference lists everything supported. Hover over an instruction or register for a description." | `Instruction Reference`, `Hover` |

- **Closing**: OK, Enter or Escape closes it with no change to simulator state (standard
  `JOptionPane` behavior).
- **Legibility**: correct in dark and light themes, 80%–250% zoom, with no clipped text.

## T5. README (FR-004, FR-008, FR-011)

- The title is `# x86Learn`. The first paragraph calls it "an educational, visual simulator for
  x86-64 assembly".
- Run commands use `build/install/x86learn/bin/x86learn`.
- A one-line note: "The launcher used to be called `x86sim`; update any scripts that call it."
- The project-layout block still shows `src/main/kotlin/x86sim/`. That's the real (unchanged)
  package path, and it isn't a product name.

## T6. Forbidden strings (FR-011, SC-001)

In `README.md` and all files under `src/main/`, the strings `x86-64 Simulator` and
`x86-simulator` MUST NOT appear, in any case.
