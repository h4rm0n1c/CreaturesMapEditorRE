# Creatures Map Editor 1.08 — edit actions / Undo-Redo

## Exact action names from resources

The action vtables contain a fourth virtual method which returns a STRINGTABLE
resource ID. The executable's own strings therefore provide the action names
without inference:

```text
0x82 No Action
0x83 Add Room
0x84 Add Metaroom
0x85 Remove Room
0x86 Remove Metaroom
0x87 Modify Room
0x88 Modify Metaroom
0x89 Undo
0x8A Redo
0x8B Move Metaroom
0x8C Set Room Property
0x8D Move Room
0x8E Set Door Opening
0x8F Set Metaroom Background
0x90 Add Metaroom Background
0x91 Remove Metaroom Background
0x92 Multiple Action
0x93 Change Metaroom Background
0x94 Change World Properties
0x98 Set Metaroom Music
0x99 Set Room Music
```

Not every string corresponds to one of the contiguous concrete vtables recovered
in this pass; `Modify Room`, `Modify Metaroom`, and `Multiple Action` remain
available as evidence for later compound/edit classes.

## Action vtable contract

Document command handlers prove:

```text
C2EEditActionVTable [0x10]

+00 deleting destructor
+04 Redo(document)
+08 Undo(document)
+0C GetDescriptionStringId()
```

`CC2ERoomEditorDoc_OnRedo` invokes slot `+04`.

`CC2ERoomEditorDoc_OnUndo` invokes slot `+08`.

The Undo/Redo update handlers call slot `+0C` to append the concrete action
description to the menu item.

## Corrected Document layout

An older pass broke this region into several unrelated-looking small objects.

The constructor and Undo/Redo code now establish:

```text
CC2ERoomEditorDoc

+0B8 C2EActionHistory undoHistory [0x30]
+0E8 C2EActionHistory redoHistory [0x30]
+118 propertyTypes
```

Each action history is a VC6 deque-like structure containing 8-byte
`C2EEditActionRef` values:

```text
C2EEditActionRef [0x08]
+00 C2EEditActionBase *action
+04 uint32 *refCount
```

Counts:

```text
Doc +0x0E4  undoHistory.count
Doc +0x114  redoHistory.count
```

These are exactly the values tested by `OnUpdateUndo` and `OnUpdateRedo`.

## New-action flow

`00408980 CC2ERoomEditorDoc_ExecuteEditAction`:

```text
action->Redo(document)

if success:
    push action to undoHistory
    clear redoHistory
    SetModifiedFlag(TRUE)
    refresh views/UI
```

This explains why the Select tool creates an action only when a drag is
committed rather than writing a special separate undo record afterwards.

## Move Room transaction

The Select tool allocates exactly `0x14` bytes for the Move Room action.

```text
C2EMoveRoomAction [0x14]

+00 vftable
+04 C2EMoveRoomSnapshotVector snapshots [0x10]
```

Each vector element is exactly `0x20` bytes:

```text
C2EMoveRoomSnapshot [0x20]

+00 int roomId
+04 C2ERoomGeometry geometry [0x1C]
```

The constructor takes the selected-room set together with drag delta/mask,
WorldModel and snap context, computes each room's target moved/snapped geometry,
and stores those target states.

### Self-inverting Redo/Undo

`00401A20 C2EMoveRoomAction_RedoSwapGeometry` performs:

```text
for snapshot in snapshots:
    current = liveRoom.geometry
    liveRoom.geometry = snapshot.geometry
    snapshot.geometry = current

invalidate derived world caches
```

The vtable's Undo slot is the shared helper at `00401BC0`, which simply calls
the action's Redo virtual.

Thus Move Room needs only one geometry state per room: every application swaps
the two states.

This is why the same action object moves cleanly between Undo and Redo stacks.

## Recovered concrete vtables

```text
0042A8F8 C2EEditActionBase
0042A908 C2EAddMetaRoomAction
0042A918 C2EAddRoomAction
0042A928 C2ERemoveRoomAction
0042A938 C2EMoveRoomAction
0042A948 C2ESetRoomPropertyAction
0042A958 C2ESetDoorOpeningAction
0042A968 C2ERemoveMetaRoomAction
0042A978 C2EMoveMetaRoomAction
0042A988 C2ESetMetaRoomBackgroundAction
0042A998 C2EAddMetaRoomBackgroundAction
0042A9A8 C2ERemoveMetaRoomBackgroundAction
0042A9B8 C2EChangeMetaRoomBackgroundAction
0042A9C8 C2EChangeWorldPropertiesAction
0042A9D8 C2ESetMetaRoomMusicAction
0042A9E8 C2ESetRoomMusicAction
```

The script also names each exact description getter and the unique Redo/Undo
implementations indicated by vtable slot position.
