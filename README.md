# x86Learn

x86Learn is an educational, visual simulator for **x86-64 assembly** (NASM syntax), written in Kotlin with a Swing UI.
Write a program, then step through it one instruction at a time. As you go you can watch the registers,
flags, stack and memory change.

## Run it

Requires a JDK (17+). Gradle will download a JDK 21 toolchain if needed.

```bash
./gradlew run                    # open the visual simulator
```

Or build a launcher once and use it:

```bash
./gradlew installDist
build/install/x86learn/bin/x86learn                               # GUI
build/install/x86learn/bin/x86learn run program.asm [--trace]     # headless, in the terminal
build/install/x86learn/bin/x86learn run --example 03_factorial
```

The launcher used to be called `x86sim`; update any scripts that call it.

## Features

- **Modern UI**: dark and light themes (View → Toggle Dark/Light, or the ◐ button), with Inter and
  JetBrains Mono fonts. **⌘+ / ⌘− / ⌘0** (Ctrl on Windows/Linux) zooms the whole window (80%–250%),
  e.g. for a projector; the zoom level is remembered. Built on [FlatLaf](https://www.formdev.com/flatlaf/).

- **Built-in assembler**: a two-pass NASM-style assembler with labels, local labels (`.loop`),
  `db/dw/dd/dq`, `resb…resq`, `times`, `equ`, `$`, strings, and expressions. When code is invalid it
  gives errors a beginner can act on, like "operation size not specified" or "two memory operands".
  It also warns (without blocking the run) when execution can run past the last instruction or a
  label has no instruction after it.
- **Crash explanations**: when execution leaves the program's code, the segmentation fault says
  why (ran past the last instruction, jumped to an empty label, `ret` to something that isn't a
  return address, a bad indirect jump), names the line responsible, highlights it and suggests a fix.
- **Stepping**: Step (F7), **Step Back (⇧F7 / ⌘[)**, Step Over (F8), Step Out (⇧F8), Run (F5) at adjustable
  speed, Pause (F6) and Reset. Step Back undoes the last instruction completely (registers, flags,
  memory, program output and consumed input), up to the last 50,000 steps, including out of a crash. Click the margin (or press F9) to set a breakpoint.
  On a `rep` string instruction, Step runs one iteration (rcx counts down and the line stays put),
  Step Back undoes one iteration, and Step Over finishes the whole repeat.
- **Registers and flags**: all 16 GPRs plus RIP and RFLAGS, shown as signed, unsigned or ASCII. Values
  that changed are highlighted. CF/PF/AF/ZF/SF/DF/OF are shown as lamps.
- **Stack Memory panel** (View → Stack Memory Panel, ⇧⌘M): a diagram of the stack with one cell per
  64-bit word. It's built for teaching local variables:
  - Each cell is labelled relative to its own frame's `rbp` (`rbp-8`, the way the code writes it) and to `rsp`.
  - Space reserved by `sub rsp, N` shows as hatched `?` cells until something is written there.
  - Each cell says what it holds: a return address, a saved `rbp`, or the source line that last wrote it.
  - Frames are bracketed with their function's name, found by following the chain of saved `rbp` values.
  - Registers that point into the stack are marked (e.g. `◀ rdi` for a pointer to a local).
  - Words popped off the stack show as "free — stale value".
  - "Show bytes" also shows the 8 bytes inside each word, so a `dword` or `byte` local visibly fills
    only part of a word.
  - Example: *Stack: local variables* (`08_locals`).
- **Memory view**: a hex and ASCII dump of `.data`, `.rodata`, `.bss` or the stack. Jump to any
  expression (`fib+16`, `rsp`, `rbp-8`). Bytes written by the last step are highlighted.
- **Control flow graph** (View → Control Flow Graph, ⇧⌘G, or the **Graph** button): splits the program
  into basic blocks, one function at a time. Edges are colour-coded: green for a branch taken, red for
  not taken, blue for a jump or fall-through. It follows execution live and outlines the block about to
  run. Clicking an instruction jumps to it in the editor.
- **I/O through Linux syscalls**: `read` (0), `write` (1), `exit` (60), `exit_group` (231),
  `getpid` (39), `time` (201). The console has a stdin line and an EOF button, and a `read` pauses
  the program until you type something.
- **Faults behave like real ones**: segmentation faults on unmapped or read-only memory, and `#DE` on
  divide-by-zero or quotient overflow.
- **Help while you work**: hover an instruction or register for a description. The status bar explains
  the next instruction, and the Reference tab lists everything that's supported.
- 9 example programs: hello world, loops, recursion, keyboard input, arrays, bubble sort, a tour of
  the flags, local variables on the stack, and string operations (`rep movsb`, `repne scasb`, ...).

## Supported instructions

`mov movzx movsx movsxd lea xchg` · `add adc sub sbb inc dec neg cmp` · `mul imul div idiv` ·
`and or xor not test` · `shl sal shr sar rol ror` · `cbw cwde cdqe cwd cdq cqo` ·
`push pop call ret leave` · `jmp jcc setcc cmovcc loop jrcxz jecxz` · `syscall nop hlt` ·
`clc stc cmc cld std` ·
`movs stos lods scas cmps` (with a `b`/`w`/`d`/`q` size suffix) · `rep repe/repz repne/repnz`

All 8/16/32/64-bit register forms are supported (`al`, `ah`, `ax`, `eax`, `rax`, `r8b`…`r15`), with
the real partial-register rules: writing a 32-bit register zeroes the upper half, while 8/16-bit writes
preserve the rest.

## Memory layout

| Address | Contents |
|---|---|
| `0x401000` | `.text` (instructions) |
| `0x500000` | `.rodata` (read-only) |
| `0x600000` | `.data` |
| `0x700000` | `.bss` |
| `0x7ffffffef000`–`0x7ffffffff000` | stack (64 KiB) |

Execution starts at `_start`, or at `main` if there's no `_start`. The stack starts with a return
address to an exit stub, so a `main` that ends with `ret` exits with the code in `eax`.

## Design notes and limitations

- Instructions run in a decoded form, not as encoded machine-code bytes. Each instruction takes a
  4-byte slot in `.text`, so addresses, `call`/`ret` and return addresses all work, but code can't be
  read as data or modified at runtime.
- Execution that leaves the program's code (running past the last instruction, or a bad `ret` or
  jump target) stops with a segmentation fault, as on real Linux. Running off the end is not treated
  as an exit: end `_start` with an `exit` syscall or `ret`.
- There's no floating point/SSE and no linker or C library (`extern` is rejected). Programs talk to
  the "OS" through `syscall`.
- String instructions are supported only in their 64-bit forms (rcx, rsi, rdi) with a size suffix
  (`movsb`, not `movs byte [rdi], [rsi]`). There's no `ins`/`outs`, and no segment-override or `a32` prefixes.

## Project layout

```
src/main/kotlin/x86sim/
  cpu/      Registers, Memory (regions and faults), Instruction model, Cpu (execution and flags)
  asm/      Expression parser, two-pass Assembler with validation
  analysis/ ControlFlowGraph: basic blocks, edges, functions
  Machine.kt   loads programs, maps memory, implements the Linux syscalls
  Main.kt      GUI launcher and headless CLI
  ui/       Swing UI: editor + gutter, registers, stack, memory, console, main window
src/main/resources/examples/   example programs
src/test/kotlin/               unit tests (./gradlew test) and UiSnapshot, a dev helper that renders the UI to PNG
```
