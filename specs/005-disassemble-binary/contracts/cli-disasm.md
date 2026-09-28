# Contract: `disasm` command (headless)

```text
x86learn disasm <binary>
```

Added to the usage text as:

```text
  x86learn disasm <binary>          disassemble an x86-64 ELF, Mach-O or PE program
```

| Case | stdout | stderr | Exit status |
|------|--------|--------|-------------|
| Supported file | listing text ([listing-format.md](listing-format.md)), identical to the editor | – | 0 |
| Supported file, cap reached | listing including the cut-off line | `warning: listing cut off at 10000 lines` | 0 |
| Rejected (any `RejectReason`) | – | `error: <message>` (same message as the GUI dialog) | 1 |
| Missing argument | usage | – | 2 |

- Exit status 1 is distinct from 2 (usage or assembly errors in `run`) and 139 (a fault in `run`).
- The command never runs the program and never touches `Machine`.
