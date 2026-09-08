# Ghidra recovery scripts

These scripts recover and refine the **Creatures Map Editor 1.08** Ghidra database.

## Execution order

Run them in the numbered directory order below on a fresh target database. The scripts are cumulative: later passes depend on structures, names and types created by earlier passes. Some later passes deliberately correct medium-confidence names from earlier archaeology, so a database is only considered current after the full chain has run.

| Order | Script | Revision represented here | Purpose |
|---:|---|---|---|
| 01 | `RecoverC2EMapEditorMFC.java` | v1.1 | Recover MFC runtime classes, message maps and handler names |
| 02 | `RecoverC2EMapEditorClassLayout.java` | v1 | Recover View/tool layouts and tool vtables |
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
| 14 | `RefineC2EMapEditorRemainingActions.java` | v1 | Recover Remove Room, Add Room corners and door selection |
| 15 | `RefineC2EMapEditorCAOSWorkflows.java` | v1 | Recover World/Addon CAOS generation |
| 16 | `RefineC2EMapEditorGameIPC.java` | v1 | Recover Creature Labs `ClientSide` IPC and Open From Game |
| 17 | `RefineC2EMapEditorLiveSyncCA.java` | v1 | Recover live CA sync, Cheese simulation and correct Room CA state |
| 18 | `RefineC2EMapEditorValidation.java` | v1 | Recover validation rules and internal/external door terminology |

## Layout

The Java filenames are intentionally **not** prefixed with order numbers. Each file contains a public Java class and Java/Ghidra requires the filename to match that class name. Numbered parent directories provide ordering without breaking compilation, for example:

```text
ghidra-scripts/
  01-mfc-recovery/RecoverC2EMapEditorMFC.java
  02-class-layout/RecoverC2EMapEditorClassLayout.java
  ...
  18-validation/RefineC2EMapEditorValidation.java
```

The corresponding reverse-engineering notes live in [`docs/recovery-notes/`](../docs/recovery-notes/).

## Target

These scripts are target-specific to the Creatures Map Editor 1.08 Win32 executable used by this project. Most passes include exact-address/byte guards and should refuse to run on a mismatched binary.
