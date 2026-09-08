# Creatures Map Editor 1.08 — derived geometry correction

## Important correction

The earlier WorldModel pass created:

```text
C2EEditorDoorRelation [0x0C]
+00 roomId1
+04 roomId2
+08 permeability
```

Those offsets were correct, but **0x0C was not the complete object size**.

The world writer only serializes those three values. Direct constructor,
cache-builder, and drawing evidence proves the live object is `0x24` bytes:

```text
C2EEditorBoundarySegment [0x24]

+00 int         roomId1
+04 int         roomId2
+08 int         permeability
+0C int         edgeCode
+10 int         length
+14 C2EIntPoint start
+1C C2EIntPoint end
```

The writer additionally emits a literal `1` between the parent room IDs and
permeability. That `1` is a file-format marker, not an object field.

The old `C2EEditorDoorRelation` type is retained and marked deprecated rather
than destructively deleting a type already present in the Ghidra DB.

## Independent proof from drawing

`C2EWorldModel_Draw` traverses the collection at WorldModel `+0x24`.

For each object it reads:

```text
+08 permeability     -> colour selection
+14 start.{x,y}      -> drawing coordinate
+1C end.{x,y}        -> drawing coordinate
```

It then traverses WorldModel `+0x38` and reads the **same +0x14/+0x1C
geometry layout**.

Thus the same compact boundary-segment shape is used for both door openings
and solid wall segments.

## WorldModel derived collections

The corrected fields are:

```text
C2EWorldModel

+24 doorSegments
+38 wallSegments
+4C inactiveDoorCache
+60 derivedCachesValid
```

### `doorSegments`

Current room-to-room geometric door openings.

They are **derived from room geometry**, but their permeability is persistent.

Cache rebuild:

1. compare room pairs;
2. find shared boundaries;
3. allocate 0x24 boundary segments;
4. default permeability to 100;
5. calculate segment length;
6. preserve permeability from prior/current or inactive cached doors.

The `.2er` writer serializes this collection but only persists:

```text
roomId1 roomId2
1
permeability
```

Geometry is rebuilt.

### `wallSegments`

Derived impermeable pieces of room boundaries.

`C2EEditorMetaRoom_BuildWallSegments` walks every room.

`C2ERoomGeometry_BuildWallSegments` starts with the room's four complete edges,
subtracts/splits those edges around door openings, and emits the pieces which
remain. Those objects have permeability `0`.

The renderer draws these after drawing doors.

### `inactiveDoorCache`

Editor-specific permeability memory.

If geometry changes so two rooms cease to share a boundary, the old door
cannot remain in `doorSegments`. Rather than throwing away its permeability
setting, cache rebuild preserves it in `inactiveDoorCache`.

If later geometry changes make that room pair adjacent again, the generated
door is matched against this cache and its previous permeability is restored.

This explains the otherwise odd merge paths around `004235A9` and is much more
specific than the previous `derivedIndex4C` label.

## Door generation functions

```text
00416A80 C2EEditorMetaRoom_BuildDoorSegments
0041A580 C2EEditorRoom_FindSharedBoundarySegment
0041B2D0 C2ERoomGeometry_FindSharedBoundarySegment
```

`00416A80` allocates exactly `0x24` bytes for each generated shared boundary.

It sets permeability to `100` and copies the output start/end points and edge
code from the shared-boundary test.

If room ordering is reversed, the edge code is transformed with:

```text
3 - edgeCode
```

For that reason the script deliberately does **not** equate the editor edge
codes with the runtime C2e `DIRECTION_LEFT/RIGHT/CEILING/FLOOR` constants yet.

## Wall generation functions

```text
00416C50 C2EEditorMetaRoom_BuildWallSegments
0041B570 C2ERoomGeometry_BuildWallSegments
```

These generate the impermeable remainder of room edges after doors are removed.

## DS source cross-reference

Docking Station-era `Map::BuildCache` has the same conceptual lifecycle:

- it maintains a temporary `myConstructedDoorCollection`;
- geometry processing pushes newly constructed doors into it;
- newly built doors are then copied into the usable `myDoorCollection`.

The runtime `Door` also carries parent identity, permeability and start/end
geometry.

The Map Editor representation is nevertheless compact and editor-specific, so
this pass uses `C2EEditorBoundarySegment` rather than importing the runtime
`Door` binary layout.
