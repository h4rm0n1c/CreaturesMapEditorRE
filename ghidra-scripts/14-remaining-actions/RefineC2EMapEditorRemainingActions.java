// Creatures Map Editor 1.08 — remaining action / selection corrections.
//
// Prerequisites:
//   all previous passes through RefineC2EMapEditorConcreteActions.java v1.1.
//
// Exact findings promoted here:
//
// 1. C2ERemoveRoomAction is 0x14 bytes:
//      +00 vftable
//      +04 vector<C2ERemoveRoomSnapshot>
//    snapshot [0x0c]:
//      +00 roomId
//      +04 C2EEditorRoomRef [0x08]
//
//    Redo refreshes each stored shared Room reference, then removes roomId.
//    Undo reinserts the preserved Room reference under the original roomId.
//
// 2. C2EAddRoomCreationState[0x20] is four C2EIntPoint values.
//    C2EEditorRoom_ctor copies all 8 DWORDs, sorts four 8-byte points with
//    comparator 00419D10 (x first, then y), and derives canonical geometry:
//      left-top, left-bottom, right-top, right-bottom.
//
// 3. View +0x164 was previously named selectedRoomRefs. This was wrong.
//    The Properties commit routine proves it is a selected DOOR-PAIR set.
//    Each selected element yields {roomId1, roomId2}; Set Door Opening is
//    created from this set when no room IDs are selected.
//
// 4. C2ESetDoorOpeningAction +0x14 is propertyIndex, passed through the same
//    generic property-edit dispatcher as C2ESetRoomPropertyAction.
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

public class RefineC2EMapEditorRemainingActions extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure viewType;
    private Structure roomType;
    private Structure intPointType;
    private Structure addRoomStateType;
    private Structure addRoomActionType;
    private Structure setDoorActionType;
    private Structure actionVtableType;

    private Structure roomRefType;
    private Structure removeRoomSnapshotType;
    private Structure removeRoomSnapshotVectorType;
    private Structure removeRoomActionType;
    private Structure doorPairType;
    private Structure selectedDoorPairSetType;

    private Pointer viewPtr;
    private Pointer removeRoomActionPtr;

    private int fieldsRefined;
    private int fieldsPreserved;
    private int functionsCreated;
    private int functionsRenamed;
    private int functionsKept;
    private int thisTypesApplied;
    private int thisTypesSkipped;
    private int returnTypesApplied;

    private static class FuncSpec {
        final long address;
        final String name;
        final String owner;
        final String comment;
        final String returnKind;

        FuncSpec(long address, String name, String owner,
                 String comment, String returnKind) {
            this.address = address;
            this.name = name;
            this.owner = owner;
            this.comment = comment;
            this.returnKind = returnKind;
        }
    }

    private final FuncSpec[] FUNCTIONS = new FuncSpec[] {
        new FuncSpec(
            0x401340L,
            "C2ERemoveRoomAction_ctor",
            "removeRoom",
            "Constructs Remove Room action. Iterates the selected room-ID collection and appends 12-byte snapshots containing roomId plus a shared/reference wrapper to the compact Room.",
            "removeRoomPtr"),

        new FuncSpec(
            0x419d10L,
            "C2EIntPoint_LexicographicLess",
            "none",
            "Comparator used when creating a Room from four corner points: compare x first; if x is equal compare y.",
            "bool"),

        new FuncSpec(
            0x40f7f0L,
            "CC2ERoomEditorView_CommitPropertyEdit",
            "view",
            "Generic Properties commit dispatcher. If selectedRoomIds is non-empty, constructs Set Room Property(propertyIndex,value). Otherwise, if selectedDoorPairs is non-empty, constructs Set Door Opening(selectedDoorPairs,propertyIndex,value). Submits the resulting action to the document Undo/Redo manager.",
            "void")
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
        // 00401288 C7 00 18 A9 42 00 -> Add Room vtable immediate at 0040128A
        // 0040137E C7 03 28 A9 42 00 -> Remove Room vtable immediate at 00401380
        if (getInt(toAddr(0x40128aL)) != 0x0042a918 ||
            getInt(toAddr(0x401380L)) != 0x0042a928) {

            popup("MapEditor 1.08 remaining-action identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        viewType = requireStructure("CC2ERoomEditorView_refined");
        roomType = requireStructure("C2EEditorRoom");
        intPointType = requireStructure("C2EIntPoint");
        addRoomStateType = requireStructure("C2EAddRoomCreationState");
        addRoomActionType = requireStructure("C2EAddRoomAction");
        setDoorActionType = requireStructure("C2ESetDoorOpeningAction");
        actionVtableType = requireStructure("C2EEditActionVTable");

        viewPtr = new PointerDataType(viewType, dtm);

        println("=== Creatures Map Editor 1.08 - remaining action corrections ===");
        println("");

        refineAddRoomCornerState();
        buildRemoveRoomTypes();
        correctSelectedDoorPairs();
        refineSetDoorPropertyIndex();

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) continue;

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E remaining actions] " + spec.comment);

            if ("view".equals(spec.owner)) {
                applyThisType(f, viewPtr, "CC2ERoomEditorView_refined");
            }
            else if ("removeRoom".equals(spec.owner)) {
                applyThisType(
                    f,
                    removeRoomActionPtr,
                    "C2ERemoveRoomAction");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateAddRoomGeometryDerivation();
        annotateRemoveRoomFlow();
        annotateDoorSelectionCorrection();
        annotateUnusedResourceNames();

        println("");
        println("Running analysis on remaining action corrections...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Remaining action summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("Corrections:");
        println("  Add Room creationState -> four C2EIntPoint corners");
        println("  View +0x164 selectedRoomRefs -> selectedDoorPairs");
        println("  Set Door Opening +0x14 context14 -> propertyIndex");
        println("  Remove Room action -> vector<{roomId, RoomRef}>");
    }

    // ---------------------------------------------------------------------
    // Add Room input
    // ---------------------------------------------------------------------

    private void refineAddRoomCornerState() {
        try {
            DataTypeComponent existing =
                addRoomStateType.getComponentContaining(0x00);

            if (existing != null) {
                String n = existing.getFieldName();

                if (n != null &&
                    !n.isBlank() &&
                    !"rawState".equals(n) &&
                    !"corners".equals(n)) {

                    fieldsPreserved++;
                    println("[add-room-state-preserve] existing='" + n + "'");
                    return;
                }

                addRoomStateType.clearAtOffset(existing.getOffset());
            }

            ArrayDataType corners =
                new ArrayDataType(intPointType, 4, 0x08);

            addRoomStateType.replaceAtOffset(
                0x00,
                corners,
                0x20,
                "corners",
                "Four room corner points. C2EEditorRoom_ctor sorts these lexicographically by x then y before deriving canonical room geometry.");

            fieldsRefined++;
            println("[correct] C2EAddRoomCreationState +0x0 -> corners[4]");
        }
        catch (Exception e) {
            println("[add-room-state-fail] " + e.getMessage());
        }

        setDescriptionSafe(
            addRoomStateType,
            "Four 8-byte C2EIntPoint corner positions (0x20 total). Order is not relied upon: C2EEditorRoom_ctor sorts the points by x then y.");

        // Keep the action field name creationState; refine its comment.
        try {
            DataTypeComponent c =
                addRoomActionType.getComponentContaining(0x04);

            if (c != null && "creationState".equals(c.getFieldName())) {
                c.setComment(
                    "Four-corner room creation state. Contains C2EAddRoomCreationState.corners[4].");
            }
        }
        catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // Remove Room
    // ---------------------------------------------------------------------

    private void buildRemoveRoomTypes() {
        roomRefType = getOrCreate("C2EEditorRoomRef", 0x08);

        field(
            roomRefType, 0x00,
            new PointerDataType(roomType, dtm), 4,
            "room",
            "Compact C2EEditorRoom object pointer.");

        field(
            roomRefType, 0x04,
            new PointerDataType(UnsignedIntegerDataType.dataType, dtm), 4,
            "refCount",
            "Reference-count pointer used by the editor shared/reference wrapper.");

        setDescriptionSafe(
            roomRefType,
            "Eight-byte shared/reference wrapper for C2EEditorRoom.");

        removeRoomSnapshotType =
            getOrCreate("C2ERemoveRoomSnapshot", 0x0c);

        field(
            removeRoomSnapshotType, 0x00,
            IntegerDataType.dataType, 4,
            "roomId",
            "Original integer room ID.");

        field(
            removeRoomSnapshotType, 0x04,
            roomRefType, 0x08,
            "removedRoom",
            "Preserved shared/reference wrapper to the compact Room.");

        setDescriptionSafe(
            removeRoomSnapshotType,
            "12-byte Remove Room snapshot: roomId + C2EEditorRoomRef.");

        removeRoomSnapshotVectorType =
            buildVector(
                "C2ERemoveRoomSnapshotVector",
                removeRoomSnapshotType,
                "Vector of 12-byte removed-room snapshots.");

        removeRoomActionType =
            getOrCreate("C2ERemoveRoomAction", 0x14);

        field(
            removeRoomActionType, 0x00,
            new PointerDataType(actionVtableType, dtm), 4,
            "vftable",
            "C2ERemoveRoomAction_vtable at 0042A928.");

        field(
            removeRoomActionType, 0x04,
            removeRoomSnapshotVectorType, 0x10,
            "snapshots",
            "One preserved Room reference per selected room ID.");

        setDescriptionSafe(
            removeRoomActionType,
            "0x14-byte Remove Room action. Redo removes rooms while preserving shared references; Undo reinserts them.");

        removeRoomActionPtr =
            new PointerDataType(removeRoomActionType, dtm);
    }

    // ---------------------------------------------------------------------
    // Door selection correction
    // ---------------------------------------------------------------------

    private void correctSelectedDoorPairs() {
        doorPairType = getOrCreate("C2EDoorPair", 0x08);

        field(
            doorPairType, 0x00,
            IntegerDataType.dataType, 4,
            "roomId1",
            "First room ID of a selected generated door.");

        field(
            doorPairType, 0x04,
            IntegerDataType.dataType, 4,
            "roomId2",
            "Second room ID of a selected generated door.");

        setDescriptionSafe(
            doorPairType,
            "Selected door identity as an unordered pair of compact room IDs.");

        selectedDoorPairSetType =
            getOrCreate("C2ESelectedDoorPairSet", 0x14);

        field(
            selectedDoorPairSetType, 0x00,
            new ArrayDataType(
                Undefined1DataType.dataType, 0x10, 1),
            0x10,
            "treeState",
            "Opaque VC6 ordered-tree state. Node payloads identify C2EDoorPair values.");

        field(
            selectedDoorPairSetType, 0x10,
            UnsignedIntegerDataType.dataType, 4,
            "count",
            "Number of selected door room-pairs.");

        setDescriptionSafe(
            selectedDoorPairSetType,
            "20-byte tree/set-like selection container for generated doors, represented by {roomId1,roomId2} pairs.");

        try {
            DataTypeComponent c = viewType.getComponentContaining(0x164);
            String n = c == null ? null : c.getFieldName();

            boolean safe =
                c == null ||
                n == null ||
                n.isBlank() ||
                "selectedRoomRefs".equals(n) ||
                "selectedDoorPairs".equals(n);

            if (!safe) {
                fieldsPreserved++;
                println("[selection-preserve] View +0x164 existing='" + n + "'");
            }
            else {
                if (c != null) {
                    viewType.clearAtOffset(c.getOffset());
                }

                viewType.replaceAtOffset(
                    0x164,
                    selectedDoorPairSetType,
                    0x14,
                    "selectedDoorPairs",
                    "Selected generated-door identities. Generic Properties edits route this set to C2ESetDoorOpeningAction when no room IDs are selected.");

                fieldsRefined++;
                println("[correct] View +0x164 selectedRoomRefs -> selectedDoorPairs");
            }
        }
        catch (Exception e) {
            println("[selection-fail] " + e.getMessage());
        }

        // Deprecate the old speculative type without deleting it.
        DataType old =
            dtm.getDataType(CAT, "C2ESelectedRoomRefSet");

        if (old instanceof Structure) {
            setDescriptionSafe(
                (Structure)old,
                "DEPRECATED speculative name from an earlier pass. Exact later evidence shows View +0x164 is a selected door-pair set; use C2ESelectedDoorPairSet.");
        }
    }

    private void refineSetDoorPropertyIndex() {
        try {
            DataTypeComponent c =
                setDoorActionType.getComponentContaining(0x14);

            String n = c == null ? null : c.getFieldName();

            boolean safe =
                c == null ||
                n == null ||
                n.isBlank() ||
                "context14".equals(n) ||
                "propertyIndex".equals(n);

            if (!safe) {
                fieldsPreserved++;
                println("[door-context-preserve] existing='" + n + "'");
                return;
            }

            if (c != null) {
                setDoorActionType.clearAtOffset(c.getOffset());
            }

            setDoorActionType.replaceAtOffset(
                0x14,
                IntegerDataType.dataType,
                4,
                "propertyIndex",
                "Generic Properties property index. The view dispatcher passes the same propertyIndex argument used by Set Room Property; RedoSwapPermeability operates solely on the already-built snapshots.");

            fieldsRefined++;
            println("[correct] C2ESetDoorOpeningAction +0x14 context14 -> propertyIndex");
        }
        catch (Exception e) {
            println("[door-context-fail] " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Evidence
    // ---------------------------------------------------------------------

    private void annotateAddRoomGeometryDerivation() {
        appendRepeatableComment(
            toAddr(0x419d40L),
            "[C2E Add Room input]\n" +
            "The sole constructor argument points to C2EAddRoomCreationState.corners[4]. " +
            "The constructor copies all four C2EIntPoint values, sorts them with C2EIntPoint_LexicographicLess, " +
            "then maps sorted points as:\n" +
            "  p0 = left/top\n" +
            "  p1 = left/bottom\n" +
            "  p2 = right/top\n" +
            "  p3 = right/bottom\n" +
            "to xLeft/xRight/yLeftCeiling/yRightCeiling/yLeftFloor/yRightFloor.");

        appendRepeatableComment(
            toAddr(0x419d10L),
            "[C2E corner sort] Lexicographic C2EIntPoint comparator: return a.x < b.x; when x equal return a.y < b.y.");

        appendRepeatableComment(
            toAddr(0x401280L),
            "[C2E Add Room action] Constructor copies exactly four 8-byte C2EIntPoint values (0x20 bytes) into creationState.");
    }

    private void annotateRemoveRoomFlow() {
        appendRepeatableComment(
            toAddr(0x401600L),
            "[C2E Remove Room concrete layout]\n" +
            "Iterates snapshots in 12-byte steps. For each snapshot it refreshes snapshot.removedRoom from WorldModel_FindRoomById if necessary, then removes snapshot.roomId through the document.");

        appendRepeatableComment(
            toAddr(0x401780L),
            "[C2E Remove Room Undo]\n" +
            "Iterates C2ERemoveRoomSnapshot records and calls CC2ERoomEditorDoc_InsertRoomWithId(snapshot.roomId, &snapshot.removedRoom).");
    }

    private void annotateDoorSelectionCorrection() {
        appendRepeatableComment(
            toAddr(0x40f7f0L),
            "[C2E selection correction]\n" +
            "View+0x150 selectedRoomIds.count is tested first. If nonzero -> Set Room Property.\n" +
            "Otherwise View+0x164 selectedDoorPairs.count is tested. If nonzero -> Set Door Opening.\n" +
            "The door action constructor iterates the selected collection and reads two room IDs from each selected pair.");

        appendRepeatableComment(
            toAddr(0x402010L),
            "[C2E Set Door Opening args] Constructor receives selectedDoorPairs, propertyIndex, value. It stores propertyIndex at +0x14 and builds {roomId1,roomId2,permeability=value} snapshots.");
    }

    private void annotateUnusedResourceNames() {
        appendRepeatableComment(
            toAddr(0x401000L),
            "[C2E resource caution] The STRINGTABLE also contains labels such as \"Modify Room\", \"Modify Metaroom\" and \"Multiple Action\", but no recovered edit-action vtable in the 0042A8F8..0042A9E8 family returns those IDs. They are therefore not assigned to action classes in this pass.");
    }

    // ---------------------------------------------------------------------
    // Type helpers
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

    private Structure buildVector(
            String name,
            Structure elementType,
            String description) {

        Structure s = getOrCreate(name, 0x10);
        Pointer elementPtr =
            new PointerDataType(elementType, dtm);

        field(
            s, 0x00,
            Undefined1DataType.dataType, 1,
            "allocatorState",
            "VC6 vector allocator/state byte.");

        field(
            s, 0x01,
            new ArrayDataType(
                Undefined1DataType.dataType, 3, 1),
            3,
            "padding01",
            "Alignment.");

        field(
            s, 0x04,
            elementPtr, 4,
            "begin",
            "First element.");

        field(
            s, 0x08,
            elementPtr, 4,
            "end",
            "One past last element.");

        field(
            s, 0x0c,
            elementPtr, 4,
            "capacityEnd",
            "One past allocated storage.");

        setDescriptionSafe(s, description);
        return s;
    }

    private void field(
            Structure s,
            int offset,
            DataType type,
            int length,
            String name,
            String comment) {

        try {
            DataTypeComponent c =
                s.getComponentContaining(offset);

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
                        " existing='" + current +
                        "' candidate='" + name + "'");
                    return;
                }

                if (c.getOffset() != offset ||
                    c.getLength() != length ||
                    !c.getDataType().isEquivalent(type)) {

                    s.clearAtOffset(c.getOffset());
                }
            }

            s.replaceAtOffset(
                offset, type, length, name, comment);

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

    private void setDescriptionSafe(
            Structure s,
            String text) {

        try {
            s.setDescription(text);
        }
        catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------
    // Function helpers
    // ---------------------------------------------------------------------

    private Function ensureFunction(
            long value,
            String desiredName) throws Exception {

        Address a = toAddr(value);
        Function f = getFunctionAt(a);

        if (f == null) {
            Function containing =
                getFunctionContaining(a);

            if (containing != null &&
                !containing.getEntryPoint().equals(a)) {

                println("[function-overlap-skip] " + a +
                    " lies inside " + containing.getName());
                return null;
            }

            if (getInstructionAt(a) == null &&
                !disassemble(a)) {

                println("[disassemble-fail] " + a +
                    " " + desiredName);
                return null;
            }

            f = createFunction(a, desiredName);

            if (f == null) {
                println("[function-create-fail] " + a +
                    " " + desiredName);
                return null;
            }

            functionsCreated++;
            println("[create] " + a +
                " -> " + desiredName);
        }

        String current = f.getName();

        if (current.equals(desiredName)) {
            functionsKept++;
        }
        else if (current.startsWith("FUN_") ||
                 current.startsWith("thunk_FUN_") ||
                 f.getSymbol().getSource() ==
                    SourceType.DEFAULT) {

            f.setName(
                desiredName,
                SourceType.USER_DEFINED);

            functionsRenamed++;

            println("[rename] " + a +
                " -> " + desiredName);
        }
        else {
            functionsKept++;

            println("[keep] " + a +
                " existing=" + current +
                " suggested=" + desiredName);
        }

        return f;
    }

    private void applyThisType(
            Function f,
            Pointer ptr,
            String typeName) {

        try {
            if (f.hasVarArgs()) {
                thisTypesSkipped++;
                return;
            }

            Register ecx =
                currentProgram.getRegister("ECX");

            if (ecx == null) {
                thisTypesSkipped++;
                return;
            }

            Parameter[] oldParams =
                f.getParameters();

            if (f.hasCustomVariableStorage() &&
                oldParams.length > 0 &&
                "this".equals(oldParams[0].getName()) &&
                oldParams[0].getDataType() != null &&
                oldParams[0].getDataType().isEquivalent(ptr) &&
                oldParams[0].getVariableStorage() != null &&
                oldParams[0].getVariableStorage().isRegisterStorage()) {

                return;
            }

            ArrayList<Variable> newParams =
                new ArrayList<>();

            newParams.add(
                new ParameterImpl(
                    "this",
                    ptr,
                    ecx,
                    currentProgram,
                    SourceType.USER_DEFINED));

            for (Parameter p : oldParams) {
                if (p.isAutoParameter() ||
                    "this".equals(p.getName())) {
                    continue;
                }

                VariableStorage storage =
                    p.getVariableStorage();

                if (storage != null &&
                    storage.isRegisterStorage() &&
                    storage.getRegister() != null &&
                    storage.getRegister().equals(ecx)) {

                    println("[drop-ecx-param] " +
                        f.getEntryPoint() +
                        " dropping old " +
                        p.getName() +
                        "{" + storage + "}");

                    continue;
                }

                newParams.add(
                    new ParameterImpl(
                        p,
                        currentProgram));
            }

            f.updateFunction(
                "__thiscall",
                null,
                newParams,
                Function.FunctionUpdateType.CUSTOM_STORAGE,
                true,
                SourceType.USER_DEFINED);

            thisTypesApplied++;

            println("[this] " +
                f.getEntryPoint() + " " +
                f.getName() + " -> " +
                typeName + " * @ ECX");
        }
        catch (Exception e) {
            thisTypesSkipped++;

            println("[this-fail] " +
                f.getEntryPoint() + " " +
                f.getName() + ": " +
                e.getMessage());
        }
    }

    private void applyReturnType(
            Function f,
            String kind) {

        try {
            DataType type = null;

            if ("void".equals(kind)) {
                type = VoidDataType.dataType;
            }
            else if ("bool".equals(kind)) {
                type = BooleanDataType.dataType;
            }
            else if ("removeRoomPtr".equals(kind)) {
                type = removeRoomActionPtr;
            }

            if (type != null &&
                (f.getReturnType() == null ||
                 !f.getReturnType().isEquivalent(type))) {

                f.setReturnType(
                    type,
                    SourceType.USER_DEFINED);

                returnTypesApplied++;
            }
        }
        catch (Exception e) {
            println("[return-skip] " +
                f.getEntryPoint() + " " +
                f.getName() + ": " +
                e.getMessage());
        }
    }

    private void appendRepeatableComment(
            Address a,
            String text) {

        String old =
            getRepeatableComment(a);

        if (old == null ||
            old.isBlank()) {

            setRepeatableComment(a, text);
        }
        else if (!old.contains(text)) {
            setRepeatableComment(
                a,
                old + "\n" + text);
        }
    }
}
