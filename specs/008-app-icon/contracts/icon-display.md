# Contract: Where the App Icon Appears

**Feature**: [../spec.md](../spec.md)

This is the learner-visible contract. The implementation and tests must keep every row true.

## Resources (packaging contract)

| Path on the classpath | Content |
|---|---|
| `/icons/x86learn-16.png` | 16×16 RGBA, byte-identical to the 16 px entry of `x86_64_simulator_retro.ico` |
| `/icons/x86learn-24.png` | 24×24, as above |
| `/icons/x86learn-32.png` | 32×32, as above |
| `/icons/x86learn-48.png` | 48×48, as above |
| `/icons/x86learn-64.png` | 64×64, as above |
| `/icons/x86learn-128.png` | 128×128, as above |
| `/icons/x86learn-256.png` | 256×256, as above |

Source files live in `src/main/resources/icons/` and are committed.

## Display contract

| # | Situation | Required result | Spec |
|---|---|---|---|
| D1 | GUI started on macOS | Dock and ⌘-Tab show the icon once the app has started | FR-003 |
| D2 | GUI started on Windows | Main window's title bar, taskbar button and Alt-Tab show the icon | FR-004 |
| D3 | GUI started on Linux (X11 or XWayland) | Taskbar and window switcher show the icon where the window manager reads window icons | FR-004 |
| D4 | Control Flow Graph window opened | Same icon as the main window | FR-004 |
| D5 | Any dialog (About, messages, Open/Save/Disassemble file choosers, "Disassembling" progress) | Shows the main window's icon where the OS shows dialog icons | FR-004 |
| D6 | Help → About x86Learn | Icon shown left of name/version at 64 px × zoom, sharp on standard and HiDPI screens, in Dark and Light themes | FR-005 |
| D7 | Icon resources missing or damaged | App starts normally. OS default icon; About shows the standard "i" icon. No error dialog, no stack trace on stdout | FR-006 |
| D8 | `x86learn run …`, `x86learn run --example …`, `x86learn disasm …`, `x86learn --help` | No window, Dock icon or taskbar entry. Output byte-identical to before. The icon code is never loaded | FR-007 |
| D9 | Any of the above | Icon pixels unchanged: no recolouring, cropping, added border or background fill | FR-008 |

## Not in the contract

- Icon of the app before launch (Finder/Explorer/file-manager icon of a bundle or `.exe`):
  no such bundle exists.
- Icons for `.asm` documents.
- A separate artwork per theme.
