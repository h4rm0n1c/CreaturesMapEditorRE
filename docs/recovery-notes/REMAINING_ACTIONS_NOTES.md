# Creatures Map Editor 1.08 — remaining action corrections

## Remove Room action

Direct constructor/Redo/Undo evidence gives:

```text
C2ERemoveRoomAction [0x14]

+00 vftable
+04 C2ERemoveRoomSnapshotVector snapshots
```

Each element is 12 bytes:

```text
C2ERemoveRoomSnapshot [0x0C]

+00 int roomId
+04 C2EEditorRoomRef removedRoom

C2EEditorRoomRef [0x08]
+00 C2EEditorRoom *room
+04 uint32 *refCount
```

Redo iterates the vector in `0x0C` steps. It resolves the current Room by
`roomId`, refreshes the preserved shared reference if necessary, and removes
the room.

Undo calls:

```text
CC2ERoomEditorDoc_InsertRoomWithId(
    snapshot.roomId,
    &snapshot.removedRoom)
```

for each element.

## Add Room's 0x20 input is four points

The previous pass deliberately left:

```text
C2EAddRoomCreationState [0x20]
    rawState[0x20]
```

opaque.

The Room constructor resolves it cleanly.

It copies eight DWORDs, then invokes a sort over four elements of size eight
using comparator `00419D10`.

That comparator is:

```text
if (a.x != b.x)
    return a.x < b.x;

return a.y < b.y;
```

So the type is:

```text
C2EAddRoomCreationState [0x20]

C2EIntPoint corners[4];
```

After sorting:

```text
p0 left/top
p1 left/bottom
p2 right/top
p3 right/bottom
```

and `C2EEditorRoom_ctor` derives:

```text
xLeft         = p0.x
xRight        = p3.x
yLeftCeiling  = p0.y
yLeftFloor    = p1.y
yRightCeiling = p2.y
yRightFloor   = p3.y
```

The constructor still invalidates `perimeterLength` after installing these
coordinates.

This also fits the Add Room tool: it gathers four room-corner points, previews
and snaps a temporary Room, then packages the resulting four corners into
`C2EAddRoomAction`.

## Selection correction: View +0x164

Earlier recovery named:

```text
View +0x164 selectedRoomRefs
```

with only medium confidence.

Later action code proves that interpretation wrong.

The generic property commit routine at `0040F7F0` checks:

```text
View +0x150 selectedRoomIds.count

if nonzero:
    C2ESetRoomPropertyAction(selectedRoomIds, propertyIndex, value)

else:

View +0x164 selectedDoorPairs.count

if nonzero:
    C2ESetDoorOpeningAction(selectedDoorPairs, propertyIndex, value)
```

The door action constructor obtains **two integer room IDs** from every selected
entry and builds:

```text
{ roomId1, roomId2, permeability=value }
```

Therefore:

```text
View +0x164 C2ESelectedDoorPairSet selectedDoorPairs
```

is the better model.

The old `C2ESelectedRoomRefSet` type is retained only as a deprecated recovery
artifact.

## Set Door Opening +0x14

The generic dispatcher passes the same two scalar arguments to the room-property
and door-opening action constructors:

```text
propertyIndex
value
```

For `C2ESetRoomPropertyAction` these become:

```text
+14 propertyIndex
snapshot.value = value
```

For `C2ESetDoorOpeningAction`:

```text
+14 propertyIndex
snapshot.permeability = value
```

Redo only needs the snapshots, but the constructor does preserve the original
generic property index.

So the earlier name:

```text
context14
```

is promoted to:

```text
propertyIndex
```

## "Modify Room", "Modify Metaroom", "Multiple Action"

These strings exist in the application's resources, but no recovered edit-action
vtable in the concrete `0042A8F8..0042A9E8` action family returns those resource
IDs.

They are therefore **not** assigned to action classes.

This avoids turning nearby UI/resource vocabulary into fake C++ class names.
