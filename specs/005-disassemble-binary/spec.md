# Feature Specification: Disassemble an x86-64 Binary into the Editor

**Feature Branch**: `005-disassemble-binary`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "I want the tool to disassemble any x86_64 binary to assemble. The idea is there should be a menu option to disassemble a binary. It should ask for the path, infer whether it is a x86 binary and later disassemble it and show it on the editor."

## Clarifications

### Session 2026-09-28

- Q: Which executable formats count as "any x86_64 binary"? → A: Linux ELF64, macOS Mach-O
  64-bit (including the x86-64 slice of a universal binary) and Windows PE32+.
- Q: Is the listing a read-only study aid or should it be runnable? → A: Read-only study aid,
  faithful to the binary; it may not assemble or run in the simulator.
- Q: Should instruction decoding be written for this project or come from an existing library?
  → A: Use an existing, established library; do not implement a disassembler in this project.
- Q: Can the library include native (platform-specific) code, or must it run entirely on the JVM?
  → A: Prefer a pure-JVM library; a native-backed one is allowed only if no suitable pure-JVM
  library meets the requirements, and that choice must be justified in the plan.
- Q: Should reading the ELF/Mach-O/PE file formats also come from a library? → A: No; the library
  is used for instruction decoding only, and the project reads file headers, sections, entry point
  and symbols itself with unit-tested code.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Disassemble a compiled program and read it in the editor (Priority: P1)

A learner has a small compiled x86-64 program (for example one they built with NASM and a linker,
or a tiny C program). They choose **File → Disassemble Binary…**, pick the file, and the editor
fills with the program's machine code written back out as NASM-syntax assembly: one instruction
per line, with labels at the entry point and at jump/call targets, and comments showing each
instruction's original address and bytes. The toolbar label shows the binary's name so the learner
knows what they are looking at.

**Why this priority**: This is the feature. It lets a learner connect "the assembly I write" with
"what is actually inside an executable", using the editor, hover help and Reference tab they
already know.

**Independent Test**: Choose File → Disassemble Binary…, select a known 64-bit executable whose
contents are known in advance, and check that the editor shows the expected instructions in order,
with the entry point labelled.

**Acceptance Scenarios**:

1. **Given** the app is open, **When** the learner opens the File menu, **Then** it contains a
   "Disassemble Binary…" item.
2. **Given** the learner chooses Disassemble Binary…, **When** they select a valid x86-64
   executable, **Then** the editor shows its code as NASM-syntax assembly and the toolbar label
   shows the binary's file name.
3. **Given** a disassembled listing is shown, **When** the learner hovers an instruction mnemonic
   or register, **Then** the usual hover help appears.
4. **Given** the listing, **When** the learner compares an instruction line with its address/bytes
   comment, **Then** the address and bytes match the original file.
5. **Given** the program had a jump or call to an address inside its code, **When** it is
   disassembled, **Then** the target line has a label and the jump/call uses that label instead of
   a raw address.

---

### User Story 2 - Clear rejection of files that aren't x86-64 binaries (Priority: P1)

A learner picks a file that isn't something the tool can disassemble: a text file, an image, an
ARM or 32-bit executable, or a corrupt/truncated executable. The tool checks the file before doing
anything and explains in plain words what the file is and why it can't be disassembled. The
editor's current program is left untouched.

**Why this priority**: The user explicitly asked that the tool infer whether the file is an x86
binary. Without a clear check, a wrong file would produce garbage or a crash, and the learner
might lose their current program.

**Independent Test**: Pick, in turn, a `.asm` text file, a PNG image, an ARM64 executable, and a
truncated copy of a valid executable; each time check that a clear message names the problem and
that the editor content is unchanged.

**Acceptance Scenarios**:

1. **Given** the learner picks a plain text file, **When** the check runs, **Then** a message says
   the file is not an executable binary, and the editor is unchanged.
2. **Given** the learner picks an executable for another CPU (e.g. ARM64), **When** the check runs,
   **Then** the message names the detected architecture (e.g. "this is an ARM64 binary; only x86-64
   is supported") and the editor is unchanged.
3. **Given** the learner picks a 32-bit x86 executable, **When** the check runs, **Then** the
   message says it is 32-bit x86 and only 64-bit is supported.
4. **Given** the learner picks a truncated or corrupt x86-64 executable, **When** the check runs,
   **Then** the message says the file is damaged or incomplete, and the editor is unchanged.
5. **Given** the learner picks a file they have no permission to read, or that no longer exists,
   **When** the check runs, **Then** the message says the file can't be read.

---

### User Story 3 - Don't lose unsaved work (Priority: P2)

If the program in the editor has unsaved changes, disassembling a binary asks the learner first,
the same way File → Open and the Examples menu already do.

**Why this priority**: Reuses an existing safety rule; important but not unique to this feature.

**Independent Test**: Edit the current program, choose Disassemble Binary…, and check that the
usual discard/save prompt appears; cancel it and confirm nothing changed.

**Acceptance Scenarios**:

1. **Given** the editor has unsaved changes, **When** the learner chooses Disassemble Binary…,
   **Then** the existing unsaved-changes prompt appears before the file picker.
2. **Given** that prompt, **When** the learner cancels, **Then** the editor and label are unchanged.
3. **Given** a disassembly is shown, **When** the learner saves it, **Then** it is saved as a new
   `.asm` text file and the original binary is never modified.

---

### User Story 4 - Disassemble from the terminal (Priority: P3)

A learner or instructor runs the headless command with a path to a binary and gets the same
listing printed to the terminal (or the same rejection message), so the listing can be saved,
compared or used in teaching material without opening the GUI.

**Why this priority**: The project requires that features available in the GUI get their data
from headless, testable logic and work from the CLI; this story makes that visible to users.

**Independent Test**: Run the headless command on the same binary used in Story 1 and compare its
output with the editor contents; they must be identical.

**Acceptance Scenarios**:

1. **Given** a valid x86-64 executable, **When** the headless disassemble command is run on it,
   **Then** the printed listing is identical to what the editor shows for that file.
2. **Given** a non-x86-64 file, **When** the command is run, **Then** the same rejection message
   is printed and the command exits with a non-zero status.

---

### Edge Cases

- File picker cancelled: nothing changes.
- Very large binaries (e.g. several MB of code, or a statically linked program): the app stays
  responsive while disassembling; if the listing would exceed a size limit, the learner is told
  and only the first part is shown, with a comment saying where it was cut off.
- Bytes that don't decode as a valid instruction (padding, embedded data, or an instruction the
  disassembler doesn't know): shown as `db` lines with a comment, and disassembly continues after
  them instead of stopping.
- Stripped binaries (no symbol names): labels are generated from addresses (e.g. `loc_401020`);
  when symbol names are present they are used instead.
- Binaries with no code section or an entry point outside any code section: reported as damaged.
- Dynamically linked binaries that call library functions: the calls are shown, with the target
  labelled by the library function name when the file records it.
- Files whose name has no extension or an unusual one: detection is based on file contents, not
  the name.
- Theme switch and every zoom level (80%–250%): the listing and messages stay readable.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The File menu MUST contain a "Disassemble Binary…" item.
- **FR-002**: Choosing it MUST first apply the existing unsaved-changes prompt, then ask the
  learner for the binary's location with a file picker.
- **FR-003**: Before disassembling, the system MUST inspect the file's contents (not its name) and
  decide whether it is a supported x86-64 executable. Supported formats: Linux ELF64, macOS
  Mach-O 64-bit (including the x86-64 slice of a universal/"fat" binary), and Windows PE32+.
  The detected format MUST be named in the listing header and in rejection messages.
- **FR-004**: When the file is not supported, the system MUST show a message in beginner-friendly
  words that says what the file appears to be (not an executable; another CPU architecture, named;
  32-bit x86; damaged/truncated; unreadable) and MUST leave the editor unchanged.
- **FR-005**: When the file is supported, the system MUST decode its executable code into
  NASM-syntax assembly and replace the editor contents with it.
- **FR-006**: Each instruction line MUST carry a comment with its original virtual address and
  its raw bytes in hex.
- **FR-007**: The entry point MUST be labelled; direct jump and call targets inside the code MUST
  be labelled and referenced by label. Symbol names from the file MUST be used where present;
  otherwise labels MUST be generated from addresses.
- **FR-008**: Bytes that cannot be decoded MUST be emitted as `db` data with an explanatory
  comment, and decoding MUST continue after them.
- **FR-009**: The listing MUST begin with a header comment naming the source file, its format and
  architecture, the entry point address, and the sections that were disassembled.
- **FR-010**: The listing is a read-only study aid: it MUST be faithful to the binary, including
  instructions and operands the simulator cannot run, and MUST NOT be rewritten or simplified to
  make it runnable. The header comment MUST state that the listing may not assemble or run in the
  simulator.
- **FR-011**: The toolbar file-name label MUST show the binary's file name marked as a
  disassembly, and the listing MUST be treated as a new, unsaved program: saving MUST ask for a
  new `.asm` location and MUST NOT overwrite the binary.
- **FR-012**: The original binary file MUST never be modified.
- **FR-013**: The UI MUST remain responsive while a file is checked and disassembled; the learner
  MUST be able to cancel a disassembly that takes noticeably long.
- **FR-014**: Listings MUST be capped at a documented maximum size; when the cap is reached the
  listing MUST say so in a comment at the point it was cut.
- **FR-015**: The same detection and disassembly MUST be available from the headless command line,
  producing byte-for-byte the same listing and the same rejection messages as the GUI, with a
  non-zero exit status on rejection.
- **FR-016**: Disassembly MUST work without network access and without any external tool installed
  on the learner's machine. A pure-JVM library MUST be preferred. A library with native code MAY be
  used only if no suitable pure-JVM library meets FR-003 to FR-010; in that case the plan MUST
  justify the choice, it MUST be bundled so `./gradlew run` and `installDist` still need no extra
  setup on macOS (Intel and Apple Silicon), Linux and Windows, and the constitution's
  "no native binaries" constraint MUST be amended before implementation.
- **FR-017**: The README MUST describe the feature, the supported formats, and its limitations.
- **FR-018**: Machine-code decoding MUST be provided by an existing, established third-party
  disassembler library; the project MUST NOT implement its own instruction decoder. The project's
  own code is limited to choosing the file, reading the executable format (FR-019), calling the
  library, and formatting its output into the listing described in FR-006 to FR-010. The README MUST credit the library
  and its license.
- **FR-019**: Reading the executable format (detecting format, architecture and bitness; locating
  code and data sections, entry point and symbol names for ELF64, Mach-O 64-bit including universal
  binaries, and PE32+) MUST be done by the project's own code, without an additional format-parsing
  library, and MUST have unit tests for each format and for each rejection reason in FR-004.

### Key Entities

- **Binary file**: the file the learner selects. Attributes: path, detected format, detected
  architecture and bitness, entry point, list of code and data sections, optional symbol names.
- **Detection result**: either "supported" with the format details, or "rejected" with a reason
  category (not an executable, wrong architecture, 32-bit, damaged, unreadable) and a
  learner-facing message.
- **Disassembly listing**: the text placed in the editor. Consists of a header comment, labels,
  instruction lines (mnemonic, operands, address/bytes comment) and `db` lines for undecodable
  bytes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A learner can go from choosing the menu item to reading the disassembly of a small
  program in under 30 seconds, with no instructions beyond the menu item's name.
- **SC-002**: For a reference set of executables built from the project's own example programs,
  in each of the three supported formats (ELF, Mach-O, PE), 100% of instructions in the listing
  match the expected mnemonics and operands.
- **SC-003**: For a reference set of at least 6 non-supported files (text, image, ARM64, 32-bit
  x86, truncated, unreadable), 100% are rejected with the correct reason and the editor content is
  unchanged in every case.
- **SC-004**: A program of up to 1 MB of code is disassembled and shown in under 5 seconds on a
  typical laptop, and the window stays responsive throughout.
- **SC-005**: The listing produced from the GUI and from the command line for the same file are
  identical in 100% of the reference cases.
- **SC-006**: No reference case causes a crash, hang, or loss of the learner's previous program.

## Assumptions

- The target learner builds small x86-64 programs on Linux, macOS or Windows; the simulator itself
  models Linux, so Linux ELF executables are the primary case, with Mach-O and PE supported so that
  locally compiled programs can be inspected on any OS.
- Because the listing is read-only, Windows and macOS binaries are shown as-is: their system calls
  and library calls are not translated to the simulator's Linux model.
- Only the executable code sections are decoded as instructions. Read-only and writable data
  sections are listed as `db`/`dq` data under their section names so the learner can see them, but
  are not interpreted.
- Output uses NASM (Intel) syntax, consistent with the rest of the tool; AT&T syntax is out of
  scope.
- Relocatable object files (`.o`), shared libraries and core dumps are treated the same as
  executables if their format is supported; there is no entry-point label when a file has none.
- Disassembling does not load the program into the simulator's memory or change the machine state;
  the learner runs it (if at all) through the normal assemble-and-run path.
- Disassembly is linear from the start of each code section (not control-flow-guided); this is the
  common, predictable behavior of standard disassemblers.
- The existing unsaved-changes prompt, file chooser, file-name label and hover help are reused.
- Per the project constitution, the tool must not depend on an installed disassembler, linker or
  NASM at runtime; any disassembler library is bundled with the app.
