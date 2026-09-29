# Implementation Plan: Assembly-Style Indentation in the Editor

**Branch**: `006-asm-editor-indentation` | **Date**: 2026-09-29 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-asm-editor-indentation/spec.md`

## Summary

Make the source editor lay code out the NASM way, as the bundled examples already do:

- labels and section-level directives in column 0
- instructions and data lines indented 4 spaces
- trailing comments lined up in a column

A new headless `x86sim.asm.SourceLayout` holds all the rules as pure string functions: split a
line, classify it, find the indent after Enter, find the comment column, and format a program.
`AsmEditor` rebinds Enter, Tab, Shift+Tab, Backspace, `:` and `;` to call those functions, and
groups each keystroke's edits into one Undo step. **Edit → Format Program** (⇧⌘F) applies
`SourceLayout.format` as whitespace-only edits. The Reference tab and README describe the
conventions and keys.

## Technical Context

**Language/Version**: Kotlin 2.2 on the JVM, JDK 21 toolchain, runs on JDK 17+

**Primary Dependencies**: Swing and FlatLaf (existing). No new dependencies.

**Storage**: N/A. The editor text is only saved when the learner saves.

**Testing**: `kotlin.test` / JUnit 5 via `./gradlew test`. The pure `SourceLayoutTest` runs
alongside a headless `EditorIndentTest`, which drives `AsmEditor` actions without showing a window,
the same way `UiSnapshot` works.

**Target Platform**: The desktop Swing app on macOS, Linux and Windows

**Project Type**: Desktop app with a headless CLI (single Gradle project)

**Performance Goals**:
- Each layout keystroke costs O(length of the caret's run); no visible lag.
- Format Program on a 10,000-line disassembly listing takes under 1 s.

**Constraints**:
- Layout only ever changes whitespace.
- Only the learner's own keystrokes trigger it.
- One Undo step per keystroke or format.

**Scale/Scope**:
- 1 new core file (~200 LOC)
- About 120 LOC added to `AsmEditor`
- 1 menu item, a Reference-tab section and a README bullet
- 2 test files
- A whitespace touch-up to one example

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | How the design complies | Status |
|-----------|-------------------------|--------|
| **I. Architectural Fidelity** | Only whitespace outside literals changes. NASM ignores it, so programs keep their meaning under both the simulator and real NASM. A test checks that formatted and original programs assemble to equal `Program`s and give the same run output. No new deviation, so nothing to add to the README limitations. | ✅ |
| **II. Learner-First Clarity** | The conventions and keys are documented in a new "Code layout" section of the Reference tab (`ui/Docs.kt`) and in the README. The feature changes text only, so both themes and every zoom level work unchanged; step 8 of the quickstart checks this. No new instructions or registers, so no new hover entries are needed. | ✅ |
| **III. Test-Backed Semantics** | `SourceLayoutTest` covers classification, literals, Enter indent, the comment column, idempotence, the examples as fixed points, strip-and-restore and assembly preservation. `EditorIndentTest` covers every row of the key contract, including one-step Undo. `./gradlew test` must pass. | ✅ |
| **IV. Headless Core, Observing UI** | All rules live in `asm/SourceLayout.kt`, which has no Swing imports and is unit-tested without a display. `AsmEditor` only maps keys to it and applies the edits. The CLI and machine are untouched. | ✅ |
| **V. Deterministic, Reversible Execution** | No effect on execution or Step Back. Editor Undo (a separate mechanism) gets one step per layout action (research R4). | ✅ (N/A to machine state) |

**Post-design re-check (after Phase 1)**: Still passing. The design adds no dependencies, no core
semantics and no UI-side execution logic. Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
specs/006-asm-editor-indentation/
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1–R8
├── data-model.md        # Phase 1: LineParts, LineKind, Run, operations
├── quickstart.md        # Phase 1: automated and manual validation
├── contracts/
│   └── editor-keys.md   # Phase 1: key/command behavior and layout rules
├── checklists/
│   └── requirements.md  # from /speckit-specify
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
src/main/kotlin/x86sim/
├── asm/
│   ├── SourceLayout.kt        # NEW: split/kind/indentAfter/commentColumnFor/format (headless)
│   └── Assembler.kt           # share the quote-aware comment scan and label regex with SourceLayout
└── ui/
    ├── AsmEditor.kt           # key bindings (Enter, Tab, Shift+Tab, Backspace, ':', ';'), compoundEdit, formatProgram()
    ├── MainWindow.kt          # Edit → Format Program (⇧⌘F)
    └── Docs.kt                # Reference tab: "Code layout" section

src/main/resources/examples/
└── 05_fibonacci.asm           # whitespace-only: line up one run's comments (research R6)

src/test/kotlin/x86sim/
├── SourceLayoutTest.kt        # NEW
└── EditorIndentTest.kt        # NEW

README.md                      # Features: editor indentation and Format Program
```

**Structure Decision**: This is a single Gradle project. The rules go in the headless `asm`
package, next to the assembler whose label and directive definitions they reuse. The Swing work is
limited to `AsmEditor` and one menu item in `MainWindow`.

## Complexity Tracking

No violations. This section is intentionally empty.
