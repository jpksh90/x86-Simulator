# Data Model: Application Icon

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

There is no persistent or user-editable data. The only "data" is a fixed picture shipped with the
app.

## Application icon

One picture prepared at seven sizes. Immutable and loaded once per GUI session.

| Field | Type | Rules |
|---|---|---|
| `sizes` | ordered list of ints | Exactly `16, 24, 32, 48, 64, 128, 256`, ascending. |
| `images` | list of RGBA images, same order as `sizes` | Each image is square, with side equal to its size, and has an alpha channel. Loaded from `/icons/x86learn-<size>.png` on the classpath. |
| state | `loaded` or `unavailable` | `unavailable` (empty `images`) if **any** of the 7 resources is missing or unreadable. The app never uses a partial set. |

### Validation rules

- V1: all 7 resources exist and decode (unit test; guards FR-001, FR-002, SC-005).
- V2: image *n* is `sizes[n]` × `sizes[n]` pixels and has alpha (guards FR-002, FR-008).
- V3: resource bytes equal the corresponding PNG entry of the original `.ico`. This is checked once
  with checksums when the files are added (quickstart step 1); after that the files are in git.

### Derived behaviour: choosing an image for a size

`bestFor(devicePixels)` returns the **smallest** image whose side is ≥ `devicePixels`, or the
**largest** image if the request is bigger than 256. Examples:

| Request (device px) | Image picked |
|---|---|
| 1–16 | 16 |
| 17–24 | 24 |
| 51 (64 × 0.8 zoom) | 64 |
| 64 | 64 |
| 128 (64 at 100% on Retina) | 128 |
| 160 (64 × 250%) | 256 |
| 320 (64 × 250% on Retina) | 256 (drawn scaled up) |

`bestFor` on an `unavailable` icon returns nothing.

### State transitions

```
(not loaded) --first use--> loaded
(not loaded) --first use, a resource missing/unreadable--> unavailable
```

Both end states are final for the session. There is no reload.

## Where the icon is used (consumers)

| Consumer | Uses | When `unavailable` |
|---|---|---|
| macOS Dock / ⌘-Tab | largest image (256) | leave the default icon |
| `MainWindow`, `CfgWindow` title bar / taskbar / switcher | all 7 images | leave the default icon |
| Owned dialogs (JOptionPane, JDialog, FileDialog) | inherited from owner window | inherited default |
| About dialog | `bestFor(Theme.z(64) × device scale)` | standard "i" information icon |
