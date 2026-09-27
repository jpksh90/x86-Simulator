# Research: Explain "RIP does not point to an instruction" Faults

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

There were no NEEDS CLARIFICATION items in the Technical Context. The research below settles the
design questions found while reading the code.

## Root cause (confirmed)

`Cpu.step()` (`cpu/Cpu.kt:176`) fetches `code[rip]` and throws
`CpuFault("Segmentation fault: RIP=0x%x does not point to an instruction")` when nothing is there.
The step that moved RIP to the bad address (a fall-through, `jz .done`, `ret`, ...) had already
completed. So when the fault is raised:

- the CPU doesn't know which instruction brought it there,
- `MainWindow.afterStop()` prints `currentInstruction()` (null), so no line is shown,
- `refresh()` clears `editor.currentLine` once the machine is finished, so nothing is highlighted.

Reproduced headlessly: a 6-instruction program ending in `imul` followed by an empty `.done:` gives
exactly `RIP=0x401018`, and `push 1` / `ret` gives `RIP=0x1`.

## R1. Keep the fault or turn it into an exit?

- **Decision**: Keep the segmentation fault. Change only how it is explained.
- **Rationale**: On Linux, running past the end of `.text` executes padding or unmapped bytes and
  crashes. An implicit exit would teach behavior that doesn't exist (Principle I). The existing
  "`ret` from `_start` exits cleanly" convenience is already documented and stays as it is.
- **Alternatives**: Implicit exit at the end of code (rejected because it is unfaithful); stopping
  one step early, before the transfer (rejected because it changes the architectural RIP and step
  count).

## R2. How to know which instruction brought RIP to the bad address

- **Decision**: Add `Cpu.lastRip: Long` (the address of the last instruction that completed, or
  `-1` if none), set at the end of every successful `step()`, reset by `reset()`, and saved and
  restored in `Machine.StepRecord`.
- **Rationale**: This is cheap (one Long per step) and deterministic. It works with history turned
  off (the CLI has `keepHistory = false`), so the history deque can't be used for this. Restoring
  it on Step Back means stepping forward again gives the identical message (FR-008, Principle V).
- **Alternatives**: Using `history.last()` (rejected because the CLI has no history); checking the
  target when `jmp`/`ret` runs and faulting early (rejected because real hardware faults on the
  fetch, with RIP = the target, so RIP and the step count would differ).

## R3. Where the explanation is built

- **Decision**: Add a new headless `analysis/FaultExplainer.kt` with a pure function
  `explainFetch(program, rip, from: Instruction?): FaultReport`. `Cpu` throws a new
  `FetchFault(rip) : CpuFault`. `Machine.step()` catches it, calls the explainer with
  `program.byAddress[cpu.lastRip]`, and stores the resulting report.
- **Rationale**: `Cpu` has only the `code` map and no symbols or line text. `Machine` owns the
  `Program`. A pure function is easy to unit-test for every case (Principle III), and the GUI and
  CLI read the same report (Principle IV, FR-007).
- **Alternatives**: Formatting in the UI (rejected because it breaks Principle IV and the CLI would
  differ); giving `Cpu` a `Program` reference (rejected because it widens the CPU's dependencies
  for text formatting).

## R4. How fault kinds are classified

- **Decision**: Check the cases in this order (see [contracts/fault-messages.md](./contracts/fault-messages.md)):
  1. `from == null` → **ENTRY** (nothing has run yet).
  2. `from.mnemonic == "ret"` → **BAD_RETURN**.
  3. `rip == from.address + slot` and `from` is not an unconditional `jmp`/`call` → **RAN_PAST_END**
     (this includes a conditional jump that wasn't taken, `loop`, a non-exit `syscall`, and a
     taken jump to a label that happens to sit right after `from`).
  4. `from` is a direct `jmp`/`jcc`/`loop`/`call` (`ImmOp` target) and a `.text` label has value
     `rip` → **EMPTY_LABEL**.
  5. `rip` is inside `.text` but not at an instruction start → **MISALIGNED**.
  6. Otherwise → **BAD_TARGET** (indirect `jmp`/`call`, or a direct target that isn't a label).
- **Rationale**: This covers every way RIP can change in this CPU (fall-through, direct and
  indirect transfer, `ret`) and needs no extra runtime state.

## R5. Message shape

- **Decision**: A `FaultReport` has a one-line `headline`, which always starts with
  `Segmentation fault:` and contains `RIP=0x…`, and a `hint` sentence. `Machine.message` is the
  headline. The GUI status bar shows the headline. The console and CLI print the headline, then
  the source line, then the hint.
- **Rationale**: The status bar has room for one line only. Keeping the `Segmentation fault:`
  prefix and the RIP value meets FR-004 and keeps existing learner expectations.
- **Alternatives**: One long sentence (rejected because it gets truncated in the status bar at
  250% zoom).

## R6. Build warnings: how, and where

- **Decision**: Add a new `analysis/ProgramLint.kt` with `warnings(program): List<AsmError>`.
  - **W1**: Build the `ControlFlowGraph` and report each block with `exit == "end of code"` at its
    last instruction. The CFG already recognizes `ret`, `jmp`, `hlt` and exit syscalls (a constant
    `rax` of 60 or 231).
  - **W2**: Report each `.text` label whose address equals the end of `.text`.

  `AsmError` gains `warning: Boolean = false`. `Program` gains `labelLines: Map<String, Int>`,
  recorded in `defineLabel`.
- **Rationale**: Reusing the CFG's exit detection avoids a second analysis and means an `exit`
  syscall never triggers a false warning. Warnings are computed after a successful assembly, so
  they can never block a build (FR-009).
- **Alternatives**: Emitting warnings inside `Assembler` (rejected because `asm/` would depend on
  `analysis/`, a cycle); a full reachability analysis (out of scope per the spec's Assumptions).
- **Check**: All 9 bundled examples end with `ret` or an exit `syscall`. They are covered by a
  zero-warnings test (SC-004).

## R7. GUI presentation

- **Decision**:
  - Add `AsmEditor.faultLine` (painted with `Theme.errorLine`, below the current line) and set it
    from `Machine.faultInstruction` in `refresh()`. It also marks runtime faults such as `div` and
    bad memory accesses, which today lose their highlight when the machine finishes.
  - In the Problems list, warnings are rendered with `⚠` in `Theme.warn`. A build that has only
    warnings doesn't switch tabs. The tab title shows the warning count.
  - When paused at an RIP with no instruction, the NEXT label shows the preview headline
    (`Machine.pendingFetchFault()`).
- **Rationale**: Theme tokens already exist for both themes. The UI only reads core APIs
  (Principle IV).

## R8. Docs and README

- **Decision**: Extend the Reference/hover entries for `ret` and `jmp` with one sentence each on
  what happens when the target isn't code. Add a README "Design notes" bullet: "Execution that
  leaves the program's code stops with a segmentation fault that names the line responsible; the
  simulator does not treat running off the end as an exit."
