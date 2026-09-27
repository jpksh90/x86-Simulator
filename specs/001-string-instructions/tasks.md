---

description: "Task list for String Instructions with REP Prefixes"
---

# Tasks: String Instructions with REP Prefixes

**Input**: Design documents from `specs/001-string-instructions/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: **Included, and written first.** Constitution Principle III (NON-NEGOTIABLE) requires
unit tests for every instruction, flag rule, fault and directive, and a Step Back test for every
new instruction. Within each phase, write the test tasks first and confirm they fail (or don't
compile) before implementing.

**Organization**: tasks are grouped by user story (US1–US4 from spec.md) so each story can be
implemented and verified on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: US1 = copy/fill (P1), US2 = iteration stepping (P1), US3 = search/compare/load (P2),
  US4 = help/examples (P3)
- Paths are relative to the repo root `/Users/jyp/x86-Simulator`. Production code is in
  `src/main/kotlin/x86sim/` and tests in `src/test/kotlin/x86sim/`.

## Conventions for every task

- Run `./gradlew test` after each task that touches production code. Existing tests must stay
  green.
- Match the existing code style: terse KDoc one-liners, `when` dispatch, and
  `need(cond, "message")` for assembler validation.
- Error messages must contain the key phrases in
  [contracts/assembly-syntax.md](./contracts/assembly-syntax.md) exactly.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: confirm the baseline and create the shared test scaffold.

- [X] T001 Run `./gradlew test` on branch `001-string-instructions` and confirm the existing
  `SimulatorTest`, `StepBackTest` and `ControlFlowGraphTest` suites all pass before any change.
  Record any pre-existing failure in this file instead of fixing it silently.
- [X] T002 [P] Create `src/test/kotlin/x86sim/StringInstructionsTest.kt` (package `x86sim`,
  `kotlin.test`), with private helpers copied in style from `SimulatorTest.kt`:
  - `exec(body: String, data: String = "", bss: String = ""): Machine` builds
    `"section .data\n$data\nsection .bss\n$bss\nsection .text\n_start:\n$body\n    hlt\n"`,
    loads it into a `Machine` with `onOutput = {}`, calls `runToEnd(5_000_000)`, and returns the
    machine. It does not assert HALTED, because some tests expect FAULTED.
  - `load(src: String): Machine` loads without running, for step-by-step tests.
  - `Machine.reg(name: String): Long = cpu.get(Registers.lookup(name)!!)`.
  - `Machine.sym(name: String): Long = program!!.symbols.getValue(name)`.
  - `Machine.bytesAt(addr: Long, n: Int): List<Int> = (0 until n).map { memory.peek(addr + it)!! }`.
  - `Machine.stepUntilLine(text: String)` steps until `cpu.currentInstruction()!!.source.trim()`
    starts with `text`, with at most 10,000 steps.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the shared string-instruction model and assembler support. Every story depends on
this phase. After it, programs using string instructions **assemble**, but executing one throws
`CpuFault("Unsupported instruction '…'")`.

**⚠️ CRITICAL**: no user story work can begin until this phase is complete.

### Tests (write first; they must fail or not compile)

- [X] T003 [P] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add model tests for
  `x86sim.cpu.StringOp` and `x86sim.cpu.RepPrefix`:
  - `StringOp.of` accepts exactly the 20 names `{movs,stos,lods,scas,cmps}×{b,w,d,q}` with sizes
    1/2/4/8, is case-insensitive (`"MOVSQ"` works), and returns null for `"movs"`, `"movsx"`,
    `"movsxd"`, `"rep"`, `"insb"` and `"mov"`.
  - `StringOp.ALL_MNEMONICS.size == 20` and `StringOp.BARE == setOf("movs","stos","lods","scas","cmps")`.
  - `RepPrefix.parse`: `rep`→REP, `repe`/`repz`→REPE, `repne`/`repnz`→REPNE (case-insensitive),
    `"movsb"`→null.
  - The stop-rule matrix `stopsAfter(prefix, zf)`:
    - For SCAS/CMPS: REP and REPE stop when `zf == false`, REPNE stops when `zf == true`, NONE
      never stops.
    - For MOVS/STOS/LODS it is always `false`.
  - The derived flags `usesRsi`, `usesRdi` and `setsFlags` match the table in `data-model.md`.
- [X] T004 [P] In `src/test/kotlin/x86sim/SimulatorTest.kt`, add assembler tests.
  - **Accepted forms**: each of `movsb`, `rep movsq`, `REPNE SCASB`, `.cmp: repe cmpsb` (after a
    global label `f:`), `repz cmpsw`, `rep scasb`, `repne stosb` and `rep lodsb` assembles to one
    `Instruction` with an empty `operands` list, the bare lowercase mnemonic, and
    `prefix == REP/REPE/REPNE/NONE` as parsed (`repz`→REPE).
  - **Unchanged programs**: every instruction of every program in `Examples.names` has
    `prefix == RepPrefix.NONE`.
  - **Rejected input E1–E11**: for each row of the table in
    `specs/001-string-instructions/contracts/assembly-syntax.md`, `assertFailsWith<AssemblyException>`,
    and assert that `e.errors.single().message` contains every key phrase listed for that row and
    that `line` is the offending 0-based line.

### Implementation

- [X] T005 Create `src/main/kotlin/x86sim/cpu/StringOps.kt` (package `x86sim.cpu`, no UI
  imports):
  - `enum class RepPrefix { NONE, REP, REPE, REPNE }` with
    `companion object { val SPELLINGS = mapOf("rep" to REP, "repe" to REPE, "repz" to REPE, "repne" to REPNE, "repnz" to REPNE); fun parse(word: String): RepPrefix? = SPELLINGS[word.lowercase()] }`.
  - `data class StringOp(val family: Family, val size: Int)` with
    `enum class Family { MOVS, STOS, LODS, SCAS, CMPS }` and the derived properties:
    - `usesRsi` = family in {MOVS, LODS, CMPS}
    - `usesRdi` = family in {MOVS, STOS, SCAS, CMPS}
    - `setsFlags` = family in {SCAS, CMPS}
    - `fun stopsAfter(prefix: RepPrefix, zf: Boolean): Boolean` = `setsFlags && when (prefix) { REP, REPE -> !zf; REPNE -> zf; NONE -> false }`
  - Companion:
    - `fun of(mnemonic: String): StringOp?`: lowercase the mnemonic; its first 4 chars must be a
      family name and the 5th char one of `b w d q`, mapping to 1/2/4/8; the length must be
      exactly 5.
    - `val BARE = setOf("movs","stos","lods","scas","cmps")`
    - `val ALL_MNEMONICS: Set<String>`, built from BARE × "bwdq".
  - Add a KDoc line on `RepPrefix` stating: "`rep` and `repe` share the F3 encoding, so `rep` on
    scas/cmps behaves as `repe`."
- [X] T006 In `src/main/kotlin/x86sim/cpu/Instruction.kt`, add the field
  `val prefix: RepPrefix = RepPrefix.NONE` as the **last** constructor parameter of `Instruction`,
  so existing call sites compile unchanged, with the KDoc "Repeat prefix; only ever set on string
  instructions."
- [X] T007 In `src/main/kotlin/x86sim/asm/Assembler.kt`, implement prefix parsing and validation
  (depends on T005, T006):
  1. **`Pending`**: add `val prefix: RepPrefix = RepPrefix.NONE` as the last parameter. In pass 2,
     pass `p.prefix` into `Instruction(...)`.
  2. **Companion**:
     - add `val PREFIXES = RepPrefix.SPELLINGS.keys`
     - add `StringOp.ALL_MNEMONICS` to `MNEMONICS`
     - add `val PORT_IO = setOf("insb","insw","insd","outsb","outsw","outsd","ins","outs")`
  3. **`defineLabel`**: also reject `name.lowercase() in PREFIXES` with the existing message
     `"'$name' is an instruction name and can't be a label"` (E10).
  4. **Pass 1, early checks.** Right after `var lw = word.lowercase()` and **before** the
     "label without colon" check, add these, in order:
     - if `lw == "a32"`, throw E8
     - if `lw in setOf("fs","gs","cs","ds","es","ss")` and the following word is a string mnemonic, a bare family or a prefix, throw E9 (so a data label like `cs db 1` still works)
     - if `lw in PORT_IO` (or `lw in PREFIXES` and the next word is in `PORT_IO`), throw E7
  5. **Pass 1, prefix handling**: if `lw in PREFIXES`, then:
     - `val (w2, r2) = splitFirst(rest)`
     - if `w2` is empty, throw E1, formatted with the actual prefix word: `"'$word' needs a string instruction after it, e.g. '$lw movsb'"`
     - if `StringOp.of(w2) == null`, throw E2: `"'$lw' only works with string instructions (movs, stos, lods, scas, cmps), not '$w2'"`
     - otherwise set `prefix = RepPrefix.parse(lw)!!`, `word = w2`, `rest = r2`, `lw = w2.lowercase()`
     Keep `prefix` in a local `var` and pass it to the instruction's `Pending`.
  6. **Pass 1, bare families**: if `lw in StringOp.BARE`, throw E4:
     `"write the size in the name: '${lw}b', '${lw}w', '${lw}d' or '${lw}q' (NASM doesn't accept operands here)"`.
  7. **`check()`**: add a branch before `m in ZERO_OPERAND`: `StringOp.of(m) != null ->` if
     `ops.isNotEmpty()`, then:
     - for `m == "movsd" || m == "cmpsd"`, throw E6: `"'$m' with operands is the SSE floating-point instruction, which isn't supported; the string instruction '$m' takes no operands"`
     - otherwise throw E5, with a per-family hint:
       `"'$m' takes no operands: it always uses ${implicit}"`, where implicit is
       `"[rsi] and [rdi]"` for movs/cmps, `"<acc> and [rdi]"` for stos/scas, and
       `"[rsi] and <acc>"` for lods; `<acc>` is `al/ax/eax/rax` by size.
     Otherwise return `ops`.

   Messages E8 and E9 are:
   - E8: `"the 32-bit address-size override ('a32') isn't supported; string instructions use rcx, rsi and rdi"`
   - E9: `"segment overrides ('$lw') aren't supported"`
   - E7: `"port I/O instructions ('insb', 'outsb', …) are privileged and not supported"`
- [X] T008 Run `./gradlew test`: T003 and T004 must now pass, and all pre-existing tests must
  still pass.

**Checkpoint**: string instructions assemble, with correct errors for every misuse. Execution isn't
supported yet.

---

## Phase 3: User Story 1 - Copy and fill memory with REP MOVS / REP STOS (Priority: P1) 🎯 MVP

**Goal**: `movs*`/`stos*`, with and without `rep`, execute with hardware-exact results, one
iteration per `Cpu.step()`.

**Independent Test**: copy "Hello, world!" (13 bytes) from `.data` to `.bss` with `rep movsb`,
and fill 4 qwords with `rep stosq`. After `runToEnd`, the destination bytes and the final
RCX/RSI/RDI are correct (spec US1 scenarios 1–5).

### Tests for User Story 1 (write first)

- [X] T009 [P] [US1] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add US1 semantics
  tests using `exec`:
  - **(a) Copy**: `rep movsb` with `lea rsi,[msg]` / `lea rdi,[buf]` / `mov ecx,13` copies
    "Hello, world!". Afterwards RCX = 0 and RSI/RDI = start + 13. Assert `m.state == HALTED`.
  - **(b) Fill**: `rep stosq` with `mov rax, 0x1122334455667788` and RCX = 4 fills 4 qwords of
    `arr resq 4`, and RDI = arr + 32.
  - **(c) RCX = 0**: first do `stc` and `cmp eax, eax` (to set CF=0 ZF=1), then `mov rax, 1` to
    set a known state. Save `rflags`, RSI and RDI, then run `rep movsb` and `rep stosb` with
    RCX = 0. No byte of `buf` changed (compare `bytesAt`), and RSI, RDI, RCX and rflags are
    unchanged.
  - **(d) DF = 1**: `std` then `rep movsb` with RSI/RDI pointing at the **last** byte, RCX = 5,
    copies backwards. RSI/RDI end at start − 1.
  - **(e) Unprefixed**: each of `movsb/movsw/movsd/movsq` and `stosb/stosw/stosd/stosq` moves
    exactly 1/2/4/8 bytes, advances the pointer(s) by that size, and leaves RCX = 0x55 untouched.
  - **(f) Overlap**: forward `rep movsb` with RDI = RSI + 1 over the bytes "A.......", RCX = 7,
    gives "AAAAAAAA".
  - **(g) Flags**: `movs`/`stos` don't change flags. Set flags with `mov al, 0x7f` then
    `add al, 1` (OF=SF=AF=1), and assert `rflags` is identical after `rep movsb`.
  - **(h) Fault partway through**: `buf resb 16` (the `.bss` region is one 4096-byte page), then
    `lea rdi,[buf+4094]`, `mov ecx,5`, `rep stosb`. The state is FAULTED, the message contains
    "Segmentation fault", RCX = 3, RDI = buf + 4096, and
    `cpu.currentInstruction()!!.source.trim() == "rep stosb"`.
  - **(i) Step count**: `load` a program with `mov ecx,5` then `rep movsb`, step to the `rep`
    line, and record `steps`. After running to the next line, `steps` increased by exactly 5.
    For RCX = 0 it increased by exactly 1.

### Implementation for User Story 1

- [X] T010 [US1] In `src/main/kotlin/x86sim/cpu/Cpu.kt` (depends on T005–T007):
  - Add `/** True right after a step that ran one iteration of a rep-prefixed instruction without finishing it. */ var repeating = false`, and set `repeating = false` in `reset()`.
  - In `step()`, right after `val m = ins.mnemonic`, add
    `StringOp.of(m)?.let { return stringStep(ins, it, next) }`, and set `repeating = false` at the
    very top of `step()`, after fetching `ins`.
  - Add `private fun stringStep(ins: Instruction, op: StringOp, next: Long): StepResult`,
    implementing contract S2 from `contracts/execution-semantics.md`:
    - If `ins.prefix != NONE && regs[RCX] == 0L`: set `rip = next` and return `Ok`.
    - `val n = op.size; val d = if (df) -n.toLong() else n.toLong()`
    - Do the element operation with **all memory access before any register update** (FR-011):
      - MOVS: `val v = memory.read(regs[RSI], n); memory.write(regs[RDI], n, v)`, then
        `regs[RSI] += d; regs[RDI] += d`.
      - STOS: `memory.write(regs[RDI], n, getPart(RAX, n))`, then `regs[RDI] += d`.
      - LODS, SCAS, CMPS: for now, `throw CpuFault("Unsupported instruction '${ins.mnemonic}'")`.
        These are implemented in US3 (T024).
    - If `ins.prefix == NONE`: set `rip = next` and return `Ok`.
    - Otherwise:
      - `regs[RCX] -= 1`
      - `val done = regs[RCX] == 0L || op.stopsAfter(ins.prefix, zf)`
      - `rip = if (done) next else ins.address`
      - `repeating = !done`
      - return `Ok`
- [X] T011 [US1] Run `./gradlew test` and confirm T009 passes. Then run the headless trace check
  from `quickstart.md` §2 (`x86sim run /tmp/rep.asm --trace` prints `rep movsb` exactly 5 times)
  after `./gradlew installDist`.

**Checkpoint**: US1 is complete. Copy and fill programs run correctly in the GUI and the CLI, and
the MVP is shippable.

---

## Phase 4: User Story 2 - Step through a REP instruction one iteration at a time (Priority: P1)

**Goal**:
- Step does one iteration, and Step Back undoes one.
- Step Over finishes the repeat at full speed.
- A breakpoint pauses once.
- The status bar explains the next iteration.

**Independent Test**: load a `rep movsb` with RCX = 5. It takes exactly 5 Step presses to leave the
line. Stepping back twice restores the last 2 bytes and RCX. Stepping forward again reproduces an
identical snapshot (spec US2 scenarios 1–5, quickstart §3 steps 2–6).

### Tests for User Story 2 (write first)

- [X] T012 [P] [US2] In `src/test/kotlin/x86sim/StepBackTest.kt`:
  - Add `m.cpu.repeating` to `Rig.snapshot()`.
  - Add the test `stepping a rep instruction one iteration at a time and back`:
    - The program copies "abcde" into `dst resb 5` with `mov ecx,5` then `rep movsb`.
    - Step until the current source is `rep movsb`, and take a snapshot `s0`.
    - Step once: RIP is unchanged, `cpu.repeating == true`, RCX = 4, and `dst[0] == 'a'`.
    - Step 3 more times, taking snapshot `s4`.
    - Step once more: RIP has moved to the next instruction and `repeating == false`.
    - Step back twice: the snapshot equals the one after 3 iterations (RCX = 2,
      `repeating == true`, `dst[3] == 0`, `dst[4] == 0`).
    - Step forward twice: the snapshot is identical to the one taken right after the repeat
      finished.
    - Step back 5 times: the snapshot equals `s0`.
- [X] T013 [P] [US2] In `src/test/kotlin/x86sim/StepBackTest.kt`, add the test
  `a fault partway through a repeat can be stepped back out of`, using the T009(h) program:
  - After `runToEnd`, the state is FAULTED.
  - `stepBack()` returns true, the state is PAUSED, RCX = 3, `repeating == true`, and the
    current source is `rep stosb`.
  - Stepping back again gives RCX = 4.
- [X] T014 [P] [US2] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add
  `headless and history runs agree`: run the same `rep movsb` + `rep stosq` program once with
  `keepHistory = true` and once with `keepHistory = false`. The final registers, rflags, RIP,
  steps and `.bss` bytes are equal (FR-013).
- [X] T015 [P] [US2] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add tests for
  `x86sim.ui.Docs.stringIteration(op: StringOp, prefix: RepPrefix, rcx: Long, df: Boolean): String`,
  asserting the exact texts of the H2 table in `contracts/ui-help.md`:
  - `rep movsb` with RCX = 5, DF = 0 gives `"copy byte [rsi] → [rdi], then rsi/rdi += 1 · 5 left"`
  - `rep stosq` with RCX = 4 gives `"store rax → qword [rdi], then rdi += 8 · 4 left"`
  - `repne scasb` with RCX = −1 gives `"compare al with byte [rdi] · stops when equal (ZF=1) or rcx = 0"`,
    with no count, because RCX ≥ 2³² unsigned
  - `repe cmpsb` with RCX = 3 gives `"compare byte [rsi] with byte [rdi] · stops when different (ZF=0) or rcx = 0 · 3 left"`
  - any prefix with RCX = 0 gives `"rcx = 0: the repeat is skipped"`
  - unprefixed `lodsb` with DF = 1 gives `"load byte [rsi] → al, then rsi -= 1"`

### Implementation for User Story 2

- [X] T016 [US2] In `src/main/kotlin/x86sim/Machine.kt`:
  - Add `val repeating: Boolean` to `StepRecord`'s constructor.
  - Pass `cpu.repeating` where the record is created in `step()`.
  - In `stepBack()`, restore it with `cpu.repeating = r.repeating`, next to
    `cpu.setFlags(r.flags)`.
- [X] T017 [US2] In `src/main/kotlin/x86sim/ui/MainWindow.kt` `tick()`, change the breakpoint
  check to `if (ins != null && ins.line in breakpoints && !machine.cpu.repeating)`, so a
  breakpoint on a `rep` line pauses only before the first iteration (FR-009, research R3).
- [X] T018 [US2] In `src/main/kotlin/x86sim/ui/MainWindow.kt`, implement Step Over for repeats
  (FR-008, research R4):
  - Add `private var runFullSpeed = false`.
  - Give `startRun(condition, label)` a third parameter `fullSpeed: Boolean = false` that sets
    `runFullSpeed = fullSpeed`.
  - In `restartTimer()`, use `delay = 15` when `runFullSpeed`.
  - In `tick()`, compute `budget = if (runFullSpeed) Int.MAX_VALUE else <existing when>`. Keep
    the existing 14 ms deadline check.
  - Reset `runFullSpeed = false` in `stopTimer()`.
  - In `stepOver()`, before the `call` check:
    `if (ins != null && ins.prefix != RepPrefix.NONE && StringOp.of(ins.mnemonic) != null) { val a = ins.address; return startRun({ machine.cpu.rip != a }, "Stepped over repeat", fullSpeed = true) }`.
  - A `read` syscall that blocks mid-run calls `stopTimer()`, which resets `runFullSpeed`. So in
    `tick()`, where `resumeAfterInput = true` is set, also save `resumeFullSpeed = runFullSpeed`
    (a new field). In `afterInput()`, call `startRun(stopWhen, stopWhenLabel, resumeFullSpeed)`.
- [X] T019 [US2] In `src/main/kotlin/x86sim/ui/Docs.kt`, add
  `fun stringIteration(op: StringOp, prefix: RepPrefix, rcx: Long, df: Boolean): String`,
  producing exactly the texts asserted in T015:
  - Accumulator name by size: `al/ax/eax/rax`. Size name via `Bits.sizeName`. Sign `+=`/`-=` by
    DF. Pointer list `rsi/rdi`, `rdi` or `rsi` per `usesRsi`/`usesRdi`.
  - Family verbs: movs "copy S [rsi] → [rdi]", stos "store ACC → S [rdi]", lods "load S [rsi] → ACC",
    scas "compare ACC with S [rdi]", cmps "compare S [rsi] with S [rdi]".
  - For SCAS/CMPS with a prefix, replace the pointer clause with the stop clause:
    - REP/REPE: " · stops when different (ZF=0) or rcx = 0"
    - REPNE: " · stops when equal (ZF=1) or rcx = 0"
  - Append " · N left" when there is a prefix and `rcx` is in 1 until 2³² (use `java.lang.Long.compareUnsigned`).
  - Return "rcx = 0: the repeat is skipped" when there is a prefix and `rcx == 0`.
- [X] T020 [US2] In `src/main/kotlin/x86sim/ui/MainWindow.kt`, where `nextLabel.text` is built
  (the `ins != null ->` branch in `refresh`), when `StringOp.of(ins.mnemonic)` is non-null, use
  `Docs.stringIteration(op, ins.prefix, machine.cpu.regs[Registers.RCX], machine.cpu.df)` as the
  dimmed description, replacing the `shortDoc` text for that instruction.
- [ ] T021 [US2] Run `./gradlew test` (T012–T015 must pass). Then do the manual GUI check in
  `quickstart.md` §3 steps 2–6: Step, Step Back, Step Over ("Stepped over repeat"), and a
  breakpoint that pauses once.
  - **Status (2026-09-27)**: tests pass. The interactive GUI steps (Step Over message, breakpoint pausing once) were not exercised by hand yet. A UiSnapshot render mid-repeat confirmed the NEXT-line text and highlighting.

**Checkpoint**: US1 and US2 both work. Repeats can be explored one iteration at a time in both
directions.

---

## Phase 5: User Story 3 - Search and compare with REPE/REPNE SCAS/CMPS and LODS (Priority: P2)

**Goal**: `lods*`, `scas*` and `cmps*` execute with the ZF-based stop rules and cmp-identical
flags.

**Independent Test**: `strlen` via `repne scasb` on "abc\0" with RCX = −1 gives length 3 and
ZF = 1. `repe cmpsb` on "abcd"/"abXd" stops with RCX = 1 and ZF = 0 (spec US3 scenarios 1–5).

### Tests for User Story 3 (write first)

- [X] T022 [P] [US3] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add US3 semantics
  tests:
  - **(a) strlen**: `repne scasb` on `s db "abc",0` with `xor eax,eax`, `mov rcx,-1` and
    `lea rdi,[s]` leaves ZF = 1, RDI = s + 4 and RCX = −5. After `not rcx` / `dec rcx`, RCX = 3.
  - **(b) Mismatch**: `repe cmpsb` on "abcd" vs "abXd" with RCX = 4 leaves RCX = 1, ZF = 0,
    RSI = a + 3 and RDI = b + 3. Its rflags (masked with `0x8D5`: CF PF AF ZF SF OF) equal those of
    a separate program running `mov al,'c'` / `cmp al,'X'`.
  - **(c) Equal strings**: `repe cmpsb` on two equal 4-byte strings leaves RCX = 0 and ZF = 1.
  - **(d) `rep scasb` = `repe scasb`**: on "aab" with AL='a' and RCX = 3, both stop after the 3rd
    byte with RCX = 0 and ZF = 0.
  - **(e) `repne stosb` = `rep stosb`**: it fills all RCX bytes.
  - **(f) `lodsb` / `lodsw`**: they preserve the upper bits. With `mov rax, -1` then `lodsb` on
    byte 0x41, RAX = 0xFFFFFFFFFFFFFF41. `lodsw` behaves the same way on 16 bits.
  - **(g) `lodsd`**: it zeroes bits 32–63.
  - **(h) `lodsq`**: it loads the full 8 bytes.
  - **(i) `rep lodsb`**: with RCX = 3 over "xyz", AL = 'z' and RSI = start + 3.
  - **(j) DF = 1 `scasb`**: RDI decreases by 1.
  - **(k) Unprefixed `scasq`/`cmpsw`**: they compare once and leave RCX untouched.
- [X] T023 [P] [US3] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add the test
  `scas and cmps set flags exactly like cmp` (SC-001, research R9):
  - For each size in 1, 2, 4, 8, and each pair (a, b) from the value grid
    `{0, 1, 0x7F…, 0x80…, 0xFF…(all ones), 0x5A5A…, random}` truncated to the size, where
    "0x7F…" means the max signed value of that size:
    - Assemble and run program A: `mov <acc>, a` / `lea rdi,[v]` / `scas<s>`, with `v` holding b.
    - Run program B: `mov <acc>, a` / `cmp <acc>, <size> [v]`.
    - Assert that `rflags and 0x8D5` is equal between A and B.
  - Do the same for `cmps<s>` against `mov <acc>,[u]` / `cmp <acc>,[v]`.
  - Use `dq` data and `mov rax, imm64` for the 8-byte case.
- [X] T024 [P] [US3] In `src/test/kotlin/x86sim/ControlFlowGraphTest.kt`, add the test
  `lods clobbers rax for exit-syscall detection`: the program
  `_start:\n mov eax, 60\n lodsb\n syscall\n hlt\n` builds a graph in which no block has
  `exit == "exit"`.

### Implementation for User Story 3

- [X] T025 [US3] In `src/main/kotlin/x86sim/cpu/Cpu.kt` `stringStep`, replace the US1 placeholder
  for LODS/SCAS/CMPS (memory access still before register updates):
  - LODS: `val v = memory.read(regs[RSI], n); setPart(RAX, n, v); regs[RSI] += d`. `setPart`
    applies the 32-bit zeroing rule.
  - SCAS: `val v = memory.read(regs[RDI], n); sub(getPart(RAX, n), v, 0, n); regs[RDI] += d`.
  - CMPS: `val a = memory.read(regs[RSI], n); val b = memory.read(regs[RDI], n); sub(a, b, 0, n); regs[RSI] += d; regs[RDI] += d`.

  The stop rule is already in place through `op.stopsAfter(ins.prefix, zf)`, evaluated after the
  flags are set.
- [X] T026 [US3] In `src/main/kotlin/x86sim/analysis/ControlFlowGraph.kt`, add
  `"lodsb", "lodsw", "lodsd", "lodsq"` to `IMPLICIT_RAX_WRITERS`.
- [X] T027 [US3] Run `./gradlew test`: T022–T024 must pass, and US1/US2 tests must still pass.

**Checkpoint**: all 20 mnemonics and 5 prefix spellings execute correctly.

---

## Phase 6: User Story 4 - Learn the instructions from the built-in help and examples (Priority: P3)

**Goal**: hover, Reference and syntax highlighting for every new word, a `09_strings` example,
and an updated README.

**Independent Test**: every one of the 30 words in contract H1 has a `Docs.lookup` entry, the
Reference HTML contains a "String instructions" heading, and `09_strings` runs to exit code 0
with the expected output (spec US4, SC-005).

### Tests for User Story 4 (write first)

- [X] T028 [P] [US4] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add the test
  `every string mnemonic and prefix is documented`:
  - For each of `StringOp.ALL_MNEMONICS + StringOp.BARE + RepPrefix.SPELLINGS.keys`,
    `Docs.lookup(w)` is non-null.
  - The instruction entries' descriptions contain "rsi" or "rdi", and contain "DF".
  - `Docs.lookup("movsw")!!.description` contains "word".
  - `Docs.lookup("scasb")!!.flags` contains "ZF", and `Docs.lookup("movsb")!!.flags == "none"`.
  - `Docs.lookup("rep")!!.description` contains "repe".
  - `Docs.referenceHtml()` contains "String instructions".

  Call `Theme` only if `referenceHtml()` requires it, and check that it doesn't need a display.
- [X] T029 [P] [US4] In `src/test/kotlin/x86sim/SimulatorTest.kt`, extend
  `examples produce the expected output` with
  `assertEquals(<expected>, run(Examples.load("09_strings")).out)`, where `<expected>` is the
  exact output chosen in T031. For example:
  `"copy: Hello, strings!\nzeroed: yes\nlength: 14\ncompare: abcd vs abXd differ at 3\n"`.

### Implementation for User Story 4

- [X] T030 [US4] In `src/main/kotlin/x86sim/ui/Docs.kt`:
  - Add to `instructions` (after the `std` entry, in a comment-delimited "string instructions"
    group) entries for `movs`, `stos`, `lods`, `scas` and `cmps`, with syntax `movsb/w/d/q` etc.
    Each description names the implicit registers and size choice, and ends with "Pointers move
    forward when DF=0 and backward when DF=1 (cld/std).". Flags are "none" for movs/stos/lods and
    "CF OF SF ZF AF PF (like cmp)" for scas/cmps.
  - Add entries for:
    - `rep`: "Repeat the string instruction rcx times (rcx counts down to 0). On scas/cmps, rep is the same as repe."
    - `repe`: "Repeat while rcx ≠ 0 and the last comparison was equal (ZF=1)."
    - `repz`: same as repe
    - `repne`: "Repeat while rcx ≠ 0 and the last comparison was not equal (ZF=0)."
    - `repnz`: same as repne
    All five have flags "none".
  - In `lookup()`, before the cc-prefix loop, if `StringOp.of(m)` is non-null, return the family
    entry with syntax set to `m` and the description prefixed with
    "${Bits.sizeName(size).replaceFirstChar { it.uppercase() }} version: ".
  - In `referenceHtml()`, render the string-instruction and prefix entries under a separate
    `<h2>String instructions</h2>` table after the main table. Exclude them from the first table.
- [X] T031 [US4] Create `src/main/resources/examples/09_strings.asm` in the style of the existing
  examples: a header comment, `section .data` / `.bss` / `.text`, and a `print`-style helper using
  `write`, reusing the patterns from `01_hello.asm` and `02_loop_sum.asm`. It demonstrates, with
  comments aimed at beginners:
  1. `cld` + `rep movsb` copying a message into a `.bss` buffer, then printing it
  2. `rep stosq` zero-filling a `resq 8` array that was pre-filled with non-zero values, then
     verifying and printing "zeroed: yes"
  3. `repne scasb` strlen of a zero-terminated string, printing the length
  4. `repe cmpsb` comparing "abcd" with "abXd" and printing the mismatch position

  It exits with `exit(0)`. It must not use any unsupported instruction.
- [X] T032 [US4] In `src/main/kotlin/x86sim/Main.kt`, append
  `"09_strings" to "Strings: rep movs/stos/scas/cmps"` to `Examples.names`.
- [X] T033 [US4] In `src/main/kotlin/x86sim/ui/AsmEditor.kt` syntax highlighting (the loop that
  uses `firstWord`, around line 186–208), keep `firstWord` true after a prefix word, so the
  mnemonic that follows is styled `sMnemonic`: change the update line to
  `if (g[1] == null) firstWord = (g[4] != null || g[3]?.value?.lowercase() in Assembler.PREFIXES) && firstWord`.
  Make sure prefixes themselves resolve through `Docs.lookup` (T030), so they're styled as
  mnemonics.
- [X] T034 [US4] Update `README.md` per contract H5:
  - "Supported instructions": add `movs stos lods scas cmps (b/w/d/q)` and
    `rep repe/repz repne/repnz`.
  - "Features": in the Stepping bullet, add that Step on a `rep` instruction runs one iteration
    and Step Over finishes it.
  - "Design notes and limitations": replace "no string instructions (`rep movsb` etc.)" with
    "string instructions only in their 64-bit forms: no `ins`/`outs`, segment overrides or `a32`".
  - Change "8 example programs" to "9 example programs" and add "string operations" to that list.
- [X] T035 [US4] Run `./gradlew test`. T028 and T029 must pass, and the loops over
  `Examples.names` in `StepBackTest` (step all the way back) and `ControlFlowGraphTest` (all code
  reachable) must pass for `09_strings`.

**Checkpoint**: all four user stories are complete and documented.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: performance, full validation and the constitution's workflow gates.

- [X] T036 [P] In `src/test/kotlin/x86sim/StringInstructionsTest.kt`, add the test
  `a million-iteration rep stosb finishes quickly` (FR-012, S7):
  - `buf resb 1000000`, `mov ecx, 1000000`, `rep stosb`, `hlt`
  - Use a default `Machine` with `keepHistory = true`.
  - Assert that `runToEnd()` finishes in < 2,000 ms (`measureTimeMillis`), that the state is
    HALTED, and that `historySize == 50_000`.
  - Spot-check that the last byte of `buf` was written.
- [ ] T037 Manual GUI validation per `quickstart.md` §3 steps 1–8 and §4. Include Step Over on
  the 1,000,000-byte `rep stosb` in < 2 s at the default speed, Pause within 0.5 s during
  `mov ecx, 100000000`, Run at Max in < 2 s, and both themes at 100% and 250% zoom. If any step
  fails, fix it in the relevant file (`MainWindow.kt`, `Docs.kt` or `AsmEditor.kt`) and add a
  regression test where possible.
  - **Status (2026-09-27)**: not yet done by hand. UiSnapshot renders (dark, light, 250% zoom) look right, and headless timing is 276 ms per 1 M iterations. Still to check in the running app: Step Over timing, Pause latency and the breakpoint.
- [X] T038 [P] Render the UI with the dev helper `src/test/kotlin/x86sim/UiSnapshot.kt`, with
  `09_strings` loaded and paused mid-repeat, in both dark and light themes. Visually confirm the
  NEXT line text and the highlighted RCX/RSI/RDI and memory bytes.
- [X] T039 Run the full `quickstart.md` §2 headless checks (the example run, a 5-line trace, and
  the assembler error with exit code 2). Then run a final `./gradlew test`, and confirm that all
  suites pass and none are skipped.
- [X] T040 Update `specs/001-string-instructions/spec.md` **Status** from `Draft` to
  `Implemented`, and check every item in `plan.md`'s Constitution Check table against the final
  code: no Swing imports in `cpu/`, `asm/`, `analysis/` or `Machine.kt`
  (`grep -rn "javax.swing\|java.awt" src/main/kotlin/x86sim/{cpu,asm,analysis} src/main/kotlin/x86sim/Machine.kt`
  returns nothing).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup. **Blocks all user stories.**
- **US1 (Phase 3)**: depends on Phase 2. This is the MVP.
- **US2 (Phase 4)**: depends on Phase 2 **and US1**. It needs a working REP loop (T010) to step
  through. T016–T020 touch different concerns but are mostly in `MainWindow.kt`, so run them
  sequentially.
- **US3 (Phase 5)**: depends on Phase 2 and US1, because it extends `stringStep` from T010. It is
  **independent of US2** and can run in parallel with it (different files, except that T025 edits
  `Cpu.kt`, which US2 doesn't touch).
- **US4 (Phase 6)**: depends on Phase 2. T030 (Docs) needs `StringOp`, and T031/T035 need
  US1 + US3 execution so that the example runs. T019 (US2) and T030 both edit `Docs.kt`, so
  sequence them.
- **Polish (Phase 7)**: depends on all stories.

### Story completion order

```text
Setup → Foundational → US1 (MVP) ─┬─► US2 ─┐
                                  └─► US3 ─┴─► US4 → Polish
```

### Within each story

- Test tasks first, confirmed failing. Then the model/core, then the UI, then the verification
  task.
- `Cpu.kt` edits: T010 → T025. `MainWindow.kt` edits: T017 → T018 → T020. `Docs.kt` edits:
  T019 → T030.

## Parallel Opportunities

- **Phase 1**: T002 can run in parallel with T001.
- **Phase 2**: T003 and T004 run in parallel (different test files). T005 → T006 → T007 run
  sequentially.
- **US1**: T009 alone, then T010.
- **US2**: T012, T013, T014 and T015 can all be written in parallel. T012 and T013 share
  `StepBackTest.kt`, so write them in one sitting or merge them carefully.
- **US3**: T022/T023 (same file, so write them together) run in parallel with T024
  (`ControlFlowGraphTest.kt`). T026 runs in parallel with T025.
- **US2 and US3** can proceed in parallel after US1, since they touch different production files.
- **US4**: T028 and T029 in parallel. T031 (asm example), T033 (AsmEditor) and T034 (README) run
  in parallel after T030.
- **Polish**: T036 and T038 in parallel.

### Parallel example: after US1 is done

```text
Worker A (US2): T012+T013 (StepBackTest) → T016 (Machine) → T017 → T018 → T020 (MainWindow)
Worker B (US3): T022+T023 (StringInstructionsTest) + T024 (CFG test) → T025 (Cpu) + T026 (CFG)
Then: T015 → T019 (Docs.stringIteration), then US4
```

## Implementation Strategy

### MVP first (US1 only)

1. Phase 1 + Phase 2: string instructions assemble, and misuse gives clear errors.
2. Phase 3 (US1): `rep movsb`/`rep stosq` programs run correctly. Step already works per
   iteration, because of the S2 semantics.
3. **Stop and validate**: `./gradlew test`, plus the headless trace check (T011).

### Incremental delivery

1. + US2: full iteration-level debugging experience (Step Back, Step Over, breakpoints, status
   bar).
2. + US3: search, compare and load idioms (`strlen`, `strcmp`).
3. + US4: documentation, highlighting and the example. After this the feature meets the
   constitution's "definition of done" (README + docs + example).
4. Polish: performance test and manual GUI/theme checks.

Commit after each checkpoint with an imperative summary, e.g. "Add rep movs/stos string
instructions".
