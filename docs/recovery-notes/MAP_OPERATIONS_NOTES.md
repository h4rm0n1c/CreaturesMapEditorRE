# Creatures Map Editor 1.08 — map operations

This pass moves from persistent object layout into the editor's actual map API.

## Layering now visible

```text
MFC/UI
  CC2ERoomEditorDoc
        |
        v
  C2EWorldModel
        |
        +-- metaroom tree
        |     |
        |     +-- C2EEditorMetaRoom
        |            |
        |            +-- roomsById
        |                   |
        |                   +-- C2EEditorRoom
        |
        +-- doorRelations
        |
        +-- derived indices (+0x38, +0x4C)
```

The document layer is mostly a thin facade over `doc + 0x54` (`worldModel`).

## MetaRoom operations

```text
00422530 C2EWorldModel_AddMetaRoom
00422770 C2EWorldModel_InsertMetaRoomWithId
00422820 C2EWorldModel_RemoveMetaRoom
00422890 C2EWorldModel_FindMetaRoomById
00422930 C2EWorldModel_FindMetaRoomIdAtPoint
```

`AddMetaRoom` scans IDs from zero, allocates exactly `0x48` bytes, calls
`C2EEditorMetaRoom_ctor`, inserts it into the world metaroom tree, and returns
the chosen ID.

`RemoveMetaRoom` erases by integer ID and invalidates derived caches.

## Room operations

```text
00422CC0 C2EWorldModel_AddRoom
00422DE0 C2EWorldModel_InsertRoomWithId
00422F90 C2EWorldModel_RemoveRoom
00423010 C2EWorldModel_IsRoomIdValid
00423080 C2EWorldModel_FindRoomById
00423230 C2EWorldModel_GetMetaRoomIdForRoomId

00415E50 C2EEditorMetaRoom_HasRoomId
00415EB0 C2EEditorMetaRoom_FindRoomById
00415F70 C2EEditorMetaRoom_FindRoomIdAtPoint
00416310 C2EEditorMetaRoom_RemoveRoom
00416380 C2EEditorMetaRoom_InsertRoomWithId
```

`AddRoom`:

1. starts at ID 0;
2. calls `IsRoomIdValid` until it finds a free integer;
3. allocates exactly `0x44` bytes;
4. constructs `C2EEditorRoom`;
5. calls `InsertRoomWithId`;
6. returns the chosen ID.

`InsertRoomWithId` reads `room.geometry.xLeft` and
`room.geometry.yLeftCeiling`, uses the world point/metaroom lookup, inserts the
room into that metaroom's `roomsById` tree, and invalidates derived caches.

This is conceptually close to the Docking Station `Map::AddRoom` /
`Map::RemoveRoom` API, but operates on the editor's compact 0x44-byte room.

## Point lookup

The editor has compact equivalents of the DS engine's map queries:

```text
00422930 C2EWorldModel_FindMetaRoomIdAtPoint
004229E0 C2EWorldModel_FindRoomIdAtPoint
00415F70 C2EEditorMetaRoom_FindRoomIdAtPoint
```

They return integer IDs or `-1`.

The scalar argument convention is left untouched in this pass because the
compiler split the point/test inputs differently from the runtime source API.
The semantic operation is clear; exact source-level parameter reconstruction
can wait until the helper geometry predicates are typed.

## Derived world caches

This is now proven:

```text
C2EWorldModel +0x60  bool derivedCachesValid
```

`00423460` is only:

```text
derivedCachesValid = false;
```

`00423470`:

```text
if (derivedCachesValid)
    return;

rebuild derived indices from metaroom/room geometry and doorRelations;

derivedCachesValid = true;
```

The derived containers at `WorldModel +0x38` and `+0x4C` remain deliberately
unnamed until their exact indexing semantics are recovered.

## Document wrappers

```text
00408BD0 CC2ERoomEditorDoc_AddMetaRoom
00408BF0 CC2ERoomEditorDoc_InsertMetaRoomWithId
00408D50 CC2ERoomEditorDoc_RemoveMetaRoom
00408D60 CC2ERoomEditorDoc_AddRoom
00408D70 CC2ERoomEditorDoc_InsertRoomWithId
00408D90 CC2ERoomEditorDoc_RemoveRoom
```

These are valuable because they expose the editor-facing API without needing
to give names to every VC6 tree/smart-reference helper in between.

## Source-reference policy

Docking Station's `Map.cpp` contains corresponding semantic operations such as:

- `AddMetaRoom`
- `RemoveMetaRoom`
- `AddRoom`
- `RemoveRoom`
- `IsRoomIDValid`
- `GetMetaRoomIDForPoint`
- `GetRoomIDForPoint`

Those names are used only where the editor binary independently demonstrates
the same operation. Runtime DS object layouts are not imported.
