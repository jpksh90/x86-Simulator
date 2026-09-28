---

description: "Task list for Disassemble an x86-64 Binary into the Editor"
---

# Tasks: Disassemble an x86-64 Binary into the Editor

**Input**: Design documents from `specs/005-disassemble-binary/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/listing-format.md](./contracts/listing-format.md),
[contracts/cli-disasm.md](./contracts/cli-disasm.md),
[contracts/ui-disassemble.md](./contracts/ui-disassemble.md), [quickstart.md](./quickstart.md)

**Tests**: **Included, and written first.** Constitution Principle III and FR-019 require unit
tests with concrete values for each format and each rejection reason. Within each story, write the
test task first, watch it fail, then implement.

**Organization**: grouped by user story from spec.md.
- US1: disassemble a valid binary into the editor (P1).
- US2: reject files that aren't x86-64 programs (P1).
- US3: don't lose unsaved work, and save safely (P2).
- US4: `x86learn disasm` in the terminal (P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on incomplete tasks)
- Paths are relative to the repo root `/Users/jyp/x86-Simulator`. Production code is in
  `src/main/kotlin/x86sim/`, and tests are in `src/test/kotlin/x86sim/`.

## Conventions for every task

- Run `./gradlew test` after each task that touches production code. Existing tests must stay green.
- Everything in `src/main/kotlin/x86sim/disasm/` MUST NOT import `javax.swing`, `java.awt` or
  anything from `x86sim.ui` (Principle IV).
- Match the existing style: terse KDoc one-liners, `when` dispatch over sealed types, no
  reflection.
- All multi-byte header fields are **little-endian**, except the Mach-O universal (fat) header and
  its `fat_arch` entries, which are **big-endian**.
- Any read past the end of the file MUST become `Detection.Rejected(DAMAGED, …)` and never
  escape as an exception (SC-006).
- Listing text must match [contracts/listing-format.md](./contracts/listing-format.md) character for
  character: mnemonic at column 8 padded to 8, comment at column 48, lower-case hex bytes, `\n`
  line endings and a trailing newline.
- Learner-facing messages must follow the wording pattern in the data-model.md `RejectReason`
  table: name the file, name the detected format or architecture, and say what is supported.

---

## Phase 1: Setup

**Purpose**: Add the library and the package skeleton.

- [X] T001 Add `implementation("io.github.icedland.iced:iced-x86:1.21.0")` to the `dependencies` block in `build.gradle.kts`. Run `./gradlew build` to confirm it resolves and existing tests pass. Then run `./gradlew installDist` and confirm `build/install/x86learn/lib/iced-x86-1.21.0.jar` exists.
- [X] T002 [P] Create the package directories `src/main/kotlin/x86sim/disasm/`, `src/test/kotlin/x86sim/disasm/` and `src/test/resources/binaries/`. Add a placeholder `src/test/resources/binaries/README.md` with the heading "Test binaries" and one line saying the fixtures are built once and committed, so tests need no toolchain.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared types, the safe byte reader and the synthetic test-binary builders. Every story
uses these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Create `src/main/kotlin/x86sim/disasm/ByteReader.kt`:
  - `class ByteReader(val bytes: ByteArray)` with `u8(off)`, `u16le(off)`, `u32le(off)`,
    `u64le(off)`, `u32be(off)`, `slice(off, len): ByteArray` and `cString(off, maxLen)`
    (stops at NUL or `maxLen`, decoded as ISO-8859-1).
  - Every method checks bounds, and throws `DamagedException(what: String)` (defined in this file,
    `class DamagedException(message: String) : Exception(message)`) when `off < 0` or
    `off + width > bytes.size`.
  - `u32le` returns `Long` (unsigned), and offsets are `Long` or `Int` with an explicit overflow
    check.
- [X] T004 [P] Create `src/main/kotlin/x86sim/disasm/BinaryImage.kt` with the types from data-model.md:
  - `enum class BinaryFormat(val display: String) { ELF64("ELF64"), MACHO64("Mach-O 64-bit"), PE32PLUS("PE32+") }`.
  - `class Section(val name: String, val address: Long, val bytes: ByteArray)`.
  - `data class BinaryImage(format, kind: String, fatSliceNote: String?, entry: Long?, codeSections: List<Section>, dataSections: List<Section>, symbols: Map<Long, String>, imports: Map<Long, String>)`.
  - `val BinaryImage.summary: String`: `"${format.display} x86-64 $kind"`, e.g. "ELF64 x86-64 executable".
  - `fun BinaryImage.validate(fileName: String)`, which throws `DamagedException` when:
    - `codeSections` is empty, or every code section has size 0 → "it has no code to disassemble";
    - `entry != null && entry != 0L` and the entry is not inside any code section → "its entry point 0x… is outside its code".
- [X] T005 [P] Create `src/main/kotlin/x86sim/disasm/Detection.kt`:
  - `enum class RejectReason { NOT_EXECUTABLE, WRONG_ARCHITECTURE, THIRTY_TWO_BIT, DAMAGED, UNREADABLE }`.
  - `sealed interface Detection { data class Supported(val image: BinaryImage) : Detection; data class Rejected(val reason: RejectReason, val message: String) : Detection }`.
  - `fun detect(bytes: ByteArray, fileName: String): Detection`: for now it returns
    `Rejected(NOT_EXECUTABLE, "$fileName is not an executable program.")`. The dispatch is filled
    in by T015 and T024.
  - `fun readAndDetect(file: java.io.File): Detection`, which maps:
    - `NoSuchFileException`/`FileNotFoundException` → `Rejected(UNREADABLE, "Can't read ${file.path}: file not found.")`;
    - `AccessDeniedException` or `!file.canRead()` → `"…: permission denied."`;
    - a directory → `"…: it is a folder, not a file."`;
    - otherwise `detect(file.readBytes(), file.name)`.
- [X] T006 Create `src/test/kotlin/x86sim/disasm/TestBinaries.kt` (test helper, no tests), depending on T003. It holds builders that return `ByteArray`, each taking `code: ByteArray` and optional overrides, all little-endian, with minimal but valid headers:
  - `elf64(code, entry = 0x401000, machine = 0x3E, elfClass = 2, withSymbols: Map<String, Long> = emptyMap(), withSectionHeaders = true, data: ByteArray? = null)`. It writes an ELF header, one `PT_LOAD` R+X program header, `.text` (`SHF_ALLOC|SHF_EXECINSTR`), and an optional `.data`, `.symtab` + `.strtab` + `.shstrtab`.
  - `elf32(machine = 3)`: just a valid 32-bit header.
  - `machO64(code, cputype = 0x01000007, entryOff via LC_MAIN, symbols)`. It writes `MH_MAGIC_64`, `LC_SEGMENT_64 __TEXT` with a `__text` section flagged `S_ATTR_PURE_INSTRUCTIONS` (0x80000000), `LC_MAIN`, and an optional `LC_SYMTAB`.
  - `machO32()` with `0xFEEDFACE` and cputype 7.
  - `fat(vararg slices: Pair<Int /*cputype*/, ByteArray>)`: big-endian header and aligned slices.
  - `pe64(code, imageBase = 0x140000000, entryRva = 0x1000, machine = 0x8664, optMagic = 0x20B, exports: Map<String, Long>, imports: Map<String /*dll*/, List<String>>)`. It writes an MZ stub, `e_lfanew`, `PE\0\0`, the COFF header, a PE32+ optional header with 16 data directories, and a `.text` section with `IMAGE_SCN_CNT_CODE|MEM_EXECUTE|MEM_READ`.
  - Helpers `truncate(bytes, n)` and `patch32(bytes, off, value)` for broken variants.
  - The code bytes used across tests: `val HELLO_CODE = hex("b8 01 00 00 00 bf 01 00 00 00 e8 03 00 00 00 0f 0b c4 c3 0f 05 c3")`, i.e. mov eax,1 / mov edi,1 / call +3 / ud2 / invalid c4 / ret, with `0f 05 c3` reachable as a call target.

**Checkpoint**: The types compile, and `TestBinaries` can build every variant.

---

## Phase 3: User Story 1 - Disassemble a compiled program and read it in the editor (Priority: P1) 🎯 MVP

**Goal**: File → Disassemble Binary… turns a valid ELF64, Mach-O 64 or PE32+ x86-64 program into
a NASM listing in the editor (FR-001, FR-003, FR-005–FR-011, FR-013, FR-014, FR-016, FR-018,
FR-019).

**Independent Test**: Pick `src/test/resources/binaries/hello-elf` via the menu. The editor shows
the listing from `hello-elf.expected.asm`, `_start` is labelled, and the label reads
`*hello-elf (disassembly)`.

### Tests for User Story 1 (write first, must fail)

- [X] T007 [P] [US1] Write `src/test/kotlin/x86sim/disasm/ElfReaderTest.kt`, using `TestBinaries.elf64`. Assert:
  - format `ELF64` and kind "executable" (`ET_EXEC`=2), "shared library" (`ET_DYN`=3 with an interpreter or entry) or "object file" (`ET_REL`=1, entry `null`);
  - one code section `.text` at 0x401000 with exactly the code bytes;
  - `.data` in `dataSections`;
  - `symbols[0x401000] == "_start"` when a symbol is given;
  - with `withSectionHeaders = false`, the code comes from the `PF_X` `PT_LOAD` segment, named `"LOAD"`;
  - `SHT_NOBITS` sections are ignored.
- [X] T008 [P] [US1] Write `src/test/kotlin/x86sim/disasm/MachOReaderTest.kt`, using `TestBinaries.machO64` and `fat`. Assert:
  - format `MACHO64` and kind "executable" (`MH_EXECUTE`=2);
  - code section named `__TEXT,__text`;
  - entry = `__TEXT` vmaddr + `LC_MAIN.entryoff`;
  - an `LC_UNIXTHREAD` variant takes the entry from `rip`;
  - `nlist_64` `N_SECT` symbols are read, and debug stabs (`n_type & 0xE0 != 0`) are skipped;
  - a fat binary with arm64 + x86_64 slices picks the x86-64 slice, with `fatSliceNote == "x86-64 slice of a universal binary (also contains arm64)"`.
- [X] T009 [P] [US1] Write `src/test/kotlin/x86sim/disasm/PeReaderTest.kt`, using `TestBinaries.pe64`. Assert:
  - format `PE32PLUS` and kind "executable", or "DLL" when `IMAGE_FILE_DLL` (0x2000) is set;
  - entry = imageBase + entryRva;
  - `.text` address = imageBase + VirtualAddress, and its bytes are `SizeOfRawData` truncated to `VirtualSize`;
  - export names end up in `symbols`;
  - an import `kernel32.dll!ExitProcess` gives `imports[iatSlotAddress] == "__imp_kernel32.dll!ExitProcess"`.
- [X] T010 [P] [US1] Write `src/test/kotlin/x86sim/disasm/DisassemblerTest.kt`, using the ELF built from `HELLO_CODE` with symbol `_start`. Assert on exact lines of `Disassembler.listing(bytes, "hello", { false }).text`:
  - the header lines, in the order from contracts/listing-format.md rule 1;
  - `_start:`;
  - `        mov     eax, 1` padded to column 48, followed by `; 401000: b8 01 00 00 00`;
  - the call rendered as `call    loc_401011` and a `loc_401011:` label before `ret` at 0x401011 (adjust addresses to the real `HELLO_CODE` layout);
  - `        db      0xc4` with `; 401010: c4  (not a valid instruction)`, and the next line decoding at +1 (FR-008, research R2);
  - labels use sanitised symbol names: `foo::bar` → `foo__bar`, a leading digit gets a `_` prefix, duplicates become `_2`;
  - a branch target inside an instruction gets no label;
  - a data section is shown in `db` rows of 16 bytes with the `; <addr>` comment, and after 4096 bytes the line `        ; … N more bytes not shown`;
  - a code section of 30,000 `nop` (0x90) bytes produces exactly 20,000 lines, the last being `; --- listing cut off here: limit of 20000 lines reached (address 0x…) ---`, and `truncated == true`;
  - `isCancelled = { true }` throws `DisassemblyCancelled`;
  - `instructionCount` and `invalidByteCount` are correct.
- [X] T011 [P] [US1] Extend `src/test/kotlin/x86sim/DocumentLabelTest.kt` with cases for `Document.Disassembly(File("/tmp/hello"), "ELF64 x86-64 executable")`:
  - `titleName == "hello (disassembly)"`;
  - `labelText(doc, true) == "*hello (disassembly)"`;
  - `labelText(doc, false) == "hello (disassembly)"`;
  - `tooltipText(doc) == "/tmp/hello — ELF64 x86-64 executable"`, using `File.absolutePath`.

### Implementation for User Story 1

- [X] T012 [P] [US1] Implement `src/main/kotlin/x86sim/disasm/ElfReader.kt`: `object ElfReader { fun read(r: ByteReader): BinaryImage }`, which assumes the magic, 64-bit class, LE and machine 0x3E have already been checked by `detect`. It reads:
  - `e_type`, `e_entry` (`null` for `ET_REL`), and the section headers at `e_shoff`, `e_shnum`, `e_shentsize`=64, with names from `e_shstrndx`;
  - code sections: `sh_flags & 0x4` (EXECINSTR) and `sh_type != 8` (NOBITS);
  - data sections: `sh_flags & 0x2` (ALLOC), not EXECINSTR, `sh_type == 1` (PROGBITS);
  - with no section headers (`e_shnum == 0`), falls back to program headers `p_type == 1 && p_flags & 1` as a section named `LOAD`;
  - symbols from `.symtab`, else `.dynsym` (24-byte entries; `st_value != 0`, type FUNC=2, OBJECT=1 or NOTYPE=0, non-empty name);
  - best-effort PLT names: for each `.rela.plt` entry `i`, `imports[pltSec.addr + 16*i (+16 if the section is .plt)] = "<dynsym name>@plt"`. Wrap this in `runCatching` so bad tables never fail the read (research R3).
  Then call `validate`. Makes T007 pass.
- [X] T013 [P] [US1] Implement `src/main/kotlin/x86sim/disasm/MachOReader.kt`: `object MachOReader { fun read(r: ByteReader, sliceOffset: Long = 0, note: String? = null): BinaryImage }`. It walks the load commands from offset 32:
  - `LC_SEGMENT_64` (0x19): sections of 80 bytes; code if `flags & 0x80000400 != 0`; zero-fill (`flags & 0xFF` in {1, 0x0C, 0x12}) skipped; other sections become data;
  - `LC_MAIN` (0x80000028): `entryoff` + the `__TEXT` segment vmaddr;
  - `LC_UNIXTHREAD` (0x5): `rip` at thread-state offset 16*8 in the x86_THREAD_STATE64 layout;
  - `LC_SYMTAB` (0x2): `nlist_64` entries of 16 bytes, where `(n_type & 0x0E) == 0x0E` (N_SECT) and `n_type & 0xE0 == 0`;
  - best-effort stub names: `__stubs` via `LC_DYSYMTAB`'s indirect symbol table and the section `reserved1`/`reserved2` fields, as `imports[stubAddr] = "<sym>@stub"`, wrapped in `runCatching`.
  - kind comes from `filetype`: 1 "object file", 2 "executable", 6 "dynamic library", 8 "bundle".
  All file offsets are relative to `sliceOffset`. Then call `validate`. Makes T008 pass.
- [X] T014 [P] [US1] Implement `src/main/kotlin/x86sim/disasm/PeReader.kt`: `object PeReader { fun read(r: ByteReader): BinaryImage }`.
  - `e_lfanew` at 0x3C, the COFF header after `PE\0\0`, and the optional header (PE32+: ImageBase at +24, AddressOfEntryPoint at +16, NumberOfRvaAndSizes at +108, data directories from +112).
  - Section table after the optional header, 40 bytes each, NUL-trimmed 8-byte name. Code if `Characteristics & 0x20` (CNT_CODE) or `0x20000000` (MEM_EXECUTE); data if `0x40` (CNT_INITIALIZED_DATA) and not code.
  - Bytes are `min(SizeOfRawData, VirtualSize)` from `PointerToRawData`.
  - Export directory (dir 0): names → `symbols[imageBase + rva]`.
  - Import directory (dir 1): walk the descriptors, and for each IAT slot at `imageBase + FirstThunk + 8*i` set `imports[...] = "__imp_<dll>!<name>"` (ordinal imports as `#<n>`), wrapped in `runCatching`.
  - An RVA→file-offset helper goes through the section table.
  - Kind: "DLL" if characteristics `& 0x2000`, else "executable".
  Then call `validate`. Makes T009 pass.
- [X] T015 [US1] Fill in the supported-format dispatch in `detect` in `src/main/kotlin/x86sim/disasm/Detection.kt` (depends on T012–T014). It recognises:
  - `7F 45 4C 46` + class 2 + data 1 + `e_machine` 0x3E → `ElfReader`;
  - `CF FA ED FE` + cputype 0x01000007 → `MachOReader`;
  - `CA FE BA BE` → a fat header (big-endian `nfat_arch` ≤ 32 and every slice inside the file). If it has an x86-64 slice, `MachOReader.read(r, slice.offset, note)`, with the note listing the other slices' architecture names (arm64, arm, x86, ppc);
  - `MZ` + a valid `PE\0\0` at `e_lfanew` + machine 0x8664 + optional magic 0x20B → `PeReader`.
  - Wrap every reader call so that `DamagedException(msg)` becomes `Rejected(DAMAGED, "$fileName looks like ${formatName} file but is damaged or incomplete: $msg.")`.
  - Anything else keeps the placeholder `NOT_EXECUTABLE` result (refined in US2).
- [X] T016 [US1] Implement `src/main/kotlin/x86sim/disasm/Listing.kt`:
  - `data class Listing(text, summary, instructionCount, invalidByteCount, truncated)`;
  - `const val MAX_LINES = 20_000` and `const val MAX_DATA_BYTES = 4096`;
  - `class DisassemblyCancelled : Exception()`;
  - an internal `ListingBuilder` that appends lines, enforces `MAX_LINES` (writing the exact cut-off line from contracts/listing-format.md rule 7 as the last line and then ignoring further appends), and provides `instr(text, address, bytes)` (column-48 comment), `label(name)` (blank line first unless the previous line is a `section` line) and `dataRow(bytes, address)`.
- [X] T017 [US1] Implement `src/main/kotlin/x86sim/disasm/Disassembler.kt` (depends on T015, T016): `object Disassembler { fun listing(bytes: ByteArray, fileName: String, isCancelled: () -> Boolean): Listing }`. It calls `detect`; on `Rejected` it throws `IllegalArgumentException(message)`, and callers must use `detect` first. Also add `fun listing(image: BinaryImage, fileName: String, isCancelled): Listing`, which does the work:
  - **Pass 1**: for each code section, `Decoder(64, bytes, address)` decodes to the end, checking `isCancelled()` every 1024 instructions. On `Code.INVALID`, record one invalid byte and `decoder.setIP(ip+1)`; a new `Decoder` from `position+1` is acceptable too, verified by T010. Collect instruction starts and near branch/call targets (`instr.getFlowControl()` in `{BRANCH, CONDITIONAL_BRANCH, CALL}` and `instr.getOp0Kind()` is a `NEAR_BRANCH*` → `instr.getNearBranchTarget()`).
  - **Label map**: symbols (sanitised: `[^A-Za-z0-9_.$@?#~]` → `_`, leading digit → `_` prefix, duplicate → `_2`, `_3`…), then imports, then `_start` for the entry if not already named, then `loc_%x` for the remaining targets that are instruction starts.
  - **Pass 2**: `NasmFormatter(resolver)` with options: `setUppercaseMnemonics(false)`, `setUppercaseRegisters(false)`, `setSpaceAfterOperandSeparator(true)`, `setHexPrefix("0x")`, `setHexSuffix("")`, `setFirstOperandCharIndex(8)` or manual padding to column 8. The resolver returns a `SymbolResult` for addresses in the label map. Emit the header, `section`, labels, instructions and invalid bytes per the contract.
  - Then the data sections as rows (a new row at each labelled address), capped at `MAX_DATA_BYTES`.
  Makes T010 pass. If an option name differs in 1.21.0, check it with `javap -cp ~/.gradle/caches/**/iced-x86-1.21.0.jar com.github.icedland.iced.x86.fmt.FormatterOptions`.
- [X] T018 [US1] Build the committed fixtures in `src/test/resources/binaries/`, using Docker (`docker run --rm --platform linux/amd64 -v "$PWD":/w -w /w debian:stable-slim sh -c 'apt-get update && apt-get install -y nasm binutils mingw-w64 && …'`):
  - `hello-elf`: `nasm -f elf64` + `ld -s` of `src/main/resources/examples/01_hello.asm`;
  - `hello-pe.exe`: `x86_64-w64-mingw32-gcc -O1 -s -nostdlib -e main` of a 5-line C file calling `ExitProcess`;
  - `hello-macho`: built on this Mac with `clang -arch x86_64 -O1 -o hello-macho hello.c`, then `strip -x`.
  Keep each under 16 KB. Record the exact commands and the source files in `src/test/resources/binaries/README.md`. If Docker isn't available, ask the user before skipping and fall back to a `TestBinaries.elf64`-generated file written by a one-off test main, noting this in the README.
- [X] T019 [US1] Write `src/test/kotlin/x86sim/disasm/ListingGoldenTest.kt`. For each fixture, compare `Disassembler.listing(bytes, name) { false }.text` with `src/test/resources/binaries/<name>.expected.asm`. Generate each `.expected.asm` once from the implementation, then **review it by hand** against `objdump -d --x86-asm-syntax=intel <fixture>` (the system objdump on macOS is LLVM's). Every mnemonic and operand must match, differing only in label names and number style (SC-002). Commit the expected files.
- [X] T020 [US1] Add `Document.Disassembly(val binary: File, val summary: String)` to the sealed interface in `src/main/kotlin/x86sim/ui/DocumentLabel.kt`, and extend `titleName`, `labelText` and `tooltipText` exactly as asserted in T011. Makes T011 pass.
- [X] T021 [US1] In `src/main/kotlin/x86sim/ui/MainWindow.kt`:
  - Add `add(item("Disassemble Binary…", KeyEvent.VK_O, InputEvent.SHIFT_DOWN_MASK) { disassemble() })` directly after the `Open…` item.
  - Implement `private fun disassemble()`, following contracts/ui-disassemble.md:
    - native `FileDialog(this, "Disassemble a compiled program", FileDialog.LOAD)`; cancel → return;
    - a `SwingWorker<Any, Unit>` whose `doInBackground` runs `readAndDetect(file)` and, on `Supported`, `Disassembler.listing(image, file.name) { isCancelled }`;
    - a `javax.swing.Timer(400)` that shows a modal `JDialog` "Disassembling <name>…" with a Cancel button calling `worker.cancel(true)`;
    - `done()` closes the dialog and calls `showDisassembly(file, listing)` on success.
  - `showDisassembly` sets `document = Document.Disassembly(file, listing.summary)`, calls `setSource(listing.text)`, sets `unsaved = true`, calls `updateTitle()`, moves the caret to 0, and sends
    `message("Disassembled ${file.name} (${listing.summary}): ${listing.instructionCount} instructions — read-only listing; it may not assemble or run here." + if (listing.truncated) " Listing cut off at 20000 lines." else "", Theme.ok)`.
  - Check that `setSource` doesn't reset `unsaved` to false after the assignment. Reorder if it does.
- [X] T022 [US1] Manually verify in the GUI (`./gradlew run`) that disassembling `hello-elf`, `hello-macho` and `hello-pe.exe` shows each listing and the label `*<name> (disassembly)`. Hover `mov` and `rax` and confirm the hover help appears (US1 acceptance 3). Confirm the window stays responsive with the progress dialog on a multi-MB binary (for example `/usr/bin/python3`'s x86-64 slice on macOS, or any large ELF) and that Cancel leaves the editor unchanged.
  - *Done 2026-09-28 via UiSnapshot (`DISASM:`/`REJECT:` modes added): listings for `hello-macho` (dark) and `hello-elf.o` (light), label `*<name> (disassembly)`, status message, and the rejection dialog at 250% zoom all render correctly. Still to check by hand, since it needs a real click-through: the menu flow, and the progress dialog + Cancel on a multi-MB binary.*

**Checkpoint**: US1 works end to end for all three formats.

---

## Phase 4: User Story 2 - Clear rejection of files that aren't x86-64 binaries (Priority: P1)

**Goal**: Every unsupported file is rejected with the right `RejectReason` and message, and the
editor is untouched (FR-004, SC-003).

**Independent Test**: Pick `01_hello.asm`, a PNG, an ARM64 binary, a truncated `hello-elf` and an
unreadable file. Each time a dialog explains the problem and the editor is unchanged.

### Tests for User Story 2 (write first, must fail)

- [X] T023 [P] [US2] Write `src/test/kotlin/x86sim/disasm/BinaryDetectTest.kt`, asserting `reason` and exact `message` for each case (the message wording comes from data-model.md):
  - plain text → `NOT_EXECUTABLE`, message containing "looks like a text file";
  - PNG magic `89 50 4E 47` → `NOT_EXECUTABLE`, "a PNG image";
  - also JPEG `FF D8 FF`, GIF `GIF8`, PDF `%PDF`, ZIP `PK\3\4`, and an empty file ("is empty");
  - `elf64(machine = 0xB7)` → `WRONG_ARCHITECTURE`, "ELF program for ARM64";
  - machine 0x28 → "ARM", 0xF3 → "RISC-V", and unknown 0x1234 → "an unknown CPU (machine 0x1234)";
  - `elf32(3)` → `THIRTY_TWO_BIT`, "32-bit x86 ELF program";
  - big-endian ELF (`EI_DATA`=2) → `WRONG_ARCHITECTURE`, "big-endian";
  - `machO64(cputype = 0x0100000C)` → `WRONG_ARCHITECTURE`, "Mach-O program for ARM64 (Apple Silicon)";
  - `machO32()` → `THIRTY_TWO_BIT`;
  - `fat(arm64 only)` → `WRONG_ARCHITECTURE`, "universal binary containing only arm64";
  - a `CA FE BA BE` Java class file (bytes 4–7 = version 0x00000034) → `NOT_EXECUTABLE`, "Java class file";
  - `pe64(machine = 0xAA64)` → `WRONG_ARCHITECTURE`, "Windows program for ARM64";
  - `pe64(machine = 0x14C, optMagic = 0x10B)` → `THIRTY_TWO_BIT`;
  - an MZ file without a PE header → `NOT_EXECUTABLE`, "an old DOS program";
  - `truncate(elf64(HELLO_CODE), 100)` → `DAMAGED`, message starting "hello looks like an ELF64 file but is damaged or incomplete";
  - `patch32` of the `.text` offset beyond the end → `DAMAGED`;
  - entry outside code → `DAMAGED`, "entry point 0x… is outside its code";
  - `readAndDetect(File("does/not/exist"))` → `UNREADABLE`, "file not found";
  - a directory → `UNREADABLE`;
  - a file made unreadable with `setReadable(false)` → `UNREADABLE`, "permission denied" (skip with `assumeTrue` when running as root).
  - Also check that no case throws.

### Implementation for User Story 2

- [X] T024 [US2] Complete the rejection branches of `detect` in `src/main/kotlin/x86sim/disasm/Detection.kt`, per research R4:
  - an ELF machine-name table (3 x86, 0x3E x86-64, 0xB7 ARM64, 0x28 ARM, 0xF3 RISC-V, 0x08 MIPS, 0x14 PowerPC, 0x15 PowerPC64, 0x16 IBM S/390, 0x2B SPARC V9);
  - Mach-O cputype names (7 x86, 0x01000007 x86-64, 12 ARM, 0x0100000C ARM64 (Apple Silicon), 18 PowerPC);
  - PE machine names (0x14C x86, 0x8664 x86-64, 0x1C4 ARM, 0xAA64 ARM64);
  - fat-vs-Java-class disambiguation: `nfat_arch` > 32, or bytes 4–7 as a big-endian u32 ≥ 45, → Java class file;
  - "text" detection: the first 512 bytes decode as UTF-8 with no NUL and ≥ 95% printable/whitespace;
  - image and archive magics and the empty file.
  Messages follow the data-model table pattern, for example:
  - `"$name is a 32-bit x86 ELF program. Only 64-bit (x86-64) programs can be disassembled."`
  - `"$name is not an executable program — it looks like a text file (assembly or source code?). Use File → Open for .asm files."`
  Makes T023 pass.
- [X] T025 [US2] In `src/main/kotlin/x86sim/ui/MainWindow.kt` `disassemble()`, handle `Detection.Rejected` from the worker by showing `JOptionPane.showMessageDialog(this, rejected.message, "Can't disassemble this file", JOptionPane.ERROR_MESSAGE)`. Any unexpected exception in `get()` shows the same dialog with `"Something went wrong while disassembling ${file.name}: ${e.message}"`. In both cases, don't touch `document`, the editor, `unsaved` or the label.
- [X] T026 [US2] Manually verify in the GUI the independent test above, in both the dark and light themes and at 250% zoom. The dialog text must be readable and wrap. If it doesn't wrap, put the message in a `JTextArea` with `lineWrap` and width `Theme.z(420)`.

**Checkpoint**: US1 and US2 work independently. Wrong files never change the editor.

---

## Phase 5: User Story 3 - Don't lose unsaved work (Priority: P2)

**Goal**: Existing unsaved-changes safety before disassembling, and saving a listing never
touches the binary (FR-002, FR-011, FR-012).

**Independent Test**: Edit the program and choose Disassemble Binary…. The prompt appears, and
Cancel changes nothing. Save a disassembly: the dialog proposes `<name>.asm` and the binary is
unchanged.

### Tests for User Story 3 (write first, must fail)

- [X] T027 [P] [US3] Add a headless test to `src/test/kotlin/x86sim/DocumentLabelTest.kt` for a new pure function `defaultSaveName(doc: Document): String`:
  - `Disassembly(File("/x/hello-pe.exe"), …)` → `"hello-pe.asm"`;
  - `Disassembly(File("/x/hello"), …)` → `"hello.asm"`;
  - `Opened(File("/x/a.asm"))` → `"a.asm"`;
  - `New` and `Example` → `"program.asm"`.
  Add a test `saveDirectory(doc)` that returns the binary's parent folder for `Disassembly` and `null` otherwise.

### Implementation for User Story 3

- [X] T028 [US3] Implement `defaultSaveName` and `saveDirectory` in `src/main/kotlin/x86sim/ui/DocumentLabel.kt`. Makes T027 pass.
- [X] T029 [US3] In `src/main/kotlin/x86sim/ui/MainWindow.kt`:
  - `disassemble()` calls `if (!confirmDiscard()) return` **before** opening the file dialog.
  - In `save(askName)`, the save dialog's `file` default uses `defaultSaveName(document)`, and `directory` uses `saveDirectory(document)?.path` when not null.
  - Since `file` is only non-null for `Document.Opened`, Save on a `Disassembly` always opens the dialog. Confirm this and add a one-line comment: `// a disassembly is never saved over its binary`.
- [ ] T030 [US3] Manually verify the independent test above. Also check that the binary's modification time and size are unchanged after Save (`stat -f '%m %z' <binary>` before and after).
  - *Open: needs the real Save dialog. In code, `save()` only writes to the file chosen in the dialog, and `file` is null for a `Document.Disassembly`, so Save always asks.*

**Checkpoint**: US1–US3 work together without data loss.

---

## Phase 6: User Story 4 - Disassemble from the terminal (Priority: P3)

**Goal**: `x86learn disasm <binary>` prints the same listing or rejection (FR-015,
contracts/cli-disasm.md).

**Independent Test**: `build/install/x86learn/bin/x86learn disasm src/test/resources/binaries/hello-elf`
prints exactly `hello-elf.expected.asm` and exits 0. On `01_hello.asm` it prints
`error: …` to stderr and exits 1.

### Tests for User Story 4 (write first, must fail)

- [X] T031 [P] [US4] Write `src/test/kotlin/x86sim/disasm/DisasmCliTest.kt`, calling `runDisasm(path, out: PrintStream, err: PrintStream): Int` with `ByteArrayOutputStream`s. Assert:
  - the fixture → exit 0, and `out` is byte-identical to `Disassembler.listing(...).text` and to the `.expected.asm` (SC-005);
  - a text file → exit 1, `out` empty, and `err == "error: <Detection message>\n"`;
  - a 30k-`nop` ELF from `TestBinaries` → exit 0, and `err` contains `warning: listing cut off at 20000 lines`;
  - a missing file → exit 1, "file not found".

### Implementation for User Story 4

- [X] T032 [US4] In `src/main/kotlin/x86sim/Main.kt`:
  - add `fun runDisasm(path: String, out: PrintStream = System.out, err: PrintStream = System.err): Int`, which uses `readAndDetect(File(path))`, then `Disassembler.listing(image, name) { false }` and prints `listing.text` with `out.print`;
  - extend `main`: `if (args[0] == "disasm") { if (args.size < 2) { print(USAGE); exitProcess(2) }; exitProcess(runDisasm(args[1])) }`, placed before the existing `run` check;
  - add the USAGE line `  ${AppInfo.COMMAND} disasm <binary>             disassemble an x86-64 ELF, Mach-O or PE program`, aligned with the existing lines.
  Makes T031 pass.
- [X] T033 [US4] Check `src/test/kotlin/x86sim/RenameTest.kt` (or wherever USAGE text is asserted) and update any expected usage text so the suite stays green. Then run the quickstart §2 commands after `./gradlew installDist` and confirm the exit codes with `echo $?`.

**Checkpoint**: All four stories work. GUI and CLI output are identical.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T034 [P] Update `README.md`:
  - Features: a "Disassembler" bullet covering File → Disassemble Binary… (⇧⌘O), the supported formats (ELF64, Mach-O 64-bit including universal, PE32+), labels and comments, the read-only note, and the rejection reasons.
  - Run section: `x86learn disasm <binary>`.
  - "Design notes and limitations": listings are read-only and may contain instructions the simulator doesn't run (SSE, libc calls); linear sweep decoding, so data inside code can show up as odd instructions; the 20,000-line and 4 KiB-per-data-section caps.
  - New "Third-party software" section: iced-x86 (MIT, https://github.com/icedland/iced) and FlatLaf.
  (FR-017, FR-018)
- [X] T035 [P] Add `THIRD_PARTY_NOTICES.md` at the repo root with the iced-x86 MIT license text, copied from the jar's `META-INF` if present, or else from the upstream `LICENSE.txt`.
- [X] T036 Performance check (SC-004, research R5):
  - Add a test in `DisassemblerTest.kt` that disassembles a synthetic 1 MB code section (repeating `HELLO_CODE`) and asserts it finishes in under 5 s.
  - Separately, time `setSource` with a 20,000-line listing in a quick UiSnapshot run (see the memory note on running UiSnapshot). If it takes more than 1 s, lower `MAX_LINES` and update the contract, README and tests together.
  - *Done 2026-09-28: 20,000 lines took 1.35 s in `AsmEditor.setSource` (warm), so `MAX_LINES` is now 10,000 (0.8 s + 0.1 s to assemble). The spec documents, tests and README are updated, and the numbers are recorded in research R5.*
- [X] T037 Render UiSnapshots of the editor with a saved listing (`FILE:/abs/path/hello-elf.asm`) in dark and light at zoom 0 and 4, and check readability (constitution workflow §4).
- [ ] T038 Run the full [quickstart.md](./quickstart.md) validation (§1–§3), and mark each GUI step done here with notes on any deviation.
  - *2026-09-28: §1 (145 tests, 0 failures) and §2 (CLI output identical to the expected listing, exit codes 0/1/2) pass. The CLI also handled `/bin/ls`, `/usr/bin/python3` and `/usr/lib/dyld`, and the first 400 instructions of `/bin/ls` match `objdump` exactly. From §3, steps 2–6 and 8 were checked with UiSnapshot renders. Steps 1, 7 and 9 need a person at the keyboard (the unsaved-changes prompt, the Save dialog, Cancel on the progress dialog) and are still open.*
- [X] T039 Final `./gradlew test` and `./gradlew installDist`. Confirm `git status` shows only the intended files (fixtures ≤ 16 KB each).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: needs T001. T003, T004 and T005 run in parallel; T006 needs T003.
- **US1 (Phase 3)**: needs Phase 2. It's the MVP.
- **US2 (Phase 4)**:
  - T023 and T024 need only Phase 2 plus T015 (the dispatch skeleton they extend).
  - T025 needs T021 (the `disassemble()` worker).
- **US3 (Phase 5)**: T027 and T028 are independent. T029 needs T021.
- **US4 (Phase 6)**: needs T017 (Disassembler) and T018/T019 (fixtures). It doesn't need any UI task.
- **Polish (Phase 7)**: after the stories you intend to ship.

### Within US1

T007–T011 (tests, in parallel) → T012, T013, T014 (readers, in parallel) → T015 → T016 → T017 →
T018 → T019 → T020 → T021 → T022

### Parallel Opportunities

- Phase 2: T003, T004 and T005 together.
- US1 tests: T007, T008, T009, T010 and T011 together.
- US1 readers: T012, T013 and T014 together (different files).
- After T017: US4 (T031, T032) can proceed alongside US1's UI work (T020, T021) and US2's T023/T024.
- Polish: T034 and T035 together.

### Parallel Example: User Story 1

```text
# Tests first, all at once:
T007 ElfReaderTest.kt   T008 MachOReaderTest.kt   T009 PeReaderTest.kt   T010 DisassemblerTest.kt   T011 DocumentLabelTest.kt
# Then the three readers at once:
T012 ElfReader.kt       T013 MachOReader.kt       T014 PeReader.kt
```

---

## Implementation Strategy

### MVP (User Story 1 only)

Phases 1–3 give a working menu item that disassembles all three formats. At that point, wrong
files only get the generic "not an executable program" message, but the editor is still never
changed.

### Incremental Delivery

1. Phases 1–3 → MVP demo (US1).
2. Phase 4 → precise rejection messages (US2). The user specifically asked for this, so ship it
   together with US1.
3. Phase 5 → save safety and prompt (US3).
4. Phase 6 → CLI (US4).
5. Phase 7 → docs, notices, performance, snapshots. The constitution requires this before merging
   to `main`.
