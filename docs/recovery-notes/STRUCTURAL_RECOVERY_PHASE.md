# Creatures Map Editor 1.08 — structural recovery phase

## Scope

This note records the evidence-backed structural recovery completed after the
main model, geometry, action, CAOS and live-game passes. It corresponds to
Ghidra passes **20–23**.

The purpose of this phase was decompiler clarity, not speculative source
reconstruction. The passes use only:

- an exact MFC runtime size where one is available;
- member offsets directly observed in decompiler P-code;
- MFC `DDX_*` calls with dialog-unique resource IDs; or
- an already recovered member-function owner and layout.

They do not rename generic runtime/container helpers, infer unseen object
tails, replace user-owned signatures, or add semantic comments to unproven
code.

## Recovered MFC/UI layouts

Pass 02 scans the recovered MFC member handlers and follows `ECX` through
P-code copies, casts and pointer arithmetic. A class with observed member
access but no exact runtime size is materialized as:

```text
/C2E/RecoveredClasses/<Class>_observed_prefix
```

The prefix ends at the highest directly observed member. It is intentionally
not a claim about the remainder of the MFC object.

Where an exact `CRuntimeClass` size is present but no members are observed, the
database instead receives an opaque flat layout:

```text
/C2E/RecoveredClasses/<Class>_flat_layout
```

The important case is:

```text
CChildFrame_flat_layout [0xC8]
```

This preserves the exact object extent without pretending to know private MFC
base-class fields.

Other recovered UI owners use bounded observed prefixes, except
`CFloorCeilingDlg`, whose stronger layout was recovered in the height/floor
pass and remains:

```text
/C2E/Refined/CFloorCeilingDlg_refined
```

## UI member receivers

Pass 20 applies an explicit custom-storage `__thiscall` receiver in `ECX` to
the recovered App, frame and dialog methods. The receiver points to the
appropriate exact flat layout or observed prefix.

Affected owner families include:

```text
CC2ERoomEditorApp
CMainFrame
CChildFrame
CMetaroomBackgroundDlg
CBackgroundFileDlg
CCAPropertiesDlg
CFloorCeilingDlg
CPropertyTypesDlg
CPropertyTypeDlg
CPropertiesDlg
CSwitchMetaroomDlg
CTipDlg
```

This is a type-only refinement. It leaves formal arguments, returns, labels,
data and manual signatures intact.

## Dialog DDX recovery

Pass 21 detects `DoDataExchange` overrides from actual `DDX_*`/`DDV_*` calls.
It assigns dialog ownership only if the referenced resource ID belongs to one
recovered dialog uniquely.

The resulting overrides are:

```text
00425CE0 CBackgroundFileDlg_DoDataExchange
004195C0 CPropertyTypeDlg_DoDataExchange
0041E980 CPropertyTypesDlg_DoDataExchange
```

The pre-existing Floor/Ceiling and Height Check DDX overrides remain part of
their pass-19 recovery.

Member refinement is intentionally overload-aware:

- `DDX_Check`, `DDX_CBIndex` and integer validation prove an `int` field;
- `DDX_Text(..., CString *)` proves a `VC6CString` field;
- `DDX_Text(..., int *)` proves an `int` field;
- `DDX_Control` proves a binding/name, but not a concrete MFC control subtype.

For example, the Background File dialog's `GeneratePreview` control binding
and the integer-backed `Enumerated` and `PropertyTypeList` DDX fields are
evidenced by their resource IDs and helpers. Unambiguous ownership is required
before any field is changed.

## CAOS output completion

The live-game IPC note documents the `C2ECAOSOutput` dual sink. Pass 22
completed its named API:

```text
00414790 C2ECAOSOutput_ctor
004148A0 C2ECAOSOutput_dtor
00414900 C2ECAOSOutput_FormatAndRun
00414980 C2ECAOSOutput_Run
```

`00414900` is the high-fanout printf-style wrapper used to format CAOS and
dispatch it through the same output object. Its object receiver is now
explicitly `C2ECAOSOutput *` in `ECX`; the vararg tail remains unforced.

## Core member-signature reconciliation

Pass 23 performs an idempotent sweep over previously named core methods. It
uses the already recovered `/C2E/Refined` layout matching each function-name
owner prefix and applies an explicit `ECX this` receiver only when missing.

It covers the document/view, WorldModel, MetaRoom, Room, RoomGeometry, tools,
edit-action hierarchy, concrete action classes, `C2ECAOSOutput`, and
`C2EClientSide`.

The final v1.1 sweep supplied the two missed concrete receivers:

```text
004010E0 C2ESetMetaRoomMusicAction_GetDescriptionStringId
004039F0 C2ESetMetaRoomMusicAction_RedoSwapTrack
```

Both now use:

```text
C2ESetMetaRoomMusicAction *this  // ECX
```

The complete sweep saw 237 named core members: 235 already had the correct
receiver and two needed this final application. No user-owned signatures were
replaced.

## Boundary before semantic recovery

The remaining high-fanout default-named functions are principally shared
runtime/container helpers. Call count and caller names do not prove their
semantics, so this phase intentionally leaves them unnamed.

The next phase should recover such helpers from their decompiled bodies and
cross-references, then apply annotations in coherent families. This keeps the
database's names and types evidence-backed rather than turning generic support
code into misleading editor-specific symbols.
