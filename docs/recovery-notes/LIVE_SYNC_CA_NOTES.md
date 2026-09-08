# Creatures Map Editor 1.08 — Live Sync / CA recovery

## Why this pass contains corrections

The earlier WorldModel/Room recovery deliberately left the first twelve bytes
of `C2EEditorRoom` unresolved, but it also gave `Room+0x40` the speculative name
`roomId`.

The CA paths now provide much stronger evidence and require correcting that
model.

## Correct compact Room layout

```text
C2EEditorRoom [0x44]

+00 float caValue
+04 float caInput
+08 float caTempValue

+0C C2ERoomGeometry geometry [0x1C]

+28 C2EIntVector propertyValues [0x10]

+38 float caTotalDoorage

+3C VC6CString track

+40 DWORD state40      // unresolved
```

### Room ID is not an object member

Room IDs are the keys in `C2EEditorMetaRoom.roomsById`.

Functions which require a room ID receive it explicitly.

For example, both:

```text
0041A620 C2EEditorRoom_BuildInjectCAOS
0041A770 C2EEditorRoom_BuildDeleteCAOS
```

format the explicit room-ID function argument into
`GAME "mapeditortmp_<roomId>"`.

`C2EEditorRoom_ctor` initializes `+0`, `+4`, the geometry/perimeter, property
vector and track, but does **not** initialize `+0x40`.

Therefore the prior `+0x40 roomId` label is disproven. This pass changes it to
the deliberately neutral `state40`.

### Room Type is property index 6

`C2EEditorRoom_GetProperty` implements:

```text
index 0..5 -> geometry[index]
index >= 6 -> propertyValues[index - 6]
```

The local CA update explicitly calls:

```text
GetProperty(6)
```

and uses the result to select one of the 16 CA-rate room-type rows.

Thus:

```text
propertyValues[0] == Room Type
```

and `Room+0x08` is not room type.

## Exact CA scalar fields

### `+0x00 caValue`

Live CA parsing at `00417D00` invokes the stream float extraction operator with
the `C2EEditorRoom*` itself as the destination address.

That writes the result to the first float in the Room:

```text
Room+0x00 caValue
```

The local simulation also reads/writes the same scalar.

### `+0x04 caInput`

For each Cheese source point, `C2EWorldModel_StepCheeseCASimulation` finds the
containing room and writes:

```text
room.caInput = 1.0f
```

The room CA helper then consumes this input.

### `+0x08 caTempValue`

`C2EEditorMetaRoom_PrepareCheeseCAStep` calls the editor's copy of:

```cpp
UpdateRoomCA(
    rates,
    room.caInput,
    room.caValue,
    room.caTempValue);
```

The helper at `0041C880` is an algorithm match for the released Creature Labs
`RoomCA.cpp` `UpdateRoomCA`.

### `+0x38 caTotalDoorage`

Before processing doors the editor clears:

```text
room.caTotalDoorage = 0
```

Door traversal accumulates each room's relative door contribution here.

Finalization applies:

```cpp
room.caValue +=
    room.caTempValue *
    (1.0f - room.caTotalDoorage);
```

which directly matches the high-level `Map::UpdateCurrentCAProperty` source
vocabulary.

## Creature Labs CA helper matches

Recovered:

```text
0041C880 C2ECA_UpdateRoomCA
0041C8E0 C2ECA_UpdateDoorCA
```

These correspond directly to the released:

```text
engine/Map/RoomCA.h
engine/Map/RoomCA.cpp
```

The editor's surrounding model is compact, but the math itself is the same.

## Cheese sources

`C2ECheeseTool_OnLButtonUp` proves:

```text
CC2ERoomEditorDoc +0x148
    C2EIntPointVector cheeseSources
```

The tool inserts exactly one 8-byte `{x,y}` point into this vector per click.

Its vector pointers are:

```text
+148 allocator/vector base
+14C begin
+150 end
+154 capacityEnd
```

The local simulation receives both:

```text
Doc +0x138 caRates
Doc +0x148 cheeseSources
```

and seeds `caInput=1.0` in rooms containing Cheese points.

## Hidden toolbar commands resolved

### ID 32824

```text
0040B210
CC2ERoomEditorDoc_StepCheeseCASimulation
```

One local simulation step, then refresh views.

### ID 32826

```text
00411570
CC2ERoomEditorView_OnToggleCheeseCASimulation
```

Toggles:

```text
View +0x1E5 simulateCheeseCAEnabled
```

When enabling it, the editor clears:

```text
View +0x1E6 updateCAFromGameEnabled
```

### ID 32829

```text
00411760
CC2ERoomEditorView_OnToggleUpdateCAFromGame
```

Does the reverse: enabling live engine sampling clears local simulation.

So the two modes are explicitly mutually exclusive.

## Local Cheese CA uses CA index 0

The local step selects a CA-rate row using:

```text
roomType = room.GetProperty(6)
```

then takes the row's `begin` pointer directly without adding a CA-index
element offset.

Therefore the local Cheese simulation uses:

```text
caRates[roomType][0]
```

It is not a generic 0..19 simulator.

## Live Update CA from Game

The View timer uses:

```text
View +0x1D8 colourRoomsCAIndex
```

as the selected live CA index.

It emits:

```text
SETV VA00 <colourRoomsCAIndex>
```

then, for every room, uses its centre:

```text
outv prop grap <centreX> <centreY> va00
outs "
"
```

Recovered helpers:

```text
00425220 C2EWorldModel_AppendRoomCAQueryCAOS
00417D70 C2EEditorMetaRoom_AppendRoomCAQueryCAOS

004251B0 C2EWorldModel_ParseRoomCAValues
00417D00 C2EEditorMetaRoom_ParseRoomCAValues
```

The returned float stream is stored one value per Room at `Room.caValue`.

### Label correction

Game IPC v1 initially labelled the format at `00434EF0` as a background
property query.

That is wrong.

The actual string is the room-centre CA query above, so this pass renames the
symbol to:

```text
C2E_GAME_QUERY_ROOM_CA_AT_POINT_FORMAT
```

## Set Metaroom in Game

`CC2ERoomEditorDoc_OnSetMetaroomInGame` opens `CSwitchMetaroomDlg`.

The actual live command sequence is in:

```text
0041E190 CSwitchMetaroomDlg_OnApply
```

It resolves the selected editor metaroom, gets its bounds/centre and sends:

```text
meta gmap <centreX> <centreY> -1 -1 0
bkgd gmap <centreX> <centreY> "<selected background>" 0
dmap <debugMapEnabled>
```

The released C2e CAOS table documents:

```text
DMAP debug_map
```

as:

```text
1 = debug map image on
0 = debug map image off
```

The debug map also includes vehicle cabin lines.

Thus this dialog changes the running game's current metaroom/background/debug
presentation. It does not rebuild the map geometry.
