# Ghidra recovery scripts

These scripts recover and refine the **Creatures Map Editor 1.08** Ghidra database.

## Execution order

Run them in the numbered directory order below on a fresh target database. The scripts are cumulative: later passes depend on structures, names and types created by earlier passes. Some later passes deliberately correct medium-confidence names from earlier archaeology, so a database is only considered current after the full chain has run.

| Order | Script | Revision represented here | Purpose |
|---:|---|---|---|
| 01 | `RecoverC2EMapEditorMFC.java` | v1.1 | Recover MFC runtime classes, message maps and handler names |
| 02 | `RecoverC2EMapEditorClassLayout.java` | v1.2 | Recover View/tool layouts, safe UI prefixes, and exact-size opaque MFC class layouts |
| 03 | `RefineC2EMapEditorStructures.java` | v1 | Initial semantic structure refinement |
| 04 | `ApplyC2EMapEditorTypes.java` | v1.4 | Apply typed members and proven custom-storage `this` parameters |
| 05 | `RefineC2EMapEditorSemanticsV2.java` | v2 | Refine selected-room/history/tool semantics |
| 06 | `RefineC2EMapEditorDocumentIO.java` | v1 | Recover `.2er` document I/O and property/CA serialization |
| 07 | `RefineC2EMapEditorWorldModel.java` | v1 | Recover WorldModel, MetaRoom and Room layouts |
| 08 | `RefineC2EMapEditorOperations.java` | v1 | Recover add/remove/find operations and cache invalidation |
| 09 | `RefineC2EMapEditorDerivedGeometry.java` | v1 | Recover generated boundary caches and door geometry |
| 10 | `RefineC2EMapEditorGeometrySemantics.java` | v1.1 | Recover edge enum, perimeter cache and geometry helpers |
| 11 | `RefineC2EMapEditorEditingGeometry.java` | v1 | Recover drag masks, snapping and editing geometry |
| 12 | `RecoverC2EMapEditorEditActions.java` | v1 | Recover Undo/Redo framework and action vtables |
| 13 | `RefineC2EMapEditorConcreteActions.java` | v1.1 | Recover concrete action layouts and document histories |
| 14 | `RefineC2EMapEditorRemainingActions.java` | v1 | Recover Remove Room, Add Room corners and boundary selection |
| 15 | `RefineC2EMapEditorCAOSWorkflows.java` | v1 | Recover World/Addon CAOS generation |
| 16 | `RefineC2EMapEditorGameIPC.java` | v1 | Recover Creature Labs `ClientSide` IPC and Open From Game |
| 17 | `RefineC2EMapEditorLiveSyncCA.java` | v1 | Recover live CA sync, Cheese simulation and correct Room CA state |
| 18 | `RefineC2EMapEditorValidation.java` | v1 | Recover validation rules and internal/external door terminology |
| 19 | `RefineC2EMapEditorHeightAndFloorTools.java` | v1 | Recover Check Heights, Floor/Ceiling calculator and selected-boundary representation |
| 20 | `ApplyC2EMapEditorMfcUiThisTypes.java` | v1.1 | Apply recovered UI layout types to all MFC app, frame and dialog member functions |
| 21 | `RecoverC2EMapEditorDialogDdx.java` | v1.1 | Recover dialog DoDataExchange overrides and type only exact-overload-proven UI members |
| 22 | `RefineC2EMapEditorCAOSOutputAPI.java` | v1 | Complete the high-fanout CAOS output dispatch API without speculative variadic typing |
| 23 | `ApplyC2EMapEditorCoreThisTypes.java` | v1.1 | Broadly reconcile existing core member signatures with recovered layouts, including the base edit-action API |
| 24 | `RefineC2EMapEditorResidualModelState.java` | v1 | Trace and annotate direct provenance for the remaining neutral Room/MetaRoom fields |
| 25 | `RefineC2EMapEditorGameImportSemantics.java` | v1 | Validate and recover the World/MetaRoom/Room live-game import adapter |
| 26 | `RefineC2EMapEditorGameImportParameters.java` | v1 | Recover the proven ERID-loop room-ID import parameter without naming ambiguous response helpers |
| 27 | `RefineC2EMapEditorGameImportResponse.java` | v1 | Recover the proven RTYP/RLOC response parameter consumed by the Room importer |
| 28 | `RefineC2EMapEditorCAOSResponseBuffer.java` | v1 | Prove and type the live IPC response buffer only where current MapEditor dataflow supports it |
| 29 | `RefineC2EMapEditorBridgeResponse.java` | v1 | Recover the per-room bridge formal that forwards the live-game response into the Room importer |
| 30 | `RefineC2EMapEditorCAOSOutputResponseReturn.java` | v1 | Recover the opaque response-pointer return of the CAOS output wrapper before concrete buffer typing |
| 31 | `RefineC2EMapEditorGameImportCStringResponse.java` | v1 | Propagate the exact recovered `FormatAndRun` `CString *` response through the live-game Room-import path |

## Layout

The Java filenames are intentionally **not** prefixed with order numbers. Each file contains a public Java class and Java/Ghidra requires the filename to match that class name. Numbered parent directories provide ordering without breaking compilation, for example:

```text
ghidra-scripts/
  01-mfc-recovery/RecoverC2EMapEditorMFC.java
  02-class-layout/RecoverC2EMapEditorClassLayout.java
  ...
  18-validation/RefineC2EMapEditorValidation.java
  19-height-floor-tools/RefineC2EMapEditorHeightAndFloorTools.java
```

The corresponding reverse-engineering notes live in [`docs/recovery-notes/`](../docs/recovery-notes/).

## Target

These scripts are target-specific to the Creatures Map Editor 1.08 Win32 executable used by this project. Most passes include exact-address/byte guards and should refuse to run on a mismatched binary.
