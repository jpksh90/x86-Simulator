# Implementation Plan: String Instructions with REP Prefixes

**Branch**: `001-string-instructions` | **Date**: 2026-09-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-string-instructions/spec.md`

## Summary

Add the 20 NASM string instructions (`movs`/`stos`/`lods`/`scas`/`cmps` × `b`/`w`/`d`/`q`) and
the `rep`/`repe`/`repz`/`repne`/`repnz` prefixes to the assembler and CPU. Each `Cpu.step()` on a
prefixed instruction performs **one iteration** and leaves RIP on the instruction until the repeat
ends, as hardware does. This reuses the existing `Machine.step()` / `StepRecord` machinery, so the
following come with no new mechanism:
- iteration-level Step Back
- change highlighting
- the CLI trace
- the fault behavior

A small new core flag, `Cpu.repeating`, lets breakpoints fire once per instruction and lets Step
Over finish a repeat at full speed. String semantics live in a new `cpu/StringOps.kt`, which is
shared by the assembler, CPU, docs and status bar. The work ships with hover/Reference docs,
status-bar explanations, a `09_strings` example and README updates
([research.md](./research.md)).

## Technical Context

**Language/Version**: Kotlin 2.2.10 on the JVM (Gradle JDK 21 toolchain; runs on JDK 17+)

**Primary Dependencies**: Swing + FlatLaf 3.7.2 (+ Inter / JetBrains Mono font packs). No new
dependencies.

**Storage**: N/A (in-memory simulated address space; examples are classpath resources)

**Testing**: `kotlin.test` on JUnit Platform (`./gradlew test`). Existing suites:
`SimulatorTest`, `StepBackTest`, `ControlFlowGraphTest`. `UiSnapshot` is a dev helper for
visual checks.

**Target Platform**: Desktop JVM (macOS/Windows/Linux GUI) and headless CLI (`x86sim run`)

**Project Type**: Desktop app + CLI, a single Gradle project (`src/main/kotlin/x86sim`)

**Performance Goals**: 1,000,000-iteration repeat in < 2 s via Step Over (any speed) or Run at Max.
Pause takes effect in ≤ 0.5 s. No noticeable slowdown to existing programs.

**Constraints**:
- Undo memory per iteration is proportional to the bytes that iteration wrote (≤ 8), and the
  history is bounded at 50,000 steps.
- The UI thread is never blocked for more than one ~15 ms tick.
- Identical GUI and CLI results.

**Scale/Scope**: 20 mnemonics and 5 prefix spellings. About 6 production files change, plus 1 new
core file, 1 example, 1 new test file and 2 extended test files.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-research | Post-design |
|---|---|---|---|
| **I. Architectural Fidelity** | Semantics follow the Intel SDM and NASM syntax. Deviations are documented in README. | ✅ Spec requires SDM behavior. Out-of-scope items are listed. | ✅ Hardware semantics are covered: per-iteration restartability (R1), F3 = REPE on scas/cmps (R6), memory-before-registers ordering for faults (data-model), 64-bit RCX/RSI/RDI. README limitations are updated (H5). |
| **II. Learner-First Clarity** | Actionable errors, visible changes, hover plus a Reference entry for every addition, both themes and all zoom levels. | ✅ FR-002, FR-014–FR-016. | ✅ Error table E1–E11 with key phrases ([assembly-syntax](./contracts/assembly-syntax.md)). Hover/Reference/status-bar contract ([ui-help](./contracts/ui-help.md)). Theme and zoom check in quickstart §3.8. |
| **III. Test-Backed Semantics** | Unit tests for every instruction, flag rule, fault and directive. Regression tests. `./gradlew test` green. | ✅ Planned. | ✅ `StringInstructionsTest` (family × size × DF, flags-equal-cmp property grid, RCX = 0, early stop, fault, perf), assembler error tests, Step Back tests (quickstart §1). |
| **IV. Headless Core, Observing UI** | No Swing in `cpu/`/`asm/`/`analysis/`/`Machine.kt`. CLI = GUI. UI holds no execution logic. | ✅ | ✅ All semantics are in `cpu/StringOps.kt` + `Cpu.kt`. The "mid-repeat" knowledge is `Cpu.repeating` (core), not in the UI (R2). The UI only reads it for breakpoint gating and gives Step Over a stop condition, which is the same pattern Step Over already uses for `call`. |
| **V. Deterministic, Reversible Execution** | Every state change is undoable. New instructions get a Step Back test. Undo memory is proportional to the change. | ✅ | ✅ One `StepRecord` per iteration, including `repeating` (data-model). Memory undo ≤ 8 bytes per iteration. The examples loop in `StepBackTest` covers `09_strings`, plus a dedicated per-iteration test. No new nondeterminism. |
| **Tech & Scope** | Kotlin/JDK 21, Swing/FlatLaf, no new deps, user-mode integer scope, memory layout unchanged. | ✅ | ✅ No dependencies are added. `ins`/`outs` (privileged), segment overrides, `a32` and SSE are rejected with explanations. The layout is untouched. |
| **Workflow gates** | Tests pass, GUI + CLI verified, README + docs updated, both themes checked, example added. | ✅ | ✅ Covered by quickstart §1–§4 and H5. |

**Result**: PASS with no violations. Complexity Tracking is not needed.

## Project Structure

### Documentation (this feature)

```text
specs/001-string-instructions/
├── plan.md              # This file
├── research.md          # Phase 0: design decisions R1–R11
├── data-model.md        # Phase 1: RepPrefix, StringOp, Instruction/Cpu/StepRecord/Pending changes
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   ├── assembly-syntax.md      # grammar + required error messages E1–E11
│   ├── execution-semantics.md  # per-step semantics, faults, Step Back, GUI controls, CLI, perf
│   └── ui-help.md              # hover/Reference/status bar/highlighting/README
├── checklists/
│   └── requirements.md  # spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/kotlin/x86sim/
├── cpu/
│   ├── StringOps.kt        # NEW: RepPrefix, StringOp (family, size, stop rule, mnemonic sets)
│   ├── Instruction.kt      # + prefix: RepPrefix = NONE
│   └── Cpu.kt              # + repeating flag; stringStep(): one iteration per step
├── asm/
│   └── Assembler.kt        # parse prefixes; Pending.prefix; string-op validation + errors E1–E11;
│                           #   reserve prefixes/mnemonics as label names
├── analysis/
│   └── ControlFlowGraph.kt # lods* added to IMPLICIT_RAX_WRITERS
├── Machine.kt              # StepRecord saves/restores cpu.repeating
├── Main.kt                 # Examples.names += "09_strings"
└── ui/
    ├── Docs.kt             # family + prefix entries; lookup() resolves sized mnemonics; Reference group
    ├── AsmEditor.kt        # highlight mnemonic after a prefix
    └── MainWindow.kt       # breakpoint gate (!cpu.repeating); Step Over for repeats at full speed;
                            #   NEXT-line iteration description

src/main/resources/examples/
└── 09_strings.asm          # NEW: rep movsb / rep stosq / repne scasb / repe cmpsb demo

src/test/kotlin/x86sim/
├── StringInstructionsTest.kt  # NEW: semantics, flags≡cmp grid, prefixes, faults, perf
├── SimulatorTest.kt           # + assembler errors E1–E11, 09_strings output
└── StepBackTest.kt            # + per-iteration undo; snapshot includes cpu.repeating

README.md                      # supported instructions, limitations, features, example count
```

**Structure Decision**: keep the existing single-project layout. The only new production file is
`cpu/StringOps.kt`, which keeps `Cpu.kt` focused and gives the assembler, CPU and UI one shared
definition of the 20 mnemonics (research R8).

## Implementation Order (for /speckit-tasks)

The order follows the user-story priorities in the spec. Each step leaves `./gradlew test` green.

1. **Foundation**: `StringOps.kt`, `Instruction.prefix`, assembler parsing and errors E1–E11, with
   tests. After this, programs assemble but execution throws "Unsupported instruction".
2. **US1 (P1)**: `movs`/`stos` element operations + REP loop in `Cpu`, with semantics tests
   (DF, RCX = 0, overlap, fault).
3. **US2 (P1)**: `Cpu.repeating` + `StepRecord` restore, the breakpoint gate, Step Over for
   repeats, the NEXT-line description, and Step Back tests.
4. **US3 (P2)**: `lods`/`scas`/`cmps`, the REPE/REPNE stop rules, the flags≡cmp grid test, and the
   CFG `IMPLICIT_RAX_WRITERS` change.
5. **US4 (P3)**: Docs entries + Reference group, editor highlighting, `09_strings.asm` + its
   output test, README.
6. **Polish**: performance test (1 M iterations < 2 s), GUI walkthrough (quickstart §3) in both
   themes, and the zoom check.

## Complexity Tracking

No constitution violations to justify.
