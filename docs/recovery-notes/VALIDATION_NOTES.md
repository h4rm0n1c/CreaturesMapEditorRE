# Creatures Map Editor 1.08 — validation and generated-door terminology

## Validation entry points

The editor's `Validate` command reaches:

```text
00424050 C2EWorldModel_Validate
    |
    +--> 004155E0 C2EEditorMetaRoom_ValidateRooms
```

`CC2ERoomEditorView_OnValidate` supplies the current:

```text
selectedRoomIds
selectedDoorPairs
```

collections. Validation can insert offending objects into those collections
before raising the editor's validation exception, allowing the UI to highlight
the objects associated with the error.

The document also calls the same world validator with null selection sets after
successful edit actions when its validation/debug state is enabled.

## World rules

`C2EWorldModel_Validate` first refreshes derived geometry with
`EnsureDerivedCaches()`.

For every metaroom it requires:

```text
left   >= 0
top    >= 0
right  <= mapWidth
bottom <= mapHeight
```

Failure text:

```text
Metaroom outside world.
```

Metaroom bounds are also compared pairwise with the Win32 rectangle
intersection operation. Any non-empty intersection produces:

```text
Metarooms overlap.
```

The validator then calls `C2EEditorMetaRoom_ValidateRooms` for each metaroom.

Finally both generated door collections are checked for a minimum segment
length of **5 units**.

Exact messages:

```text
An internal door has been generated which is too small
(doors must be at least 5 units long).

An external door has been generated which is too small
(doors must be at least 5 units long).
```

## Room / metaroom rules

For every room:

```text
xLeft < xRight

yLeftCeiling  < yLeftFloor
yRightCeiling < yRightFloor
```

Otherwise:

```text
Room bad shape
```

All four room corners are passed through `PtInRect` against the owning
metaroom's bounds.

Failure:

```text
Room not inside metaroom
```

The validator then compares rooms pairwise. It tests room corner points with
`C2EEditorRoom_ContainsPoint`; if a corner of either room lies inside the other
room the pair is rejected:

```text
Rooms must not overlap
```

When a `selectedRoomIds` set is supplied, the offending room IDs are inserted
before the validation exception is raised.

## Internal vs external doors — terminology correction

This pass corrects an earlier recovery name.

Previous model:

```text
World +0x24 doorSegments
World +0x38 wallSegments
World +0x4C inactiveDoorCache
```

The derived geometry itself was correct, but validation gives the editor's
actual terminology:

```text
World +0x24 internalDoorSegments
World +0x38 externalDoorSegments
World +0x4C inactiveInternalDoorCache
```

### Internal doors

These are shared boundaries between two Rooms.

They:

- are discovered by comparing room-pair geometry;
- carry a permeability;
- have permeability preserved/restored across edits;
- are emitted as generated CAOS:

```text
door game "mapeditortmp_<room1>"
     game "mapeditortmp_<room2>"
     <permeability>
```

so `C2EEditorMetaRoom_BuildDoorSegments` becomes:

```text
C2EEditorMetaRoom_BuildInternalDoorSegments
```

and:

```text
C2EWorldModel_FindDoorByRoomPair
```

becomes:

```text
C2EWorldModel_FindInternalDoorByRoomPair
```

### External doors

The geometry helper previously named `BuildWallSegments` starts from a Room's
complete four-edge perimeter and subtracts shared/internal openings.

The remaining one-parent boundary pieces are not merely anonymous walls: the
editor validates them explicitly as **external doors**.

Therefore:

```text
C2EEditorMetaRoom_BuildWallSegments
    -> C2EEditorMetaRoom_BuildExternalDoorSegments

C2ERoomGeometry_BuildWallSegments
    -> C2ERoomGeometry_BuildExternalDoorSegments
```

This also fits the original C2e door model, which supports doors with one or
two parent rooms.

## Minimum generated door length

The constant is not inferred from UI wording alone.

`C2EWorldModel_Validate` directly compares the `length` member of each internal
and external generated boundary segment against integer `5`.

So:

```text
minimum generated internal door length = 5
minimum generated external door length = 5
```

is binary-proven.
