; Hello, world! - the classic first program.
; Linux 'write' system call: rax=1, rdi=file descriptor, rsi=buffer, rdx=length.

section .data


section .text
global _start
_start:
    mov rdi, 2
    mov rsi, 10
    mov rax, 1          ; result = 1 (handles x^0 = 1)

.loop:
    test rsi, rsi       ; check if exponent (n) == 0
    jz .done            ; if n == 0, jump to finish

    imul rax, rdi       ; result = result * x
    dec rsi             ; n = n - 1
    jmp .loop           ; repeat loop

.done:
    ret                 ; return result in RAX
    