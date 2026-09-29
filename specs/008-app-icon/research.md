# Research: Application Icon

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-29

The spec left no NEEDS CLARIFICATION items. The questions below are the technical unknowns
found while filling in the plan's Technical Context.

## Facts about the source file

`/tmp/x86_64_simulator_retro.ico` (57,755 bytes) is an ICO container holding 7 entries. **Every
entry is a PNG**, not a BMP/DIB:

| Size | PNG bytes |
|---|---|
| 16×16 | 518 |
| 24×24 | 847 |
| 32×32 | 1,313 |
| 48×48 | 2,461 |
| 64×64 | 3,685 |
| 128×128 | 10,835 |
| 256×256 | 37,978 |

All are 8-bit RGBA, but **every pixel is opaque**: the background is solid black (`0xff000000`), not
transparent. This was found during implementation; at first only the presence of an alpha channel had
been checked. The user chose to keep the artwork unchanged (a black square tile). The artwork is a pixel-art beige monitor
showing a green `>_` prompt and "x86-64", sitting on a dark chip labelled "x86-64" with gold pins.

## R1 — How the icon is stored in the project

- **Decision**: Extract the 7 embedded PNGs **byte for byte** (no decoding or re-encoding) into
  `src/main/resources/icons/x86learn-<size>.png`, and commit them. The `.ico` itself is not
  committed.
- **Rationale**:
  - The JDK's `ImageIO` reads PNG out of the box but has no ICO reader, so storing PNGs needs no
    parsing code and no new dependency (constitution: prefer the standard library).
  - A byte-for-byte copy guarantees that the pixels are unchanged (FR-008), and it can be checked
    with a checksum against the original while `/tmp` still has it.
  - Resources are packed into the jar, so `./gradlew run` and `installDist` pick them up with no
    extra setup (Technology & Scope Constraints), and a fresh clone has them (SC-005).
- **Alternatives considered**:
  - *Commit the `.ico` and parse it at start-up* (~30 lines of directory parsing plus PNG decoding):
    it's one file instead of seven, but it's extra code to test and maintain for no benefit to the
    learner, and it would still need a BMP path for any future ICO with non-PNG entries.
  - *Commit only the 256 px PNG and scale it down*: rejected, because the small sizes are
    hand-tuned pixel art. Downscaling 256 px to 16 px blurs it (SC-002).
  - *Also commit the `.ico` for future Windows packaging*: not needed now (native installers are
    out of scope), and it can be rebuilt from the PNGs if packaging is added.

## R2 — macOS Dock and ⌘-Tab icon

- **Decision**: Use `java.awt.Taskbar.getTaskbar().setIconImage(...)`, passing the 256 px image,
  only when `Taskbar.isTaskbarSupported()` and `isSupported(Taskbar.Feature.ICON_IMAGE)` are both
  true. Wrap the call in `try/catch` for `UnsupportedOperationException` and `SecurityException`.
  Call it from `MainWindow.launch()` before the first window is shown.
- **Rationale**: `Taskbar` (JDK 9+, fine for the JDK 17+ runtime floor) is the supported public
  API for the Dock icon. `Window.setIconImages` has no effect on the macOS Dock. Calling it from
  `launch()` means that only the GUI path touches it. The CLI (`run`, `disasm`) never reaches
  `launch()`, so it can't create a Dock entry or pay any start-up cost (FR-007).
- **Alternatives considered**:
  - `com.apple.eawt.Application.setDockIconImage`: internal API, blocked by the module system
    since JDK 9 without `--add-exports`. Rejected.
  - A `-Xdock:icon=<path>` JVM flag in the start scripts: it needs a real file path on disk
    (not a jar resource) and only helps the `installDist` scripts. Rejected.
- **Known limit** (accepted in the spec's Assumptions): until the call runs, the Dock briefly
  shows the generic Java icon.

## R3 — Windows and Linux taskbar, switcher and title bar

- **Decision**: Call `Window.setIconImages(allSevenImages)` on `MainWindow` and `CfgWindow` (the
  only top-level `JFrame`s). Dialogs (`JOptionPane`, `JDialog`, `FileDialog`) are all created with
  the main window as owner, so they inherit its icon.
- **Rationale**: With the whole list, AWT and the OS pick the best size for each use: 16 px in
  the title bar, 32 or 48 px in the taskbar or Alt-Tab, and larger on HiDPI (FR-002, FR-004,
  SC-002). Owned dialogs without their own icon use their owner's, so nothing else needs changing.
  On macOS the call is harmless.
- **Alternatives considered**: `setIconImage(single)` lets the OS scale one image, which blurs the
  pixel art. Rejected.
- **Linux note**: X11 window managers read the `_NET_WM_ICON` hint that AWT sets from this list.
  Some Wayland compositors (XWayland) look up icons by `.desktop` file instead. There is no
  `.desktop` file (native packaging is out of scope), so on those desktops a generic icon may
  still appear. This will be recorded in README limitations.

## R4 — Icon in the About dialog, both themes, zoom 80%–250%, HiDPI

- **Decision**: Pass a custom `javax.swing.Icon` to the 5-argument
  `JOptionPane.showMessageDialog(..., INFORMATION_MESSAGE, icon)`. It replaces the generic "i"
  information icon. The icon's logical size is `Theme.z(64)`, so it follows the zoom level. In
  `paintIcon` it reads the device scale from the `Graphics2D` transform, picks the smallest
  prepared image whose side is ≥ logical size × device scale (or the largest if none is big
  enough), and draws it with bicubic interpolation.
- **Rationale**: Choosing the source image from the actual device pixels keeps it sharp at every
  zoom level and on Retina and 4K screens (SC-003). The picking rule is pure arithmetic, so it can
  be unit-tested headlessly. The dialog is rebuilt each time it opens, so a zoom change is picked
  up the next time.
- **Alternatives considered**:
  - `ImageIcon(BaseMultiResolutionImage(...))`: Swing picks variants for HiDPI, but its size is
    fixed to the base image and ignores FlatLaf zoom. Rejected.
  - Putting an `<img src=...>` in `aboutHtml`: HTML-in-JLabel image scaling is poor and doesn't
    follow zoom. Rejected.
- **Themes**: the artwork is an opaque black square tile, drawn as it is in both themes. In the Light
  theme it reads as a dark app tile. The user accepted this over editing the artwork. The beige monitor and dark chip read well on both
  FlatLaf Dark and Light backgrounds, so no per-theme variant is needed (the spec rules out a
  second artwork). This is checked with `UiSnapshot ABOUT` renders in both themes.

## R5 — Missing icon, headless runs, tests

- **Decision**: `AppIcon` loads the images lazily from the classpath. Any missing resource or
  `IOException` gives an **empty list**, and every consumer treats an empty list as "keep the
  default icon". The loader takes the resource lookup as a parameter, so tests can simulate a
  missing file.
- **Rationale**: FR-006 says a missing icon must never stop the app. `ImageIO.read` and
  `BufferedImage` work under `java.awt.headless=true`, so the loading tests run in CI. `Taskbar`
  is only touched from `launch()`, which tests and `UiSnapshot` never call.
- **Alternatives considered**: failing hard on a missing icon to catch packaging mistakes. That is
  better done by a unit test asserting all 7 resources load, so the app itself can stay lenient.

## R6 — Licence and credit (FR-009)

- **Decision**: The spec assumes the user owns the artwork or has the right to redistribute it. The
  implementation adds one line to README (Project layout and a short "App icon" note) saying where
  the icon lives and that it is distributed with the project. A `THIRD_PARTY_NOTICES.md` entry is
  added **only if** the user says it came from someone else. The implementer asks the user once
  before the README change and does not guess an author.
- **Rationale**: Publishing an unattributed third-party image would be a licence problem, and
  guessing an author would be wrong. It is a one-question check, not a design unknown.
