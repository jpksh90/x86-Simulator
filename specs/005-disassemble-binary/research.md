# Research: Disassemble an x86-64 Binary into the Editor

All Technical Context unknowns are resolved below. Checked against Maven Central and a local smoke
test on 2026-09-28.

## R1. Disassembler library

- **Decision**: `io.github.icedland.iced:iced-x86:1.21.0` (the Java port of iced), used for
  decoding and its `NasmFormatter` for text.
- **Rationale**:
  - Pure Java, so no native code (FR-016's preferred path; the constitution's "no native binaries"
    rule stays intact and needs no amendment). The jar is Java 8 bytecode (class version 52), so it
    runs on the JDK 17+ the app targets.
  - MIT license. No runtime dependencies; the POM lists only JUnit, test-scoped. Jar size ≈ 1.3 MB.
  - It has a NASM-syntax formatter built in, which is what FR-005 asks for, plus a `SymbolResolver`
    hook for replacing branch targets and RIP-relative addresses with labels (FR-007).
  - It decodes the full x86-64 instruction set (SSE/AVX/AVX-512 and the rest). That matters for
    FR-010 because the listing must be faithful even for instructions the simulator can't run.
  - Smoke test: `48 89 e5 e8 00 00 00 00 0f 0b` at 0x401000 decoded to `mov rbp,rsp`,
    `call 0000000000401008h`, `ud2`. Undecodable bytes come back as `Code.INVALID` rather than
    throwing.
- **Alternatives considered**:
  - *Capstone (Java/JNA bindings)*: mature, but it needs a native `libcapstone` for each OS and CPU
    (macOS arm64/x64, Linux, Windows). That breaks the "no native binaries" rule, and FR-016 allows
    native code only if no pure-JVM option works. Rejected.
  - *Zydis (via JNI)*: same native problem, and there are no maintained Java bindings. Rejected.
  - *Ghidra's decoder as a library*: very large (hundreds of MB), awkward to use as a library, and
    it doesn't output NASM. Rejected.
  - *Writing our own decoder*: ruled out by the spec (FR-018).
- **Note**: iced-x86 1.21.0 (Jan 2024) is the latest Java release. The upstream project is stable
  and still used heavily through its Rust and .NET versions. Tests pin the output format (see R7),
  so a future upgrade would show up as a test diff.

## R2. Handling undecodable bytes (FR-008)

- **Decision**: When `decode()` returns `Code.INVALID`, emit **one** byte as `db 0xNN` with the
  comment `not a valid instruction`, then continue decoding at the next byte (`decoder.setIP` and
  position +1).
- **Rationale**: For an invalid instruction iced reports a length of up to 15 bytes (the smoke test
  gave `c4 ff 0f 05` as one 4-byte "(bad)"). Skipping all of those can hide a real instruction
  (here the `0f 05` = `syscall`). Advancing one byte at a time lets decoding get back in step, the
  way objdump does.
- **Alternatives considered**: using iced's reported length (hides instructions); stopping at the
  first bad byte (violates FR-008).

## R3. Labels and symbols (FR-007)

- **Decision**: Two passes over each code section.
  1. Decode everything. Collect the entry point, file symbols (functions and other code-address
     symbols), and every near branch or call target (`Instruction.getNearBranchTarget()` when the
     flow control is a branch or call) that falls inside a decoded code section **and** on an
     instruction start.
  2. Format using a `SymbolResolver` that returns the label for any address in the label map. This
     covers branch targets and RIP-relative memory operands that point at a named symbol.
- Label names:
  - File symbol name, cleaned up to a valid NASM identifier: characters outside
    `[A-Za-z0-9_.$@?#~]` become `_`, and a leading digit gets a `_` prefix. Duplicates get `_2`,
    `_3`, and so on.
  - Otherwise the entry point is `_start`, unless a symbol already names it.
  - Otherwise `loc_<hex address>`.
  - A target that falls inside an instruction (overlapping code) gets no label, and the operand
    stays a raw address.
- Library call names (edge case "dynamically linked binaries"), done as best effort:
  - ELF: `.rela.plt` / `.plt` / `.plt.sec` entries named `<sym>@plt`.
  - Mach-O: `__stubs` entries named from the indirect symbol table.
  - PE: import address table slots named `__imp_<dll>!<func>`, so `call [rel __imp_...]` is shown.
  - If a format's tables are missing or odd, no names are added and the operand stays an address.
    It never fails the disassembly.

## R4. File-format reading (FR-003, FR-004, FR-019): written in this project

Each detector reads only fixed little-endian headers through a bounds-checked reader. Any read past
the end of the file becomes **Damaged** instead of an exception leaking out.

| Format | Magic | x86-64 accepted when | Rejections recognised |
|--------|-------|----------------------|------------------------|
| ELF | `7F 45 4C 46` | `EI_CLASS`=2 (64-bit), `EI_DATA`=1 (LE), `e_machine`=0x3E | class 1 + machine 3 → "32-bit x86"; machine 0xB7 AArch64, 0x28 ARM, 0xF3 RISC-V, 0x08 MIPS, 0x14/0x15 PowerPC, other → "architecture N"; big-endian → named |
| Mach-O | `CF FA ED FE` (64-bit LE) | `cputype`=0x01000007 | `CE FA ED FE` + cputype 7 → "32-bit x86"; cputype 0x0100000C → ARM64; 12 → ARM |
| Mach-O universal | `CA FE BA BE` (BE) | contains an x86-64 slice → use that slice | no x86-64 slice → lists the architectures found; `nfat_arch` > 32 or slices out of range → treated as Java class file (same magic) → "not an executable" when the bytes after the magic look like a class-file version |
| PE | `MZ` + `PE\0\0` at `e_lfanew` | `Machine`=0x8664 and optional header magic 0x20B | 0x14C → "32-bit x86"; 0xAA64 → ARM64; 0x1C4 → ARM; `MZ` without a valid PE header → "DOS program, not supported" |
| anything else | – | – | "not an executable". Mentions "looks like text (assembly/source?)" when the first 512 bytes are printable UTF-8, and names common image/archive magics (PNG, JPEG, GIF, PDF, ZIP) |

- Code to decode:
  - ELF: sections with `SHF_EXECINSTR`. If there are no section headers, fall back to `PT_LOAD`
    segments with `PF_X`.
  - Mach-O: sections with the `S_ATTR_PURE_INSTRUCTIONS` or `S_ATTR_SOME_INSTRUCTIONS` flag.
  - PE: sections with `IMAGE_SCN_CNT_CODE` or `IMAGE_SCN_MEM_EXECUTE`.
  - Sections with no file data (`SHT_NOBITS`, zero-fill) are skipped.
- Entry point:
  - ELF: `e_entry`. `ET_REL` object files have no entry.
  - Mach-O: `LC_MAIN.entryoff` + `__TEXT` vmaddr, or `LC_UNIXTHREAD` rip.
  - PE: `ImageBase + AddressOfEntryPoint`.
  - A nonzero entry point outside every code section → **Damaged** (spec edge case).
- Symbols:
  - ELF: `.symtab`, else `.dynsym`, using `STT_FUNC` and `STT_NOTYPE`/`STT_OBJECT` entries whose
    value is in a mapped section.
  - Mach-O: `LC_SYMTAB` `nlist_64` entries of type `N_SECT`, excluding debug stabs.
  - PE: export directory names. COFF symbols are usually stripped from PE files and are ignored.
- Data sections (spec assumption): ELF allocated, non-exec `PROGBITS`; Mach-O non-code sections in
  `__DATA`/`__DATA_CONST`/`__TEXT` (e.g. `__cstring`, `__const`); PE initialised-data sections.
- **Rationale**: This is what the user chose in clarification (library for decoding only). All
  three formats are documented, fixed-layout, and only need header and section-table reading here
  (no relocation processing), so each reader is small and easy to unit-test.

## R5. Size cap and performance (FR-013, FR-014, SC-004)

- **Decision**: Cap the listing at **10,000 lines** in total. Data sections get at most **4 KiB**
  each (256 lines of 16 bytes), then the comment `; … N more bytes not shown`. When the overall cap
  is hit: `; --- listing cut off here: limit of 10000 lines reached (address 0x…) ---`. Both caps
  are constants in `disasm/Listing.kt` and are documented in the README.
- **Rationale**: Decoding is fast. `/usr/lib/dyld` (600 KB of code) is read, decoded and formatted
  in about 1 s from the CLI, and 1 MB of synthetic code in well under the 5 s of SC-004. The limit
  is the editor: loading a listing re-highlights the whole text on the UI thread.
- **Measured 2026-09-28** (T036, `/usr/lib/dyld` listing, warm JVM):

  | Lines | `AsmEditor.setSource` | Assemble |
  |-------|-----------------------|----------|
  | 20,000 | 1.35 s | 0.15–0.2 s |
  | 10,000 | 0.8 s | 0.1 s |

  The first load after starting the app takes roughly twice as long, while the JVM warms up. The
  cap started at 20,000 and was lowered to 10,000 to stay within the 1 s budget.
- **Alternatives considered**:
  - No cap: the editor stalls on a multi-MB statically-linked binary.
  - Lazy/virtual editor, or faster highlighting in `AsmEditor`: this would help every large file,
    not just disassemblies, and should be its own change.

## R6. Responsiveness and cancel (FR-013)

- **Decision**: Run detection and disassembly in a `SwingWorker`. If the work isn't finished after
  400 ms, show a small modal "Disassembling <name>…" dialog with **Cancel**. Cancel sets a flag the
  disassembler checks between instructions. The core API takes a `() -> Boolean` `isCancelled`
  callback (no Swing types) and throws `DisassemblyCancelled`. The editor is only replaced on the
  EDT after success.
- **Rationale**: Keeps the core headless (Principle IV). The editor stays untouched on cancel,
  failure or rejection (FR-004, SC-006).

## R7. Listing format and GUI/CLI parity (FR-006, FR-009, FR-015)

- **Decision**: One pure function `Disassembler.listing(bytes, fileName, isCancelled): Listing`
  in core. The GUI and the CLI both print `Listing.text`, so they are byte-identical by
  construction (SC-005). The exact text format is fixed in
  [contracts/listing-format.md](contracts/listing-format.md).
- Formatter options:
  - `NasmFormatter` with lower-case mnemonics and registers, a space after operand commas, and
    `0x` hex prefix instead of the `h` suffix, matching how this project's examples are written.
  - Branch targets always go through the resolver.
  - Mnemonics are padded to column 8 and the address/bytes comment starts at column 48.
- Tests compare full listings against golden text for fixtures, so any formatting change (or a
  library upgrade) shows up as a diff.

## R8. What happens after the listing is loaded

- **Decision**: The listing goes through the normal `setSource` path, so it is assembled like any
  program. Lines the simulator can't handle get the usual errors (Principle II). The status bar
  message says: `Disassembled <name> (ELF64 x86-64) — read-only listing; it may not assemble or
  run here`.
  - A new `Document.Disassembly(binary: File, format: String)` variant sets the toolbar label to
    `<name> (disassembly)`, and the tooltip to the full binary path plus the format.
  - The document starts **unsaved**, so the label shows `*<name> (disassembly)`.
  - Save/Save As always ask for a location, defaulting to `<name>.asm` next to the binary. The
    binary is never written, because `save` only writes to `Document.Opened` files (FR-011,
    FR-012).
- **Rationale**: Reuses the existing flow. Showing which lines don't assemble is itself useful for
  learning (it shows where real code goes beyond the simulator's subset). Nothing is loaded into
  `Machine` unless it assembles and the learner presses Run (spec assumption).
- **Alternatives considered**: suppressing assembly for disassemblies. Rejected because it would
  add a special mode to the editor and hide useful feedback.

## R9. Test fixtures

- **Decision**: Two kinds.
  1. **Synthetic builders** in test code (`TestBinaries.kt`) that write minimal valid ELF64,
     Mach-O 64 (thin and universal) and PE32+ images around a given code byte array, plus variants:
     32-bit, ARM64, truncated, bad section offsets, entry outside code, no section headers. These
     cover every rejection reason (SC-003) and every reader branch without binary blobs.
  2. A few **small real binaries** in `src/test/resources/binaries/`, each under 16 KB, used for
     golden-listing tests (SC-002): `hello-elf` (NASM + ld), `hello-macho` (clang, x86-64) and
     `hello-pe.exe` (MinGW, stripped). Each is built from `01_hello`/a tiny C file, with the build
     commands recorded in `src/test/resources/binaries/README.md`. Tests never need the toolchain;
     the binaries are committed.
- **Rationale**: This meets Principle III (concrete asserted values) and the constitution's rule
  that no toolchain is needed at build or test time.
