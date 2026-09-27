# Data Model: Explain "RIP does not point to an instruction" Faults

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

## New types

### `FetchFault` (`cpu/Memory.kt`, next to `CpuFault`)

| Field | Type | Notes |
|---|---|---|
| `rip` | `Long` | The address that had no instruction |

It is a subclass of `CpuFault`, so existing `catch (f: CpuFault)` blocks still work. Its default
message is the old text, used only when no program is attached.

### `FaultKind` (`analysis/FaultExplainer.kt`)

`ENTRY`, `RAN_PAST_END`, `EMPTY_LABEL`, `BAD_RETURN`, `BAD_TARGET`, `MISALIGNED`, which match
cases (a)–(e) of FR-002 plus the entry edge case. The classification order is in research R4.

### `FaultReport` (`analysis/FaultExplainer.kt`)

| Field | Type | Notes |
|---|---|---|
| `kind` | `FaultKind` | |
| `rip` | `Long` | The offending address (FR-004) |
| `line` | `Int?` | 0-based source line of the instruction that transferred control; null for `ENTRY` |
| `source` | `String?` | That instruction's source text with the comment removed |
| `label` | `String?` | The label at `rip`, as written in the source (local labels shown as `.done`) |
| `headline` | `String` | One line. Starts with `Segmentation fault:` and contains `RIP=0x…` |
| `hint` | `String` | One sentence the learner can act on (FR-005) |

It is immutable. The same inputs always give an equal report (FR-008).

## Changed types

| Type | Change | Why |
|---|---|---|
| `Cpu` | `var lastRip: Long = -1`. Set to `ins.address` after each completed step; reset in `reset()` | R2 |
| `Cpu.step()` | Throws `FetchFault(rip)` instead of a plain `CpuFault` when `code[rip]` is null | R3 |
| `Machine.StepRecord` | Adds `lastRip: Long` and `fault: FaultReport?` | Step Back restores both (FR-008, Principle V) |
| `Machine` | `var fault: FaultReport?` (null unless the machine is faulted by a fetch). Cleared by `load()` | Read by GUI and CLI |
| `Machine` | `val faultInstruction: Instruction?`: when FAULTED, returns `cpu.currentInstruction()` if there is one, otherwise `fault?.line`'s instruction | Editor highlight (FR-006) |
| `Machine` | `fun pendingFetchFault(): FaultReport?`: when paused with no instruction at RIP, returns a preview report without changing any state | NEXT label |
| `AsmError` | Adds `warning: Boolean = false`. `toString()` is unchanged | Shared Problems list |
| `Program` | Adds `labelLines: Map<String, Int>` (label → 0-based definition line) | W2 and label display |
| `AsmEditor` | Adds `var faultLine = -1` | FR-006 |

## State transitions (fetch fault)

```text
PAUSED (rip on imul) --step--> PAUSED (rip = 0x401018, lastRip = imul, NEXT shows preview)
                     --step--> FAULTED (fault = RAN_PAST_END report, message = headline)
FAULTED --stepBack--> PAUSED (rip = 0x401018, lastRip = imul, fault = null)
        --step------> FAULTED (identical report)
```

The faulting step doesn't increment `steps`, as today. Its `StepRecord` is still remembered, so it
can be stepped back out of.

## Validation rules

- `headline` always contains `Segmentation fault:` and `RIP=0x` followed by lowercase hex.
- `line` is non-null for every kind except `ENTRY`.
- `ProgramLint.warnings()` returns entries with `warning = true` only, sorted by line, and at most
  one per line.
