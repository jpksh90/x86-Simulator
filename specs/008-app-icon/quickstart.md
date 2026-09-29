# Quickstart: Validate the Application Icon

**Feature**: [spec.md](spec.md) | **Contract**: [contracts/icon-display.md](contracts/icon-display.md)

## Prerequisites

- JDK 17+ (the build uses the JDK 21 toolchain), repository checked out.
- For step 1 only: `/tmp/x86_64_simulator_retro.ico` still present.

## 1. Resources match the original icon (V3, FR-001, FR-008)

While the original `.ico` is still in `/tmp`, compare the SHA-256 of each PNG entry inside it with
`src/main/resources/icons/x86learn-<size>.png`. All 7 must match. (A few lines of Python or
Kotlin reading the ICO directory — 6-byte header, 16-byte entries, size/offset at bytes 8/12 of
each entry — are enough; don't commit that script.)

Expected: 7 of 7 checksums equal.

## 2. Unit tests (FR-002, FR-006, SC-004)

```sh
./gradlew test
```

Expected: all tests pass, including the new icon tests (7 images load with the right sizes and
alpha; `bestFor` follows the table in [data-model.md](data-model.md); a missing resource gives
"unavailable" without throwing).

## 3. CLI unchanged (FR-007, D8)

```sh
./gradlew installDist
time build/install/x86learn/bin/x86learn run --example 01_hello
build/install/x86learn/bin/x86learn disasm src/test/resources/binaries/hello-elf.o | head
```

Expected: the same output as on `main`, no Dock icon or window appears on macOS, and the time is
about the same as on `main` (compare 3 runs each).

## 4. GUI on each OS (D1–D5, SC-001)

```sh
./gradlew run
```

- **macOS**: the Dock and ⌘-Tab show the monitor-on-chip icon. Open View → Control Flow Graph
  and Help → About: no generic Java cup anywhere.
- **Windows**: check the title bar (16 px), taskbar and Alt-Tab.
- **Linux (X11)**: check the taskbar and window switcher.

Also open File → Open…, File → Disassemble Binary… and an error dialog, and check that each
dialog shows the icon where the OS shows dialog icons.

## 5. About dialog in both themes and at every zoom level (D6, SC-003)

Render headlessly with UiSnapshot (see the project memory note on how to run it), using the
`ABOUT` scenario, once per theme and at the smallest and largest zoom:

- Dark theme, 100% zoom
- Light theme, 100% zoom
- Light theme, zoom in to 250%
- Dark theme, zoom out to 80%

Expected: the icon is left of the text, sharp (no blur at 250%), and drawn as the artwork's own black square
tile, unchanged, in both themes. The standard "i" icon is gone.

## 6. Missing icon fallback (D7, FR-006)

Temporarily move `src/main/resources/icons/` away, run `./gradlew run`, open About, then put the
folder back.

Expected: the app starts and works normally. It shows the OS default icon and the "i" in About,
and nothing is printed to the terminal.

## 7. Fresh clone (SC-005)

Clone into a new directory, empty `/tmp` of the `.ico` (or just check that nothing references
`/tmp`), then run `./gradlew run`.

Expected: the icon shows.
