# Data Model: Disassemble an x86-64 Binary

All types live in the headless package `x86sim.disasm` (no Swing imports), except `Document`,
which is in `x86sim.ui`.

## BinaryImage

The parsed view of a supported file. It is produced by a format reader and consumed by
`Disassembler`.

| Field | Type | Notes |
|-------|------|-------|
| `format` | `BinaryFormat` | `ELF64`, `MACHO64`, `PE32PLUS` |
| `kind` | `String` | e.g. "executable", "shared library", "object file" (from `e_type` / `filetype` / PE characteristics) |
| `fatSliceNote` | `String?` | e.g. "x86-64 slice of a universal binary (also contains arm64)" |
| `entry` | `Long?` | virtual address; `null` for object files without one |
| `codeSections` | `List<Section>` | decoded as instructions, in address order |
| `dataSections` | `List<Section>` | listed as `db`, in address order |
| `symbols` | `Map<Long, String>` | raw names from the file (functions and data), before sanitising |
| `imports` | `Map<Long, String>` | PLT stub / Mach-O stub address, or PE IAT slot address → display name |

Validation (a failure → `Detection.Rejected(DAMAGED, …)`):
- Every section's `fileOffset + size` ≤ file length.
- `entry`, if present and nonzero, falls inside a code section.
- There is at least one code section with size > 0.

## Section

| Field | Type | Notes |
|-------|------|-------|
| `name` | `String` | `.text`, `__TEXT,__text`, `.text` (PE, NUL-trimmed) |
| `address` | `Long` | virtual address |
| `bytes` | `ByteArray` | copied from the file, `size` long |

## Detection (result of the check)

```text
sealed Detection
├── Supported(image: BinaryImage)
└── Rejected(reason: RejectReason, message: String)
```

| RejectReason | Example message (learner-facing, FR-004) |
|--------------|-------------------------------------------|
| `NOT_EXECUTABLE` | "hello.asm is not an executable program — it looks like a text file (assembly or source code?). Use File → Open for .asm files." |
| `WRONG_ARCHITECTURE` | "app is a Mach-O program for ARM64 (Apple Silicon). Only x86-64 programs can be disassembled." |
| `THIRTY_TWO_BIT` | "prog is a 32-bit x86 ELF program. Only 64-bit (x86-64) programs can be disassembled." |
| `DAMAGED` | "prog looks like an ELF file but is damaged or incomplete: section .text runs past the end of the file." |
| `UNREADABLE` | "Can't read /path/prog: permission denied." / "…: file not found." |

Messages always name the file and the detected format when there is one. They are used unchanged by
the GUI dialog and the CLI (stderr).

## Listing

| Field | Type | Notes |
|-------|------|-------|
| `text` | `String` | full editor/CLI text, see [contracts/listing-format.md](contracts/listing-format.md) |
| `summary` | `String` | e.g. "ELF64 x86-64 executable", used in the status bar and tooltip |
| `instructionCount` | `Int` | for the status message |
| `invalidByteCount` | `Int` | number of `db` lines emitted for undecodable bytes |
| `truncated` | `Boolean` | true when the 10,000-line cap was reached (FR-014) |

## Document (UI, extended)

The new variant is added to the existing sealed interface in `ui/DocumentLabel.kt`:

| Variant | `titleName` | `labelText` | `tooltipText` |
|---------|-------------|-------------|---------------|
| `Disassembly(binary: File, summary: String)` | `<name> (disassembly)` | `*<name> (disassembly)` until saved | `<absolute path> — <summary>` |

State transitions:
- `any` → Disassemble Binary… → (unsaved prompt) → file chosen → worker succeeds → `Disassembly`,
  `unsaved = true`.
- Worker rejected, failed or cancelled, or picker cancelled → state unchanged.
- `Disassembly` → Save / Save As (always asks, default `<name>.asm`) → `Opened(asmFile)`,
  `unsaved = false`.
