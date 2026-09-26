; Bubble sort an array of signed qwords in .data, then print it.
; The Memory tab highlights each swap as it happens.

section .data
    array dq 64, 25, 12, 22, 11, 90, 3, 47
    count equ ($ - array) / 8

section .text
global _start
_start:
    mov rcx, count - 1      ; number of comparisons in this pass
.outer:
    xor esi, esi            ; i = 0
    xor r8d, r8d            ; swapped = false
.inner:
    mov rax, [array + rsi*8]
    mov rdx, [array + rsi*8 + 8]
    cmp rax, rdx
    jle .no_swap            ; signed comparison: already in order
    mov [array + rsi*8], rdx
    mov [array + rsi*8 + 8], rax
    mov r8d, 1              ; swapped = true
.no_swap:
    inc rsi
    cmp rsi, rcx
    jl .inner
    test r8, r8
    jz .sorted              ; no swaps: done early
    dec rcx
    jnz .outer
.sorted:
    xor ebx, ebx
.print:
    mov rdi, [array + rbx*8]
    call print_uint
    inc rbx
    cmp rbx, count
    jl .print

    mov rax, 60
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
