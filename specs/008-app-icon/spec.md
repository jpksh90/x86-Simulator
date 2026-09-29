# Feature Specification: Application Icon

**Feature Branch**: `008-app-icon`

**Created**: 2026-09-29

**Status**: Draft

**Input**: User description: "the /tmp folder contains an .ico file. Use it and make it icon for this tool."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Recognise the app by its icon while it runs (Priority: P1)

A learner starts x86Learn. Wherever the operating system shows running applications, they see
x86Learn's own icon instead of the generic Java icon: a retro computer monitor showing a `>_`
prompt and "x86-64", sitting on a gold-pinned x86-64 chip. This covers the macOS Dock and
⌘-Tab switcher, the Windows taskbar and Alt-Tab switcher, the window's title bar where the OS
shows one, and Linux taskbars and window switchers.

**Why this priority**: Today the app appears with a generic Java coffee-cup icon, so it's hard to
find among other windows and looks unfinished. This is the whole point of the request.

**Independent Test**: Launch the app on macOS, Windows and Linux and check that the Dock, taskbar,
app switcher and title bar (where the OS shows one) show the new icon, not the Java icon.

**Acceptance Scenarios**:

1. **Given** the app is started on macOS, **When** the learner looks at the Dock and presses ⌘-Tab,
   **Then** both show the x86Learn icon.
2. **Given** the app is started on Windows, **When** the learner looks at the taskbar, the
   window's title bar and Alt-Tab, **Then** all three show the x86Learn icon.
3. **Given** the app is started on a Linux desktop, **When** the learner looks at the taskbar or
   window switcher, **Then** it shows the x86Learn icon.
4. **Given** any of the above, **When** the icon is shown at a small size (16–32 px) or a large
   one (128–256 px, e.g. on a high-resolution display), **Then** it looks sharp, because the
   OS picks the prepared image closest to that size rather than scaling one image.

---

### User Story 2 - See the icon inside the app (Priority: P2)

A learner opens **Help → About x86Learn** and sees the same icon beside the app's name and
version. Other dialogs that the app opens (file choosers, messages) belong to the same app and
carry the same icon where the OS shows one.

**Why this priority**: It makes the app look consistent and finished, but it matters less than
the icon the OS shows.

**Independent Test**: Open Help → About x86Learn and check that the icon appears in the dialog, in
both the dark and light themes.

**Acceptance Scenarios**:

1. **Given** the app is running, **When** the learner opens About x86Learn, **Then** the dialog
   shows the icon next to the name and version, at a readable size.
2. **Given** the dark theme or the light theme, **When** the About dialog is open, **Then** the
   icon looks right against the background in both.
3. **Given** a zoom level anywhere from 80% to 250%, **When** the About dialog is open, **Then**
   the icon scales with the rest of the dialog and stays sharp.

---

### Edge Cases

- If the icon image can't be loaded when the app starts (e.g. a damaged build), the app still
  starts normally with the default icon. The missing icon must never stop the app from starting.
- Running from the terminal with the headless CLI (`x86learn run …`, `x86learn disasm …`) shows no
  window. The icon work must not make the CLI open a window, touch the Dock, or slow it down.
- Running the tests without a display (the `UiSnapshot` renders and CI) must keep working.
- The source file lives in `/tmp`, which the OS may empty. The icon must be copied into the project
  and version-controlled; the app must never read it from `/tmp`.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The icon supplied in `/tmp/x86_64_simulator_retro.ico` MUST become x86Learn's
  application icon, stored inside the project and version-controlled so it survives `/tmp` being
  cleared.
- **FR-002**: The app MUST keep all seven prepared sizes of the icon (16, 24, 32, 48, 64, 128 and
  256 px) and offer all of them to the operating system, so that it can pick the best one for
  each place it shows the icon.
- **FR-003**: When the GUI starts on macOS, the Dock and app switcher MUST show the icon.
- **FR-004**: When the GUI starts on Windows or Linux, the taskbar, app switcher and window title
  bar (where the OS shows one) MUST show the icon for the main window and for every other window
  the app opens.
- **FR-005**: The About x86Learn dialog MUST show the icon beside the name and version, and it MUST
  look right in both themes and at every zoom level (80%–250%).
- **FR-006**: If the icon can't be loaded, the app MUST start normally with the default icon.
- **FR-007**: The headless CLI MUST behave exactly as before: no window, Dock or taskbar entry, and
  no noticeable extra start-up time.
- **FR-008**: The icon's pixels MUST be shown unchanged (no recolouring, cropping or
  extra border). The artwork's own square black background is kept as it is.
- **FR-009**: The README MUST mention the icon's source file and licence terms. If the icon came
  from someone else, `THIRD_PARTY_NOTICES.md` MUST credit it.

### Key Entities

- **Application icon**: one picture ("retro monitor on an x86-64 chip") prepared at seven sizes,
  16–256 px, drawn on its own opaque black square. It stays the same for the app's whole life and is
  shown by the OS and in the About dialog.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On macOS, Windows and Linux, 0 places where the OS shows the running app (Dock,
  taskbar, app switcher, title bar) still show the generic Java icon.
- **SC-002**: The icon looks sharp, without blurry upscaling, at every size the OS asks for from
  16 to 256 px, as judged by comparing it with the original image at the same size.
- **SC-003**: The About dialog shows the icon correctly in 2 of 2 themes and at all zoom levels
  from 80% to 250%.
- **SC-004**: CLI start-up time and output are unchanged. The full test suite, including
  display-less runs, passes with no new failures.
- **SC-005**: A fresh clone of the repository, with `/tmp` empty, builds and shows the icon.

## Assumptions

- The user created the icon or has the right to use and redistribute it with the project. If it
  came from someone else, its licence goes in `THIRD_PARTY_NOTICES.md` (FR-009).
- The icon is used as it is. Making new artwork, changing its colours, or preparing a separate
  light-theme version is out of scope.
- "Icon for this tool" means the running app's icon (Dock, taskbar, title bar, About). Native
  installers or double-clickable app bundles (`.app`, `.exe`, `.deb`) don't exist today, so
  file-browser icons for them are out of scope. If such packaging is added later, it should reuse
  this icon.
- The icon of an unsaved or saved `.asm` file in the OS file browser (a document icon) is out of
  scope.
- The OS takes the icon from the running app. For a moment while the app is starting, the OS may
  still show the generic Java icon (for example in the macOS Dock). That is accepted.
- No new third-party libraries are needed to show the icon. Any image-format conversion is done
  once, when the icon is added to the project, not at run time. Whether the project stores the
  `.ico` itself or the seven sizes as separate images is decided in the plan.
