# Quickstart: Validate "Disassemble Binary"

## Prerequisites

- JDK 17+. Gradle downloads the iced-x86 dependency on the first build, so the build needs network
  access once; running the app doesn't.
- Fixture binaries are committed under `src/test/resources/binaries/` (`hello-macho`, `hello-elf.o`;
  the PE fixture is built in memory by `TestBinaries.helloPe()`). No NASM, linker or compiler
  is needed.

## 1. Automated checks

```bash
./gradlew test
```

What to expect:
- `BinaryDetectTest`: every rejection reason in FR-004 (text, PNG, ARM64 ELF/Mach-O/PE, 32-bit
  ELF/Mach-O/PE, truncated, bad section offset, entry outside code, unreadable path) returns the
  expected `RejectReason` and a message naming the file (SC-003).
- `ElfReaderTest`, `MachOReaderTest`, `PeReaderTest`: sections, entry point, symbols and imports
  read from synthetic images, plus the universal-binary slice choice.
- `DisassemblerTest`: labels for the entry point, branch targets and symbols; one-byte `db`
  recovery after invalid bytes; data rows; the 10,000-line cap; cancellation.
- `ListingGoldenTest`: the full listing for each committed fixture matches its `.expected.asm`
  byte-for-byte (SC-002).
- `DisasmCliTest`: `runDisasm` output equals `Listing.text`, and the exit codes follow
  [contracts/cli-disasm.md](contracts/cli-disasm.md) (SC-005).
- `DocumentLabelTest`: label and tooltip for `Document.Disassembly`.

## 2. Command line

```bash
./gradlew installDist
build/install/x86learn/bin/x86learn disasm src/test/resources/binaries/hello-elf.o     # listing, exit 0
build/install/x86learn/bin/x86learn disasm src/main/resources/examples/01_hello.asm    # "error: … looks like a text file", exit 1
echo $?
```

## 3. GUI walkthrough

Run `./gradlew run`, then:

1. Edit the default example, then choose File → Disassemble Binary…. The unsaved-changes prompt
   appears. Cancel it and check that nothing changed.
2. Choose Disassemble Binary… again (answer No) and pick `hello-elf.o`.
   - The editor shows the listing and the label reads `*hello-elf.o (disassembly)`.
   - The status message mentions "read-only listing".
3. Hover `mov` and `rax` in the listing. The usual hover help appears.
4. Compare with step 2 of the command-line check. The texts are identical.
5. Pick `hello-macho`, then a real system program such as `/bin/ls` (a universal binary). Each
   shows a listing, with the right format named in the header.
6. Pick `01_hello.asm` and a PNG. You get a clear error dialog, and the editor is unchanged.
7. Save the listing. The dialog proposes `hello-elf.asm`, next to the binary. After saving, the label shows
   `hello-elf.asm` and the binary's modification time is unchanged.
8. Toggle the dark/light theme and zoom to 250%. The dialogs and label stay readable. For
   screenshots, use UiSnapshot with `FILE:` pointing at a saved listing.
9. Pick a large binary (e.g. `/usr/lib/dyld`, 600 KB of code). The progress dialog appears
   and Cancel works. When you let it finish, the listing ends with the cut-off line.
