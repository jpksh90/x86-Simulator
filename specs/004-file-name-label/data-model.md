# Data Model: Replace Examples Dropdown with File Name Label

## Document (UI state, not machine state)

The program currently in the editor. It is one of three cases:

| Case | Fields | Created by |
|------|--------|------------|
| `New` | none | File → New |
| `Example` | `displayName: String` (from `Examples.names`, e.g. "Hello, world") | Examples menu, startup |
| `Opened` | `file: File` | File → Open, Save, Save As |

A companion flag, `unsaved: Boolean`, already exists in `MainWindow`.

### Transitions

```text
any ──New────────────► New                      unsaved = false
any ──Examples menu──► Example(name)            unsaved = false
any ──Open (chosen)──► Opened(file)             unsaved = false
any ──Save/Save As───► Opened(file)             unsaved = false
any ──edit───────────► (same case)              unsaved = true
any ──dialog cancelled► (unchanged)             (unchanged)
```

Save on `New` or `Example` always prompts for a name, as it does today because `file == null`.

### Derived values (see [contracts/toolbar-label.md](./contracts/toolbar-label.md))

- `labelText(document, unsaved)`: the toolbar label.
- `tooltipText(document)`: the full name, or the absolute path for `Opened`.
- `titleName(document)`: the window title name. It keeps today's text, so `New` is shown as
  `untitled`.

### Validation rules

- The label is never empty.
- At most one leading `*`.
- `Opened` shows `file.name` only, with no directory (FR-003).
