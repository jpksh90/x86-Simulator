# Implementation Plan: Replace Examples Dropdown with File Name Label

**Branch**: `004-file-name-label` | **Date**: 2026-09-28 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-file-name-label/spec.md`

## Summary

The toolbar's "Examples" dropdown (`ui/MainWindow.kt:194`) does the same job as the menu bar's
**Examples** menu and always reads "Examples". This change removes it. In its place, a read-only
label names the current document: a file name, an example's display name, or `*New File`, with a
leading `*` when there are unsaved edits.

`MainWindow` currently tracks the document in two variables, `file` and a free-form `docName`
string. It will instead track a small `Document` value (new, example or file). The label text and
tooltip will come from a pure, Swing-free function (`ui/DocumentLabel.kt`) so they can be unit
tested headlessly. `updateTitle()` already runs after New, Open, Save, Save As, example loads and
edits, so it becomes the single place where both the window title and the label are refreshed.
Design decisions are in [research.md](./research.md) (R1–R5).

## Technical Context

**Language/Version**: Kotlin 2.2.10 on the JVM (Gradle JDK 21 toolchain; runs on JDK 17+)

**Primary Dependencies**: Swing + FlatLaf 3.7.2. No new dependencies.

**Storage**: N/A. Files are read and written exactly as today.

**Testing**: `kotlin.test` on JUnit Platform (`./gradlew test`). New `DocumentLabelTest` for the
label and tooltip text. Visual check with `UiSnapshot` in dark and light themes and at 250% zoom.

**Target Platform**: Desktop JVM GUI (macOS/Windows/Linux). The headless CLI is untouched.

**Project Type**: Desktop app + CLI, a single Gradle project (`src/main/kotlin/x86sim`)

**Performance Goals**: N/A. The label updates on document events, not on every step.

**Constraints**: The label must not push toolbar buttons off-screen (SC-004). Its width is capped
and long names are shortened with an ellipsis.

**Scale/Scope**: One UI file changed (`MainWindow.kt`), one small file added (`DocumentLabel.kt`),
one test added.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment | Status |
|-----------|------------|--------|
| I. Architectural Fidelity | No change to instruction, flag, syscall or assembler behavior. | PASS |
| II. Learner-First Clarity | Improves clarity, since the learner always sees which program is open. The label follows theme colors and zoom through `Theme.onChange`, like other toolbar text. It needs no hover help or Reference entry because it's not an instruction, register or syscall. | PASS |
| III. Test-Backed Semantics | No execution semantics change. The naming rules (FR-003 to FR-006) are unit tested in `DocumentLabelTest`. | PASS |
| IV. Headless Core, Observing UI | Only UI code changes. `DocumentLabel.kt` has no Swing imports, so its tests run without a display. The core, CLI and `run --example` are untouched. | PASS |
| V. Deterministic, Reversible Execution | Document naming is not machine state and isn't part of the undo history. | PASS |
| Tech & Scope | No new dependencies. `./gradlew run` and `./gradlew installDist` are unaffected. | PASS |
| Workflow gates | Tests; the CLI is unaffected; README gets a one-line toolbar mention; `UiSnapshot` check in both themes. | PASS |

**Post-design re-check**: The design in `data-model.md` and `contracts/` adds no core dependencies
and no execution logic, so all gates still PASS.

## Project Structure

### Documentation (this feature)

```text
specs/004-file-name-label/
├── plan.md              # This file
├── research.md          # Phase 0: design decisions R1–R5
├── data-model.md        # Phase 1: Document value and label derivation rules
├── quickstart.md        # Phase 1: manual + automated validation
├── contracts/
│   └── toolbar-label.md # Phase 1: exact label/tooltip text for every document state
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/kotlin/x86sim/ui/
├── DocumentLabel.kt   # NEW: Document (New | Example | Opened) + labelText()/tooltipText(); no Swing
└── MainWindow.kt      # CHANGED: drop the JComboBox, add docLabel, docName/file → document,
                       #          updateTitle() also refreshes the label

src/test/kotlin/x86sim/
├── DocumentLabelTest.kt  # NEW: every row of contracts/toolbar-label.md
└── UiSnapshot.kt         # unchanged; used for the visual check

README.md                 # one line: the toolbar shows the open file; examples are in the Examples menu
```

**Structure Decision**: Single existing Gradle project. The new code lives in `ui/` because it is
purely presentational, but it has no Swing imports so it can be tested headlessly.

## Complexity Tracking

No Constitution violations, so nothing to justify.
