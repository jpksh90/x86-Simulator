# Contract: Execution Semantics and Stepping

**Consumers**: `Cpu.step()`, `Machine.step()` / `stepBack()` / `runToEnd()`, the headless CLI
trace, and the unit tests. The source of truth is the Intel SDM Vol. 2 (MOVS/MOVSB…, STOS, LODS,
SCAS, CMPS, and REP/REPE/REPZ/REPNE/REPNZ). The element-operation table is in
[data-model.md](../data-model.md#element-operation-one-iteration).

## S1. Unprefixed instruction

- Performs exactly one element operation of its size, then moves RIP to the next instruction.
- It does not read or modify RCX.
- `movs`/`stos`/`lods` change no flags. `scas`/`cmps` set CF, PF, AF, ZF, SF and OF identically
  to `cmp A, [RDI]` / `cmp [RSI], [RDI]` of the same size.

## S2. One `Machine.step()` on a prefixed instruction

```text
if RCX == 0 at entry         → no memory access, no register or flag change, RIP = next
else:
    element operation (memory first, then pointers)
    RCX -= 1
    done = RCX == 0 || (family ∈ {SCAS, CMPS} && stop(prefix, ZF))
        where stop(REP or REPE, ZF) = !ZF ;  stop(REPNE, ZF) = ZF
    RIP = if done then next else (unchanged)
    Cpu.repeating = !done
```

- RCX, RSI and RDI are the full 64-bit registers. RCX = −1 (0xFFFF…FFFF) means about 2⁶⁴
  iterations.
- `Machine.steps` increases by 1 per `Machine.step()`, i.e. per iteration. When RCX = 0 at entry,
  the no-op still counts as 1 step.
- The history gets one `StepRecord` per `Machine.step()`. The record includes `repeating`.

## S3. Faults

- If an iteration's memory access faults (unmapped, or a write to read-only memory), the machine
  goes to `FAULTED` with the existing segmentation-fault message. RIP is the instruction's
  address, and RCX/RSI/RDI/flags are exactly as they were after the last completed iteration.
- `stepBack()` from the faulted state returns to `PAUSED` with the same register values
  (existing behavior for faults).

## S4. Step Back

- `stepBack()` undoes exactly one iteration. It restores every byte written, RCX/RSI/RDI/RAX,
  RFLAGS, RIP and `Cpu.repeating`.
- Stepping all the way back from the end of any program, including the new example, reproduces
  the initial snapshot exactly (the existing `StepBackTest` invariant).

## S5. GUI stepping controls

| Control | When the next instruction is a prefixed string instruction |
|---|---|
| Step (F7) | Runs one `Machine.step()`, i.e. one iteration. The editor line stays the same until the repeat ends. |
| Step Over (F8) | Runs until `cpu.rip != thisInstruction.address`, or the machine stops (exit, fault, halt). It uses an unlimited per-tick budget, whatever the speed slider says. Pause (F6) interrupts it between ticks. Status message: "Stepped over repeat". |
| Step Out (⇧F8) | Unchanged. |
| Run (F5) | Unchanged, at the slider speed. A breakpoint on the line pauses only when `!cpu.repeating`, i.e. before the first iteration. |
| Step Back (⇧F7 / ⌘[) | Undoes one iteration (S4). |

## S6. CLI

- `x86sim run file.asm` produces the same final state and output as the GUI.
- `--trace` prints one line per `Machine.step()`, so a repeat of N iterations prints N lines, all
  with the same address and source text. The `rax=`/`rsp=` columns are unchanged.

## S7. Performance

- In the GUI, Step Over on a `rep stosb` with RCX = 1,000,000 completes in < 2 s. This also holds
  for Run at the **Max** speed.
- Headless, `runToEnd()` on the same program with history enabled completes in < 2 s. This is
  checked by a unit test.
