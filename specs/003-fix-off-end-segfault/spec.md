# Feature Specification: Explain "RIP does not point to an instruction" Faults

**Feature Branch**: `003-fix-off-end-segfault`

**Created**: 2026-09-27

**Status**: Draft

**Input**: User description: "fix the bug × Segmentation fault: RIP=0x401018 does not point to an instruction"

## Background

The reported message can be reproduced with a program like this, where the jump target `.done` is a
label with no instruction after it:

```nasm
_start:
    mov rdi, 2
    mov rsi, 10
    mov rax, 1
.loop:
    test rsi, rsi
    jz .done
    imul rax, rdi
.done:
```

The six instructions take the slots 0x401000–0x401014. After `imul`, execution continues into
0x401018, where no instruction exists, and the simulator stops with
`Segmentation fault: RIP=0x401018 does not point to an instruction`. The same message appears when
`ret` pops a value that isn't a return address (for example `push 1` / `ret` gives `RIP=0x1`), or
when a `jmp`/`call` goes through a register or memory operand that holds a non-code address.

Stopping here is correct: a real x86-64 CPU under Linux would also crash when execution leaves the
program's code (Principle I). The bug is that the message gives only a hex address. It doesn't say
which line caused the problem, why execution got there, or how to fix it, so a beginner can't act
on it (Principle II).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Understand a program that runs off the end of its code (Priority: P1)

A student writes a program that forgets to finish with `ret` or an `exit` system call, or that jumps
to a label placed after the last instruction. When they run or step it, execution leaves the code.
The simulator still stops with a segmentation fault, but the message says in plain words that
execution ran past the last instruction, names the line that was executed last, and suggests the
usual fixes (end with `ret` or an `exit` syscall, or put an instruction after the label).

**Why this priority**: This is exactly the reported case and the most common way beginners hit the
fault. Every program without a proper ending runs into it.

**Independent Test**: Run the program from the Background section in the GUI and in the headless CLI.
Both stop with a message that names the last executed line and says execution ran past the end of
the program. No hex-only message remains.

**Acceptance Scenarios**:

1. **Given** a program whose last instruction is not a control transfer or exit, **When** it runs to
   completion, **Then** it stops in the faulted state with a message stating that execution ran past
   the last instruction, naming that instruction's source line, and suggesting `ret` or an `exit`
   syscall.
2. **Given** a program that jumps to a label with no instruction after it, **When** the jump is
   taken, **Then** the fault message names the jumping instruction's line and the label, and says
   the label has no instruction after it.
3. **Given** the fault has occurred in the GUI, **When** the student looks at the editor, **Then**
   the line that caused the fault (the last one executed) is highlighted as the fault location.
4. **Given** the fault has occurred, **When** the student uses Step Back, **Then** the machine
   returns to the state just before the faulting step, as it does for any other fault.

---

### User Story 2 - Understand a bad `ret` or indirect jump (Priority: P2)

A student's `ret` pops a value that isn't a return address (the stack is unbalanced, or a value was
pushed and never popped), or an indirect `jmp`/`call` goes through a register or memory holding a
non-code address. The fault message names the instruction that transferred control, its line, and
the address it tried to go to. For `ret`, it explains that the value on top of the stack wasn't a
return address and points to unbalanced `push`/`pop` as the likely cause.

**Why this priority**: This is the second common source of the same unhelpful message. It is less
frequent than falling off the end, but just as confusing.

**Independent Test**: Run `_start: push 1` / `ret` and `_start: mov rax, 5` / `jmp rax`. Each stops
with a message naming the `ret`/`jmp` line, the bad target address, and a cause the student can
act on.

**Acceptance Scenarios**:

1. **Given** `ret` pops an address outside the program's code, **When** it executes, **Then** the
   fault message names the `ret` line, shows the popped address, and says the top of the stack
   wasn't a return address (a likely unbalanced `push`/`pop`).
2. **Given** an indirect `jmp` or `call` whose target isn't an instruction, **When** it executes,
   **Then** the fault message names that instruction's line and the target address.
3. **Given** a control transfer lands inside `.text` but not at the start of an instruction slot
   (for example, a computed address that is off by a few bytes), **When** it executes, **Then** the
   message says the address is inside the program's code but not at the start of an instruction.

---

### User Story 3 - Get warned before running (Priority: P3)

When the student builds the program, they get a warning (not an error) if the code can reach the
end of `.text` without a `ret`, `jmp`, `hlt` or `exit` syscall, or if a label in `.text` has no
instruction after it. The program still builds and runs, so the warning never stops a student who
wants to see the crash for themselves.

**Why this priority**: Catching the mistake before running is useful but optional. The runtime
message from P1 already makes the problem understandable.

**Independent Test**: Build the Background program. A warning appears in the Problems list pointing
at the last instruction or the empty label, and Run is still available.

**Acceptance Scenarios**:

1. **Given** a program whose final instruction can fall through to the end of the code, **When** it
   is built, **Then** a warning names that line and says execution may run past the end of the
   program.
2. **Given** a label in `.text` with no instruction after it, **When** the program is built,
   **Then** a warning names the label's line.
3. **Given** a program that ends with `ret`, `jmp`, `hlt` or an `exit` syscall (such as every
   bundled example), **When** it is built, **Then** no such warning appears.
4. **Given** only warnings (no errors), **When** the student presses Run, **Then** the program runs
   normally.

---

### Edge Cases

- `ret` from `_start` back to the simulator's exit address keeps ending the program cleanly, as it
  does today, and must not be reported as a fault.
- A fault on the very first step (for example, an empty program or one where `_start` labels no
  instruction) still gets a useful message even though no instruction has run yet.
- A program with no instructions at all is reported as such rather than as a hex address.
- Falling off the end after a `syscall` that is not `exit` (for example `write` as the last
  instruction) is treated as running past the end.
- The last instruction is a conditional jump (`jz`, `loop`, ...). If the branch isn't taken,
  execution falls through past the end and gets the P1 message.
- A fault reached during Run, Step Over and Step Out gets the same message as one reached by
  single-stepping.
- Step Back out of the fault, then Step again, produces the same fault and message (deterministic
  replay).
- Very long label names or addresses don't break the layout of the status bar or console at any
  zoom level.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When execution reaches an address with no instruction, the simulator MUST keep
  stopping with a segmentation fault (faulted state). It MUST NOT silently exit or skip ahead.
- **FR-002**: The fault message MUST say what kind of problem happened, using one of these cases:
  (a) ran past the last instruction, (b) jumped or called to a label with no instruction after it,
  (c) `ret` popped a value that isn't a return address, (d) an indirect `jmp`/`call` went to a
  non-code address, (e) the address is inside the code but not at the start of an instruction.
- **FR-003**: The fault message MUST name the source line number of the instruction that transferred
  control to the bad address (the last one executed), and its label when one exists.
- **FR-004**: The fault message MUST still include the offending RIP value, so the architectural
  information stays visible.
- **FR-005**: Each message MUST include a short suggestion a beginner can act on (for example "end
  `_start` with `ret` or an `exit` syscall", "check that every `push` has a matching `pop`").
- **FR-006**: In the GUI, the line named in the message MUST be highlighted in the editor as the
  fault location, in both themes.
- **FR-007**: The headless CLI MUST print the same message as the GUI for the same program.
- **FR-008**: Step Back from the fault MUST restore the pre-fault state completely, and stepping
  again MUST reproduce the identical fault and message.
- **FR-009**: When a program is built, the assembler SHOULD warn when (a) the final instruction in
  `.text` can fall through past the end, or (b) a label in `.text` has no instruction after it.
  Warnings MUST NOT prevent the program from running.
- **FR-010**: Existing behaviour that ends the program cleanly (a `ret` from `_start` to the exit
  address, an `exit` syscall, `hlt`) MUST stay unchanged.
- **FR-011**: The Reference tab / hover help for `ret` and the "Design notes and limitations" in the
  README MUST explain what happens when execution leaves the program's code.
- **FR-012**: Each fault case in FR-002 and each warning in FR-009 MUST have a regression test that
  fails without this fix (including the exact reported program producing `RIP=0x401018`).

### Key Entities

- **Fault report**: the fault kind (FR-002), the offending address, the source line and label of the
  instruction that transferred control, and a suggestion. The GUI status bar, console and CLI all
  show the same report.
- **Build warning**: a non-blocking problem attached to a source line, shown in the Problems list
  next to errors but marked as a warning.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For 100% of the fault cases in FR-002, the message names a source line and a cause.
  None of them produces a message that contains only a hex address.
- **SC-002**: A beginner who sees the fault message for the Background program can find the line to
  change without using the trace or the memory view (checked by walking through it: the named line
  is the one to fix or follow).
- **SC-003**: The reported program (`RIP=0x401018`) and at least one program per fault case give
  the same message in the GUI and the CLI.
- **SC-004**: All bundled example programs build with zero new warnings and run with the same
  results as before.
- **SC-005**: The full existing test suite still passes, and the number of fault-related regression
  tests grows by at least one per fault case and one per warning.

## Assumptions

- The reported error comes from a program that ran past its last instruction (reproduced above with
  exactly `RIP=0x401018`). Faulting there is correct x86-64/Linux behaviour, so "fix" here means
  making the fault understandable, not stopping it from happening.
- The simulator will not treat falling off the end as an implicit exit, because that would teach
  behaviour real hardware doesn't have (Principle I).
- Build warnings are new to the simulator. They share the existing Problems list and don't need a
  separate panel.
- The warning analysis is intentionally simple (the last instruction and empty labels). A full
  reachability analysis of every path is out of scope.
- The unusual exit code seen when `ret` returns from `_start` with a large value in RAX (for example
  1024, where Linux would report `1024 & 0xff = 0`) is a separate issue and out of scope here.
