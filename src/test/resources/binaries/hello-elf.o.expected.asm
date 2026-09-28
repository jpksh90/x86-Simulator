; Disassembly of hello-elf.o (ELF64 x86-64 object file (sections placed one after another from address 0))
; Entry point: none
; Code: .text 0x0-0x29 (41 bytes)
; Data: .data 0x30-0x3e (14 bytes)
; This is a read-only listing made from a compiled program. It may not assemble or run in x86Learn.
; Decoded with iced-x86 (MIT license).

section .text

_start:
        mov     rax, 1                          ; 0: 48 c7 c0 01 00 00 00
        mov     rdi, 1                          ; 7: 48 c7 c7 01 00 00 00
        lea     rsi, [rel 0x15]                 ; e: 48 8d 35 00 00 00 00
        mov     rdx, 0xe                        ; 15: 48 c7 c2 0e 00 00 00
        syscall                                 ; 1c: 0f 05
        mov     rax, 0x3c                       ; 1e: 48 c7 c0 3c 00 00 00
        xor     edi, edi                        ; 25: 31 ff
        syscall                                 ; 27: 0f 05

section .data

msg:
        db      0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x2c, 0x20, 0x77, 0x6f, 0x72, 0x6c, 0x64, 0x21, 0x0a ; 30
