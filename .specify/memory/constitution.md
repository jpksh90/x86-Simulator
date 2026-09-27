# x86-64 Simulator Constitution

## Core Principles

### I. Architectural Fidelity

The simulator exists to teach how a real x86-64 CPU running under Linux behaves. Anything a learner
can observe MUST match real hardware and the Linux ABI:

- Instruction results, flag effects (CF/PF/AF/ZF/SF/DF/OF), partial-register rules (32-bit writes
  zero the upper half; 8/16-bit writes preserve the rest) and fault conditions (`#DE`, segmentation
  faults on unmapped or read-only memory) MUST follow the Intel/AMD manuals.
- Syscall numbers, argument registers and return values MUST follow the Linux x86-64 ABI.
- Assembler syntax MUST follow NASM. A program the simulator accepts SHOULD also assemble under
  NASM with the same meaning.
- Any intentional deviation or simplification (e.g. decoded instructions in 4-byte slots, no
  self-modifying code) MUST be listed under "Design notes and limitations" in `README.md`.
  Undocumented divergence counts as a bug.

Rationale: a simulator that teaches wrong behavior is worse than no simulator.

### II. Learner-First Clarity

The target user is a student meeting assembly for the first time.

- Assembler and runtime errors MUST name the line and say what is wrong in words a beginner can act
  on (e.g. "operation size not specified"), not just report an internal failure.
- Every state change an instruction causes (registers, flags, memory, stack, output) MUST be
  visible in the UI and highlighted as changed after the step that caused it.
- Every supported instruction, register and syscall MUST have hover help and an entry in the
  Reference tab (`ui/Docs.kt`). Adding support for something without its documentation counts as
  incomplete work.
- UI features MUST work in both dark and light themes and at every supported zoom level
  (80%–250%).

Rationale: the simulator's value is in explaining what happened, not only in computing it.

### III. Test-Backed Semantics (NON-NEGOTIABLE)

- Every instruction, flag rule, addressing form, syscall, fault and assembler directive MUST have
  unit tests under `src/test/kotlin/` that assert on concrete register, flag and memory values.
- Every bug fix MUST add a regression test that fails without the fix.
- `./gradlew test` MUST pass before any commit to `main`.
- Changes to execution semantics SHOULD be written test-first: write the expected state from the
  manuals, watch the test fail, then implement.

Rationale: small errors in semantics are silent and easy to reintroduce. Tests are the only reliable
record of correct behavior.

### IV. Headless Core, Observing UI

- `cpu/`, `asm/`, `analysis/` and `Machine.kt` MUST NOT depend on Swing, FlatLaf or any UI code.
- Every program that runs in the GUI MUST also run from the headless CLI
  (`x86sim run program.asm [--trace]`) with identical results.
- The UI MUST read and observe machine state. It MUST NOT carry execution logic of its own. New
  features that need information (e.g. frame detection, basic blocks) MUST get it from core or
  analysis APIs that can be unit-tested without a display.

Rationale: keeping the core headless lets it be tested, run from the terminal and scripted, and
stops the UI from quietly changing semantics.

### V. Deterministic, Reversible Execution

- Given the same program and the same stdin, execution MUST produce the same states step by step.
  Nondeterministic inputs (e.g. `time`, `getpid`) MUST be recorded when first observed so that
  replay and Step Back reproduce them exactly.
- Every operation that changes state (registers, flags, memory, program output, consumed input,
  halt/fault status) MUST record enough undo information for Step Back to restore the previous state
  completely. New instructions and syscalls MUST come with a Step Back test.
- The undo history is bounded (currently 50,000 steps). Memory use per step MUST stay proportional
  to what the step changed, not to total machine size.

Rationale: stepping backwards is a core teaching tool. One instruction that can't be undone breaks
trust in the whole timeline.

## Technology & Scope Constraints

- Language and runtime: Kotlin on the JVM with a Gradle build, targeting the JDK 21 toolchain. The
  app MUST still run on any JDK 17+.
- UI: Swing with FlatLaf and its bundled Inter and JetBrains Mono fonts. New runtime dependencies
  need a stated justification in the feature plan. Prefer the standard library.
- Distribution: `./gradlew run` and `./gradlew installDist` MUST keep working with no extra setup.
  The simulator MUST NOT need network access, native binaries or an installed NASM/linker at
  runtime.
- Scope: user-mode, single-threaded x86-64 integer code in NASM syntax, talking to a simulated Linux
  through `syscall`. Floating point/SSE, string instructions, `extern` or a linker/libc, and
  privileged instructions are out of scope unless a feature spec explicitly brings them in and
  updates the README limitations.
- Memory layout (`.text` 0x401000, `.rodata` 0x500000, `.data` 0x600000, `.bss` 0x700000, 64 KiB
  stack below 0x7ffffffff000) is part of the learner-facing contract. Changing it counts as a
  breaking change and MUST be reflected in `README.md` and the examples.

## Development Workflow & Quality Gates

- Each feature follows Spec Kit: `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` →
  `/speckit-implement`. The plan's Constitution Check MUST explicitly address Principles I–V.
- A change is done only when all of the following are true:
  1. `./gradlew test` passes.
  2. The program works both in the GUI and in the headless CLI.
  3. `README.md` (Features, Supported instructions, limitations) and the Reference/hover docs are
     updated.
  4. User-visible UI changes are checked in both themes. `UiSnapshot` renders can be used for this.
- New learner-facing capabilities SHOULD ship with an example program in
  `src/main/resources/examples/` or an extension of an existing one.
- Commits stay focused on one feature or fix, with an imperative summary line
  (e.g. "Add Step Back (reverse stepping)").

## Governance

- This constitution overrides informal conventions and earlier practice. Where a plan or task
  conflicts with it, the constitution wins until it is amended.
- Amendments are made by editing this file (via `/speckit-constitution`) in a dedicated commit that
  states the version bump and the reason for it.
- Versioning follows semantic versioning. MAJOR: a principle is removed or redefined incompatibly.
  MINOR: a principle or section is added or guidance is materially expanded. PATCH: wording and
  clarifications.
- Compliance: every feature plan MUST pass the Constitution Check gate before design and again
  after it. Any violation MUST be recorded with a justification in the plan's Complexity Tracking
  table, or the plan is rejected. Code review MUST check changes against Principles I–V.

**Version**: 1.0.0 | **Ratified**: 2026-09-27 | **Last Amended**: 2026-09-27
