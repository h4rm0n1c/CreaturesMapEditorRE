# Creatures Map Editor 1.08 — editing geometry

## Geometry nesting correction

The previous semantic pass correctly identified:

```text
Room +0x24 = perimeterLength
```

but represented it as a sibling field after:

```text
Room +0x0c C2ERoomGeometry[0x18]
```

The editing code proves the source-level value is better represented as:

```text
C2ERoomGeometry [0x1c]

+00 xLeft
+04 xRight
+08 yLeftCeiling
+0c yRightCeiling
+10 yLeftFloor
+14 yRightFloor
+18 float perimeterLength
```

and:

```text
C2EEditorRoom +0x0c C2ERoomGeometry[0x1c]
```

`C2EEditorMetaRoom_SnapRoomGeometry` copies **seven DWORDs** starting at
`Room+0x0c`, edits the temporary geometry, and copies seven DWORDs back.

This is independent structural evidence that the lazy perimeter cache travels
with the geometry during editor operations.

## Common editor-tool mouse state

The base tool layout becomes:

```text
C2EEditorToolBase [0x1c]

+00 vftable
+04 ownerView
+08 C2EIntPoint dragStartPoint
+10 C2EIntPoint currentPoint
+18 uint32 mouseDownTick
```

Default `LButtonDown`:

```text
mouseDownTick = GetTickCount();
dragStartPoint = currentPoint;
```

Default `MouseMove` stores the new point in `currentPoint` after processing.

## Room edit mask

`C2ERoomGeometry_TranslateMaskedCoordinates` proves the six-bit mask:

```text
0x01 X_LEFT
0x02 X_RIGHT
0x04 Y_LEFT_CEILING
0x08 Y_LEFT_FLOOR
0x10 Y_RIGHT_CEILING
0x20 Y_RIGHT_FLOOR
```

The Select tool uses compound masks:

```text
0x05 TOP_LEFT_CORNER
0x09 BOTTOM_LEFT_CORNER
0x12 TOP_RIGHT_CORNER
0x22 BOTTOM_RIGHT_CORNER
0x3F WHOLE_ROOM
```

After applying the translation it invalidates:

```text
geometry.perimeterLength = -1.0f
```

## Snapping pipeline

### World level

```text
004232A0 C2EWorldModel_SnapRoomGeometry
```

Arguments include the edited room, an optional exclusion/multi-edit collection,
snap tolerance, and metaroom ID.

If the supplied metaroom ID is `-1`, the helper derives a metaroom from the
edited room's approximate centre before continuing.

### MetaRoom level

```text
00416170 C2EEditorMetaRoom_SnapRoomGeometry
```

Pipeline:

1. copy complete 0x1c geometry;
2. clamp to metaroom bounds;
3. iterate other rooms (with optional exclusions);
4. snap vertical room edges;
5. accumulate best nearby ceiling/floor line candidates;
6. apply the winning ceiling and floor lines.

### Room / geometry level

```text
0041A4F0 C2EEditorRoom_SnapVerticalEdgesToRoom
0041ADB0 C2ERoomGeometry_SnapVerticalEdgesToRoom

0041A510 C2EEditorRoom_AccumulateCeilingFloorSnapCandidates
0041AF60 C2ERoomGeometry_AccumulateCeilingFloorSnapCandidates

0041BBD0 C2ERoomGeometry_TranslateMaskedCoordinates
```

Vertical snapping requires both:

- overlapping vertical ranges; and
- opposing X coordinates closer than the snap tolerance.

The sloped ceiling/floor path works over overlapping X intervals, tracks
candidate endpoint pairs while scanning neighbouring rooms, then applies one
whole snapped line after the scan.

## Room +0x38

Still intentionally unresolved.

The Select edit path copies Room `+0x38` into a complete temporary Room
snapshot, but the recovered Room methods do not independently read/write it in
a way that exposes semantics.

That makes it persistent editor state or reserved/vestigial state, but not yet
something we can honestly name.
