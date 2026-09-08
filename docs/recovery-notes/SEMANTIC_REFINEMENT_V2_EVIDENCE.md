# Creatures Map Editor 1.08 — semantic refinement v2 evidence

This pass is based on the latest post-v1.4 Ghidra database and direct
disassembly of the exact supplied `MapEditor.exe`.

## Corrected `CC2ERoomEditorView` region

| Offset | Semantic field | Confidence |
|---:|---|---|
| `+0x120` | `cheeseTool` (`0x1c` bytes) | High |
| `+0x13c` | `currentViewRect` (`0x10` bytes) | High |
| `+0x14c` | `selectedMetaroomId` | High |
| `+0x150` | `selectedRoomIds` (`0x14` tree/set) | High |
| `+0x164` | `selectedRoomRefs` (`0x14` tree/set) | Medium-high |
| `+0x178` | `zoomBackHistory` (`0x30` deque-like) | High |
| `+0x1a8` | `zoomForwardHistory` (`0x30` deque-like) | High |
| `+0x1d8` | `colourRoomsCAIndex` | Already proven |
| `+0x1dc` | `caTimerPhase` | High |
| `+0x1e0` | `timerId` | High |
| `+0x1e4` | `showBackground` | Already proven |
| `+0x1e5` | unresolved hidden-command flag | Intentionally not renamed |
| `+0x1e6` | `updateCAFromGameEnabled` | High |

## Cheese-tool correction

The earlier pass treated `C2ECheeseTool` as `0x2c` bytes because the next
known constructor-initialized member began at `+0x14c`.

Direct code use shows the Cheese object only requires the common `0x1c`
editor-tool base and ends at view `+0x13b`.

The next 16 bytes, `view +0x13c .. +0x14b`, are copied together as the current
viewport rectangle by zoom/navigation code. They are not Cheese state.

## Zoom history

`CC2ERoomEditorView_SetViewRectWithHistory` (`0x0040ecf0`) copies the existing
`+0x13c` rectangle into the `+0x178` container, increments its count at
`+0x1a4`, clears the `+0x1a8` forward container, then installs a new 16-byte
rectangle.

`OnZoomForward` (`0x004109a0`) performs the complementary transfer.

The resource strings confirm:

- command 32791: Back — “View the last area selected”
- command 32792: Forward — “View area selected before you selected Back”

This establishes `+0x178 = zoomBackHistory` and
`+0x1a8 = zoomForwardHistory`.

## Selection state

`CC2ERoomEditorView_ClearSelection` (`0x0040ee80`) clears both containers at
`+0x150` and `+0x164` and resets `+0x14c` to `-1`.

`0x0040f660` simply returns `+0x14c`, supporting
`selectedMetaroomId`.

## Timer / CA state

The timer handler computes:

`(view->field_1dc + 1) % 20`

and stores the remainder back, supporting `caTimerPhase`.

`+0x1e0` is populated from `SetTimer` and consumed by timer/destroy paths,
supporting `timerId`.

Command 32829 is explicitly “Update CA from Game”; its handlers toggle/read
byte `+0x1e6`, supporting `updateCAFromGameEnabled`.

## Deliberately unresolved

Toolbar IDs 32824 and 32826 are real hidden commands. The timer path shows a
relationship between them and byte `+0x1e5`, but the available resources do
not establish a trustworthy human-facing semantic name. The script adds
evidence comments and leaves the names generic.
