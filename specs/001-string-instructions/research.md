# Research: String Instructions with REP Prefixes

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Date**: 2026-09-27

The Technical Context had no open NEEDS CLARIFICATION items: the language, build and test stack are
fixed by the existing project and the constitution. The research below settles the design
questions the spec raises against the existing code (`cpu/Cpu.kt`, `Machine.kt`,
`asm/Assembler.kt`, `ui/MainWindow.kt`).

---

## R1. How a repeat maps onto the existing step machinery

**Decision**: One call to `Cpu.step()` on a repeat-prefixed instruction performs **at most one
iteration**. If RCX = 0 at entry it performs none and moves RIP to the next instruction.
Otherwise it does one element operation, decrements RCX, and leaves RIP **unchanged** unless the
repeat just ended (RCX = 0, or the ZF stop condition for `scas`/`cmps`), in which case
RIP = next.

**Rationale**:
- This is how hardware behaves: a REP string instruction is restartable after each iteration, and a
  single-step trap fires after every iteration with RIP still on the instruction.
- `Machine.step()` already wraps each `Cpu.step()` in a `StepRecord` (registers, RIP, flags,
  memory undo log), so iteration-level Step Back (FR-010), change highlighting (FR-014), the
  step counter and the CLI `--trace` (FR-013) follow without new machinery.
- A fault mid-repeat (FR-011) behaves correctly without extra work, as long as the element
  operation does its memory read/write *before* RSI/RDI/RCX are updated. `Memory` throws
  `CpuFault` before mutating anything, and `Machine.step()` records the faulting step so it can be
  undone.

**Alternatives considered**:
- *Run the whole repeat inside one `Cpu.step()`*: rejected. It contradicts Story 2 (stepping one
  iteration at a time), makes Step Back all-or-nothing, and could create a single undo record with
  up to millions of memory entries.
- *Expand `rep movsb` into N synthetic instructions at assembly time*: rejected. RCX is only known
  at run time, and this would break the one-slot-per-instruction address model.

## R2. Knowing that execution is "in the middle of a repeat"

**Decision**: Add a boolean `repeating` to `Cpu`. It is set to `true` by a step that finishes an
iteration without ending the repeat, and reset to `false` at the start of every other step and by
`reset()`. `StepRecord` saves it and `stepBack()` restores it.

**Rationale**: breakpoints (FR-009) and Step Over (FR-008) need to tell "about to start this
instruction" apart from "about to continue it". RCX and RIP alone can't say this: a `rep` inside a
loop legitimately reaches the same RIP again with a fresh RCX. Keeping the flag in the core, not
in the UI, satisfies Principle IV, and saving it in the step record satisfies Principle V.

**Alternatives considered**:
- *Compare with the previous step's RIP in the UI*: rejected. It puts execution knowledge in
  `MainWindow` and breaks after Step Back.
- *Derive it from `history.last().rip == cpu.rip`*: rejected. It only works while history is kept,
  and the CLI turns history off.

## R3. Breakpoints on a repeat line

**Decision**: `MainWindow.tick()` checks breakpoints only when `!machine.cpu.repeating`.

**Rationale**: the run loop checks `ins.line in breakpoints` before every step. Without this guard,
a breakpoint on `rep movsb` would pause before every iteration, which FR-009 forbids.

## R4. Step Over and run speed for long repeats

**Decision**: when the next instruction is repeat-prefixed, `stepOver()` starts a run whose stop
condition is "RIP has left this instruction's address" (or the machine stopped). That run uses an
**unlimited per-tick step budget** regardless of the speed slider. The existing 14 ms per-tick
deadline and Swing `Timer` keep the UI responsive, and Pause (F6) stops the timer between ticks.
Run (F5) keeps honoring the slider.

**Rationale**:
- SC-003 requires a 1,000,000-iteration repeat to finish in under 2 s under Step Over. At the
  default 10 steps/s it would take more than a day, and Step Over means "finish this instruction
  now", as it does in debuggers.
- The existing `tick()` loop already slices work by time (`(n and 1023) == 0 && nanoTime() >
  deadline`), so no new threading is needed.
- A rough cost estimate: one `Machine.step()` with history (a copy of 16 longs, one `MemUndo`, a
  map lookup) is about 0.2–1 µs, so 1 M iterations take about 0.2–1 s. The history deque keeps at
  most 50,000 records.

**Alternatives considered**:
- *Loop synchronously in `Machine.finishRepeat()`*: rejected. It would block the event thread
  (no Pause, frozen window) for large or unbounded counts, such as `repne scasb` with RCX = −1 on
  a buffer with no terminator.
- *Run on a background thread*: rejected. It would add concurrency to a single-threaded design for
  no benefit over time slicing.

SC-003's "via Run" wording applies at the **Max** speed setting. This is recorded in
quickstart.md.

## R5. Representing the prefix

**Decision**: add `enum class RepPrefix { NONE, REP, REPE, REPNE }` and a field
`val prefix: RepPrefix = RepPrefix.NONE` on `Instruction`. The mnemonic stays the plain string
mnemonic (`"movsb"`), so `Cpu.step()`'s `when (m)` dispatch, `Docs.lookup(mnemonic)` and the CFG
keep working. `repz` is parsed as `REPE`, and `repnz` as `REPNE`.

**Alternatives considered**:
- *Mnemonic `"rep movsb"`*: rejected. It multiplies the dispatch cases and breaks the
  mnemonic-keyed lookups.
- *Wrap the prefix in an operand*: rejected. The operand list should stay empty, since string
  instructions have no explicit operands in NASM.

## R6. `rep` in front of `scas`/`cmps`, and `repe`/`repne` in front of `movs`/`stos`/`lods`

**Decision**:
- `rep` and `repe` produce the same encoding (F3). On `scas`/`cmps`, `rep` therefore **behaves as
  `repe`**. It is accepted, and its hover/status text says so ("rep = repe here: stops when
  ZF = 0").
- `repe`/`repne` on `movs`/`stos`/`lods` behave as plain `rep` (FR-005). On hardware, F2 on
  `movs`/`stos`/`lods` also repeats unconditionally.

**Rationale**: Principle I. NASM accepts these combinations, and hardware treats F3 as REPE for
`cmps`/`scas`. The simulator stores the parsed prefix and resolves its meaning per family in one
function (`StringOp.stopsOn(prefix)`).

## R7. Assembler parsing and error messages

**Decision**: in pass 1, a leading word in `{rep, repe, repz, repne, repnz}` is consumed as the
prefix. The next word must be one of the 20 string mnemonics, or an error is reported. `Pending`
gains a `prefix` field that pass 2 copies into `Instruction`. New beginner-facing errors (exact
wording is in [contracts/assembly-syntax.md](./contracts/assembly-syntax.md)):

| Input | Error |
|---|---|
| `rep` alone | "'rep' needs a string instruction after it, e.g. 'rep movsb'" |
| `rep add rax, 1` | "'rep' only works with string instructions (movs, stos, lods, scas, cmps), not 'add'" |
| `movs byte [rdi], [rsi]`, `stos`, … (unsuffixed) | "write the size in the name: 'movsb', 'movsw', 'movsd' or 'movsq' (NASM doesn't accept operands here)" |
| `stosb al` (suffixed, with operands) | "'stosb' takes no operands: it always uses al and [rdi]" |
| `movsd xmm0, [rsi]` / `cmpsd …` with operands | "'movsd' with operands is the SSE floating-point instruction, which isn't supported; the string instruction 'movsd' takes no operands" |
| `insb`, `outsw`, `rep outsb`, … | "port I/O instructions ('insb', 'outsb', …) are privileged and not supported" |
| `a32 rep movsb` | "the 32-bit address-size override ('a32') isn't supported; string instructions use rcx, rsi and rdi" |
| `fs movsb` / `gs rep movsb` | "segment overrides ('fs', 'gs') aren't supported" |

The prefix words are added to the reserved names, so `rep:` can't be a label (matching the
existing mnemonic check).

**Alternatives considered**: *Treat `rep` as a standalone zero-operand "instruction" that modifies
the next line*: rejected. NASM requires the prefix on the same line for this usage, and a split
form would make "dangling prefix" errors confusing.

## R8. Where the string semantics live

**Decision**: a new file `cpu/StringOps.kt` holds:
- `RepPrefix`
- `StringOp` (family `MOVS/STOS/LODS/SCAS/CMPS` + element size, parsed from a mnemonic via
  `StringOp.of("movsq")`)
- the per-family facts shared by the assembler, CPU, docs and status bar: which pointers the family
  uses, whether it writes flags, and its stop rule under each prefix

`Cpu.step()` gets one branch: `StringOp.of(m)?.let { return stringStep(ins, it) }`, which calls a
private `stringStep` in `Cpu.kt` that reuses the existing private `sub()` for `scas`/`cmps` flags
and `setPart()` for `lods`.

**Rationale**: it keeps `Cpu.kt` readable (the file is 368 lines), gives the assembler, `Docs` and
the status bar one source of truth for the 20 mnemonics, and adds no dependency on the UI
(Principle IV).

## R9. Verifying "matches real hardware" (SC-001)

**Decision**:
1. Expected values in unit tests come from the Intel SDM Vol. 2 pseudocode for MOVS/STOS/LODS/
   SCAS/CMPS and the REP/REPE/REPNE table: DF direction, RCX = 0 does nothing, ZF is checked after
   each iteration, and flags are identical to CMP.
2. An **equivalence test** checks that for every element size and a grid of edge values (0, 1,
   0x7F…, 0x80…, 0xFF…, random), the flags after `scasX`/`cmpsX` equal the flags after the
   simulator's existing `cmp` on the same operands. `cmp` is already covered by the
   "flags tour matches real hardware" test.
3. Out of scope for CI: a differential run against NASM-assembled native code. The development
   machine is macOS, and native x86-64 Linux execution isn't available. If an x86-64 Linux machine
   is available, quickstart.md shows how to cross-check manually.

## R10. Status bar, hover, reference and syntax highlighting

**Decisions**:
- `Docs`:
  - Add one entry per family (`movs`, `stos`, `lods`, `scas`, `cmps`) plus entries for `rep`,
    `repe`/`repz` and `repne`/`repnz`.
  - Extend `Docs.lookup()` so that `movsb`/`movsw`/`movsd`/`movsq` etc. resolve to their family
    entry with the element size filled in (the same approach the lookup already uses for `jcc`,
    `setcc` and `cmovcc`).
  - The Reference tab lists them in a new "String instructions" group.
- `AsmEditor` highlighting: the word after a prefix is highlighted as a mnemonic. Currently only
  the first word on the line is.
- Status bar (`nextLabel`): for a string instruction, append a one-line description of the next
  iteration built from `StringOp` and live register values. Examples: "copy byte [rsi] → [rdi],
  4 left", "compare al with byte [rdi], stops when equal (ZF=1) or rcx=0, 12 left". With
  RCX = 0: "rcx = 0: skipped, nothing happens".
- CFG (`analysis/ControlFlowGraph.kt`): add `lodsb/w/d/q` to `IMPLICIT_RAX_WRITERS`, so the
  syscall-number inference doesn't mislabel a `syscall` that follows a `lods`. Repeat instructions
  stay straight-line (no new edges).

## R11. Example program

**Decision**: add `src/main/resources/examples/09_strings.asm` ("Strings: rep movs/stos/scas/cmps")
and register it in `Examples.names`. It:
1. copies a message with `rep movsb`
2. zero-fills a qword array with `rep stosq`
3. computes a length with `repne scasb`
4. compares two strings with `repe cmpsb`

and prints each result. Because the existing tests loop over `Examples.names`, it's automatically
covered by the step-all-the-way-back test and the exits-with-code-0 test.
