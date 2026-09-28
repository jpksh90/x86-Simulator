# Research: Replace Examples Dropdown with File Name Label

The Technical Context had no NEEDS CLARIFICATION items. These are the design decisions.

## R1: Model the current document as a value, not a loose string

- **Decision**: Replace `docName: String` (and its `"untitled"` sentinel) with
  `document: Document`, a sealed type with three cases: `New`, `Example(displayName)` and
  `Opened(file)`. `MainWindow.file` is derived from it (`(document as? Opened)?.file`) or kept in
  sync from one assignment point.
- **Rationale**: The label needs to know the document's origin, because FR-005 treats a new
  document differently. A string can't carry the origin. A sealed type makes the three cases
  exhaustive in `when`.
- **Alternatives considered**: Keeping `docName` and comparing it to `"untitled"`. This was
  rejected because it is fragile, and a learner could save a file named `untitled`.

## R2: Keep the label derivation pure and Swing-free

- **Decision**: `DocumentLabel.kt` exposes `labelText(doc, unsaved)` and `tooltipText(doc)` as
  plain functions.
- **Rationale**: Principle III wants tests on concrete values, and Principle IV keeps display-free
  logic testable. The rules (the `*` prefix, no double `*` on New File) are the only part that can
  be wrong in a subtle way.
- **Alternatives considered**: Computing the text inline in `MainWindow`. This was rejected
  because testing it would need a display.

## R3: Refresh the label from `updateTitle()`

- **Decision**: Rename or extend `updateTitle()` to `updateDocumentUi()`, which sets the window
  title and the label's text and tooltip.
- **Rationale**: `updateTitle()` is already called after `setSource` (New, Open, example load),
  after `save`, and from `editor.onEdited`. That covers every event in FR-007 with no new call
  sites. Cancelled dialogs return before any of these calls, so the label stays unchanged, as the
  edge cases require.
- **Alternatives considered**: A property-change listener on `document`/`unsaved`. This was
  rejected as unnecessary indirection for one consumer.

## R4: Label appearance and truncation

- **Decision**: A `JLabel` in the dropdown's old slot, followed by the existing 14px separator.
  Font `Theme.ui(13f, Font.BOLD)` and foreground `Theme.text`, both set in `Theme.onChange`.
  Maximum width `Theme.z(220)`, re-set on zoom. `JLabel` adds an ellipsis by itself when its text is
  wider than its size.
- **Rationale**: This matches how the Speed label is themed, and scales with zoom (Principle II). A
  width of 220 is close to the old 200px dropdown, so the toolbar layout barely shifts (SC-004).
- **Alternatives considered**: A non-editable `JTextField`. This was rejected because it looks like
  an input, and the spec says the label does nothing when clicked.

## R5: The window title stays the same

- **Decision**: The title keeps `"<App> — <name>"` plus `" •"`. For a new document the name is
  still `untitled`.
- **Rationale**: The spec's Assumptions keep the window title's format unchanged, so the title is
  computed from `document` without changing its text.
- **Alternatives considered**: Switching the title to "New File" as well. This is deferred because
  the spec doesn't ask for it, and it's a one-line change later if wanted.
