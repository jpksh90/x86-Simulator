; String instructions: copy, fill, search and compare memory with rep movs/stos/scas/cmps.
; They use rsi (source) and rdi (destination) implicitly and move them on after each element.
; A rep prefix repeats the instruction rcx times. Press Step on a 'rep' line to watch one
; iteration at a time (rcx counts down), or Step Over to finish the whole repeat.

section .data
    msg       db "Hello, strings!"
    msglen    equ $ - msg
    sentence  db "count my chars", 0      ; zero-terminated, like a C string
    word1     db "abcd"
    word2     db "abXd"
    l_copy    db "copy: "
    l_yes     db "zeroed: yes", 10
    l_no      db "zeroed: no", 10
    l_len     db "length: "
    l_diff    db "compare: first difference at index "
    l_same    db "compare: equal", 10

section .bss
    buf       resb 32
    arr       resq 8

section .text
global _start
_start:
    cld                     ; DF = 0: rsi/rdi move forwards

    ; 1. Copy msg into buf (like memcpy)
    lea rsi, [msg]
    lea rdi, [buf]
    mov ecx, msglen
    rep movsb               ; copy rcx bytes from [rsi] to [rdi]
    mov byte [rdi], 10      ; rdi now points just past the copy: add a newline
    lea rsi, [l_copy]
    mov edx, 6
    call print
    lea rsi, [buf]
    mov edx, msglen + 1
    call print

    ; 2. Fill arr with -1, then clear it (like memset)
    lea rdi, [arr]
    mov rax, -1
    mov ecx, 8
    rep stosq               ; store rax into 8 qwords
    lea rdi, [arr]
    xor eax, eax
    mov ecx, 8
    rep stosq               ; ...and now zero them
    lea rdi, [arr]
    mov ecx, 8
    repe scasq              ; compare rax (0) with each qword while they're equal
    lea rsi, [l_yes]
    mov edx, 12
    je .zeroed              ; ZF = 1: every qword was 0
    lea rsi, [l_no]
    mov edx, 11
.zeroed:
    call print

    ; 3. Length of a zero-terminated string (like strlen)
    lea rdi, [sentence]
    xor eax, eax            ; al = 0, the byte to search for
    mov rcx, -1             ; "unlimited" count
    repne scasb             ; scan until [rdi] == al
    not rcx                 ; rcx = -(bytes scanned) - 1, so not rcx = bytes scanned
    dec rcx                 ; don't count the terminating 0
    mov rbx, rcx
    lea rsi, [l_len]
    mov edx, 8
    call print
    mov rdi, rbx
    call print_uint

    ; 4. Compare two strings (like memcmp)
    lea rsi, [word1]
    lea rdi, [word2]
    mov ecx, 4
    repe cmpsb              ; compare while the bytes are equal
    je .same
    lea rbx, [word1 + 1]
    sub rsi, rbx            ; rsi stopped one past the differing byte
    mov rbx, rsi
    lea rsi, [l_diff]
    mov edx, 35
    call print
    mov rdi, rbx
    call print_uint
    jmp .done
.same:
    lea rsi, [l_same]
    mov edx, 15
    call print
.done:
    mov rax, 60             ; exit(0)
    xor edi, edi
    syscall

; print: writes rdx bytes starting at rsi to stdout.
print:
    mov eax, 1              ; sys_write
    mov edi, 1              ; stdout
    syscall
    ret

; ------------------------------------------------------------
; print_uint: prints the unsigned number in rdi, then a newline.
; ------------------------------------------------------------
print_uint:
    push rbp
    mov rbp, rsp
    sub rsp, 32
    mov rax, rdi
    lea rsi, [rbp-1]
    mov byte [rsi], 10
    mov rcx, 10
.digit:
    xor edx, edx
    div rcx
    add dl, '0'
    dec rsi
    mov [rsi], dl
    test rax, rax
    jnz .digit
    mov rax, 1
    mov rdi, 1
    mov rdx, rbp
    sub rdx, rsi
    syscall
    leave
    ret
