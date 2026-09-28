# Implementation Plan: Disassemble an x86-64 Binary into the Editor

**Branch**: `005-disassemble-binary` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-disassemble-binary/spec.md`

## Summary

Add **File → Disassemble Binary…** and a headless `x86learn disasm <binary>` command. Both call a
new headless package, `x86sim.disasm`, which does three things:

1. Reads ELF64, Mach-O 64 (thin or universal) and PE32+ headers with the project's own small
   bounds-checked readers. Anything that isn't a supported x86-64 program is rejected with a
   beginner-friendly reason.
2. Decodes the code sections with the **iced-x86** Java library (pure Java, MIT). Invalid bytes
   are recovered from one at a time.
3. Formats a NASM-syntax, read-only listing with labels, address/bytes comments and a header.

The GUI runs this in a cancellable background worker and loads the result into the editor as a new
unsaved `Document.Disassembly`.

## Technical Context

**Language/Version**: Kotlin 2.2 on the JVM, JDK 21 toolchain, runs on JDK 17+

**Primary Dependencies**: FlatLaf (existing); **new: `io.github.icedland.iced:iced-x86:1.21.0`**
(pure Java, MIT, no transitive runtime dependencies, ≈1.3 MB, Java 8 bytecode). Justification:
the spec forbids writing our own decoder (FR-018), and it's the only maintained pure-JVM x86-64
disassembler with a NASM formatter (research R1).

**Storage**: Files only. The binary is read and never written. The listing is saved as `.asm` only
when the learner asks.

**Testing**: `kotlin.test` / JUnit 5 via `./gradlew test`, using synthetic binary builders and
small committed fixture binaries (research R9).

**Target Platform**: Desktop (macOS Intel and Apple Silicon, Linux, Windows) through the existing
Swing app and CLI

**Project Type**: Desktop app with a headless CLI (single Gradle project)

**Performance Goals**: 1 MB of code checked, decoded and shown in under 5 s (SC-004). A 10k-line
listing loads into the editor in 1 s or less.

**Constraints**:
- No native code.
- No network access or external tools at runtime.
- The UI stays responsive, and the work can be cancelled.
- The listing is capped at 10,000 lines, with at most 4 KiB per data section.

**Scale/Scope**: About 8 new core files (~1,200 LOC), small UI and CLI changes, and about 7 test
files

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | How the design complies | Status |
|-----------|-------------------------|--------|
| **I. Architectural Fidelity** | No execution semantics change. The listing is a faithful NASM rendering of real machine code, produced by an established decoder. No new simulator deviation. The README limitations will state that disassembled listings may contain instructions the simulator doesn't run. | ✅ |
| **II. Learner-First Clarity** | Rejection messages name the file, the detected format and the reason in plain words (data-model table). The listing header explains what it is and that it may not run. Assembly errors on unsupported lines use the existing beginner-friendly messages. No new instruction support is added, so the Reference/hover docs don't need new entries. Existing hover keeps working for supported mnemonics. Dialogs are checked in both themes and at 80–250% zoom. | ✅ |
| **III. Test-Backed Semantics** | Unit tests with concrete values for every reader branch, every rejection reason, label and recovery rules, the cap, cancellation, golden listings and CLI exit codes (quickstart §1). This feature has no execution semantics, so no Step Back test is needed. | ✅ |
| **IV. Headless Core, Observing UI** | All logic is in `x86sim.disasm` with no Swing imports. Cancellation is a plain `() -> Boolean`. The GUI and the CLI share one `Disassembler.listing()` call, so the output is identical. | ✅ |
| **V. Deterministic, Reversible Execution** | Disassembly doesn't touch `Machine`. Loading a listing is like File → Open (it resets the machine through the existing `setSource`). The output depends only on the file bytes. | ✅ |
| **Tech constraints: new runtime dependency** | iced-x86 is justified above and in research R1. | ✅ (justified) |
| **Tech constraints: no native binaries, no network/toolchain at runtime** | iced-x86 is pure Java. The fixtures are committed, so no toolchain is needed to build or test. | ✅ |
| **Tech constraints: scope (no SSE, etc.)** | Scope is unchanged: the simulator still doesn't execute SSE. The disassembler can *show* such instructions, which the spec explicitly asks for (FR-010). This is documented in the README limitations. | ✅ |
| **Workflow: done criteria** | Tests; GUI and CLI parity; README (Features, CLI usage, limitations, third-party credit); both themes checked. Example program: not applicable (no new instruction support), and the fixtures serve as samples. | ✅ |

**Post-design re-check (after Phase 1)**: all gates still pass. The design added no UI-side logic
beyond calling the worker, no new state in `Machine`, and no dependency besides iced-x86. No
entries are needed in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/005-disassemble-binary/
├── plan.md              # This file
├── research.md          # Phase 0: library choice, format rules, caps, fixtures
├── data-model.md        # Phase 1: BinaryImage, Section, Detection, Listing, Document.Disassembly
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   ├── listing-format.md   # exact listing text
│   ├── cli-disasm.md       # x86learn disasm
│   └── ui-disassemble.md   # menu item, flow, dialogs
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
build.gradle.kts                       # + implementation("io.github.icedland.iced:iced-x86:1.21.0")

src/main/kotlin/x86sim/
├── disasm/                            # NEW, headless (no Swing)
│   ├── ByteReader.kt                  # bounds-checked little/big-endian reads → DamagedException
│   ├── BinaryImage.kt                 # BinaryImage, Section, BinaryFormat
│   ├── Detection.kt                   # Detection, RejectReason, detect(bytes, name), readAndDetect(file)
│   ├── ElfReader.kt                   # ELF64 sections/segments, entry, symtab/dynsym, PLT names
│   ├── MachOReader.kt                 # thin + universal, LC_SEGMENT_64, LC_MAIN/UNIXTHREAD, nlist, stubs
│   ├── PeReader.kt                    # MZ/PE, section table, entry, exports, IAT imports
│   ├── Disassembler.kt                # iced decode, label passes, cancellation
│   └── Listing.kt                     # Listing, text formatting, caps (MAX_LINES, MAX_DATA_BYTES)
├── Main.kt                            # + `disasm` subcommand, runDisasm(path): Int, USAGE line
└── ui/
    ├── DocumentLabel.kt               # + Document.Disassembly (titleName/labelText/tooltipText)
    └── MainWindow.kt                  # + menu item, SwingWorker + progress/cancel dialog,
                                       #   save default name for disassemblies

src/test/kotlin/x86sim/disasm/
├── TestBinaries.kt                    # synthetic ELF/Mach-O/PE builders and broken variants
├── BinaryDetectTest.kt
├── ElfReaderTest.kt
├── MachOReaderTest.kt
├── PeReaderTest.kt
├── DisassemblerTest.kt
├── ListingGoldenTest.kt
└── DisasmCliTest.kt
src/test/kotlin/x86sim/DocumentLabelTest.kt   # + Disassembly cases
src/test/resources/binaries/            # hello-elf, hello-macho, hello-pe.exe, *.expected.asm, README.md

README.md                               # Features, CLI usage, limitations, third-party notice
```

**Structure Decision**: Single Gradle project, following the existing layering. The new
`x86sim.disasm` package sits beside `asm/`, `cpu/` and `analysis/` as headless core (Principle
IV). The UI and CLI touch only `Main.kt`, `ui/MainWindow.kt` and `ui/DocumentLabel.kt`.

## Complexity Tracking

No constitution violations; no entries.
