# Creatures Map Editor 1.08 — `.2er` format notes

These notes are based on the exact `MapEditor.exe` binary plus the supplied
`undocked_station.2er` and `C3_RoomData.2er` samples.

## Top-level current format

Current saves begin:

```text
1 4
<property-type-count>
```

`1 4` is the current major/minor file version.

The loader also supports a legacy unversioned form. If the first integer is
`32`, it is treated directly as the Property Type count rather than as a
version number.

Internally the loader packs a version as:

```text
packed_version = major * 1000 + minor
```

so current format `1.4` becomes `1004`.

## Property Types

The document constructs 38 Property Type records, each `0x14` bytes.

The first six are built-ins and are not emitted by the writer. The file count
is therefore the number of records after those six.

Each serialized record is three lines:

```text
<name>
<minimum> <maximum> <enumerated-bool>
<pipe-separated-enum-values>
```

Record layout:

```text
+00  VC6 CString   name
+04  int           minValue
+08  int           maxValue
+0C  bool          enumerated
+0D  byte[3]       padding
+10  VC6 CString   enumValues
```

Example from `undocked_station.2er`:

```text
Room Type
0 128 1
Atmosphere|Wooden Walkway|Concrete Walkway|Indoor Corridor|...
```

## CA RATE matrix

Current format stores exactly:

```text
16 room types × 20 CA indices = 320 lines
```

In `undocked_station.2er`, these occupy lines 99–418, and line 419 starts the
World Model section.

Each line is three floats:

```text
<gain> <loss> <diffusion>
```

matching the CAOS `RATE room_type ca_index gain loss diffusion` order.

In memory:

```text
document.caRates
  outer vector: 16 × C2ECARateRow (0x10 each)

C2ECARateRow
  vector: 20 × C2ECARate (0x0C each)

C2ECARate
  +00 float gain
  +04 float loss
  +08 float diffusion
```

Compatibility behavior:

- versioned file with `minor >= 3`: read 20 CAs per room type;
- older versioned file with `minor < 3`: read 16 CAs per room type;
- legacy unversioned path skips this versioned CA-matrix branch.

## World Model section

Current sample begins:

```text
10000 10000
10 600
5
...
```

The top-level serializer proves:

```text
+00 int mapWidth
+04 int mapHeight
+08 tree/index metarooms        (count at +18)
+1C int nextMetaroomId
+20 int nextRoomId
+24 second serialized tree/index (count at +34)
+38 derived index
+4C derived index
```

The loader reads `nextMetaroomId` / `nextRoomId` only when
`packed_version >= 1004`.

The metaroom, room, geometry, and persisted door-record serializers beneath
`C2EWorldModel_Read2ER` and `C2EWorldModel_Write2ER` have since been
recovered. See `WORLD_MODEL_NOTES.md` and `DERIVED_GEOMETRY_NOTES.md`.
