# Contract: Assembly Syntax for String Instructions

**Consumers**: learners writing `.asm` programs; `Assembler.assemble()`; the headless CLI (`x86sim
run`), which prints the same errors as `error: line N: …`.

## Grammar (additions to the existing NASM subset)

```text
statement      := [label ":"] [prefix] string-mnem            ; no operands
prefix         := "rep" | "repe" | "repz" | "repne" | "repnz"  ; case-insensitive
string-mnem    := family size
family         := "movs" | "stos" | "lods" | "scas" | "cmps"
size           := "b" | "w" | "d" | "q"                        ; 1, 2, 4, 8 bytes
```

- The prefix and mnemonic MUST be on the same line, separated by whitespace.
- A comment may follow: `rep movsb ; copy`.
- Every combination of prefix and family is accepted. For the meaning of each, see
  [execution-semantics.md](./execution-semantics.md).
- Each statement takes one 4-byte instruction slot in `.text`, as any other instruction does.

### Accepted examples

```nasm
    movsb
    rep movsq
    REPNE SCASB
.cmp: repe cmpsb
    repz cmpsw          ; same as repe
    rep scasb           ; accepted; behaves as repe (F3 encoding)
    repne stosb         ; accepted; behaves as rep
    rep lodsb           ; accepted; AL ends with the last byte loaded
```

## Rejected input and required messages

Each error is reported as `line N: <message>`. Messages MUST match in substance and name the
offending word. Final wording may be polished during implementation, but a test asserts that each
key phrase below appears in the message.

| # | Input | Key phrase(s) the message MUST contain |
|---|---|---|
| E1 | `rep` (nothing after it) | `'rep' needs a string instruction after it`, `rep movsb` |
| E2 | `rep add rax, 1` | `only works with string instructions`, `'add'` |
| E3 | `repne jmp foo`, `rep rep movsb` | same as E2, naming the second word |
| E4 | `movs byte [rdi], [rsi]`, `stos`, `lods`, `scas`, `cmps` (no size suffix, with or without operands) | `write the size in the name`, e.g. `'movsb', 'movsw', 'movsd' or 'movsq'` |
| E5 | `stosb al`, `lodsq rax`, `movsb [rdi], [rsi]` (suffixed, with operands) | `takes no operands` |
| E6 | `movsd xmm0, [rsi]`, `cmpsd xmm1, xmm2, 0` | `SSE floating-point`, `isn't supported`, `takes no operands` |
| E7 | `insb`/`insw`/`insd`, `outsb`/`outsw`/`outsd`, with or without a prefix | `port I/O`, `privileged`, `not supported` |
| E8 | `a32 rep movsb`, `a32 movsb` | `address-size override`, `isn't supported`, `rcx, rsi and rdi` |
| E9 | `fs movsb`, `gs rep stosb` | `segment overrides`, `aren't supported` |
| E10 | `rep: nop` or `movsb: nop` (reserved word as label) | `is an instruction name and can't be a label` (existing message) |
| E11 | `section .data` then `rep movsb` | `outside section .text` (existing message) |

## Unchanged behavior

- All currently accepted programs assemble to identical instructions. For every existing
  instruction, `Instruction.prefix` is `NONE`.
- Error aggregation is unchanged: all errors are collected and reported sorted by line.
