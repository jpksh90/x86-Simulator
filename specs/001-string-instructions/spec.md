# Feature Specification: String Instructions with REP Prefixes

**Feature Branch**: `001-string-instructions`

**Created**: 2026-09-27

**Status**: Implemented (manual GUI checks T021/T037 pending)

**Input**: User description: "add string instructions with rep prefixes"

## Clarifications

### Session 2026-09-27

- Q: Which list should the spec's new "Out of Scope" section declare? → A: Option A: port I/O
  string instructions, segment overrides, the 32-bit address-size override, explicit-operand forms,
  SSE `movsd`/`cmpsd` forms with operands, any new UI panel just for repeats, and cycle timing /
  "fast string" behavior.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Copy and fill memory with REP MOVS / REP STOS (Priority: P1)

A student writes a program that copies a buffer (`rep movsb`, `rep movsq`) or fills one with a value
(`rep stosb`, `rep stosq`), the way real `memcpy`/`memset` code does. The program assembles, runs,
and they can watch the destination buffer fill in while RCX counts down and RSI/RDI advance.

**Why this priority**: copying and filling are the most common uses of string instructions in real
code and course material. They are also the simplest, because they don't involve comparisons or
flags, so they deliver value on their own.

**Independent Test**: Assemble a program that copies a 13-byte string from `.data` to `.bss` with
`rep movsb`, and fills a 4-qword array with `rep stosq`. Run it to the end and check the
destination bytes and the final RCX, RSI and RDI values.

**Acceptance Scenarios**:

1. **Given** RSI points to "Hello, world!", RDI points to a 13-byte `.bss` buffer, RCX = 13 and
   DF = 0, **When** `rep movsb` runs to completion, **Then** the buffer holds "Hello, world!",
   RCX = 0, RSI and RDI have each advanced by 13, and no flags have changed.
2. **Given** RDI points to a 4-qword array, RAX = 0x1122334455667788 and RCX = 4, **When**
   `rep stosq` runs, **Then** all four qwords equal RAX and RDI has advanced by 32.
3. **Given** RCX = 0, **When** any `rep`-prefixed instruction runs, **Then** no memory is read or
   written and RCX, RSI, RDI and the flags are unchanged.
4. **Given** DF = 1 (after `std`), **When** `rep movsb` runs, **Then** it copies from high
   addresses to low and RSI/RDI decrease.
5. **Given** `movsb`, `movsw`, `movsd`, `movsq`, `stosb`, `stosw`, `stosd` or `stosq` without a
   prefix, **When** it runs, **Then** it performs exactly one element transfer of that size.

---

### User Story 2 - Step through a REP instruction one iteration at a time (Priority: P1)

A student steps (F7) onto `rep movsb`. Each press performs one iteration: one byte is copied,
RCX goes down by one, RSI and RDI move, and the instruction pointer stays on the same line until
RCX reaches zero. Step Over (F8) finishes the rest of the repeat in one go. Step Back (⇧F7) undoes
one iteration at a time.

**Why this priority**: seeing one iteration at a time is the whole reason to use a teaching
simulator for string instructions, and it matches how real hardware and debuggers treat REP
instructions. Without it the loop is a black box.

**Independent Test**: Load a `rep movsb` program with RCX = 5, press Step until the next line is
reached, and check that it took 5 presses, each copying one byte. Step Back twice and check that
the last two bytes and the register values are restored.

**Acceptance Scenarios**:

1. **Given** the next instruction is `rep movsb` with RCX = 5, **When** the user presses Step once,
   **Then** exactly one byte is copied, RCX = 4, RSI/RDI have advanced by 1, the highlighted line is
   still `rep movsb`, and the changed registers and memory byte are highlighted.
2. **Given** the same situation, **When** the user presses Step Over, **Then** all remaining
   iterations run and execution stops on the following line.
3. **Given** a `rep movsb` partway through (RCX = 2), **When** the user presses Step Back, **Then**
   the previously copied byte is restored to its old value and RCX, RSI and RDI go back by one
   iteration.
4. **Given** a breakpoint on a `rep` line, **When** the program runs (F5), **Then** execution pauses
   once, before the first iteration, not before every iteration.
5. **Given** the next instruction is a `rep`-prefixed string instruction, **When** the status bar
   explains it, **Then** it states the current iteration's effect and how many iterations remain
   (e.g. "copy byte [rsi] → [rdi], 4 left").

---

### User Story 3 - Search and compare with REPE/REPNE SCAS/CMPS and LODS (Priority: P2)

A student writes `strlen` with `repne scasb`, compares two strings with `repe cmpsb`, and walks an
array with `lodsb`/`lodsq` in a loop. The conditional repeats stop either when RCX runs out or when
the comparison result (ZF) says to stop, and the flags show the result of the last comparison.

**Why this priority**: these are classic textbook idioms and exercise the flags, but they build on
the plain repeat from Stories 1–2.

**Independent Test**: Run a `strlen` routine using `repne scasb` on "abc\0" with RCX = -1 and AL = 0,
then check the computed length (3) and ZF = 1. Run `repe cmpsb` on "abcd"/"abXd" and check it stops
after the third byte with ZF = 0.

**Acceptance Scenarios**:

1. **Given** RDI points to "abc\0", AL = 0 and RCX = -1, **When** `repne scasb` runs, **Then** it
   stops after comparing the zero byte, with ZF = 1, RDI pointing one past the zero byte, and
   RCX = -5 (so `not rcx; dec rcx` gives 3).
2. **Given** RSI → "abcd", RDI → "abXd" and RCX = 4, **When** `repe cmpsb` runs, **Then** it stops
   after the third comparison with ZF = 0, RCX = 1, and CF/SF/OF/PF/AF set exactly as
   `cmp byte [rsi], byte [rdi]` would set them for 'c' vs 'X'.
3. **Given** two equal 4-byte strings, **When** `repe cmpsb` runs with RCX = 4, **Then** it stops
   because RCX = 0, with ZF = 1.
4. **Given** RSI points to an array of bytes, **When** `lodsb` runs, **Then** AL gets the byte,
   RSI advances by 1, and the rest of RAX is preserved. `lodsd` zeroes the upper 32 bits of RAX,
   following the usual 32-bit write rule.
5. **Given** `rep lodsb`, **When** it is assembled, **Then** it is accepted and loads RCX bytes in
   turn into AL (matching NASM and hardware), leaving AL holding the last byte.

---

### User Story 4 - Learn the instructions from the built-in help and examples (Priority: P3)

A student who hasn't seen string instructions before hovers `rep movsb`, opens the Reference tab,
or loads a new example program, and learns which registers each instruction uses implicitly, what
DF does, and how REP, REPE and REPNE decide when to stop.

**Why this priority**: required by the project's learner-first principle, but it only matters once
the instructions work.

**Independent Test**: Hover each new mnemonic and prefix and confirm a description appears. Find
each one in the Reference tab. Load the new example and run it to completion.

**Acceptance Scenarios**:

1. **Given** any new mnemonic or prefix in the editor, **When** the user hovers it, **Then** a
   description names its implicit operands (RSI, RDI, RCX, AL/AX/EAX/RAX), the element size, the
   DF direction rule and, for prefixes, the stop condition.
2. **Given** the example list, **When** the user opens the string-instructions example, **Then** it
   demonstrates at least copy (`rep movsb`), fill (`rep stosq`), length (`repne scasb`) and compare
   (`repe cmpsb`), and prints results to the console.

---

### Edge Cases

- **Fault mid-repeat**: if an iteration reads unmapped memory or writes read-only memory, the
  program faults with the usual segmentation-fault report. RCX, RSI and RDI reflect only the
  iterations that completed, the instruction pointer stays on the faulting instruction, and
  Step Back can undo the fault and earlier iterations.
- **Overlapping copies**: `rep movsb` with overlapping source and destination copies element by
  element in the DF direction, exactly as hardware does (e.g. a forward copy with RDI = RSI + 1
  spreads the first byte through the buffer).
- **Very large counts**: a repeat with a large RCX (e.g. 1,000,000) run with Run or Step Over
  completes without freezing the UI. A negative-looking RCX used as "unbounded" (e.g. `repne scasb`
  with RCX = -1) runs until its stop condition is met or it faults.
- **Undo history limit**: each iteration counts as one step for the Step Back history, so a repeat
  longer than the history limit can only be partly undone. This matches the existing limit and
  isn't treated as an error.
- **Invalid prefix use**: `rep`/`repe`/`repne` in front of a non-string instruction (e.g.
  `rep add rax, 1`) or with no instruction after it is rejected at assembly with a clear message
  naming the line. `repe`/`repne` on `movs`/`stos`/`lods` is accepted and behaves as a plain `rep`,
  matching NASM and hardware.
- **Operand-form syntax**: explicit-operand forms that NASM doesn't accept (e.g.
  `movs byte [rdi], [rsi]`) are rejected with a message suggesting the suffixed form (`movsb`).
- **Prefix spelling**: `repe`/`repz` and `repne`/`repnz` are accepted as synonyms, case-insensitively,
  and a prefix may be on the same line as a label (`.copy: rep movsb`).
- **Control flow graph**: a `rep` instruction appears as an ordinary straight-line instruction in
  its basic block. It doesn't split blocks or add edges.
- **Headless mode**: a trace in the CLI (`--trace`) shows each iteration of a repeat as its own
  traced step, consistent with GUI stepping.

## Requirements *(mandatory)*

### Functional Requirements

**Instructions and prefixes**

- **FR-001**: The assembler MUST accept `movsb/movsw/movsd/movsq`, `stosb/stosw/stosd/stosq`,
  `lodsb/lodsw/lodsd/lodsq`, `scasb/scasw/scasd/scasq` and `cmpsb/cmpsw/cmpsd/cmpsq` with no
  operands.
- **FR-002**: The assembler MUST accept the prefixes `rep`, `repe`, `repz`, `repne` and `repnz`
  directly before a string instruction on the same line. Anything else a prefix is applied to MUST
  be rejected with a line-specific, beginner-readable error.
- **FR-003**: Each string instruction MUST perform one element operation of its size (1, 2, 4 or 8
  bytes) using RSI as source and/or RDI as destination, and then add the element size to each
  pointer it used when DF = 0, or subtract it when DF = 1.
- **FR-004**: `movs` copies memory at RSI to memory at RDI. `stos` stores AL/AX/EAX/RAX at RDI.
  `lods` loads memory at RSI into AL/AX/EAX/RAX, following the existing partial-register rules.
  `scas` compares AL/AX/EAX/RAX with memory at RDI. `cmps` compares memory at RSI with memory at
  RDI. `scas` and `cmps` MUST set CF, PF, AF, ZF, SF and OF exactly as the equivalent `cmp`
  (accumulator − [RDI] and [RSI] − [RDI] respectively). `movs`, `stos` and `lods` MUST NOT change
  any flag.
- **FR-005**: With a repeat prefix, the instruction MUST do nothing if RCX = 0 at the start.
  Otherwise it MUST repeatedly perform one element operation and decrement RCX, stopping when
  RCX = 0, or (for `scas`/`cmps`) after an iteration where ZF = 0 under `repe`/`repz`, or ZF = 1
  under `repne`/`repnz`. On `movs`/`stos`/`lods`, `repe`/`repne` MUST behave like `rep`.
- **FR-006**: The repeat counter and pointers MUST be the full 64-bit RCX, RSI and RDI.

**Stepping, running and reversibility**

- **FR-007**: A single Step on a repeat-prefixed instruction MUST perform exactly one iteration
  (or none if RCX = 0). The instruction pointer MUST stay on that instruction until the repeat
  ends, then move to the next instruction.
- **FR-008**: Step Over MUST complete all remaining iterations of the current repeat instruction
  and stop at the following instruction.
- **FR-009**: A breakpoint on a repeat instruction MUST pause execution only before its first
  iteration, not before each iteration.
- **FR-010**: Step Back MUST undo one iteration at a time, restoring every memory element,
  register and flag that iteration changed, including undoing a fault raised by an iteration.
- **FR-011**: A memory fault during any iteration MUST stop execution with the existing fault
  report. RCX/RSI/RDI MUST reflect only completed iterations and the instruction pointer MUST
  remain on the faulting instruction.
- **FR-012**: Running a repeat of 1,000,000 iterations with Run or Step Over MUST keep the
  interface responsive, and Pause MUST be able to interrupt it between iterations.
- **FR-013**: Results MUST be identical in the GUI and the headless command-line runner, and the
  headless trace MUST list each iteration.

**Visibility and learning support**

- **FR-014**: After each iteration the registers, flags and memory bytes it changed MUST be
  highlighted like any other step.
- **FR-015**: The status-bar explanation for a pending string instruction MUST describe the
  current iteration's effect and, for repeats, the remaining count and stop condition.
- **FR-016**: Every new mnemonic and prefix MUST have hover help and a Reference entry that names
  its implicit operands, element size, DF rule and (for prefixes) stop condition.
- **FR-017**: A new example program MUST demonstrate copy, fill, string length and string
  comparison with string instructions and print its results.
- **FR-018**: The project README MUST list the new instructions under supported instructions and
  remove string instructions from its limitations.

### Key Entities

- **String instruction**: an operation (move, store, load, scan, compare) plus an element size
  (byte, word, dword, qword). Its operands are implicit: RSI, RDI and/or the accumulator.
- **Repeat prefix**: none, `rep`, `repe`/`repz` or `repne`/`repnz`. It sets the stop rule applied
  after each iteration (counter only, counter or ZF = 0, counter or ZF = 1).
- **Iteration**: one element operation plus a counter update. It is the unit of stepping, change
  highlighting and Step Back.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For every one of the 20 mnemonics × both DF directions, and every prefix/instruction
  combination, the resulting registers, flags and memory match real x86-64 hardware behavior on a
  reference set of test programs, with 0 mismatches.
- **SC-002**: A student can go through a 10-byte `rep movsb` one iteration at a time and back again
  with Step Back, and the displayed state matches the recorded forward states at every point
  (100% of 20 checkpoints).
- **SC-003**: A 1,000,000-iteration `rep stosb` completes via Run or Step Over in under 2 seconds on
  a typical laptop, and the window stays responsive (Pause takes effect within 0.5 seconds).
- **SC-004**: Every misuse listed under Edge Cases (prefix on a non-string instruction, dangling
  prefix, explicit-operand form) produces an error that names the line and suggests a fix. 0 of
  them produce a generic or internal error.
- **SC-005**: Every new mnemonic and prefix (20 mnemonics + 5 prefix spellings) has hover help and
  a Reference entry (100% coverage).
- **SC-006**: The textbook idioms `strlen` (repne scasb), `memcpy` (rep movsb), `memset` (rep stosb/q)
  and `strcmp` (repe cmpsb) can be written unchanged, the way they appear in common assembly
  tutorials, and produce correct results.

## Out of Scope

Only the five string families (`movs`, `stos`, `lods`, `scas`, `cmps`) with the `rep`/`repe`/`repz`/
`repne`/`repnz` prefixes are in scope. The following are explicitly excluded from this feature:

- **Port I/O string instructions** (`ins`/`outs`, with or without `rep`). They are privileged and
  outside the user-mode scope set by the constitution. They produce an "unsupported instruction"
  error.
- **Segment-override prefixes** on string instructions (e.g. `fs:`/`gs:`, `movsb` with a
  source-segment override).
- **The 32-bit address-size override** (e.g. `a32 rep movsb`, using ECX as the counter and
  ESI/EDI as pointers). Only the 64-bit RCX/RSI/RDI forms are supported.
- **Explicit-operand forms** (e.g. `movs byte [rdi], [rsi]`, `stos qword [rdi]`). NASM doesn't
  accept them either. They're rejected with a message suggesting the suffixed mnemonic (see Edge
  Cases).
- **SSE `movsd`/`cmpsd` with operands** (scalar double move/compare). Floating point/SSE is out of
  scope for the simulator. These are rejected with a message saying the SSE forms aren't supported.
- **A new UI panel or widget just for repeats** (e.g. an iteration-progress bar). Repeat progress
  is shown only through the existing register, flag, memory and status-bar views (FR-014, FR-015).
- **Cycle timing and hardware "fast string" behavior**: no modeling of instruction latency,
  microcode fast-string paths or iteration batching. Each iteration is always executed and shown as
  one step.

## Assumptions

- **Stepping granularity**: one Step = one iteration. Real CPUs single-step REP instructions one
  iteration at a time, and debuggers such as GDB behave the same way. Step Over is the way to run a
  whole repeat at once.
- **Ambiguity with SSE `movsd`/`cmpsd`**: because the SSE forms are out of scope, `movsd` and
  `cmpsd` with no operands always mean the string instructions.
- **Undo history**: each iteration uses one entry of the existing bounded Step Back history (50,000
  steps). Undoing a repeat longer than that is only partly possible, as with any long run.
- **Execution model**: a repeat instruction still takes one instruction slot in `.text`. The
  repetition is a property of how it executes, not extra instructions.
- **Existing support reused**: `cld`/`std` and the DF lamp already exist and need no changes beyond
  being honored by the new instructions.
