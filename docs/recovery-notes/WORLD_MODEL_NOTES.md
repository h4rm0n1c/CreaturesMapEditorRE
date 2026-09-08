# Creatures Map Editor 1.08 — world model cross-reference

## Evidence policy

This pass does **not** paste the Docking Station engine classes into the editor.

The order of trust is:

1. exact MapEditor 1.08 machine code and current Ghidra database;
2. the local original Docking Station source tree;
3. the local original Creatures 3 source tree;
4. the supplied `.2er` files and public CAOS documentation.

The source trees are used as a semantic dictionary only when the editor binary
independently agrees.

## Why the editor types are smaller

The engine runtime `Room` includes live door collections, neighbour collections,
CA histories, diffusion state, navigation pointers, links and later DS/Sea
Monkeys fields.

The Map Editor's room object is only `0x44` bytes.

It stores the information needed to edit and generate the world:

```text
C2EEditorRoom  [0x44]
+00        float caValue
+04        float caInput
+08        float caTempValue
+0C        C2ERoomGeometry [0x1C]
+28        propertyValues vector [0x10]
+38        float caTotalDoorage
+3C        track (VC6 CString)
+40        state40 (DWORD; unresolved)
```

Room IDs are keys in `C2EEditorMetaRoom.roomsById`, not a member of the
compact Room object.  The live-import path does copy its explicit room-ID
argument to `state40`, but no recovered Room method consumes that member;
the deliberately neutral name remains appropriate.  See
[`LIVE_SYNC_CA_NOTES.md`](LIVE_SYNC_CA_NOTES.md) for the evidence.

### Room geometry

The six values are proven by both the binary's indexed property accessor and
the C2e `Map::AddRoom` / `GetRoomLocation` API order:

```text
property 0  xLeft
property 1  xRight
property 2  yLeftCeiling
property 3  yRightCeiling
property 4  yLeftFloor
property 5  yRightFloor
property 6  Room Type  -> propertyValues[0]
```

`C2EEditorRoom_GetProperty` at `00419E50` implements exactly this split.

Changing any of properties 0..5 writes `0xBF800000` (`-1.0f`) to `+0x24`,
so that member is a derived geometry cache rather than another map property.

## Room `.2er` body

The owning metaroom writes the room ID first. `C2EEditorRoom_Write2ER`
then writes:

```text
<xLeft> <xRight> <yLeftCeiling> <yRightCeiling> <yLeftFloor> <yRightFloor>
<roomType>
<track>
```

Compatibility:

```text
packed version >= 1001  -> Room Type present
packed version >= 1002  -> room track present
```

## Room injection CAOS

The binary format string at `00434F54` is:

```text
setv va00 addr %s %d %d %d %d %d %d
rtyp va00 %d
rmsc %d %d "%s"
setv game "mapeditortmp_%d" va00
```

That independently confirms:

- the six room geometry values;
- property 6 as `RTYP`;
- `+0x3C` as room music;
- `+0x40` as room ID.

The delete helper uses:

```text
delg "mapeditortmp_%d"
```

with the same `+0x40` value.

## Compact metaroom

The editor metaroom is `0x48` bytes:

```text
C2EEditorMetaRoom [0x48]
+00  bounds {left, top, right, bottom} [0x10]
+10  lifetime/reference state [0x08]
+18  backgrounds vector [0x10]
+28  currentBackground (source-correlated; not directly serialized)
+2C  roomsById tree [0x14]
+40  track
+44  unresolved
```

The editor world writes the metaroom ID as the **outer tree key**. The
metaroom body itself writes:

```text
<left> <top> <right> <bottom>
<backgroundCount>
<background paths...>
<track>
<roomCount>
<roomId + room body>...
```

Packed version `>=1002` contains the metaroom track.

The DS engine source's `MetaRoom` contains the same semantic concepts
(background collection, current background and track), but also runtime fields
that the compact editor representation does not copy.

## Persisted door records and live boundary segments

After all metarooms, the current `.2er` samples contain a relation section.

The earlier WorldModel pass identified the persisted subset:

```text
C2EEditorDoorRelation [0x0C] (deprecated serialized-subset type)
+00 int roomId1
+04 int roomId2
+08 int permeability
```

On disk the writer emits:

```text
<roomId1> <roomId2>
1
<permeability>
```

The middle `1` is a legacy/file-format marker. It is emitted literally and is
not a field in the persisted subset.

The live collection at WorldModel `+0x24` instead contains
`C2EEditorBoundarySegment[0x24]`: the three persisted fields plus edge code,
length, and start/end points. Door geometry is rebuilt from rooms; only the
room pair and permeability are read/written. See `DERIVED_GEOMETRY_NOTES.md`
for the corrected full layout and cache behaviour.

In `undocked_station.2er` there are 172 such records; observed permeability
values include `100` and `50`.

The editor's generated CAOS uses the `DOOR` concept, and the DS engine source
exposes `SetDoorPermiability` / `GetDoorPermiability`. That supports the
persisted permeability semantics, without importing the larger runtime `Door`
or `Link` structures.

## High-confidence functions

```text
004152D0  C2EEditorMetaRoom_ctor
004164F0  C2EEditorMetaRoom_Write2ER
00416630  C2EEditorMetaRoom_Read2ER

00419D40  C2EEditorRoom_ctor
00419E50  C2EEditorRoom_GetProperty
00419E70  C2EEditorRoom_SetProperty
0041A5D0  C2EEditorRoom_Write2ER
0041AB10  C2EEditorRoom_Read2ER
0041AC90  C2ERoomGeometry_Write2ER
0041AD20  C2ERoomGeometry_Read2ER
0041A620  C2EEditorRoom_BuildInjectCAOS
0041A770  C2EEditorRoom_BuildDeleteCAOS

00422C20  C2EWorldModel_FindDoorRelation
```

## Deliberately not promoted yet

The script leaves these unresolved until the next pass:

- Room `+0x00..+0x0B`;
- Room `+0x38`;
- MetaRoom `+0x44`;
- WorldModel derived indices `+0x38` and `+0x4C`;
- the exact semantic quantity cached in Room `+0x24`;
- deeper tree-node/smart-reference implementation details.

That is intentional. The DS/C3 source is useful enough that it would be very
easy to give those bytes plausible-but-wrong runtime names.
