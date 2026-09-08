# Creatures Map Editor 1.08 — Height Check and Floor/Ceiling tools

## Scope

This is recovery pass **19**.

It covers:

- `Check Heights...` (command ID 32847);
- `Floor/Ceiling Values...` (command ID 32835);
- the two associated dialogs;
- the WorldModel / MetaRoom height-check worker;
- a correction to the View's second selection container.

## Selection correction: `View +0x164`

Earlier passes first called this field `selectedRoomRefs`, then refined it to
`selectedDoorPairs`.

The latter was closer semantically, but the actual stored value is now proven.

```text
CC2ERoomEditorView +0x164
    C2ESelectedBoundarySegmentSet selectedBoundarySegments [0x14]
```

Each set/tree node contains:

```text
C2EEditorBoundarySegmentRef [0x08]

+00 C2EEditorBoundarySegment *segment
+04 uint32 *refCount
```

### Direct proof

`CC2ERoomEditorView_OnFloorCeilingValues` takes the sole selected item and:

1. obtains `segment*` and `refCount*` from the selected tree node;
2. increments the reference count;
3. reads:

```text
segment +0x14 start
segment +0x1C end
```

and passes those points to `C2EGeometry_ComputeLineEquation`.

This is only possible if the selected object is a boundary segment reference.

### Why Set Door Opening looked like a pair set

`C2ESetDoorOpeningAction_ctor` iterates the same selected collection.

It obtains the selected `segment*` and immediately reads:

```text
segment +0x00 roomId1
segment +0x04 roomId2
```

to build its compact action snapshots.

So the earlier `{roomId1,roomId2}` interpretation described what that
constructor **consumed**, not what the View actually stored.

The corrected View field is therefore:

```text
selectedBoundarySegments
```

The old `C2ESelectedDoorPairSet` and `C2ESelectedRoomRefSet` types are retained
only as deprecated archaeological types.

---

# Height Check

## UI resource

Dialog resource:

```text
ID 157 / 0x9D
Title: "Height Check"
```

Exact controls:

```text
Static: "Minimum &Permability"
Edit:   0x413

Static: "Minimum &Height"
Edit:   0x414

OK
Cancel
```

The `Permability` spelling is the typo present in the original executable.

## Dialog class layout

Constructor:

```text
00414DE0 CHeightCheckDlg_ctor
```

Derived fields:

```text
CHeightCheckDlg [0x68]

+00..+5F opaque MFC42 CDialog base

+60 int minimumHeight
+64 int minimumPermeability
```

Both values initialize to zero.

DDX:

```text
00414E10 CHeightCheckDlg_DoDataExchange
```

Exact binding/ranges:

```text
control 0x414 -> minimumHeight
                   range 0..500

control 0x413 -> minimumPermeability
                   range 0..100
```

## Call chain

```text
00411BA0 CC2ERoomEditorView_OnCheckHeights
    |
    +--> 0040B900 CC2ERoomEditorDoc_CheckHeights
            |
            +--> 00425290 C2EWorldModel_CheckHeights
                    |
                    +--> 00417E50 C2EEditorMetaRoom_CheckHeights
```

The View clears its current selection before the scan.

The argument order reaching the WorldModel is:

```text
minimumPermeability
minimumHeight
selectedRoomIds
```

The WorldModel supplies each metaroom with two more inputs:

```text
internalDoorSegments
externalDoorSegments
```

so the MetaRoom worker receives five arguments in total.

## What the height scan does

The binary supports the following description directly.

For each Room, the scan first decides whether it has a qualifying
floor-side boundary.

### Internal boundary candidate

For an internal boundary, the Room must be on the floor side of the generated
boundary and:

```text
segment.permeability < minimumPermeability
```

### External boundary candidate

An external boundary with:

```text
segment.edge == FLOOR
```

also qualifies.

Only Rooms passing this first stage continue through the expensive geometry
scan.

## Geometry comparison

The worker then scans qualifying horizontal boundary pieces:

```text
edge == CEILING
or
edge == FLOOR
```

Internal segments must again satisfy:

```text
permeability < minimumPermeability
```

The segment's X interval must overlap the current Room's X interval.

For each overlap it builds:

1. the current Room's **floor** line from:

```text
xLeft, yLeftFloor
xRight, yRightFloor
```

2. the candidate boundary line from:

```text
segment.start
segment.end
```

using the already-recovered:

```text
004210F0 C2EGeometry_ComputeLineEquation
```

The two lines are evaluated at the overlap endpoints.

## Exact height threshold

The binary contains double:

```text
0042C1D8 = 1.0
```

The height scan uses it as a lower cutoff.

A candidate endpoint separation must be:

```text
> 1.0
```

before it is compared to the user's minimum height.

A Room is marked when a positive endpoint separation satisfies:

```text
1.0 < separation <= minimumHeight
```

When the condition is met, the current Room ID is inserted into:

```text
View.selectedRoomIds
```

The UI therefore reports the result by selecting/highlighting rooms rather than
by throwing the validation exception used by the normal `Validate` command.

This pass deliberately describes the tested line separation rather than
inventing a higher-level creature-navigation rule not present in the binary.

---

# Floor/Ceiling Values

## UI resource

Dialog resource:

```text
ID 152 / 0x98
Title: "Floor/Ceiling values"
```

Controls:

```text
&X          edit 0x40F
&Y          edit 0x411
Calculate   button 0x410
Close
```

The Y edit control is read-only in the dialog resource.

## Enable rule

```text
00411710
CC2ERoomEditorView_OnUpdateFloorCeilingValues
```

The command is enabled only when:

```text
selectedBoundarySegments.count == 1
```

and the selected segment's edge is exactly:

```text
C2EEditorEdge::CEILING == 1
or
C2EEditorEdge::FLOOR   == 2
```

So it is explicitly a horizontal/sloped floor-or-ceiling line tool.

## Dialog layout

```text
CFloorCeilingDlg_refined [0xE8]

+00..+5F opaque CDialog base

+60 float lineSlope
+64 float lineIntercept

+68 CEdit-like Y result control [0x40]
+A8 CEdit-like X input control  [0x40]
```

Constructor:

```text
00414620 CFloorCeilingDlg_ctor
```

DDX:

```text
004146C0 CFloorCeilingDlg_DoDataExchange
```

Control mapping:

```text
+68 -> control 0x411 -> Y
+A8 -> control 0x40F -> X
```

## View preparation

`CC2ERoomEditorView_OnFloorCeilingValues`:

1. copies the selected boundary reference;
2. calls:

```text
C2EGeometry_ComputeLineEquation(
    segment.start,
    segment.end)
```

3. converts the resulting slope/intercept from doubles to floats;
4. stores them in:

```text
dialog +0x60 lineSlope
dialog +0x64 lineIntercept
```

5. opens the dialog.

## Calculate

Existing MFC recovery had already identified:

```text
00414700 CFloorCeilingDlg_OnCalculate
```

Its actual formula is now explicit:

```text
X = atof(xEdit.text)

Y = lineSlope * X + lineIntercept
```

The result is formatted with:

```text
"%.6f"
```

and written to the Y edit control.

No WorldModel, Room or action object is modified.

Therefore `Floor/Ceiling Values` is a **coordinate calculator for the selected
floor/ceiling line**, not a geometry-edit command.

---

# Recovered names in this pass

```text
00414DE0 CHeightCheckDlg_ctor
00414E10 CHeightCheckDlg_DoDataExchange

0040B900 CC2ERoomEditorDoc_CheckHeights
00425290 C2EWorldModel_CheckHeights
00417E50 C2EEditorMetaRoom_CheckHeights

00414620 CFloorCeilingDlg_ctor
004146C0 CFloorCeilingDlg_DoDataExchange
00414700 CFloorCeilingDlg_OnCalculate   (existing name, now typed)
```

Existing MFC names retained and typed:

```text
00411BA0 CC2ERoomEditorView_OnCheckHeights
004115B0 CC2ERoomEditorView_OnFloorCeilingValues
00411710 CC2ERoomEditorView_OnUpdateFloorCeilingValues
```
