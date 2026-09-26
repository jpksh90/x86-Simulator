; A tour of the status flags. Step through one instruction at a time
; and watch CF, ZF, SF and OF in the Registers panel.

section .text
global _start
_start:
    mov al, 200
    add al, 100         ; 300 doesn't fit in 8 bits -> al = 44, CF=1 (unsigned overflow)

    mov al, 100
    add al, 50          ; 150 > 127 -> OF=1 (signed overflow), SF=1 (looks negative)

    mov eax, 5
    cmp eax, 5          ; 5 - 5 = 0 -> ZF=1 ("equal")
    cmp eax, 7          ; 5 - 7 borrows -> CF=1 ("below"), SF=1, OF=0 -> "less"
    setl bl             ; bl = 1 because 5 < 7 (signed)
    seta cl             ; cl = 0 because 5 is not above 7 (unsigned)

    mov rax, -1
    inc rax             ; wraps to 0 -> ZF=1 (inc never changes CF)

    mov rdx, 0x8000000000000000
    neg rdx             ; negating the most negative number overflows -> OF=1

    mov eax, 0b1011
    test eax, 1         ; test = AND without storing: lowest bit set -> ZF=0
    shr eax, 1          ; shifted-out bit (1) goes into CF

    mov rax, 60
    xor edi, edi
    syscall
