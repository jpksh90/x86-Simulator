---

description: "Task list for Explain 'RIP does not point to an instruction' Faults"
---

# Tasks: Explain "RIP does not point to an instruction" Faults

**Input**: Design documents from `specs/003-fix-off-end-segfault/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/fault-messages.md](./contracts/fault-messages.md),
[quickstart.md](./quickstart.md)

**Tests**: **Included, and written first.** Constitution Principle III (NON-NEGOTIABLE) requires a
regression test for every bug fix and a test for every fault. Spec FR-012 requires one per fault
kind and one per warning. In each phase, write the test tasks first and confirm they fail before
implementing.

**Organization**: grouped by user story. US1 = running off the end and empty labels (P1). US2 = bad
`ret` and indirect or misaligned targets (P2). US3 = build warnings (P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on incomplete tasks)
- Paths are relative to the repo root `/Users/jyp/x86-Simulator`. Production code is in
  `src/main/kotlin/x86sim/`, and tests are in `src/test/kotlin/x86sim/`.

## Conventions for every task

- Run `./gradlew test` after each task that touches production code. Existing tests must stay green
  unless a task explicitly updates them.
- Match the existing style: terse KDoc one-liners, `when` dispatch, and no Swing imports outside
  `ui/`.
- Fault and warning texts must contain the **key phrases** in
  [contracts/fault-messages.md](./contracts/fault-messages.md) exactly. Tests assert on those
  phrases, not on the whole sentences.
- Line numbers in messages are 1-based (`line + 1`). RIP is formatted `0x%x` (lowercase hex).
- Local labels are stored qualified (`_start.done`) but always displayed as written
  (`.done`): take the substring from the first `.` after the global part.

---

## Phase 1: Setup

- [X] T001 Create and switch to branch `003-fix-off-end-segfault` from the current HEAD (`git checkout -b 003-fix-off-end-segfault`). The spec folder `specs/003-fix-off-end-segfault/` already exists.
- [X] T002 Run `./gradlew test` and confirm the baseline is green before any change. Record any pre-existing failures in the PR description instead of fixing them here.

---

## Phase 2: Foundational (blocks all user stories)

**Purpose**: provenance (`lastRip`), the typed fetch fault, the report type and the Machine wiring.
Until US1/US2 fill in the classification, the explainer returns the **legacy headline**
`Segmentation fault: RIP=0x%x does not point to an instruction`. That keeps
`SimulatorTest.faults are reported` green.

### Tests first

- [X] T003 [P] In `src/test/kotlin/x86sim/StepBackTest.kt`, add `m.cpu.lastRip` and `m.fault` to `Rig.snapshot()`. Add a test `lastRip tracks the previous instruction and is undone`: assemble `_start:\n mov eax, 1\n mov ebx, 2\n ret\n`. Check that `lastRip == -1` after load, `== 0x401000` after one step and `== 0x401004` after two. After `stepBack()` it must be `0x401000` again. Expected to fail to compile until T004–T008.

### Implementation

- [X] T004 [P] In `src/main/kotlin/x86sim/cpu/Memory.kt`, add `class FetchFault(val rip: Long) : CpuFault("Segmentation fault: RIP=0x%x does not point to an instruction".format(rip))` next to `CpuFault`.
- [X] T005 In `src/main/kotlin/x86sim/cpu/Cpu.kt`:
  - Add `/** Address of the last instruction that completed a step, or -1 before the first one. */ var lastRip = -1L`.
  - Reset it to `-1` in `reset()`.
  - In `step()`, replace the `CpuFault` thrown when `code[rip]` is null with `throw FetchFault(rip)`.
  - Set `lastRip = ins.address` after every step that returns normally (Ok, Exit, Halt), including string-instruction iterations from `stringStep`. Do **not** set it on `Blocked` or when a fault is thrown. Set it in one place, for example by wrapping the body.

  Depends on T004.
- [X] T006 [P] In `src/main/kotlin/x86sim/asm/Assembler.kt`, add `val labelLines: Map<String, Int> = emptyMap()` to `Program`. Record `labelLines[name] = ln` in `defineLabel` using a new `private val labelLines = mutableMapOf<String, Int>()`, and pass it into the `Program(...)` constructor call at the end of `assemble`.
- [X] T007 [P] Create `src/main/kotlin/x86sim/analysis/FaultExplainer.kt`:
  - `enum class FaultKind { ENTRY, RAN_PAST_END, EMPTY_LABEL, BAD_RETURN, BAD_TARGET, MISALIGNED }`
  - `data class FaultReport(val kind: FaultKind, val rip: Long, val line: Int?, val source: String?, val label: String?, val headline: String, val hint: String)`
  - `object FaultExplainer { fun explainFetch(program: Program, rip: Long, from: Instruction?): FaultReport }`. For now it returns kind `BAD_TARGET` with the legacy headline and an empty hint.
  - A private helper `displayLabel(program, addr): String?` that returns the `.text` label whose value is `addr`, shown as written (local labels as `.name`). Prefer a local label over a global one when both exist.
  - A helper `sourceOf(ins)` that returns `ins.source.substringBefore(';').trim()`.

  The data-model rules are binding: "`headline` always contains `Segmentation fault:` and `RIP=0x` followed by lowercase hex" and "`line` is non-null for every kind except `ENTRY`". No Swing imports.
- [X] T008 In `src/main/kotlin/x86sim/Machine.kt`:
  - Add `var fault: FaultReport? = null; private set`, and clear it in `load()`.
  - Add `lastRip: Long` and `fault: FaultReport?` to `StepRecord`. Capture them in `step()` and restore both (`cpu.lastRip = r.lastRip; fault = r.fault`) in `stepBack()`.
  - In the `catch (f: CpuFault)` block of `step()`: if `f is FetchFault && program != null`, set `fault = FaultExplainer.explainFetch(program!!, f.rip, program!!.byAddress[cpu.lastRip])` and `message = fault!!.headline`. Otherwise keep the current behavior.
  - Add `val faultInstruction: Instruction?`. It is null unless `state == FAULTED`. It returns `cpu.currentInstruction() ?: fault?.line?.let { program?.byLine?.get(it) }`.
  - Add `fun pendingFetchFault(): FaultReport?`. When `canStep` and `cpu.currentInstruction() == null` and `program != null`, it returns `explainFetch(program, cpu.rip, program.byAddress[cpu.lastRip])`. Otherwise it returns null. It must not change any state.

  Depends on T005–T007.
- [X] T009 Run `./gradlew test`. T003 and all existing tests (including `faults are reported` and the examples round-trip in `StepBackTest`) must pass.

**Checkpoint**: behavior is identical to before, but each fetch fault now carries its provenance
and survives Step Back.

---

## Phase 3: User Story 1 - Running off the end of the code (Priority: P1) 🎯 MVP

**Goal**: a program that falls past its last instruction, jumps to a label with nothing after it,
or starts at an empty `_start` stops with a message that names the line and the cause. The GUI
highlights that line, and the CLI prints the same text.

**Independent test**: run the quickstart `past_end.asm` in the CLI and the GUI. Both name line 8
and `RIP=0x401018` and say "ran past the last instruction".

### Tests first

- [X] T010 [P] [US1] Create `src/test/kotlin/x86sim/FaultExplainerTest.kt`. Add a helper `fun fault(src: String): Machine` that assembles `src`, loads it into a `Machine`, calls `runToEnd()`, asserts `state == MachineState.FAULTED` and returns the machine. Add these tests:
  - (a) **Regression (the reported bug)**: `_start:\n mov rdi, 2\n mov rsi, 10\n mov rax, 1\n.loop:\n test rsi, rsi\n jz .done\n imul rax, rdi\n.done:\n`. Expect `fault!!.kind == RAN_PAST_END`, `rip == 0x401018`, `line == 7` (0-based; `imul`), and `label == ".done"`. The `message` must contain `"ran past the last instruction"`, `"RIP=0x401018"` and `"line 8"`. The hint must contain `"exit syscall"` and `"'.done'"`.
  - (b) A program that falls through a not-taken `jz` as its last instruction (`_start:\n xor eax, eax\n inc eax\n jz _start\n`) gives `RAN_PAST_END` with the `jz` line.
  - (c) `EMPTY_LABEL`: `_start:\n jmp .done\n nop\n.done:\n` gives kind `EMPTY_LABEL`, line 1, label `.done`. The message contains `"jumped to label '.done'"` and `"no instruction after it"`.
  - (d) `EMPTY_LABEL` via `call`: `_start:\n call f\n ret\n nop\nf:\n`. The message contains `"called"`.
  - (e) `ENTRY`: `nop\n_start:\n`. `lastRip == -1`, so the kind is `ENTRY` and `line == null`. The message contains `"no instruction after _start"`.
  - (f) **FR-010**: `_start:\n mov eax, 3\n ret\n` ends `EXITED` with `exitCode == 3` and `fault == null`.
  - (g) Every report in (a)–(e) has a `message` that starts with `"Segmentation fault:"` and contains `"RIP=0x"`.

  All of these must fail before T012.
- [X] T011 [P] [US1] In `src/test/kotlin/x86sim/StepBackTest.kt`, add `a fetch fault replays identically after stepping back`. Use the T010(a) program: `runToEnd()`, save `fault` and `message`, call `stepBack()`, and assert `state == PAUSED`, `fault == null` and `cpu.rip == 0x401018`. Then `step()` and assert the fault and message are equal to the saved ones. Also call `stepBack()` twice and assert `cpu.rip` is the `imul` address.

### Implementation

- [X] T012 [US1] In `src/main/kotlin/x86sim/analysis/FaultExplainer.kt`, implement classification steps 1, 3 and 4 of research R4, using the texts in [contracts/fault-messages.md](./contracts/fault-messages.md):
  - `from == null` gives `ENTRY`.
  - `rip == from.address + Instruction.INSTRUCTION_SLOT && from.mnemonic !in setOf("jmp", "call")` gives `RAN_PAST_END`. Add the label clause to the hint only when `displayLabel(rip) != null`.
  - `from.operands.firstOrNull() is ImmOp` with a jump, `loop` or `call` mnemonic, and `displayLabel(rip) != null`, gives `EMPTY_LABEL`. Use "called" for `call` and "jumped to label" otherwise.

  Leave the other cases returning the legacy `BAD_TARGET` report (US2 fills them in). Make T010 pass.
- [X] T013 [US1] In `src/main/kotlin/x86sim/Main.kt` `runCli`, after the final `[sim] <message> (N instructions)` line, print `System.err.println("[sim] hint: ${it.hint}")` when `m.fault != null`. The exit code stays `139`.
- [X] T014 [P] [US1] In `src/main/kotlin/x86sim/ui/AsmEditor.kt`, add `var faultLine = -1` (repaint on set). In the paint path next to `errorLines` (around line 123), fill that line with `Theme.errorLine` **before** the current-line fill, so the current line wins if both are set. In the gutter, draw its line number in `Theme.bad`.
- [X] T015 [US1] In `src/main/kotlin/x86sim/ui/MainWindow.kt`:
  - In `refresh()`, set `editor.faultLine = machine.faultInstruction?.line ?: -1`, and scroll to it when it is set.
  - In `afterStop()` `FAULTED`: keep `console.error(machine.message)`. Then print the line from `machine.faultInstruction` (`"L${line + 1}: <source without comment>"`), not from `currentInstruction()`. Then print `console.error(it.hint)` when `machine.fault` is non-null.
  - In the `nextLabel` `when`: when `ins == null && !machine.isFinished`, show `machine.pendingFetchFault()` as `NEXT  no instruction at RIP=0x… — the next step will fault: <short kind phrase>` in `Theme.bad`, HTML-escaped. The short phrases are "ran past the last instruction", "label with no instruction", "not a return address", "not an instruction", "middle of an instruction" and "empty _start".
  - Make sure `stepBack()` clears the highlight (it does once `refresh` reads `faultInstruction`).

  Depends on T008, T012 and T014.
- [X] T016 [US1] Run `./gradlew test` (T010 and T011 green). Then do quickstart §2 for `past_end.asm` and `empty_label.asm`, and §3 steps 2–4 in the GUI.

**Checkpoint**: the reported bug is fixed end-to-end. This is shippable as the MVP.

---

## Phase 4: User Story 2 - Bad `ret` and indirect targets (Priority: P2)

**Goal**: `ret` to a non-return address, indirect `jmp`/`call` to non-code, and jumps into the
middle of an instruction each get a specific message.

**Independent test**: the quickstart `bad_ret.asm` and `bad_jmp.asm` name the `ret`/`jmp` line and
the bad RIP, with the contract's key phrases.

### Tests first

- [X] T017 [P] [US2] Add these tests to `src/test/kotlin/x86sim/FaultExplainerTest.kt`:
  - (a) `_start:\n push 1\n ret\n` gives `BAD_RETURN`, `rip == 1`, and line 2 (0-based). The message contains `"ret on line 3"` and `"which is not a return address"`. The hint contains `"matching pop"`.
  - (b) `_start:\n mov rax, 5\n jmp rax\n` gives `BAD_TARGET`, `rip == 5`, and a message containing `"which is not an instruction"` and `"line 2"`.
  - (c) `_start:\n mov rax, 7\n call rax\n` gives `BAD_TARGET`.
  - (d) `_start:\n mov rax, _start\n add rax, 2\n jmp rax\n` gives `MISALIGNED` with `rip == 0x401002`. The message contains `"inside the code but not at the start of an instruction"`. The hint names line 1 and `0x401000` as the nearest instruction.
  - (e) `ret` inside a called function whose stack is unbalanced: `_start:\n call f\n ret\nf:\n push 9\n ret\n` gives `BAD_RETURN` with `rip == 9` and the inner `ret` line.
- [X] T018 [P] [US2] In `src/test/kotlin/x86sim/SimulatorTest.kt` `faults are reported`, change the assertion `contains("does not point to an instruction")` to `contains("which is not a return address")`. This is expected to fail until T019.

### Implementation

- [X] T019 [US2] In `src/main/kotlin/x86sim/analysis/FaultExplainer.kt`, implement R4 steps 2, 5 and 6:
  - `from.mnemonic == "ret"` gives `BAD_RETURN`. It is checked **before** `RAN_PAST_END`.
  - `rip` inside `[TEXT_BASE, last instruction address + slot)` and not in `program.byAddress` gives `MISALIGNED`. The hint names the instruction at the greatest address `<= rip`.
  - Everything else gives `BAD_TARGET`.

  Remove the legacy headline entirely. Make T017, T018 and all of T010 pass.
- [X] T020 [US2] Run `./gradlew test` and quickstart §2 for `bad_ret.asm` and `bad_jmp.asm`.

**Checkpoint**: every fetch-fault kind has a specific message (SC-001).

---

## Phase 5: User Story 3 - Build warnings (Priority: P3)

**Goal**: warn at build time, without blocking the run, when code can run past the end or a label
has no instruction after it.

**Independent test**: building `past_end.asm` shows 2 warnings, and Run still works. All examples
show zero warnings.

### Tests first

- [X] T021 [P] [US3] Create `src/test/kotlin/x86sim/ProgramLintTest.kt` with these tests:
  - (a) The T010(a) program gives exactly 2 warnings, sorted by line. The first is at line 7 (`imul`) with text containing `"execution can run past the last instruction"`. The second is at line 8 (`.done:`) and contains `"label '.done' has no instruction after it"`. Both have `warning == true`.
  - (b) `_start:\n jmp .done\n nop\n.done:\n` gives only the W2 warning. The unreachable `nop` block gives no W1.
  - (c) `_start:\n mov eax, 60\n xor edi, edi\n syscall\n` gives no warnings.
  - (d) `_start:\n ret\n` gives no warnings.
  - (e) For every `id` in `Examples.names`, `ProgramLint.warnings(Assembler.assemble(Examples.load(id)))` is empty (SC-004).

### Implementation

- [X] T022 [P] [US3] In `src/main/kotlin/x86sim/asm/Assembler.kt`, add `val warning: Boolean = false` to `AsmError`. Leave `toString()` unchanged.
- [X] T023 [US3] Create `src/main/kotlin/x86sim/analysis/ProgramLint.kt` with `object ProgramLint { fun warnings(program: Program): List<AsmError> }`:
  - **W1**: build `ControlFlowGraph(program)`. For each block that is reachable from `program.entry` (a BFS over `edges`, plus call targets listed in `functions`) and has `exit == "end of code"`, add a warning at `block.last.line` with the text `execution can run past the last instruction: end with ret, jmp or an exit syscall`.
  - **W2**: for each `(name, value)` in `program.symbols` where `value == last instruction address + INSTRUCTION_SLOT` and `name in program.labelLines`, add a warning at `labelLines[name]` with the text `label '<display name>' has no instruction after it`.
  - Deduplicate by line (W2 wins) and sort by line. All entries have `warning = true`.

  Depends on T006 and T022. Make T021 pass.
- [X] T024 [US3] In `src/main/kotlin/x86sim/Main.kt` `runCli`, after a successful assemble, print `System.err.println("warning: $it")` for each `ProgramLint.warnings(program)` before running. The exit code is unaffected.
- [X] T025 [US3] In `src/main/kotlin/x86sim/ui/MainWindow.kt`:
  - In `assemble()` on success, compute `ProgramLint.warnings(p)` and add them to `problems`.
  - Set the tab title to `Problems` when there are none, otherwise to `Problems (N warning[s])`.
  - Append ` · N warning[s]` to the `Built · …` message.
  - Do **not** change `bottomTabs.selectedIndex` for warnings.
  - In the `problemList` cell renderer, render `value.warning` as `"⚠  L${line + 1}  ${message}"` with foreground `Theme.warn`. Errors stay `×` in `Theme.bad`.
  - Keep `editor.errorLines` for errors only.
- [X] T026 [US3] Run `./gradlew test`, quickstart §2 (the `warning:` lines) and §3 steps 1 and 6.

**Checkpoint**: all three stories work independently.

---

## Phase 6: Polish & Cross-Cutting

- [X] T027 [P] In `src/main/kotlin/x86sim/ui/Docs.kt`, extend the `ret` entry description with "If the popped value isn't the address of an instruction, the program stops with a segmentation fault." Extend `jmp` (and `call`) with "Jumping to an address with no instruction, such as a label at the very end of the code, stops the program with a segmentation fault." Keep the entries as short as the existing ones.
- [X] T028 [P] In `README.md` "Design notes and limitations", add a bullet saying that execution which leaves the program's code (running past the last instruction, a bad `ret` or jump target) stops with a segmentation fault that names the responsible line, as on real Linux. Running off the end is not treated as an exit, so end `_start` with an `exit` syscall or `ret`. Mention the build warnings under Features.
- [X] T029 Visual check (Principle II): use `src/test/kotlin/x86sim/UiSnapshot.kt` (or the GUI) to render the faulted `past_end.asm` state in the dark and light themes at 80% and 250% zoom. Confirm that the fault line highlight is visible, the status-bar headline doesn't overflow, and the warning rows in Problems are readable. *Done: rendered dark 100%, light 100% and light 250% (`UiSnapshot` gained a `FILE:<path>` mode). 80% was not rendered because the helper can only zoom in. The long headline originally clipped mid-word, so the status bar now truncates with “…” and shows the full text in a tooltip.*
- [X] T030 Run the whole [quickstart.md](./quickstart.md), including `./gradlew installDist` and the `x86learn run --example 01_hello` check that there are no warnings and the output is unchanged. Then run the final `./gradlew test`.

---

## Dependencies & Execution Order

```text
Setup (T001–T002)
  └─> Foundational (T003–T009)
        ├─> US1 (T010–T016)  🎯 MVP
        │     └─> US2 (T017–T020)  (same file FaultExplainer.kt; R4 ordering puts ret before fall-through)
        └─> US3 (T021–T026)  (needs only T006 from Foundational; independent of US1/US2)
                  └─> Polish (T027–T030) after the desired stories
```

- US2 depends on US1 only because both edit `FaultExplainer.kt`. Its tests are independent.
- US3 can proceed in parallel with US1/US2 because it touches different files, except
  `Main.kt`/`MainWindow.kt` (T024/T025 vs T013/T015). Do those tasks sequentially.
- Within each phase, tests come first, then core, then CLI/GUI.

## Parallel Opportunities

- **Foundational**: T003, T004, T006 and T007 touch different files and can run together. T005
  follows T004, and T008 follows all of them.
- **US1**: T010 ∥ T011 ∥ T014. Then T012 → T013 → T015.
- **US2**: T017 ∥ T018. Then T019.
- **US3**: T021 ∥ T022. Then T023 → T024 → T025.
- **Across stories**: once Foundational is done, one developer can take US1 → US2
  (`FaultExplainer.kt`) while another takes US3 (`ProgramLint.kt` and its tests).
- **Polish**: T027 ∥ T028.

## Implementation Strategy

1. **MVP**: Setup, Foundational, then US1. This fixes the reported
   `RIP=0x401018` bug with a clear message, the line highlight and the CLI hint. Stop and validate
   with quickstart §2/§3.
2. **Increment 2**: US2. Every remaining fetch-fault kind gets its own message, and the legacy text
   is gone.
3. **Increment 3**: US3. Build warnings catch the mistake before running.
4. **Finish**: Polish (docs, README, theme/zoom check, full quickstart). Commit as a single focused
   change such as "Explain faults when execution leaves the code", or one commit per story.
