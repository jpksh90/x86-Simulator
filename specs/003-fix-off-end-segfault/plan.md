# Implementation Plan: Explain "RIP does not point to an instruction" Faults

**Branch**: `003-fix-off-end-segfault` | **Date**: 2026-09-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-fix-off-end-segfault/spec.md`

## Summary

When RIP reaches an address with no instruction, the simulator correctly stops with a segmentation
fault. However, the message (`RIP=0x401018 does not point to an instruction`) names no line and no
cause, and the GUI highlights nothing. The fix keeps the fault and adds provenance and explanation:

- `Cpu` remembers the address of the last completed instruction (`lastRip`, undoable).
- The fetch fault becomes a typed `FetchFault`.
- A new headless `analysis/FaultExplainer` classifies the fault into six kinds (ran past the end,
  empty label, bad `ret`, bad indirect target, misaligned, entry) and produces a one-line headline
  plus a hint.
- The GUI and CLI show the same report. The GUI highlights the responsible line and previews the
  coming fault in the NEXT label.
- A new `analysis/ProgramLint` reuses the CFG's existing "end of code" detection to give
  non-blocking build warnings.

Design decisions are in [research.md](./research.md) (R1–R8).

## Technical Context

**Language/Version**: Kotlin 2.2.10 on the JVM (Gradle JDK 21 toolchain; runs on JDK 17+)

**Primary Dependencies**: Swing + FlatLaf 3.7.2. No new dependencies.

**Storage**: N/A

**Testing**: `kotlin.test` on JUnit Platform (`./gradlew test`). This feature adds
`FaultExplainerTest` and `ProgramLintTest`, and extends `StepBackTest` and `SimulatorTest`.

**Target Platform**: Desktop JVM GUI (macOS/Windows/Linux) and headless CLI (`x86learn run`)

**Project Type**: Desktop app + CLI, a single Gradle project (`src/main/kotlin/x86sim`)

**Performance Goals**: No measurable slowdown per step. The only hot-path cost is one extra Long
store per step. Explaining happens only when a fault occurs. Linting runs once per build and is
linear in the number of instructions.

**Constraints**:
- Identical GUI and CLI text.
- The undo record grows by one Long and one reference.
- The status-bar headline stays on one line.

**Scale/Scope**: 2 new core files, about 6 changed production files, 2 new test files and 2
extended ones. README and Docs are updated too.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-research | Post-design |
|---|---|---|---|
| **I. Architectural Fidelity** | Behavior matches hardware and Linux. Deviations are documented. | ✅ The fault is kept (spec FR-001). | ✅ R1: no implicit exit. R2: the fault is still raised on fetch with RIP = the bad target and an unchanged step count. MISALIGNED wording reflects the documented 4-byte-slot simplification. The README gets a design note (R8). |
| **II. Learner-First Clarity** | Errors name the line and cause in beginner words. Changes are visible. Hover/Reference docs exist. Both themes and all zoom levels work. | ✅ This is the point of the feature. | ✅ The [fault-messages contract](./contracts/fault-messages.md) defines a line, cause and hint for every kind. The editor fault highlight uses theme tokens. There is a NEXT preview. `ret`/`jmp` docs are extended. Themes and zoom are checked in quickstart §3.5. |
| **III. Test-Backed Semantics** | Tests for every fault. A regression test for the bug. `./gradlew test` green. | ✅ FR-012. | ✅ One `FaultExplainerTest` case per kind, including the exact reported program (`0x401018`). `ProgramLintTest` covers W1/W2 and all examples. A Step Back replay test is included. |
| **IV. Headless Core, Observing UI** | No Swing in the core. CLI = GUI. No logic in the UI. | ✅ | ✅ Classification and linting live in `analysis/` (pure functions). `Machine` exposes `fault`, `faultInstruction` and `pendingFetchFault()`. The UI and CLI only render them. |
| **V. Deterministic, Reversible Execution** | Every state change is undoable. A Step Back test. Undo size is proportional to the change. | ✅ | ✅ `lastRip` and `fault` are saved in `StepRecord` and restored. The replay test proves an identical report. The cost is O(1) per step. |
| **Tech & Scope** | No new deps. Memory layout unchanged. | ✅ | ✅ |
| **Workflow gates** | Tests, GUI + CLI, README/docs, themes | ✅ | ✅ Quickstart §1–§3. No new example is needed. The fault is demonstrated by the quickstart programs, and existing examples must stay warning-free. |

**Result**: PASS with no violations. Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/003-fix-off-end-segfault/
├── plan.md                    # This file
├── research.md                # Phase 0: root cause + decisions R1–R8
├── data-model.md              # Phase 1: FetchFault, FaultKind, FaultReport, changed types, state diagram
├── quickstart.md              # Phase 1: validation guide (tests, CLI, GUI)
├── contracts/
│   └── fault-messages.md      # headline/hint per kind, surfaces, build warnings W1/W2
├── checklists/
│   └── requirements.md        # spec quality checklist
└── tasks.md                   # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/kotlin/x86sim/
├── cpu/
│   ├── Memory.kt            # + class FetchFault(rip) : CpuFault
│   └── Cpu.kt               # + lastRip (set per step, reset); throw FetchFault on missing code
├── asm/
│   └── Assembler.kt         # AsmError.warning flag; Program.labelLines (recorded in defineLabel)
├── analysis/
│   ├── FaultExplainer.kt    # NEW: FaultKind, FaultReport, explainFetch(program, rip, from)
│   └── ProgramLint.kt       # NEW: warnings(program): W1 via ControlFlowGraph "end of code", W2 empty labels
├── Machine.kt               # catch FetchFault → fault report; StepRecord += lastRip, fault;
│                            #   faultInstruction; pendingFetchFault()
├── Main.kt                  # runCli: print warnings; print "[sim] hint: …"
└── ui/
    ├── AsmEditor.kt         # faultLine highlight (Theme.errorLine)
    ├── MainWindow.kt        # show warnings (non-blocking); console headline/line/hint;
    │                        #   faultLine from machine.faultInstruction; NEXT preview
    └── Docs.kt              # ret/jmp entries: what happens when the target isn't code

src/test/kotlin/x86sim/
├── FaultExplainerTest.kt    # NEW
├── ProgramLintTest.kt       # NEW
├── StepBackTest.kt          # + fault replay; snapshot includes lastRip
└── SimulatorTest.kt         # update "does not point to an instruction" assertion

README.md                    # design note: leaving the code faults, with an explanation
```

**Structure Decision**: This is the existing single Gradle project. The new logic goes in
`analysis/`, next to `ControlFlowGraph`, which it reuses. That keeps `cpu/` free of text
formatting and avoids an `asm/` → `analysis/` dependency cycle.

## Complexity Tracking

No violations. This section is intentionally empty.
