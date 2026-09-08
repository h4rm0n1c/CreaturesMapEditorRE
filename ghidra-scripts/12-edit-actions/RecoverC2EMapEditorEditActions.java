// Creatures Map Editor 1.08 — edit action / undo-redo recovery.
//
// Prerequisites:
//   all previous recovery passes through
//   RefineC2EMapEditorEditingGeometry.java
//
// Exact binary + resource findings:
//
// C2EEditAction virtual contract (4 slots):
//   +00 deleting destructor
//   +04 Redo(document)
//   +08 Undo(document)
//   +0c GetDescriptionStringId()
//
// CC2ERoomEditorDoc_OnRedo calls slot +04.
// CC2ERoomEditorDoc_OnUndo calls slot +08.
//
// Document:
//   +0x0b8 C2EActionHistory undoHistory [0x30]
//   +0x0e8 C2EActionHistory redoHistory [0x30]
//
// Action descriptions are exact STRINGTABLE resources:
//   0x82 No Action
//   0x83 Add Room
//   0x84 Add Metaroom
//   0x85 Remove Room
//   0x86 Remove Metaroom
//   0x8b Move Metaroom
//   0x8c Set Room Property
//   0x8d Move Room
//   0x8e Set Door Opening
//   0x8f Set Metaroom Background
//   0x90 Add Metaroom Background
//   0x91 Remove Metaroom Background
//   0x93 Change Metaroom Background
//   0x94 Change World Properties
//   0x98 Set Metaroom Music
//   0x99 Set Room Music
//
// Move Room action:
//   object size 0x14
//   +00 vftable
//   +04 vector<C2EMoveRoomSnapshot>
// snapshot size 0x20:
//   +00 roomId
//   +04 C2ERoomGeometry[0x1c]
//
// @category C2E Map Editor
// @author OpenAI

import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;

public class RecoverC2EMapEditorEditActions extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;
    private Structure docType;
    private Structure geometryType;

    private Structure actionVtableType;
    private Structure actionBaseType;
    private Structure actionRefType;
    private Structure actionIteratorType;
    private Structure actionHistoryType;
    private Structure moveRoomSnapshotType;
    private Structure moveRoomSnapshotVectorType;
    private Structure moveRoomActionType;

    private Pointer docPtr;
    private Pointer actionBasePtr;
    private Pointer moveRoomActionPtr;

    private int fieldsRefined;
    private int fieldsPreserved;
    private int functionsCreated;
    private int functionsRenamed;
    private int functionsKept;
    private int thisTypesApplied;
    private int thisTypesSkipped;
    private int returnTypesApplied;
    private int labelsCreated;

    private static class VTableSpec {
        final long address;
        final String className;
        final int stringId;
        final String description;

        VTableSpec(long address, String className, int stringId, String description) {
            this.address = address;
            this.className = className;
            this.stringId = stringId;
            this.description = description;
        }
    }

    private static class FuncSpec {
        final long address;
        final String name;
        final String owner;
        final String comment;
        final String returnKind;
        final String[] replaceNames;

        FuncSpec(long address, String name, String owner,
                 String comment, String returnKind, String... replaceNames) {
            this.address = address;
            this.name = name;
            this.owner = owner;
            this.comment = comment;
            this.returnKind = returnKind;
            this.replaceNames = replaceNames;
        }
    }

    private final VTableSpec[] VTABLES = new VTableSpec[] {
        new VTableSpec(0x42a8f8L, "C2EEditActionBase", 0x82, "No Action"),
        new VTableSpec(0x42a908L, "C2EAddMetaRoomAction", 0x84, "Add Metaroom"),
        new VTableSpec(0x42a918L, "C2EAddRoomAction", 0x83, "Add Room"),
        new VTableSpec(0x42a928L, "C2ERemoveRoomAction", 0x85, "Remove Room"),
        new VTableSpec(0x42a938L, "C2EMoveRoomAction", 0x8d, "Move Room"),
        new VTableSpec(0x42a948L, "C2ESetRoomPropertyAction", 0x8c, "Set Room Property"),
        new VTableSpec(0x42a958L, "C2ESetDoorOpeningAction", 0x8e, "Set Door Opening"),
        new VTableSpec(0x42a968L, "C2ERemoveMetaRoomAction", 0x86, "Remove Metaroom"),
        new VTableSpec(0x42a978L, "C2EMoveMetaRoomAction", 0x8b, "Move Metaroom"),
        new VTableSpec(0x42a988L, "C2ESetMetaRoomBackgroundAction", 0x8f, "Set Metaroom Background"),
        new VTableSpec(0x42a998L, "C2EAddMetaRoomBackgroundAction", 0x90, "Add Metaroom Background"),
        new VTableSpec(0x42a9a8L, "C2ERemoveMetaRoomBackgroundAction", 0x91, "Remove Metaroom Background"),
        new VTableSpec(0x42a9b8L, "C2EChangeMetaRoomBackgroundAction", 0x93, "Change Metaroom Background"),
        new VTableSpec(0x42a9c8L, "C2EChangeWorldPropertiesAction", 0x94, "Change World Properties"),
        new VTableSpec(0x42a9d8L, "C2ESetMetaRoomMusicAction", 0x98, "Set Metaroom Music"),
        new VTableSpec(0x42a9e8L, "C2ESetRoomMusicAction", 0x99, "Set Room Music")
    };

    private final FuncSpec[] FUNCTIONS = new FuncSpec[] {

        // Document action manager
        new FuncSpec(
            0x408980L,
            "CC2ERoomEditorDoc_ExecuteEditAction",
            "doc",
            "Executes a newly submitted edit action by calling action->Redo(this). On success pushes it to undoHistory, clears redoHistory, sets the document modified flag and updates views/UI.",
            "bool"),

        new FuncSpec(
            0x4090a0L,
            "CC2ERoomEditorDoc_OnUndo",
            "doc",
            "Undo command handler. Pops the newest action from undoHistory, calls action->Undo(this), pushes the action to redoHistory, then updates views/UI.",
            "void"),

        // Base/default action methods
        new FuncSpec(
            0x401110L,
            "C2EEditAction_NoOpReturnFalse",
            "none",
            "Base C2EEditAction Redo/Undo implementation; returns false.",
            "bool"),

        new FuncSpec(
            0x401bc0L,
            "C2EEditAction_UndoViaRedo",
            "action",
            "Default Undo implementation for self-inverting actions. Calls this->Redo(document) through vtable slot +0x04.",
            "bool"),

        // Move Room: ctor + self-swapping apply
        new FuncSpec(
            0x4017c0L,
            "C2EMoveRoomAction_ctor",
            "move",
            "Constructs a Move Room edit action. Builds one C2EMoveRoomSnapshot per selected room using room ID + complete geometry, computes the moved/snapped target geometry from delta/drag mask/world/tolerance, and stores those target snapshots.",
            "movePtr"),

        new FuncSpec(
            0x401a20L,
            "C2EMoveRoomAction_RedoSwapGeometry",
            "move",
            "Redo/apply implementation. For each snapshot, swaps snapshot.geometry with the live room's geometry. Because the saved geometry is replaced with the previous live geometry, the same operation is self-inverting and supports Undo through C2EEditAction_UndoViaRedo.",
            "bool"),

        // Exact description getters from STRINGTABLE IDs
        new FuncSpec(0x401000L, "C2EEditActionBase_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x82: \"No Action\".", "int"),
        new FuncSpec(0x401010L, "C2EAddMetaRoomAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x84: \"Add Metaroom\".", "int"),
        new FuncSpec(0x401020L, "C2ERemoveMetaRoomAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x86: \"Remove Metaroom\".", "int"),
        new FuncSpec(0x401030L, "C2EMoveMetaRoomAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x8B: \"Move Metaroom\".", "int"),
        new FuncSpec(0x401040L, "C2EAddRoomAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x83: \"Add Room\".", "int"),
        new FuncSpec(0x401050L, "C2ESetRoomPropertyAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x8C: \"Set Room Property\".", "int"),
        new FuncSpec(0x401060L, "C2EMoveRoomAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x8D: \"Move Room\".", "int"),
        new FuncSpec(0x401070L, "C2ERemoveRoomAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x85: \"Remove Room\".", "int"),
        new FuncSpec(0x401080L, "C2ESetDoorOpeningAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x8E: \"Set Door Opening\".", "int"),
        new FuncSpec(0x401090L, "C2ESetMetaRoomBackgroundAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x8F: \"Set Metaroom Background\".", "int"),
        new FuncSpec(0x4010a0L, "C2EAddMetaRoomBackgroundAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x90: \"Add Metaroom Background\".", "int"),
        new FuncSpec(0x4010b0L, "C2ERemoveMetaRoomBackgroundAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x91: \"Remove Metaroom Background\".", "int"),
        new FuncSpec(0x4010c0L, "C2EChangeMetaRoomBackgroundAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x93: \"Change Metaroom Background\".", "int"),
        new FuncSpec(0x4010d0L, "C2EChangeWorldPropertiesAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x94: \"Change World Properties\".", "int"),
        new FuncSpec(0x4010e0L, "C2ESetMetaRoomMusicAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x98: \"Set Metaroom Music\".", "int"),
        new FuncSpec(0x4010f0L, "C2ESetRoomMusicAction_GetDescriptionStringId", "none",
            "Returns STRINGTABLE 0x99: \"Set Room Music\".", "int"),

        // Exact Redo/Undo methods from vtable slot position.
        new FuncSpec(0x401220L, "C2EAddMetaRoomAction_Redo", "none",
            "C2EAddMetaRoomAction vtable slot +0x04 (Redo).", "bool"),
        new FuncSpec(0x401250L, "C2EAddMetaRoomAction_Undo", "none",
            "C2EAddMetaRoomAction vtable slot +0x08 (Undo).", "bool"),

        new FuncSpec(0x4012e0L, "C2EAddRoomAction_Redo", "none",
            "C2EAddRoomAction vtable slot +0x04 (Redo).", "bool"),
        new FuncSpec(0x401310L, "C2EAddRoomAction_Undo", "none",
            "C2EAddRoomAction vtable slot +0x08 (Undo).", "bool"),

        new FuncSpec(0x401600L, "C2ERemoveRoomAction_Redo", "none",
            "C2ERemoveRoomAction vtable slot +0x04 (Redo).", "bool"),
        new FuncSpec(0x401780L, "C2ERemoveRoomAction_Undo", "none",
            "C2ERemoveRoomAction vtable slot +0x08 (Undo).", "bool"),

        new FuncSpec(0x401e60L, "C2ESetRoomPropertyAction_RedoSwapValue", "none",
            "Set Room Property action vtable slot +0x04. Swaps the stored property value with the live value; default Undo calls this same operation.", "bool"),

        new FuncSpec(0x4022d0L, "C2ESetDoorOpeningAction_RedoSwapPermeability", "none",
            "Set Door Opening action vtable slot +0x04. Swaps stored and live permeability for each affected room-pair door; default Undo reuses the same operation.", "bool"),

        new FuncSpec(0x4025b0L, "C2ERemoveMetaRoomAction_Redo", "none",
            "Remove Metaroom action vtable slot +0x04 (Redo).", "bool"),
        new FuncSpec(0x402770L, "C2ERemoveMetaRoomAction_Undo", "none",
            "Remove Metaroom action vtable slot +0x08 (Undo).", "bool"),

        new FuncSpec(0x402800L, "C2EMoveMetaRoomAction_RedoSwapBounds", "none",
            "Move Metaroom action vtable slot +0x04; default Undo reuses the self-inverting operation.", "bool"),

        new FuncSpec(0x402b30L, "C2ESetMetaRoomBackgroundAction_RedoSwapBackground", "none",
            "Set Metaroom Background action vtable slot +0x04; default Undo reuses the self-inverting operation.", "bool"),

        new FuncSpec(0x402dd0L, "C2EAddMetaRoomBackgroundAction_Redo", "none",
            "Add Metaroom Background action vtable slot +0x04 (Redo).", "bool"),
        new FuncSpec(0x402f60L, "C2EAddMetaRoomBackgroundAction_Undo", "none",
            "Add Metaroom Background action vtable slot +0x08 (Undo).", "bool"),

        new FuncSpec(0x4031f0L, "C2ERemoveMetaRoomBackgroundAction_Redo", "none",
            "Remove Metaroom Background action vtable slot +0x04 (Redo).", "bool"),
        new FuncSpec(0x4033c0L, "C2ERemoveMetaRoomBackgroundAction_Undo", "none",
            "Remove Metaroom Background action vtable slot +0x08 (Undo).", "bool"),

        new FuncSpec(0x403620L, "C2EChangeMetaRoomBackgroundAction_RedoSwapBackground", "none",
            "Change Metaroom Background action vtable slot +0x04; default Undo reuses the self-inverting operation.", "bool"),

        new FuncSpec(0x403870L, "C2EChangeWorldPropertiesAction_RedoSwapProperties", "none",
            "Change World Properties action vtable slot +0x04; default Undo reuses the self-inverting operation.", "bool"),

        new FuncSpec(0x4039f0L, "C2ESetMetaRoomMusicAction_RedoSwapTrack", "none",
            "Set Metaroom Music action vtable slot +0x04; default Undo reuses the self-inverting operation.", "bool"),

        new FuncSpec(0x403db0L, "C2ESetRoomMusicAction_RedoSwapTrack", "none",
            "Set Room Music action vtable slot +0x04; default Undo reuses the self-inverting operation.", "bool")
    };

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the latest recovered MapEditor.exe database first.");
            return;
        }

        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This pass is deliberately limited to the 32-bit Map Editor target.");
            return;
        }

        // Exact target guards:
        // Move Room vtable [42A938] = 4022B0,401A20,401BC0,401060.
        if (getInt(toAddr(0x42a938L)) != 0x004022b0 ||
            getInt(toAddr(0x42a93cL)) != 0x00401a20 ||
            getInt(toAddr(0x42a940L)) != 0x00401bc0 ||
            getInt(toAddr(0x42a944L)) != 0x00401060) {

            popup("MapEditor 1.08 action-vtable identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        docType = requireStructure("CC2ERoomEditorDoc_refined");
        geometryType = requireStructure("C2ERoomGeometry");

        docPtr = new PointerDataType(docType, dtm);

        println("=== Creatures Map Editor 1.08 - edit action / undo-redo recovery ===");
        println("");

        buildActionTypes();
        correctDocumentHistoryLayout();
        labelVTables();

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(
                spec.address,
                spec.name,
                spec.replaceNames);

            if (f == null) continue;

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E edit actions] " + spec.comment);

            if ("doc".equals(spec.owner)) {
                applyThisType(f, docPtr, "CC2ERoomEditorDoc_refined");
            }
            else if ("action".equals(spec.owner)) {
                applyThisType(f, actionBasePtr, "C2EEditActionBase");
            }
            else if ("move".equals(spec.owner)) {
                applyThisType(f, moveRoomActionPtr, "C2EMoveRoomAction");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateDocumentFlow();
        annotateMoveRoomTransaction();
        annotateResourceEvidence();

        println("");
        println("Running analysis on edit-action changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Edit action summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("labels created:       " + labelsCreated);
        println("");
        println("Document:");
        println("  +0x0B8 undoHistory [0x30]");
        println("  +0x0E8 redoHistory [0x30]");
        println("");
        println("Action virtuals:");
        println("  +00 deleting destructor");
        println("  +04 Redo(document)");
        println("  +08 Undo(document)");
        println("  +0C GetDescriptionStringId()");
        println("");
        println("Move Room action snapshots are roomId + C2ERoomGeometry (0x20 bytes).");
    }

    // ---------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------

    private void buildActionTypes() {
        actionVtableType = getOrCreate("C2EEditActionVTable", 0x10);
        safeField(actionVtableType, 0x00, new PointerDataType(VoidDataType.dataType, dtm), 4,
            "DeletingDestructor", "Virtual deleting destructor.");
        safeField(actionVtableType, 0x04, new PointerDataType(VoidDataType.dataType, dtm), 4,
            "Redo", "Redo/apply action against CC2ERoomEditorDoc.");
        safeField(actionVtableType, 0x08, new PointerDataType(VoidDataType.dataType, dtm), 4,
            "Undo", "Undo action against CC2ERoomEditorDoc.");
        safeField(actionVtableType, 0x0c, new PointerDataType(VoidDataType.dataType, dtm), 4,
            "GetDescriptionStringId", "Returns a STRINGTABLE resource ID used for Undo/Redo menu text.");
        setDescriptionSafe(
            actionVtableType,
            "Four-slot Map Editor edit-action vtable. Slot semantics are proven by Document OnRedo/OnUndo and update-UI handlers.");

        actionBaseType = getOrCreate("C2EEditActionBase", 0x04);
        Pointer vtPtr = new PointerDataType(actionVtableType, dtm);
        safeField(actionBaseType, 0x00, vtPtr, 4,
            "vftable", "C2EEditAction virtual table.");
        setDescriptionSafe(
            actionBaseType,
            "Base polymorphic edit action used by the Map Editor Undo/Redo system.");

        actionBasePtr = new PointerDataType(actionBaseType, dtm);

        actionRefType = getOrCreate("C2EEditActionRef", 0x08);
        safeField(actionRefType, 0x00, actionBasePtr, 4,
            "action", "Edit-action object pointer.");
        safeField(actionRefType, 0x04, new PointerDataType(UnsignedIntegerDataType.dataType, dtm), 4,
            "refCount", "Reference-count pointer used by the editor's shared/reference wrapper.");
        setDescriptionSafe(
            actionRefType,
            "Eight-byte shared/reference wrapper stored in Undo/Redo histories.");

        actionIteratorType = getOrCreate("C2EActionDequeIterator", 0x10);
        safeField(actionIteratorType, 0x00, PointerDataType.dataType, 4,
            "blockBegin", "Beginning of current deque storage block.");
        safeField(actionIteratorType, 0x04, PointerDataType.dataType, 4,
            "blockEnd", "End of current deque storage block.");
        safeField(actionIteratorType, 0x08, PointerDataType.dataType, 4,
            "current", "Current 8-byte C2EEditActionRef element.");
        safeField(actionIteratorType, 0x0c, PointerDataType.dataType, 4,
            "mapSlot", "Pointer into deque block map.");
        setDescriptionSafe(
            actionIteratorType,
            "VC6 deque-like iterator used by C2EActionHistory.");

        actionHistoryType = getOrCreate("C2EActionHistory", 0x30);
        safeField(actionHistoryType, 0x00, Undefined1DataType.dataType, 1,
            "allocatorState", "VC6 deque/container allocator state.");
        safeField(actionHistoryType, 0x01,
            new ArrayDataType(Undefined1DataType.dataType, 3, 1), 3,
            "padding01", "Alignment.");
        safeField(actionHistoryType, 0x04, actionIteratorType, 0x10,
            "begin", "Deque begin iterator.");
        safeField(actionHistoryType, 0x14, actionIteratorType, 0x10,
            "end", "Deque end iterator.");
        safeField(actionHistoryType, 0x24, Undefined4DataType.dataType, 4,
            "dequeState24", "Additional VC6 deque state.");
        safeField(actionHistoryType, 0x28, Undefined4DataType.dataType, 4,
            "dequeState28", "Additional VC6 deque state.");
        safeField(actionHistoryType, 0x2c, UnsignedIntegerDataType.dataType, 4,
            "count", "Number of edit-action references. OnUpdateUndo/Redo test this field.");
        setDescriptionSafe(
            actionHistoryType,
            "0x30-byte VC6 deque-like history of 8-byte C2EEditActionRef values.");

        moveRoomSnapshotType = getOrCreate("C2EMoveRoomSnapshot", 0x20);
        safeField(moveRoomSnapshotType, 0x00, IntegerDataType.dataType, 4,
            "roomId", "Room whose geometry participates in the Move Room action.");
        safeField(moveRoomSnapshotType, 0x04, geometryType, 0x1c,
            "geometry", "Geometry state swapped with the live room on each Redo/Undo application.");
        setDescriptionSafe(
            moveRoomSnapshotType,
            "Move Room snapshot: integer room ID plus complete 0x1C C2ERoomGeometry. Element size is exactly 0x20.");

        Pointer snapshotPtr = new PointerDataType(moveRoomSnapshotType, dtm);
        moveRoomSnapshotVectorType = getOrCreate("C2EMoveRoomSnapshotVector", 0x10);
        safeField(moveRoomSnapshotVectorType, 0x00, Undefined1DataType.dataType, 1,
            "allocatorState", "VC6 vector allocator/state.");
        safeField(moveRoomSnapshotVectorType, 0x01,
            new ArrayDataType(Undefined1DataType.dataType, 3, 1), 3,
            "padding01", "Alignment.");
        safeField(moveRoomSnapshotVectorType, 0x04, snapshotPtr, 4,
            "begin", "First C2EMoveRoomSnapshot.");
        safeField(moveRoomSnapshotVectorType, 0x08, snapshotPtr, 4,
            "end", "One past last snapshot.");
        safeField(moveRoomSnapshotVectorType, 0x0c, snapshotPtr, 4,
            "capacityEnd", "One past allocated snapshot storage.");

        moveRoomActionType = getOrCreate("C2EMoveRoomAction", 0x14);
        safeField(moveRoomActionType, 0x00, vtPtr, 4,
            "vftable", "C2EMoveRoomAction vtable at 0042A938.");
        safeField(moveRoomActionType, 0x04, moveRoomSnapshotVectorType, 0x10,
            "snapshots", "One target/current geometry snapshot per selected room.");
        setDescriptionSafe(
            moveRoomActionType,
            "0x14-byte Move Room undoable action. Redo swaps stored/live geometry; default Undo invokes the same swap operation.");

        moveRoomActionPtr = new PointerDataType(moveRoomActionType, dtm);
    }

    private void correctDocumentHistoryLayout() {
        replaceRangeWithField(
            docType,
            0x0b8,
            0x30,
            actionHistoryType,
            "undoHistory",
            "Undo history. OnUndo pops its newest C2EEditActionRef; new successful actions are pushed here.",
            new String[] {
                "stateB8", "state0B8", "stateObject0BC", "stateObjectBC",
                "stateObject0CC", "stateObjectCC", "state0DC", "stateDC",
                "state0E0", "stateE0", "state0E4", "stateE4"
            });

        replaceRangeWithField(
            docType,
            0x0e8,
            0x30,
            actionHistoryType,
            "redoHistory",
            "Redo history. OnRedo pops its newest C2EEditActionRef; OnUndo pushes completed undos here. New edit actions clear this history.",
            new String[] {
                "stateE8", "state0E8", "stateObject0EC", "stateObjectEC",
                "stateObject0FC", "stateObjectFC", "state10C",
                "state110", "state114"
            });

        setDescriptionSafe(
            docType,
            "Creatures Map Editor document with recovered Undo/Redo action histories at +0xB8 and +0xE8.");
    }

    private void replaceRangeWithField(
            Structure s,
            int offset,
            int length,
            DataType type,
            String name,
            String comment,
            String[] acceptedGeneratedNames) {

        int end = offset + length;
        HashSet<String> accepted = new HashSet<>();
        accepted.add(name);
        accepted.addAll(Arrays.asList(acceptedGeneratedNames));

        // Validate all named components intersecting the target range.
        for (DataTypeComponent c : s.getComponents()) {
            int c0 = c.getOffset();
            int c1 = c0 + c.getLength();

            if (c1 <= offset || c0 >= end) continue;

            String n = c.getFieldName();

            if (n != null && !n.isBlank() && !accepted.contains(n)) {
                fieldsPreserved++;
                println("[history-preserve] " + s.getName() +
                    " target +0x" + Integer.toHexString(offset) +
                    " intersects unexpected field '" + n + "' at +0x" +
                    Integer.toHexString(c0));
                return;
            }
        }

        try {
            // Clear components starting within the target range.
            ArrayList<Integer> starts = new ArrayList<>();

            for (DataTypeComponent c : s.getComponents()) {
                int c0 = c.getOffset();
                if (c0 >= offset && c0 < end) starts.add(c0);
            }

            Collections.sort(starts, Collections.reverseOrder());

            for (Integer c0 : starts) {
                s.clearAtOffset(c0);
            }

            s.replaceAtOffset(offset, type, length, name, comment);
            fieldsRefined++;

            println("[correct] " + s.getName() + " +0x" +
                Integer.toHexString(offset) + " -> " + name + "[0x" +
                Integer.toHexString(length) + "]");
        }
        catch (Exception e) {
            println("[history-fail] " + name + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Vtables / labels
    // ---------------------------------------------------------------------

    private void labelVTables() {
        for (VTableSpec v : VTABLES) {
            try {
                Address a = toAddr(v.address);
                String label = v.className + "_vtable";

                createLabel(a, label, true);
                labelsCreated++;

                setPlateComment(
                    a,
                    v.className + " virtual table.\n" +
                    "Description resource 0x" + Integer.toHexString(v.stringId).toUpperCase() +
                    ": \"" + v.description + "\"\n" +
                    "slots: destructor, Redo, Undo, GetDescriptionStringId.");

                println("[vtable] " + a + " -> " + label +
                    " [\"" + v.description + "\"]");
            }
            catch (Exception e) {
                println("[vtable-label-skip] " + Long.toHexString(v.address) +
                    ": " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Comments / flow
    // ---------------------------------------------------------------------

    private void annotateDocumentFlow() {
        appendRepeatableComment(
            toAddr(0x408980L),
            "[C2E Undo/Redo flow]\n" +
            "New action submission:\n" +
            "  action->Redo(document)\n" +
            "  if false: do not record\n" +
            "  if true: push action into document.undoHistory\n" +
            "           clear document.redoHistory\n" +
            "           SetModifiedFlag(TRUE)\n" +
            "           UpdateAllViews / refresh UI.");

        appendRepeatableComment(
            toAddr(0x408da0L),
            "[C2E action-vtable proof] OnRedo pops from document.redoHistory, calls action vtable +0x04, pushes the same C2EEditActionRef to undoHistory, then refreshes the document.");

        appendRepeatableComment(
            toAddr(0x4090a0L),
            "[C2E action-vtable proof] OnUndo pops from document.undoHistory, calls action vtable +0x08, pushes the same C2EEditActionRef to redoHistory, then refreshes the document.");

        appendRepeatableComment(
            toAddr(0x408f70L),
            "[C2E history layout] OnUpdateRedo enables the command when document.redoHistory.count (Doc+0x114) != 0 and appends the newest action's GetDescriptionStringId text to the Redo menu label.");

        appendRepeatableComment(
            toAddr(0x409270L),
            "[C2E history layout] OnUpdateUndo enables the command when document.undoHistory.count (Doc+0x0E4) != 0 and appends the newest action's GetDescriptionStringId text to the Undo menu label.");
    }

    private void annotateMoveRoomTransaction() {
        appendRepeatableComment(
            toAddr(0x4017c0L),
            "[C2E Move Room transaction]\n" +
            "Constructor inputs are the selected-room ID collection plus edit delta/mask/world/snap context. For each selected room it computes the target moved/snapped C2ERoomGeometry and appends C2EMoveRoomSnapshot {roomId, geometry} to snapshots.");

        appendRepeatableComment(
            toAddr(0x401a20L),
            "[C2E Move Room self-inversion]\n" +
            "For each C2EMoveRoomSnapshot:\n" +
            "  current = liveRoom.geometry\n" +
            "  liveRoom.geometry = snapshot.geometry\n" +
            "  snapshot.geometry = current\n" +
            "After all rooms: invalidate WorldModel derived caches.\n" +
            "Therefore repeating this operation toggles between before/after states.");

        appendRepeatableComment(
            toAddr(0x4200caL),
            "[C2E Select commit] Select-tool LButtonUp allocates C2EMoveRoomAction[0x14] and supplies selectedRoomIds, drag delta, dragMask, worldModel and snap tolerance/context. The resulting action is handed to CC2ERoomEditorDoc_ExecuteEditAction.");
    }

    private void annotateResourceEvidence() {
        try {
            createLabel(toAddr(0x42a938L), "C2EMoveRoomAction_vtable", true);
        }
        catch (Exception ignored) {}

        appendRepeatableComment(
            toAddr(0x401060L),
            "[C2E STRINGTABLE] returns 0x8D => \"Move Room\".");

        appendRepeatableComment(
            toAddr(0x401050L),
            "[C2E STRINGTABLE] returns 0x8C => \"Set Room Property\".");

        appendRepeatableComment(
            toAddr(0x401080L),
            "[C2E STRINGTABLE] returns 0x8E => \"Set Door Opening\".");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run previous recovery passes first.");
        }

        return (Structure)dt;
    }

    private Structure getOrCreate(String name, int size) {
        DataType existing = dtm.getDataType(CAT, name);

        if (existing instanceof Structure) {
            Structure s = (Structure)existing;

            if (s.getLength() < size) {
                s.growStructure(size - s.getLength());
            }
            else if (s.getLength() > size) {
                println("[type-size-preserve] " + name +
                    " existing=0x" + Integer.toHexString(s.getLength()) +
                    " expected=0x" + Integer.toHexString(size));
            }

            return s;
        }

        if (existing != null) {
            throw new IllegalStateException(
                "/C2E/Refined/" + name + " exists but is not a Structure.");
        }

        StructureDataType fresh =
            new StructureDataType(CAT, name, size, dtm);

        return (Structure)dtm.addDataType(
            fresh,
            DataTypeConflictHandler.REPLACE_HANDLER);
    }

    private void safeField(
            Structure s,
            int offset,
            DataType type,
            int length,
            String name,
            String comment,
            String... acceptedOldNames) {

        try {
            DataTypeComponent component = s.getComponentContaining(offset);

            if (component != null) {
                String currentName = component.getFieldName();

                boolean safe =
                    currentName == null ||
                    currentName.isBlank() ||
                    name.equals(currentName);

                if (!safe && acceptedOldNames != null) {
                    for (String old : acceptedOldNames) {
                        if (old != null && old.equals(currentName)) {
                            safe = true;
                            break;
                        }
                    }
                }

                if (!safe) {
                    fieldsPreserved++;
                    println("[field-preserve] " + s.getName() +
                        " +0x" + Integer.toHexString(offset) +
                        " existing='" + currentName +
                        "' candidate='" + name + "'");
                    return;
                }

                if (component.getOffset() != offset ||
                    component.getLength() != length ||
                    !component.getDataType().isEquivalent(type)) {

                    s.clearAtOffset(component.getOffset());
                }
            }

            s.replaceAtOffset(offset, type, length, name, comment);
            fieldsRefined++;

            println("[field] " + s.getName() +
                " +0x" + Integer.toHexString(offset) +
                " -> " + name);
        }
        catch (Exception e) {
            println("[field-fail] " + s.getName() +
                " +0x" + Integer.toHexString(offset) +
                " " + name + ": " + e.getMessage());
        }
    }

    private void setDescriptionSafe(Structure s, String text) {
        try {
            s.setDescription(text);
        }
        catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // Function naming / typing
    // ---------------------------------------------------------------------

    private Function ensureFunction(
            long value,
            String desiredName,
            String... replaceNames) throws Exception {

        Address a = toAddr(value);
        Function f = getFunctionAt(a);

        if (f == null) {
            Function containing = getFunctionContaining(a);

            if (containing != null &&
                !containing.getEntryPoint().equals(a)) {

                println("[function-overlap-skip] " + a +
                    " lies inside " + containing.getName());
                return null;
            }

            if (getInstructionAt(a) == null && !disassemble(a)) {
                println("[disassemble-fail] " + a + " " + desiredName);
                return null;
            }

            f = createFunction(a, desiredName);

            if (f == null) {
                println("[function-create-fail] " + a + " " + desiredName);
                return null;
            }

            functionsCreated++;
            println("[create] " + a + " -> " + desiredName);
        }

        String current = f.getName();

        if (current.equals(desiredName)) {
            functionsKept++;
            return f;
        }

        boolean replace =
            current.startsWith("FUN_") ||
            current.startsWith("thunk_FUN_") ||
            f.getSymbol().getSource() == SourceType.DEFAULT;

        if (!replace && replaceNames != null) {
            for (String old : replaceNames) {
                if (old != null && old.equals(current)) {
                    replace = true;
                    break;
                }
            }
        }

        if (replace) {
            f.setName(desiredName, SourceType.USER_DEFINED);
            functionsRenamed++;
            println("[rename] " + a + " " + current + " -> " + desiredName);
        }
        else {
            functionsKept++;
            println("[keep] " + a + " existing=" + current +
                " suggested=" + desiredName);
        }

        return f;
    }

    private void applyThisType(Function f, Pointer ptr, String typeName) {
        try {
            if (f.hasVarArgs()) {
                thisTypesSkipped++;
                return;
            }

            Register ecx = currentProgram.getRegister("ECX");
            if (ecx == null) {
                thisTypesSkipped++;
                return;
            }

            Parameter[] oldParams = f.getParameters();

            if (f.hasCustomVariableStorage() &&
                oldParams.length > 0 &&
                "this".equals(oldParams[0].getName()) &&
                oldParams[0].getDataType() != null &&
                oldParams[0].getDataType().isEquivalent(ptr) &&
                oldParams[0].getVariableStorage() != null &&
                oldParams[0].getVariableStorage().isRegisterStorage()) {
                return;
            }

            ArrayList<Variable> newParams = new ArrayList<>();

            newParams.add(new ParameterImpl(
                "this", ptr, ecx, currentProgram, SourceType.USER_DEFINED));

            for (Parameter p : oldParams) {
                if (p.isAutoParameter() || "this".equals(p.getName())) continue;

                VariableStorage storage = p.getVariableStorage();

                if (storage != null &&
                    storage.isRegisterStorage() &&
                    storage.getRegister() != null &&
                    storage.getRegister().equals(ecx)) {

                    println("[drop-ecx-param] " + f.getEntryPoint() +
                        " dropping old " + p.getName() + "{" + storage + "}");
                    continue;
                }

                newParams.add(new ParameterImpl(p, currentProgram));
            }

            f.updateFunction(
                "__thiscall",
                null,
                newParams,
                Function.FunctionUpdateType.CUSTOM_STORAGE,
                true,
                SourceType.USER_DEFINED);

            thisTypesApplied++;
            println("[this] " + f.getEntryPoint() + " " +
                f.getName() + " -> " + typeName + " * @ ECX");
        }
        catch (Exception e) {
            thisTypesSkipped++;
            println("[this-fail] " + f.getEntryPoint() +
                " " + f.getName() + ": " + e.getMessage());
        }
    }

    private void applyReturnType(Function f, String kind) {
        try {
            DataType type = null;

            if ("void".equals(kind)) type = VoidDataType.dataType;
            else if ("bool".equals(kind)) type = BooleanDataType.dataType;
            else if ("int".equals(kind)) type = IntegerDataType.dataType;
            else if ("movePtr".equals(kind)) type = moveRoomActionPtr;

            if (type != null &&
                (f.getReturnType() == null ||
                 !f.getReturnType().isEquivalent(type))) {

                f.setReturnType(type, SourceType.USER_DEFINED);
                returnTypesApplied++;
            }
        }
        catch (Exception e) {
            println("[return-skip] " + f.getEntryPoint() +
                " " + f.getName() + ": " + e.getMessage());
        }
    }

    private void appendRepeatableComment(Address a, String text) {
        String old = getRepeatableComment(a);

        if (old == null || old.isBlank()) {
            setRepeatableComment(a, text);
        }
        else if (!old.contains(text)) {
            setRepeatableComment(a, old + "\n" + text);
        }
    }
}
