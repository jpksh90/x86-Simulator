# Quickstart: Validating String Instructions

**Feature**: [spec.md](./spec.md) | **Contracts**: [contracts/](./contracts/)

## Prerequisites

- JDK 17+ (Gradle provisions the JDK 21 toolchain)
- Run from the repo root: `/Users/jyp/x86-Simulator`

## 1. Automated checks (must pass)

```bash
./gradlew test
```

The tests below are expected to be added by the tasks for this feature:

| Test (in `src/test/kotlin/x86sim/`) | Proves |
|---|---|
| `StringInstructionsTest`: semantics per family × size × DF | S1/S2 and the element-operation table in [data-model.md](./data-model.md) |
| `StringInstructionsTest`: `scas`/`cmps` flags == `cmp` flags over an edge-value grid | FR-004, SC-001 |
| `StringInstructionsTest`: RCX = 0, early stop, `rep` on `scas` = `repe`, `repne` on `stos` = `rep` | FR-005, R6 |
| `StringInstructionsTest`: fault partway through (write into `.rodata`) | FR-011 / S3 |
| `StringInstructionsTest`: 1,000,000 × `rep stosb` with history on, in < 2 s | FR-012 / S7 |
| `SimulatorTest`: assembler errors E1–E11 | [assembly-syntax.md](./contracts/assembly-syntax.md) |
| `SimulatorTest`: `09_strings` output | FR-017 |
| `StepBackTest`: per-iteration undo, `repeating` restored; examples loop now includes `09_strings` | FR-010 / S4 |

## 2. Headless run

```bash
./gradlew installDist
build/install/x86sim/bin/x86sim run --example 09_strings
```

**Expected**: the example's output lines, covering the copied string, the zero-filled array
check, the string length and the comparison result. The program exits with code 0, and the last
stderr line is `[sim] Exited · code 0 (N instructions)`.

Trace a short repeat and count its iterations:

```bash
printf 'section .data\nsrc db "abcde"\nsection .bss\ndst resb 5\nsection .text\n_start:\n lea rsi,[src]\n lea rdi,[dst]\n mov ecx,5\n rep movsb\n mov eax,60\n xor edi,edi\n syscall\n' > /tmp/rep.asm
build/install/x86sim/bin/x86sim run /tmp/rep.asm --trace 2>&1 | grep -c 'rep movsb'
```

**Expected**: `5`, one trace line per iteration (S6).

Check an assembler error:

```bash
printf '_start:\n rep add rax, 1\n' > /tmp/bad.asm
build/install/x86sim/bin/x86sim run /tmp/bad.asm; echo "exit=$?"
```

**Expected**: `error: line 2: 'rep' only works with string instructions … not 'add'` and `exit=2`.

## 3. GUI walkthrough (manual, about 3 minutes)

```bash
./gradlew run
```

1. Open **Examples → Strings: rep movs/stos/scas/cmps**.
2. Step (F7) until the next line is `rep movsb`. The status bar reads
   `copy byte [rsi] → [rdi] … · N left`.
3. Press Step 3 times. Each press should copy one byte (highlighted in the Memory view), decrease
   RCX by 1 and advance RSI/RDI by 1, and the current line should stay on `rep movsb`.
4. Press Step Back twice. The last two bytes should revert, RCX should go back up by 2, and the
   line should be unchanged.
5. Press Step Over (F8). The repeat should finish at once and the next line should be highlighted.
   The status shows "Stepped over repeat".
6. Reset, set a breakpoint (F9) on `repe cmpsb`, and press Run (F5) at any speed. Execution should
   pause **once** at that line. Press Run again: it should continue past the line without stopping
   at each iteration.
7. Hover `rep`, `repne`, `scasb` and `movsq` in the editor. Each should show a description of its
   implicit registers, the DF rule and, for prefixes, the stop condition. Check the Reference tab
   for a "String instructions" section.
8. Toggle the theme (◐) and zoom (⌘+ to 250%), and repeat step 3. The highlights and status text
   should stay legible.

## 4. Performance spot check (SC-003)

In the GUI, load this program, set the speed slider to any value, reach `rep stosb` and press
Step Over. It should complete in under 2 seconds, and pressing Pause during a larger count (e.g.
`mov ecx, 100000000`) should stop it within 0.5 s.

```nasm
section .bss
buf resb 1000000
section .text
_start:
    lea rdi, [buf]
    mov ecx, 1000000
    xor eax, eax
    rep stosb
    mov eax, 60
    xor edi, edi
    syscall
```

Repeat with Run (F5) at **Max** speed. It should also finish in under 2 s.

## 5. Optional hardware cross-check (x86-64 Linux only)

On an x86-64 Linux machine with `nasm` and `gdb`, assemble a test program natively (`nasm -f elf64
t.asm && ld t.o`), and compare `info registers` after each `stepi` over a `rep`/`repe` instruction
with the simulator's `--trace` and register panel. This isn't part of CI (see research R9).
