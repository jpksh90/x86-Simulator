# Contract: File → Disassemble Binary… (GUI)

## Menu

The File menu has **Disassemble Binary…** directly after **Open…**, with shortcut ⇧⌘O
(Ctrl+Shift+O on Windows/Linux).

## Flow

1. Run `confirmDiscard()`. Cancel stops the flow and nothing changes.
2. Show a native file dialog titled "Disassemble a compiled program". There's no extension filter
   because detection uses the file's contents. Cancel stops the flow and nothing changes.
3. Start a background worker: read the file, detect the format, build the listing.
   - If it's still running after 400 ms, show a modal "Disassembling <name>…" dialog with a
     **Cancel** button.
   - Cancel stops the worker, closes the dialog, and changes nothing.
4. Outcomes, handled on the event thread:
   - **Rejected** (or a read error): an error dialog titled "Can't disassemble this file" with the
     `Detection.Rejected.message`. The editor, document and label are unchanged.
   - **Success**:
     - `document = Document.Disassembly(file, summary)`, and `setSource(listing.text)`.
     - `unsaved = true`, so the label shows `*hello (disassembly)`.
     - Caret at line 1.
     - Status message:
       `Disassembled hello (ELF64 x86-64 executable): 1234 instructions — read-only listing; it may not assemble or run here.`
     - If the listing was truncated, the message adds ` Listing cut off at 10000 lines.`
5. **Save / Save As** on a disassembly always opens the save dialog with the default name
   `<binary name without extension>.asm` in the binary's folder. After saving,
   `document = Opened(asmFile)`. The binary is never written.

## Appearance

- Dialogs use the existing FlatLaf look and work in dark and light themes and at every zoom level.
- Hover help works as for any program: supported mnemonics and registers show their Reference
  entry. Mnemonics the simulator doesn't support show no hover, as they do today for unknown
  words.
