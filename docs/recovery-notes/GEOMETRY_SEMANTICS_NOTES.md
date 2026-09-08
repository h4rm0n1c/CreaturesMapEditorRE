# Creatures Map Editor 1.08 — geometry semantics

## Edge-code enum

The compact editor's boundary edge code is now fully proven from
`C2ERoomGeometry_FindSharedBoundarySegment`:

```text
C2EEditorEdge

0 LEFT
1 CEILING
2 FLOOR
3 RIGHT
```

This is **not** the numeric ordering used by Docking Station's runtime
`DIRECTION_*` constants.

### Proof

`LEFT = 0`

The code tests:

```text
this.xLeft == other.xRight
```

and requires positive overlap of the two vertical Y intervals.

`RIGHT = 3`

The code tests:

```text
this.xRight == other.xLeft
```

and requires the same positive Y overlap.

`CEILING = 1`

The room X intervals must overlap, then the editor compares:

```text
this.ceiling(x) == other.floor(x)
```

at both ends of the overlap.

`FLOOR = 2`

The inverse comparison is used:

```text
this.floor(x) == other.ceiling(x)
```

The cache builder's parent-order reversal uses:

```text
oppositeEdge = 3 - edge
```

which produces exactly:

```text
LEFT    <-> RIGHT
CEILING <-> FLOOR
```

The wall builder independently seeds complete room edges in this same order.

## Room perimeter cache

Previous passes conservatively named Room `+0x24`:

```text
derivedGeometryCache
```

The exact routine at `0041BC50` proves it is:

```text
float perimeterLength
```

When negative, it computes:

```text
ceilingLength =
    hypot(xRight-xLeft,
          yRightCeiling-yLeftCeiling)

floorLength =
    hypot(xRight-xLeft,
          yRightFloor-yLeftFloor)

leftWallLength =
    yLeftFloor-yLeftCeiling

rightWallLength =
    yRightFloor-yRightCeiling

perimeterLength =
    ceilingLength +
    floorLength +
    leftWallLength +
    rightWallLength
```

Geometry-mutating helpers write IEEE float `-1.0f` (`0xBF800000`) to invalidate
the cache.

The Docking Station runtime source independently has `Room::perimeterLength`
and calculates the same conceptual quantity, making this a high-confidence
semantic promotion rather than a source-only guess.

## Recovered helpers

```text
004210F0 C2EGeometry_ComputeLineEquation

0041AC10 C2ERoomGeometry_GetCeilingLineEquation
0041AC50 C2ERoomGeometry_GetFloorLineEquation

0041AD70 C2EGeometry_IntersectIntervalsStrict

0041BAA0 C2ERoomGeometry_ContainsPoint

0041B1B0 C2ERoomGeometry_SetFloorFromLine
0041B210 C2ERoomGeometry_SetCeilingFromLine

0041B270 C2ERoomGeometry_ClampToBounds

0041B2D0 C2ERoomGeometry_FindSharedBoundarySegment

0041BC50 C2ERoomGeometry_GetPerimeterLength
0041BCF0 C2ERoomGeometry_GetCentrePoint
```

Thin editor-room wrappers:

```text
0041A4D0 C2EEditorRoom_ContainsPoint
0041A540 C2EEditorRoom_SetFloorFromLine
0041A560 C2EEditorRoom_SetCeilingFromLine
0041A5B0 C2EEditorRoom_TranslateVertices
```

## Point containment

`C2ERoomGeometry_ContainsPoint`:

1. builds a bounding rectangle from `xLeft`, `xRight`, the minimum ceiling Y
   and maximum floor Y;
2. optionally inflates that rectangle by the supplied margin;
3. rejects points outside it;
4. evaluates the ceiling line at point X;
5. requires point Y to be on/below that ceiling;
6. evaluates the floor line at point X;
7. requires point Y to be on/above that floor.

This is the compact-editor analogue of the DS engine's
`Map::IsPointInsideRoom`.

## Bounds clamping

`C2ERoomGeometry_ClampToBounds` clamps room geometry into the metaroom's
integer rectangle:

```text
xLeft        >= bounds.left
xRight       <  bounds.right

yLeftCeiling  >= bounds.top
yRightCeiling >= bounds.top

yLeftFloor    < bounds.bottom
yRightFloor   < bounds.bottom
```

Right and bottom are treated as exclusive, hence the `-1`.

## Source-reference caution

Docking Station source remains useful for semantic names (`perimeterLength`,
point-inside-room concepts, Room geometry), but the exact editor enum ordering
came from the MapEditor binary and differs from the runtime constants.

That is a concrete example of why the source tree is being used as a semantic
dictionary rather than imported wholesale.
