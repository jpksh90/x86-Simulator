# Feature Specification: Rename the Project to x86Learn

**Feature Branch**: `002-rename-x86learn`

**Created**: 2026-09-27

**Status**: Implemented (manual checks T021/T023 pending)

**Input**: User description: "change the project name. The current name is x86-simulator. change it to x86Learn. Update the about documentation in help."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The app presents itself as x86Learn (Priority: P1)

A student opens the application and sees the name **x86Learn** everywhere the product names
itself: the window title (including when a file is open or unsaved), the macOS menu bar/Dock
name, the headless command's usage text, and the project README.

**Why this priority**: the rename is the core request. A mix of old and new names looks broken
and confuses students following course material.

**Independent Test**: launch the GUI, open a file and edit it, then run the command-line tool
with `--help`. Every place the product names itself says "x86Learn", and none says
"x86-64 Simulator" or "x86-simulator".

**Acceptance Scenarios**:

1. **Given** the app is launched with no file, **When** the window appears, **Then** its title
   is "x86Learn".
2. **Given** a file `loop.asm` is open with unsaved edits, **When** the user looks at the title
   bar, **Then** it reads "x86Learn — loop.asm •", keeping the existing document-name and
   unsaved-marker format.
3. **Given** macOS, **When** the app is running, **Then** the application menu and Dock show
   "x86Learn".
4. **Given** a terminal, **When** the user runs the command-line tool with `--help`, **Then** the
   usage text's heading names x86Learn and its examples show the command name the tool is
   actually installed as.
5. **Given** the README, **When** a reader opens it, **Then** its title and introduction use
   "x86Learn", and all build and run commands in it work exactly as written.

---

### User Story 2 - Help → About describes x86Learn (Priority: P1)

A student chooses **Help → About** and gets a short, useful description of x86Learn instead of
the current two-line placeholder.

**Why this priority**: explicitly requested. The About box is the in-app place that says what the
tool is and where to go next.

**Independent Test**: open Help → About and check that it has the required content (FR-005) and
that it's readable in both themes and at 250% zoom.

**Acceptance Scenarios**:

1. **Given** the app is running, **When** the user opens Help → About, **Then** a dialog titled
   "About x86Learn" shows:
   - the name x86Learn
   - the version number
   - a one-sentence description of what it is
   - what it supports: x86-64 assembly in NASM syntax, simulated Linux system calls
   - its main learning features: stepping forward and back, breakpoints, and visual registers,
     flags, stack and memory
   - where to get help: the Reference tab (Help → Instruction Reference) and hovering over
     instructions
2. **Given** the About dialog is open, **When** the user presses Enter/Escape or clicks OK,
   **Then** it closes and the simulator state is unchanged.
3. **Given** dark or light theme at any zoom level from 80% to 250%, **When** About is opened,
   **Then** all its text is legible and nothing is cut off.

---

### User Story 3 - Existing users keep their settings (Priority: P2)

A student who used the app under its old name upgrades. Their chosen theme (dark/light) and zoom
level are still applied the first time they open x86Learn.

**Why this priority**: a rename shouldn't silently reset people's preferences. That matters for
projector setups that depend on a saved zoom. It is secondary to the visible rename.

**Independent Test**: with the old version, set light theme and 150% zoom, then start the renamed
version. Light theme and 150% zoom are active.

**Acceptance Scenarios**:

1. **Given** saved settings from the previous version, **When** x86Learn starts for the first
   time, **Then** the saved theme and zoom are applied.
2. **Given** a user who never ran the old version, **When** x86Learn starts, **Then** the defaults
   apply (dark theme, 100% zoom).

---

### Edge Cases

- **Name spelling**: the name is always written "x86Learn": lowercase "x", digits "86",
  capital "L". It is never "X86Learn", "x86learn" or "x86 Learn" in user-facing text. The one
  exception is identifiers that must be lowercase by convention, such as a terminal command name,
  where `x86learn` is used.
- **Technical references stay technical**: phrases describing the *CPU* rather than the product
  (e.g. "x86-64 assembly", "the simulator executes instructions…" in code comments) aren't
  renamed. Only the product's name changes.
- **Historical records**: git history and the already-committed spec for feature 001 keep the old
  name. They are records, not user-facing product text.
- **Example programs and outputs**: no example program or its printed output mentions the product
  name, so their behavior and tests don't change.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The main window title MUST be "x86Learn" with no document, and
  "x86Learn — <document name>" (plus the existing unsaved marker " •") with a document.
- **FR-002**: On macOS, the application name shown in the menu bar and Dock MUST be "x86Learn".
- **FR-003**: The command-line usage text MUST name the product "x86Learn". Its example
  invocations MUST use the installed command name (see FR-008).
- **FR-004**: The README title and product references MUST read "x86Learn". Every command shown in
  the README MUST work unchanged when copied.
- **FR-005**: Help → About MUST open a dialog titled "About x86Learn" containing:
  - the name
  - the current version
  - a one-sentence description
  - what the app supports (x86-64 assembly in NASM syntax; simulated Linux system calls)
  - its main learning features (step forward and back, breakpoints, visual registers, flags,
    stack and memory)
  - pointers to in-app help (Reference tab / Help → Instruction Reference, and hovering over
    instructions)
- **FR-006**: The version shown in About MUST be the project's actual version, not a hard-coded
  copy that can drift.
- **FR-007**: The About dialog MUST be legible in dark and light themes at every supported zoom
  level (80%–250%), and MUST close with OK, Enter or Escape without changing any simulator state.
- **FR-008**: The installed launcher command MUST be renamed from `x86sim` to `x86learn` (the
  lowercase form of the new name), so the command matches the product name. The README and usage
  text MUST show `x86learn`.
- **FR-009**: Saved preferences (theme and zoom) from the previous version MUST carry over to
  x86Learn on first launch. After that, x86Learn MUST keep its settings under its own name.
- **FR-010**: The build's project name MUST be "x86Learn", so artifacts and IDE project names
  match the product.
- **FR-011**: After the change, no user-facing text (window, dialogs, menus, CLI output, README)
  may contain "x86-64 Simulator" or "x86-simulator" as a product name.

### Key Entities

- **Product name**: "x86Learn", the single canonical display name used in all user-facing text.
- **Command name**: `x86learn`, the lowercase form used where the platform expects lowercase
  identifiers (the terminal launcher).
- **Saved preferences**: the user's theme and zoom level. Carried over from the old name once, then
  stored under the new name.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A search of all user-facing surfaces (window title, macOS app name, dialogs, menus,
  CLI usage/output, README) finds 0 occurrences of "x86-64 Simulator" or "x86-simulator" used as
  the product name, and at least one occurrence of "x86Learn" on each surface.
- **SC-002**: The About dialog contains all six required items from FR-005 (6/6), and a new
  student can say what the tool is and where to find the instruction reference after reading it
  once.
- **SC-003**: A user with saved light theme and 150% zoom from the old version sees both applied on
  first launch of x86Learn (both settings carried over).
- **SC-004**: Every build/run command in the README succeeds when copied and pasted on a fresh
  checkout.
- **SC-005**: All existing automated tests still pass. Program behavior, example output and
  simulator semantics are unchanged.

## Out of Scope

- **Renaming the source code's internal package/namespace** (currently `x86sim`). It isn't
  user-facing, and renaming it would touch every file for no user benefit.
- **Renaming the GitHub repository or the local checkout folder** (`x86-Simulator`). This is done
  by the repository owner in GitHub settings. GitHub redirects the old URL.
- **A logo, app icon or other visual branding.**
- **Rewriting historical documents** (git history, the feature-001 spec and its artifacts).
- **Adding a license or author credits to About.** The project has no license file, and inventing
  one is out of scope. It can be added once the owner provides it.

## Assumptions

- **Command name**: the launcher becomes `x86learn`. Command names are conventionally lowercase,
  and keeping `x86sim` would leave the old name in daily use. Anyone with scripts that call
  `x86sim` needs to update them. This is noted in the README.
- **Version source**: the version shown in About is the one already defined for the project
  (currently 1.0.0).
- **Preference migration**: carrying settings over means copying them once, on first launch, from
  where the old version stored them. The old values are left in place, harmlessly.
- **Terminology**: the product is still described as a "simulator" in prose ("an educational
  x86-64 simulator"). Only the *name* changes, not the description of what it is.
- **Branch**: this feature builds on the current work, including feature 001's string
  instructions, because the README it edits already contains those changes.
