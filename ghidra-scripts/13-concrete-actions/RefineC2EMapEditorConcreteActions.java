// Creatures Map Editor 1.08 — concrete edit action layouts + history fix.
//
// Prerequisite:
//   RecoverC2EMapEditorEditActions.java v1 already run.
//
// Revision 1.1: fix identity guard address for the immediate operand in
// 00401C11 (vtable 0042A948 begins at 00401C14, not 00401C13).
//
// This pass is deliberately incremental:
//   1) fixes the two document history fields which v1 conservatively skipped
//      because it encountered generated paddingB9/paddingE9 fields;
//   2) builds concrete layouts for action classes whose constructors / Redo
//      methods expose their storage unambiguously;
//   3) names the corresponding constructors and applies typed `this`.
//
// It does NOT rerun or replace the main action-vtable recovery.
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

public class RefineC2EMapEditorConcreteActions extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure docType;
    private Structure actionVtableType;
    private Structure actionHistoryType;
    private Structure vc6CString;
    private Structure intRect;
    private Structure roomGeometry;
    private Structure metaRoomType;

    private Pointer actionVtablePtr;

    private int fieldsRefined;
    private int fieldsPreserved;
    private int functionsCreated;
    private int functionsRenamed;
    private int functionsKept;
    private int thisTypesApplied;
    private int thisTypesSkipped;
    private int returnTypesApplied;

    private static class ActionCtorSpec {
        final long address;
        final String name;
        final String typeName;
        final String comment;

        ActionCtorSpec(long address, String name, String typeName, String comment) {
            this.address = address;
            this.name = name;
            this.typeName = typeName;
            this.comment = comment;
        }
    }

    private final ActionCtorSpec[] CTORS = new ActionCtorSpec[] {
        new ActionCtorSpec(
            0x401140L,
            "C2EAddMetaRoomAction_ctor",
            "C2EAddMetaRoomAction",
            "Constructs Add Metaroom action from background string + four-int bounds. Redo stores the allocated metaRoomId at +0x18."),

        new ActionCtorSpec(
            0x401280L,
            "C2EAddRoomAction_ctor",
            "C2EAddRoomAction",
            "Constructs Add Room action by copying an exact 0x20-byte room-creation state blob. Redo stores the allocated roomId at +0x24."),

        new ActionCtorSpec(
            0x401bd0L,
            "C2ESetRoomPropertyAction_ctor",
            "C2ESetRoomPropertyAction",
            "Builds an 8-byte {roomId,value} snapshot vector for the selected rooms and stores propertyIndex at +0x14."),

        new ActionCtorSpec(
            0x402010L,
            "C2ESetDoorOpeningAction_ctor",
            "C2ESetDoorOpeningAction",
            "Builds 12-byte {roomId1,roomId2,permeability} entries for affected doors. The extra DWORD at +0x14 is constructor context not needed by Redo and remains generically named."),

        new ActionCtorSpec(
            0x402360L,
            "C2ERemoveMetaRoomAction_ctor",
            "C2ERemoveMetaRoomAction",
            "Initialises an empty shared/reference wrapper for the removed compact metaroom and stores metaRoomId at +0x0C."),

        new ActionCtorSpec(
            0x4027a0L,
            "C2EMoveMetaRoomAction_ctor",
            "C2EMoveMetaRoomAction",
            "Copies four-int metaroom bounds into +0x04 and stores metaRoomId at +0x14. Redo swaps these bounds with the live metaroom."),

        new ActionCtorSpec(
            0x402a60L,
            "C2ESetMetaRoomBackgroundAction_ctor",
            "C2ESetMetaRoomBackgroundAction",
            "Stores metaRoomId + CString. Redo swaps the stored string with the live current background."),

        new ActionCtorSpec(
            0x402d00L,
            "C2EAddMetaRoomBackgroundAction_ctor",
            "C2EAddMetaRoomBackgroundAction",
            "Stores metaRoomId + background CString for undoable background-list insertion."),

        new ActionCtorSpec(
            0x403120L,
            "C2ERemoveMetaRoomBackgroundAction_ctor",
            "C2ERemoveMetaRoomBackgroundAction",
            "Stores metaRoomId, backgroundIndex and a CString used to preserve the removed background for Undo."),

        new ActionCtorSpec(
            0x403550L,
            "C2EChangeMetaRoomBackgroundAction_ctor",
            "C2EChangeMetaRoomBackgroundAction",
            "Stores metaRoomId, backgroundIndex and replacement/background CString."),

        new ActionCtorSpec(
            0x403840L,
            "C2EChangeWorldPropertiesAction_ctor",
            "C2EChangeWorldPropertiesAction",
            "Stores mapWidth/mapHeight/nextMetaRoomId/nextRoomId. Redo swaps all four with the live WorldModel."),

        new ActionCtorSpec(
            0x403920L,
            "C2ESetMetaRoomMusicAction_ctor",
            "C2ESetMetaRoomMusicAction",
            "Stores metaRoomId + track CString. Redo swaps stored/live metaroom track."),

        new ActionCtorSpec(
            0x403c00L,
            "C2ESetRoomMusicAction_ctor",
            "C2ESetRoomMusicAction",
            "Builds a vector of {roomId,track CString} entries for selected rooms. Redo swaps each stored track with the live room track.")
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

        // Exact-target guards:
        // SetRoomProperty ctor writes vtable 0042A948.
        // ChangeWorldProperties ctor writes vtable 0042A9C8.
        if (getInt(toAddr(0x401c14L)) != 0x0042a948 ||
            getInt(toAddr(0x403867L)) != 0x0042a9c8) {

            popup("MapEditor 1.08 action-layout identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        docType = requireStructure("CC2ERoomEditorDoc_refined");
        actionVtableType = requireStructure("C2EEditActionVTable");
        actionHistoryType = requireStructure("C2EActionHistory");
        vc6CString = requireStructure("VC6CString");
        intRect = requireStructure("C2EIntRect");
        roomGeometry = requireStructure("C2ERoomGeometry");
        metaRoomType = requireStructure("C2EEditorMetaRoom");

        actionVtablePtr = new PointerDataType(actionVtableType, dtm);

        println("=== Creatures Map Editor 1.08 - concrete action layouts ===");
        println("");

        fixDocumentHistories();
        buildConcreteTypes();

        for (ActionCtorSpec spec : CTORS) {
            monitor.checkCancelled();

            Structure owner = requireStructure(spec.typeName);
            Pointer ownerPtr = new PointerDataType(owner, dtm);

            Function f = ensureFunction(spec.address, spec.name);

            if (f == null) continue;

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E concrete action] " + spec.comment);

            applyThisType(f, ownerPtr, spec.typeName);
            applyReturnType(f, ownerPtr);
        }

        annotateSwapEvidence();

        println("");
        println("Running analysis on concrete action layouts...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Concrete action summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("Document histories corrected:");
        println("  +0x0B8 undoHistory [0x30]");
        println("  +0x0E8 redoHistory [0x30]");
        println("");
        println("Concrete action layouts added for:");
        println("  Add MetaRoom / Add Room");
        println("  Set Room Property / Set Door Opening");
        println("  Remove / Move MetaRoom");
        println("  MetaRoom background actions");
        println("  Change World Properties");
        println("  MetaRoom / Room music");
    }

    // ---------------------------------------------------------------------
    // Document history fix
    // ---------------------------------------------------------------------

    private void fixDocumentHistories() {
        replaceGeneratedHistoryRange(
            0x0b8,
            "undoHistory",
            "Undo history. This correction replaces the earlier generated state/padding decomposition.");

        replaceGeneratedHistoryRange(
            0x0e8,
            "redoHistory",
            "Redo history. This correction replaces the earlier generated state/padding decomposition.");
    }

    private void replaceGeneratedHistoryRange(
            int offset,
            String fieldName,
            String comment) {

        int end = offset + 0x30;

        // Accept only the old generated decomposition, including paddingB9/E9.
        for (DataTypeComponent c : docType.getComponents()) {
            int c0 = c.getOffset();
            int c1 = c0 + c.getLength();

            if (c1 <= offset || c0 >= end) continue;

            String n = c.getFieldName();

            if (n == null || n.isBlank()) continue;
            if (fieldName.equals(n)) continue;

            String lower = n.toLowerCase(Locale.ROOT);

            boolean generated =
                lower.startsWith("state") ||
                lower.startsWith("padding") ||
                lower.startsWith("field_") ||
                lower.startsWith("undefined");

            if (!generated) {
                fieldsPreserved++;
                println("[history-preserve] +0x" +
                    Integer.toHexString(offset) +
                    " intersects non-generated field '" + n +
                    "' at +0x" + Integer.toHexString(c0));
                return;
            }
        }

        try {
            ArrayList<Integer> starts = new ArrayList<>();

            for (DataTypeComponent c : docType.getComponents()) {
                int c0 = c.getOffset();
                if (c0 >= offset && c0 < end) {
                    starts.add(c0);
                }
            }

            Collections.sort(starts, Collections.reverseOrder());

            for (Integer c0 : starts) {
                docType.clearAtOffset(c0);
            }

            docType.replaceAtOffset(
                offset,
                actionHistoryType,
                0x30,
                fieldName,
                comment);

            fieldsRefined++;

            println("[correct] CC2ERoomEditorDoc_refined +0x" +
                Integer.toHexString(offset) + " -> " + fieldName);
        }
        catch (Exception e) {
            println("[history-fail] " + fieldName + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Concrete structures
    // ---------------------------------------------------------------------

    private void buildConcreteTypes() {
        Structure metaRef = buildMetaRoomRef();

        // Add MetaRoom
        Structure addMeta = getOrCreate("C2EAddMetaRoomAction", 0x1c);
        vt(addMeta, "C2EAddMetaRoomAction_vtable");
        field(addMeta, 0x04, vc6CString, 4,
            "background", "Initial metaroom background string passed to AddMetaRoom.");
        field(addMeta, 0x08, intRect, 0x10,
            "bounds", "Metaroom bounds used by Redo.");
        field(addMeta, 0x18, IntegerDataType.dataType, 4,
            "metaRoomId", "ID allocated by Redo; consumed by Undo.");
        describe(addMeta,
            "Undoable Add Metaroom action. Exact size 0x1C.");

        // Add Room - keep the 0x20 creation blob opaque for now.
        Structure addRoomState = getOrCreate("C2EAddRoomCreationState", 0x20);
        field(addRoomState, 0x00,
            new ArrayDataType(Undefined1DataType.dataType, 0x20, 1),
            0x20,
            "rawState",
            "Exact 0x20-byte room creation state copied by C2EAddRoomAction_ctor and passed as one object to CC2ERoomEditorDoc_AddRoom. Individual fields intentionally deferred.");
        describe(addRoomState,
            "Opaque-but-sized Add Room creation state. Kept opaque until every field can be mapped without guessing.");

        Structure addRoom = getOrCreate("C2EAddRoomAction", 0x28);
        vt(addRoom, "C2EAddRoomAction_vtable");
        field(addRoom, 0x04, addRoomState, 0x20,
            "creationState", "Room creation state passed to document/world AddRoom.");
        field(addRoom, 0x24, IntegerDataType.dataType, 4,
            "roomId", "Room ID allocated by Redo; consumed by Undo.");
        describe(addRoom,
            "Undoable Add Room action. Exact size 0x28.");

        // Set Room Property
        Structure propertyEntry = getOrCreate("C2ERoomPropertySnapshot", 0x08);
        field(propertyEntry, 0x00, IntegerDataType.dataType, 4,
            "roomId", "Room whose property is affected.");
        field(propertyEntry, 0x04, IntegerDataType.dataType, 4,
            "value", "Value swapped with the live room property on Redo/Undo.");

        Structure propertyVector = buildVector(
            "C2ERoomPropertySnapshotVector",
            propertyEntry,
            "Vector of 8-byte room property snapshots.");

        Structure setProp = getOrCreate("C2ESetRoomPropertyAction", 0x18);
        vt(setProp, "C2ESetRoomPropertyAction_vtable");
        field(setProp, 0x04, propertyVector, 0x10,
            "snapshots", "One {roomId,value} snapshot per affected room.");
        field(setProp, 0x14, IntegerDataType.dataType, 4,
            "propertyIndex", "Room property index supplied to C2EEditorRoom_GetProperty/SetProperty.");
        describe(setProp,
            "Self-inverting Set Room Property action. Redo swaps stored/live values.");

        // Set Door Opening
        Structure doorEntry = getOrCreate("C2EDoorOpeningSnapshot", 0x0c);
        field(doorEntry, 0x00, IntegerDataType.dataType, 4,
            "roomId1", "First door parent room.");
        field(doorEntry, 0x04, IntegerDataType.dataType, 4,
            "roomId2", "Second door parent room.");
        field(doorEntry, 0x08, IntegerDataType.dataType, 4,
            "permeability", "Value swapped with C2EEditorBoundarySegment.permeability.");

        Structure doorVector = buildVector(
            "C2EDoorOpeningSnapshotVector",
            doorEntry,
            "Vector of 12-byte room-pair permeability snapshots.");

        Structure setDoor = getOrCreate("C2ESetDoorOpeningAction", 0x18);
        vt(setDoor, "C2ESetDoorOpeningAction_vtable");
        field(setDoor, 0x04, doorVector, 0x10,
            "snapshots", "Door-room-pair permeability snapshots.");
        field(setDoor, 0x14, Undefined4DataType.dataType, 4,
            "context14",
            "Constructor context value. It is not consumed by RedoSwapPermeability, so semantic naming is intentionally deferred.");
        describe(setDoor,
            "Self-inverting Set Door Opening action. Exact object size 0x18.");

        // Remove MetaRoom
        Structure removeMeta = getOrCreate("C2ERemoveMetaRoomAction", 0x10);
        vt(removeMeta, "C2ERemoveMetaRoomAction_vtable");
        field(removeMeta, 0x04, metaRef, 0x08,
            "removedMetaRoom",
            "Shared/reference wrapper used to preserve the removed compact metaroom for Undo.");
        field(removeMeta, 0x0c, IntegerDataType.dataType, 4,
            "metaRoomId", "Metaroom ID removed/reinserted.");
        describe(removeMeta,
            "Remove Metaroom action. Redo preserves the removed metaroom reference; Undo reinserts it.");

        // Move MetaRoom
        Structure moveMeta = getOrCreate("C2EMoveMetaRoomAction", 0x18);
        vt(moveMeta, "C2EMoveMetaRoomAction_vtable");
        field(moveMeta, 0x04, intRect, 0x10,
            "bounds", "Bounds swapped with the live metaroom on each application.");
        field(moveMeta, 0x14, IntegerDataType.dataType, 4,
            "metaRoomId", "Metaroom whose bounds are moved.");
        describe(moveMeta,
            "Self-inverting Move Metaroom action.");

        // MetaRoom string actions
        buildMetaRoomStringAction(
            "C2ESetMetaRoomBackgroundAction",
            "currentBackground",
            "CString swapped with the live current metaroom background.");

        buildMetaRoomStringAction(
            "C2EAddMetaRoomBackgroundAction",
            "background",
            "Background path inserted/removed by Redo/Undo.");

        buildMetaRoomStringAction(
            "C2ESetMetaRoomMusicAction",
            "track",
            "Track string swapped with the live metaroom music.");

        // Remove/change background by index
        Structure removeBg = getOrCreate("C2ERemoveMetaRoomBackgroundAction", 0x10);
        vt(removeBg, "C2ERemoveMetaRoomBackgroundAction_vtable");
        field(removeBg, 0x04, IntegerDataType.dataType, 4,
            "metaRoomId", "Owning metaroom.");
        field(removeBg, 0x08, IntegerDataType.dataType, 4,
            "backgroundIndex", "Index in the metaroom background collection.");
        field(removeBg, 0x0c, vc6CString, 4,
            "removedBackground", "Removed path retained for Undo.");
        describe(removeBg,
            "Undoable Remove Metaroom Background action.");

        Structure changeBg = getOrCreate("C2EChangeMetaRoomBackgroundAction", 0x10);
        vt(changeBg, "C2EChangeMetaRoomBackgroundAction_vtable");
        field(changeBg, 0x04, IntegerDataType.dataType, 4,
            "metaRoomId", "Owning metaroom.");
        field(changeBg, 0x08, IntegerDataType.dataType, 4,
            "backgroundIndex", "Index in the metaroom background collection.");
        field(changeBg, 0x0c, vc6CString, 4,
            "background", "String swapped/replaced at backgroundIndex.");
        describe(changeBg,
            "Self-inverting Change Metaroom Background action.");

        // World properties
        Structure worldState = getOrCreate("C2EWorldPropertiesState", 0x10);
        field(worldState, 0x00, IntegerDataType.dataType, 4,
            "mapWidth", "World map width.");
        field(worldState, 0x04, IntegerDataType.dataType, 4,
            "mapHeight", "World map height.");
        field(worldState, 0x08, IntegerDataType.dataType, 4,
            "nextMetaRoomId", "WorldModel nextMetaRoomId.");
        field(worldState, 0x0c, IntegerDataType.dataType, 4,
            "nextRoomId", "WorldModel nextRoomId.");
        describe(worldState,
            "Four world properties swapped atomically by Change World Properties.");

        Structure worldAction = getOrCreate("C2EChangeWorldPropertiesAction", 0x14);
        vt(worldAction, "C2EChangeWorldPropertiesAction_vtable");
        field(worldAction, 0x04, worldState, 0x10,
            "state", "World properties swapped with the live WorldModel.");
        describe(worldAction,
            "Self-inverting Change World Properties action.");

        // Set Room Music
        Structure roomMusicEntry = getOrCreate("C2ERoomMusicSnapshot", 0x08);
        field(roomMusicEntry, 0x00, IntegerDataType.dataType, 4,
            "roomId", "Room whose track is affected.");
        field(roomMusicEntry, 0x04, vc6CString, 4,
            "track", "Track string swapped with the live room track.");

        Structure roomMusicVector = buildVector(
            "C2ERoomMusicSnapshotVector",
            roomMusicEntry,
            "Vector of {roomId,track} snapshots.");

        Structure roomMusicAction = getOrCreate("C2ESetRoomMusicAction", 0x14);
        vt(roomMusicAction, "C2ESetRoomMusicAction_vtable");
        field(roomMusicAction, 0x04, roomMusicVector, 0x10,
            "snapshots", "One room/track snapshot per selected room.");
        describe(roomMusicAction,
            "Self-inverting Set Room Music action.");
    }

    private Structure buildMetaRoomRef() {
        Structure s = getOrCreate("C2EEditorMetaRoomRef", 0x08);

        Pointer metaPtr = new PointerDataType(metaRoomType, dtm);

        field(s, 0x00, metaPtr, 4,
            "metaRoom", "Compact metaroom object pointer.");
        field(s, 0x04,
            new PointerDataType(UnsignedIntegerDataType.dataType, dtm), 4,
            "refCount", "Reference-count pointer.");

        describe(s,
            "Eight-byte shared/reference wrapper for C2EEditorMetaRoom.");
        return s;
    }

    private void buildMetaRoomStringAction(
            String typeName,
            String valueName,
            String valueComment) {

        Structure s = getOrCreate(typeName, 0x0c);
        vt(s, typeName + "_vtable");

        field(s, 0x04, IntegerDataType.dataType, 4,
            "metaRoomId", "Owning metaroom ID.");
        field(s, 0x08, vc6CString, 4,
            valueName, valueComment);

        describe(s,
            "0x0C " + typeName + " layout.");
    }

    private Structure buildVector(
            String name,
            Structure elementType,
            String description) {

        Structure s = getOrCreate(name, 0x10);
        Pointer elementPtr = new PointerDataType(elementType, dtm);

        field(s, 0x00, Undefined1DataType.dataType, 1,
            "allocatorState", "VC6 vector allocator/state byte.");
        field(s, 0x01,
            new ArrayDataType(Undefined1DataType.dataType, 3, 1), 3,
            "padding01", "Alignment.");
        field(s, 0x04, elementPtr, 4,
            "begin", "First element.");
        field(s, 0x08, elementPtr, 4,
            "end", "One past last element.");
        field(s, 0x0c, elementPtr, 4,
            "capacityEnd", "One past allocated storage.");

        describe(s, description);
        return s;
    }

    private void vt(Structure s, String expectedLabel) {
        field(s, 0x00, actionVtablePtr, 4,
            "vftable",
            "Concrete action vtable (" + expectedLabel + ").");
    }

    // ---------------------------------------------------------------------
    // Evidence comments
    // ---------------------------------------------------------------------

    private void annotateSwapEvidence() {
        appendRepeatableComment(
            toAddr(0x401e60L),
            "[C2E concrete layout proof] Iterates C2ESetRoomPropertyAction.snapshots in 8-byte steps. snapshot.roomId selects the Room; snapshot.value is swapped with Room[propertyIndex].");

        appendRepeatableComment(
            toAddr(0x4022d0L),
            "[C2E concrete layout proof] Iterates C2ESetDoorOpeningAction.snapshots in 12-byte steps. Each {roomId1,roomId2,permeability} record finds the current geometric door and swaps +0x08 permeability.");

        appendRepeatableComment(
            toAddr(0x402800L),
            "[C2E concrete layout proof] Move Metaroom Redo resolves metaRoomId at action+0x14 and swaps the four-DWORD bounds stored at +0x04.");

        appendRepeatableComment(
            toAddr(0x403870L),
            "[C2E concrete layout proof] Change World Properties swaps action.state.mapWidth/mapHeight with C2EWorldModel +0/+4 and swaps action.state.nextMetaRoomId/nextRoomId with WorldModel +0x1C/+0x20.");

        appendRepeatableComment(
            toAddr(0x403db0L),
            "[C2E concrete layout proof] Set Room Music iterates 8-byte {roomId, CString track} snapshots and swaps each stored track with C2EEditorRoom.track (+0x3C).");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run RecoverC2EMapEditorEditActions.java first.");
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

    private void field(
            Structure s,
            int offset,
            DataType type,
            int length,
            String name,
            String comment) {

        try {
            DataTypeComponent c = s.getComponentContaining(offset);

            if (c != null) {
                String current = c.getFieldName();

                boolean safe =
                    current == null ||
                    current.isBlank() ||
                    name.equals(current) ||
                    current.startsWith("field_") ||
                    current.startsWith("undefined") ||
                    current.startsWith("padding");

                if (!safe) {
                    fieldsPreserved++;
                    println("[field-preserve] " + s.getName() +
                        " +0x" + Integer.toHexString(offset) +
                        " existing='" + current + "' candidate='" + name + "'");
                    return;
                }

                if (c.getOffset() != offset ||
                    c.getLength() != length ||
                    !c.getDataType().isEquivalent(type)) {

                    s.clearAtOffset(c.getOffset());
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

    private void describe(Structure s, String description) {
        try {
            s.setDescription(description);
        }
        catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // Function helpers
    // ---------------------------------------------------------------------

    private Function ensureFunction(long value, String desiredName) throws Exception {
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
        }
        else if (current.startsWith("FUN_") ||
                 current.startsWith("thunk_FUN_") ||
                 f.getSymbol().getSource() == SourceType.DEFAULT) {

            f.setName(desiredName, SourceType.USER_DEFINED);
            functionsRenamed++;
            println("[rename] " + a + " -> " + desiredName);
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

    private void applyReturnType(Function f, Pointer ptr) {
        try {
            if (f.getReturnType() == null ||
                !f.getReturnType().isEquivalent(ptr)) {

                f.setReturnType(ptr, SourceType.USER_DEFINED);
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
