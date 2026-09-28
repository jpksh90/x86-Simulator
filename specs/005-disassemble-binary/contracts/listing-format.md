# Contract: Disassembly Listing Text

The GUI editor and `x86learn disasm` produce exactly this text (FR-015). Golden tests pin it.

## Layout

```nasm
; Disassembly of hello (ELF64 x86-64 executable)
; Entry point: 0x401000
; Code: .text 0x401000-0x401026 (38 bytes)
; Data: .data 0x402000-0x40200e (14 bytes)
; This is a read-only listing made from a compiled program. It may not assemble or run in x86Learn.
; Decoded with iced-x86 (MIT license).

section .text

_start:
        mov     eax, 1                          ; 401000: b8 01 00 00 00
        mov     edi, 1                          ; 401005: bf 01 00 00 00
        lea     rsi, [rel msg]                  ; 40100a: 48 8d 35 ef 0f 00 00
        call    loc_401020                      ; 401011: e8 0a 00 00 00
        db      0xc4                            ; 401016: c4  (not a valid instruction)
        ...

loc_401020:
        syscall                                 ; 401020: 0f 05
        ret                                     ; 401022: c3

section .data

msg:
        db      0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x2c, 0x20, 0x77, 0x6f, 0x72, 0x6c, 0x64, 0x21, 0x0a ; 402000
```

## Rules

1. **Header**: comment lines in this order.
   - `Disassembly of <file name> (<summary>)`.
   - `Entry point: 0x<hex>`, or `Entry point: none` when the file has no entry.
   - For a universal binary, the slice note.
   - One `Code:` line per code section and one `Data:` line per data section, as
     `<name> 0x<start>-0x<end> (<n> bytes)`.
   - The read-only warning line (FR-010) and the library credit line.
   - Then one blank line.
2. **Sections**: `section <name>` starts each section. Names that aren't valid NASM section names
   (e.g. `__TEXT,__text`) are written as-is. The listing doesn't promise to assemble.
3. **Labels**: `<label>:` at column 0, with a blank line before it unless the previous line is
   already blank. Each `section` line is followed by one blank line. Naming rules are in research R3.
4. **Instruction lines**:
   - 8 spaces, then the mnemonic padded to 8 characters, then the operands (iced `NasmFormatter`:
     lower-case, `, ` separators, `0x` hex, labels for resolved addresses).
   - The comment starts at column 48, or one space after the text if the text is longer:
     `; <address hex, no 0x>: <bytes, lower-case hex, space-separated>`.
   - Prefixes (`rep`, `lock`, …) are part of the mnemonic text as iced prints them.
5. **Undecodable byte**: `db      0x<nn>` with the comment `; <addr>: <nn>  (not a valid instruction)`.
   It covers one byte, and decoding resumes at the next byte.
6. **Data**: rows of up to 16 bytes as `db` with comma-separated `0x` bytes, and the comment
   `; <address hex>`. A labelled address starts a new row. After 4 KiB of one section:
   `        ; … <n> more bytes not shown`.
7. **Cap**: after 10,000 lines in total, one final line
   `; --- listing cut off here: limit of 10000 lines reached (address 0x<hex>) ---` and nothing else.
8. Line endings are `\n`, with a trailing newline at the end.
