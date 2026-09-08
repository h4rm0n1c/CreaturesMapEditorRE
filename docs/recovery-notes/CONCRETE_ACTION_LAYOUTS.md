# Creatures Map Editor 1.08 — concrete action layouts

## Document history fix

The previous Edit Actions v1 pass correctly identified the history objects but
did not replace the old document members because its conservative safety check
treated generated `paddingB9` / `paddingE9` names as user fields.

This pass accepts only generated `state*`, `padding*`, `field_*` and undefined
members in those two exact ranges and replaces them with:

```text
CC2ERoomEditorDoc
+0x0B8 C2EActionHistory undoHistory [0x30]
+0x0E8 C2EActionHistory redoHistory [0x30]
```

No surrounding document fields are touched.

## Exact/simple concrete layouts

```text
C2EAddMetaRoomAction [0x1C]
+00 vftable
+04 VC6CString background
+08 C2EIntRect bounds
+18 int metaRoomId
```

```text
C2EAddRoomAction [0x28]
+00 vftable
+04 C2EAddRoomCreationState [0x20]
+24 int roomId
```

The 0x20 creation state is intentionally still opaque. Its size and copy
boundary are exact, but this pass does not invent names for its internal
DWORDs.

```text
C2ESetRoomPropertyAction [0x18]
+00 vftable
+04 vector<C2ERoomPropertySnapshot> snapshots
+14 int propertyIndex

C2ERoomPropertySnapshot [0x08]
+00 int roomId
+04 int value
```

Redo proves this layout by swapping `value` with
`C2EEditorRoom[propertyIndex]`.

```text
C2ESetDoorOpeningAction [0x18]
+00 vftable
+04 vector<C2EDoorOpeningSnapshot> snapshots
+14 DWORD context14

C2EDoorOpeningSnapshot [0x0C]
+00 int roomId1
+04 int roomId2
+08 int permeability
```

Redo iterates in 12-byte steps, resolves the generated door by room pair, and
swaps the record's `permeability` with the live boundary segment's +0x08 value.

The +0x14 constructor field is deliberately left semantically generic because
Redo does not consume it.

```text
C2ERemoveMetaRoomAction [0x10]
+00 vftable
+04 C2EEditorMetaRoomRef removedMetaRoom
+0C int metaRoomId
```

```text
C2EMoveMetaRoomAction [0x18]
+00 vftable
+04 C2EIntRect bounds
+14 int metaRoomId
```

Redo swaps the stored bounds with the live metaroom bounds.

## Background/music action family

Simple 0x0C actions:

```text
C2ESetMetaRoomBackgroundAction
C2EAddMetaRoomBackgroundAction
C2ESetMetaRoomMusicAction

+00 vftable
+04 int metaRoomId
+08 VC6CString value
```

Indexed background actions:

```text
C2ERemoveMetaRoomBackgroundAction [0x10]
+00 vftable
+04 int metaRoomId
+08 int backgroundIndex
+0C VC6CString removedBackground

C2EChangeMetaRoomBackgroundAction [0x10]
+00 vftable
+04 int metaRoomId
+08 int backgroundIndex
+0C VC6CString background
```

## Change World Properties

`00403870` gives a particularly clean proof:

```text
C2EChangeWorldPropertiesAction [0x14]
+00 vftable
+04 C2EWorldPropertiesState state

C2EWorldPropertiesState [0x10]
+00 mapWidth
+04 mapHeight
+08 nextMetaRoomId
+0C nextRoomId
```

Redo fetches the live map dimensions and WorldModel `+0x1C/+0x20`, writes the
stored values into the WorldModel, then stores the previous live values back
into the action. It is therefore another self-inverting swap command.

## Set Room Music

```text
C2ESetRoomMusicAction [0x14]
+00 vftable
+04 vector<C2ERoomMusicSnapshot> snapshots

C2ERoomMusicSnapshot [0x08]
+00 int roomId
+04 VC6CString track
```

Redo iterates in 8-byte steps and swaps `track` with
`C2EEditorRoom.track (+0x3C)`.
