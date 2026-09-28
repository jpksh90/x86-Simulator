# Contract: Toolbar document label

The label replaces the Examples dropdown at the left end of the main toolbar. It is read-only and
does nothing when clicked.

## Text and tooltip

| Document | unsaved | Label text | Tooltip |
|----------|---------|-----------|---------|
| `New` | false | `*New File` | `New File (not saved yet)` |
| `New` | true | `*New File` | `New File (not saved yet)` |
| `Example("Hello, world")` | false | `Hello, world` | `Example: Hello, world` |
| `Example("Hello, world")` | true | `*Hello, world` | `Example: Hello, world` |
| `Opened(/home/u/loop.asm)` | false | `loop.asm` | `/home/u/loop.asm` |
| `Opened(/home/u/loop.asm)` | true | `*loop.asm` | `/home/u/loop.asm` |

## Presentation

- Position: the first toolbar item, followed by the existing 14px separator and then **Build**.
- Font: the UI font at 13pt bold, scaled with zoom. Color: `Theme.text`. Both are re-applied on
  theme or zoom change.
- Width: at most `Theme.z(220)`. Longer text is shortened with a trailing ellipsis, and the tooltip
  still shows the full value.

## Removed

- The toolbar `JComboBox` ("Examples" plus 9 entries). The **Examples** menu in the menu bar is the
  only way to load an example from the GUI. It still asks about unsaved changes first.

## Unchanged

- Window title format: `x86Learn — <name>` (`AppInfo.NAME`) plus ` •` when unsaved, where `<name>` is `untitled`
  for a new document.
- CLI: `x86learn run --example <id>`.
