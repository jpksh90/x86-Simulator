# Data Model: String Instructions with REP Prefixes

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

This is an in-memory simulator, so there's no persistent storage. The "data model" is the set of
core types the feature adds or extends, all in `src/main/kotlin/x86sim/cpu/` and `Machine.kt`.

---

## RepPrefix *(new, `cpu/StringOps.kt`)*

| Value | Source spellings (case-insensitive) | Meaning |
|---|---|---|
| `NONE` | none | Execute one element operation, no repeat |
| `REP` | `rep` | Repeat while RCX ≠ 0. On `scas`/`cmps` it acts as `REPE` (same F3 encoding, R6) |
| `REPE` | `repe`, `repz` | Repeat while RCX ≠ 0 and, for `scas`/`cmps`, ZF = 1 |
| `REPNE` | `repne`, `repnz` | Repeat while RCX ≠ 0 and, for `scas`/`cmps`, ZF = 0 |

- `RepPrefix.parse(word): RepPrefix?` returns null for non-prefix words.
- On `movs`/`stos`/`lods`, `REPE` and `REPNE` behave exactly like `REP`.

## StringOp *(new, `cpu/StringOps.kt`)*

A string instruction descriptor, derived from the mnemonic. It isn't stored separately.

| Field | Type | Values / rule |
|---|---|---|
| `family` | enum `Family` | `MOVS`, `STOS`, `LODS`, `SCAS`, `CMPS` |
| `size` | Int | 1, 2, 4, 8 from the suffix `b`, `w`, `d`, `q` |
| `usesRsi` | Boolean (derived) | `MOVS`, `LODS`, `CMPS` |
| `usesRdi` | Boolean (derived) | `MOVS`, `STOS`, `SCAS`, `CMPS` |
| `usesAccumulator` | Boolean (derived) | `STOS` (reads), `LODS` (writes), `SCAS` (reads) |
| `setsFlags` | Boolean (derived) | `SCAS`, `CMPS` only |

- `StringOp.of(mnemonic): StringOp?` recognizes exactly the 20 names
  `{movs,stos,lods,scas,cmps} × {b,w,d,q}`. Anything else returns null.
- `StringOp.ALL_MNEMONICS: Set<String>` is used by the assembler's reserved words, `Docs` and
  editor highlighting.
- `StringOp.BARE: Set<String>` = `{movs, stos, lods, scas, cmps}`. These are recognized only so
  the assembler can report the "write the size in the name" error.
- `fun stopsAfter(prefix: RepPrefix, zf: Boolean): Boolean` is the ZF-based stop rule, checked
  after each iteration. It is `false` for families that don't set flags.

### Element operation (one iteration)

Let `n = size`, `d = if (DF) -n else +n`, `A` = accumulator (AL/AX/EAX/RAX by `n`).

| Family | Reads | Writes | Flags | Pointer update |
|---|---|---|---|---|
| MOVS | `[RSI]`(n) | `[RDI]`(n) ← value | none | RSI += d, RDI += d |
| STOS | A | `[RDI]`(n) ← A | none | RDI += d |
| LODS | `[RSI]`(n) | A ← value (a 32-bit write zeroes bits 32–63; 8/16-bit writes preserve the rest) | none | RSI += d |
| SCAS | A, `[RDI]`(n) | none | as `cmp A, [RDI]` | RDI += d |
| CMPS | `[RSI]`(n), `[RDI]`(n) | none | as `cmp [RSI], [RDI]` | RSI += d, RDI += d |

**Ordering invariant**: all memory reads and writes of an iteration happen **before** any register
update. A `CpuFault` therefore leaves RSI, RDI, RCX and RIP at their values from the last completed
iteration (FR-011).

## Instruction *(extended, `cpu/Instruction.kt`)*

| Field | Change |
|---|---|
| `prefix: RepPrefix` | **New**, default `RepPrefix.NONE`. Set only when `StringOp.of(mnemonic) != null`. |
| `mnemonic` | Unchanged: the bare string mnemonic (e.g. `"movsb"`), never including the prefix |
| `operands` | Always empty for string instructions |
| `source` | Unchanged: the original line text, including the prefix |

**Validation** (assembler): `prefix != NONE` ⇒ `StringOp.of(mnemonic) != null`, and string
instructions have `operands.isEmpty()`.

## Cpu *(extended, `cpu/Cpu.kt`)*

| Field | Change |
|---|---|
| `repeating: Boolean` | **New**. It is `true` only immediately after a step that completed an iteration of a prefixed instruction without ending the repeat. Every step starts by setting it to `false`. `reset()` clears it. |

### Repeat state transitions (per `Cpu.step()` on a prefixed instruction at address `a`)

```text
                  ┌──────────── RCX = 0 at entry ───────────────┐
                  │                                              ▼
 [Start: RIP=a, repeating=false] ──RCX≠0──► iteration ──► RCX -= 1
                                                   │
                     RCX = 0  or  stopsAfter(prefix, ZF)?
                     ├── yes ─► [Done: RIP=a+4, repeating=false]
                     └── no  ─► [Continuing: RIP=a, repeating=true] ──next Step──► iteration …
 Any memory fault in an iteration ─► [Faulted: RIP=a, RCX/RSI/RDI from last completed iteration]
```

- The RCX = 0 check happens only at **entry** (before the first iteration). Each iteration then
  decrements RCX and tests the termination conditions.
- An unprefixed string instruction always performs exactly one iteration and moves to `a+4`. It
  doesn't read or change RCX.

## Machine.StepRecord *(extended, `Machine.kt`)*

| Field | Change |
|---|---|
| `repeating: Boolean` | **New**. It holds `cpu.repeating` from before the step and is restored by `stepBack()`, so iteration-level undo also restores "mid-repeat" status (Principle V). |

Everything else about an iteration is already captured by the existing record: registers, RIP,
RFLAGS, the memory undo log, state, message and step count. One iteration uses one history entry,
so the 50,000-entry limit applies per iteration.

## Assembler.Pending *(extended, `asm/Assembler.kt`)*

| Field | Change |
|---|---|
| `prefix: RepPrefix` | **New**, default `NONE`. Parsed in pass 1 and copied into `Instruction` in pass 2. |

Reserved words: `MNEMONICS` gains `StringOp.ALL_MNEMONICS`, and a new `PREFIXES` set
`{rep, repe, repz, repne, repnz}` is also rejected as a label name.

## Relationships

```text
Assembler ──parses──► Instruction(mnemonic, prefix: RepPrefix)
                          │ mnemonic ──StringOp.of──► StringOp(family, size)
Cpu.step() ──uses──► StringOp + prefix ──► one iteration; updates Cpu.repeating
Machine.step() ──records──► StepRecord(…, repeating)   ◄──restores── Machine.stepBack()
UI (MainWindow / Docs / AsmEditor) ──reads──► StringOp, Instruction.prefix, Cpu.repeating, regs
```
