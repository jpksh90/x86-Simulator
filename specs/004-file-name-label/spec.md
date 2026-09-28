# Feature Specification: Replace Examples Dropdown with File Name Label

**Feature Branch**: `004-file-name-label`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "The examples dropdown is not needed. It can be replaced with a label for the file name or mention *New File"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See which program is open (Priority: P1)

A learner looks at the left end of the toolbar and sees the name of the program currently in the
editor: the file name of an opened or saved file, the name of a loaded example, or "*New File" for
a program that has never been saved. The "Examples" dropdown that used to be there is gone;
examples are still loaded from the **Examples** menu in the menu bar.

**Why this priority**: This is the whole feature. The dropdown duplicated the Examples menu and
always read "Examples", so it never told the learner what they were working on.

**Independent Test**: Start the app, then use New, Open, Save As and the Examples menu in turn,
checking after each step that the toolbar shows the expected name and that no examples dropdown
appears.

**Acceptance Scenarios**:

1. **Given** the app has just started with the default example loaded, **When** the learner looks
   at the toolbar, **Then** it shows that example's name and no examples dropdown is present.
2. **Given** any program is open, **When** the learner chooses File → New, **Then** the toolbar
   shows "*New File".
3. **Given** any program is open, **When** the learner opens `loop.asm` from disk, **Then** the
   toolbar shows "loop.asm".
4. **Given** "*New File" is shown, **When** the learner saves it as `mine.asm`, **Then** the
   toolbar shows "mine.asm".
5. **Given** any program is open, **When** the learner picks an example from the Examples menu,
   **Then** the toolbar shows that example's name.

---

### User Story 2 - See when the program has unsaved changes (Priority: P2)

When the learner edits the program, the label marks it as unsaved with a leading "*" (for
example "*loop.asm"), matching the unsaved marker in the window title. Saving removes the marker.

**Why this priority**: Useful, but the window title already shows unsaved state, so this is a
convenience on top of Story 1.

**Independent Test**: Open a file, type a character, check the label gains a "*"; save, check the
"*" is gone.

**Acceptance Scenarios**:

1. **Given** `loop.asm` is open and unchanged, **When** the learner types in the editor, **Then**
   the label shows "*loop.asm".
2. **Given** the label shows "*loop.asm", **When** the learner saves, **Then** the label shows
   "loop.asm".
3. **Given** a never-saved new program, **When** the learner edits it, **Then** the label still
   shows "*New File" (no double marker).

---

### Edge Cases

- Very long file names: the label is shortened with an ellipsis so it doesn't push toolbar
  buttons off-screen, and hovering shows the full name (and full path for files on disk).
- Save As cancelled: the label is unchanged.
- Open cancelled, or discard prompt cancelled: the label is unchanged.
- Theme switch (dark/light) and every zoom level (80%–250%): the label stays readable and uses
  the theme's text colors.
- An example that the learner edits and then saves to disk: the label changes from the example
  name to the saved file name.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The toolbar MUST NOT contain the examples dropdown.
- **FR-002**: The toolbar MUST show a read-only label, in the position the dropdown occupied, that
  names the program currently in the editor.
- **FR-003**: For a program opened from or saved to disk, the label MUST show the file's name
  (without directory).
- **FR-004**: For a loaded example that hasn't been saved to disk, the label MUST show the
  example's display name, as listed in the Examples menu.
- **FR-005**: For a program created with New and never saved, the label MUST show "*New File".
- **FR-006**: When the program has unsaved edits, the label MUST start with "*"; when there are
  none, it MUST NOT (except "*New File", which always carries it).
- **FR-007**: The label MUST update right away after New, Open, Save, Save As, loading an
  example, and the first edit after a save or load.
- **FR-008**: Hovering the label MUST show the full name, including the full path for files on
  disk.
- **FR-009**: The Examples menu in the menu bar MUST keep working as it does today, including the
  unsaved-changes prompt before replacing the program.
- **FR-010**: The label MUST follow the current theme and zoom level like other toolbar text.

### Key Entities

- **Current document**: the program in the editor. Its attributes are its origin (a file on disk,
  a built-in example or new), its display name and whether it has unsaved edits.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100% of the flows in User Stories 1 and 2 (start, New, Open, Save, Save As,
  example load, edit), the toolbar label matches the expected name and unsaved marker.
- **SC-002**: Learners can say which program is open by looking only at the toolbar, without
  opening a menu, in under 2 seconds.
- **SC-003**: No existing way to load an example is lost. Every example that the dropdown offered
  is still reachable in at most 2 clicks from the menu bar.
- **SC-004**: The toolbar keeps every existing button visible at the default window size, both
  themes and zoom 80%–250%, with a file name of up to 40 characters.

## Assumptions

- "*New File" is the literal label text for a never-saved new program. The leading "*" follows
  the common editor convention for "not saved", so edited named files also get a leading "*".
- Examples stay available only through the menu bar's Examples menu. No replacement toolbar
  control for examples is needed.
- The label is informational only. Clicking it does nothing, and renaming is still done through
  Save As.
- The window title keeps its current format. This feature only changes the toolbar.
- The headless CLI is unaffected. `run --example` keeps working.
