; Sum 1 + 2 + ... + 100 with a loop, then print the result (5050).
; Try setting a breakpoint on the 'add' line and pressing Run repeatedly.

section .text
global _start
_start:
    xor eax, eax        ; rax = 0 (running sum). Writing eax also clears the top half of rax.
    mov rcx, 100        ; loop counter
.again:
    add rax, rcx        ; sum += rcx
    loop .again         ; rcx = rcx - 1; jump if rcx != 0

    mov rdi, rax
    call print_uint

    mov rax, 60         ; exit(0)
    xor edi, edi
    syscall

; ------------------------------------------------------------
; print_uint: prints the unsigned number in rdi, then a newline.
; Builds the digits backwards in a buffer on the stack.
; ------------------------------------------------------------
print_uint:
    push rbp
    mov rbp, rsp
    sub rsp, 32             ; room for up to 20 digits + newline
    mov rax, rdi
    lea rsi, [rbp-1]        ; last byte of the buffer
    mov byte [rsi], 10      ; newline
    mov rcx, 10
.digit:
    xor edx, edx            ; rdx:rax is the dividend, so clear rdx
    div rcx                 ; rax = rax / 10, rdx = rax % 10
    add dl, '0'             ; remainder -> ASCII digit
    dec rsi
    mov [rsi], dl
    test rax, rax
    jnz .digit
    mov rax, 1              ; sys_write
    mov rdi, 1              ; stdout
    mov rdx, rbp
    sub rdx, rsi            ; length = end of buffer - first digit
    syscall
    leave                   ; mov rsp, rbp / pop rbp
    ret
