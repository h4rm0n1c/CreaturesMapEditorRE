// Second semantic refinement pass for Creatures Map Editor 1.08.
//
// Prerequisites:
//   1) RecoverC2EMapEditorMFC.java
//   2) RecoverC2EMapEditorClassLayout.java
//   3) RefineC2EMapEditorStructures.java
//   4) ApplyC2EMapEditorTypes.java v1.4
//
// This pass is derived from the latest post-v1.4 Ghidra database plus direct
// disassembly of the exact uploaded MapEditor.exe.
//
// It corrects one earlier layout mistake (Cheese tool size), promotes several
// view members from generic state fields to evidence-backed semantic types/names,
// and names a handful of high-confidence zoom/selection helper functions.
//
// It deliberately leaves the hidden toolbar commands 32824 and 32826 unresolved.
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
import ghidra.program.model.symbol.Symbol;

public class RefineC2EMapEditorSemanticsV2 extends GhidraScript {

    private static final CategoryPath CAT =
        new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private int fieldsRefined;
    private int functionsRenamed;
    private int functionsKept;
    private int thisTypesApplied;
    private int thisTypesSkipped;

    private static class FuncSpec {
        final long address;
        final String name;
        final String comment;
        final boolean returnsInt;

        FuncSpec(long address, String name, String comment) {
            this(address, name, comment, false);
        }

        FuncSpec(long address, String name, String comment, boolean returnsInt) {
            this.address = address;
            this.name = name;
            this.comment = comment;
            this.returnsInt = returnsInt;
        }
    }

    private final FuncSpec[] viewHelpers = new FuncSpec[] {
        new FuncSpec(
            0x40ecf0L,
            "CC2ERoomEditorView_SetViewRectWithHistory",
            "Pushes currentViewRect onto zoomBackHistory, clears zoomForwardHistory, " +
            "copies the supplied 16-byte view rectangle into currentViewRect, then redraws."),
        new FuncSpec(
            0x40ee80L,
            "CC2ERoomEditorView_ClearSelection",
            "Clears selectedRoomIds and selectedRoomRefs, resets selectedMetaroomId to -1, " +
            "and refreshes the view."),
        new FuncSpec(
            0x40f660L,
            "CC2ERoomEditorView_GetSelectedMetaroomId",
            "Returns this->selectedMetaroomId. -1 is the no-selection sentinel.",
            true),
        new FuncSpec(
            0x410b40L,
            "CC2ERoomEditorView_OnUpdateZoomForward",
            "MFC update-UI handler. Enables Forward when zoomForwardHistory.count != 0."),
        new FuncSpec(
            0x410b60L,
            "CC2ERoomEditorView_OnZoomBack",
            "Moves currentViewRect to zoomForwardHistory, restores the last rectangle from " +
            "zoomBackHistory, then redraws."),
        new FuncSpec(
            0x410d00L,
            "CC2ERoomEditorView_OnUpdateZoomBack",
            "MFC update-UI handler. Enables Back when zoomBackHistory.count != 0.")
    };

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the recovered MapEditor.exe database first.");
            return;
        }

        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This script is deliberately limited to the 32-bit Map Editor target.");
            return;
        }

        // Exact-target guard.  This is the PUSH 0x1e8 in the View CreateObject path
        // used by the earlier target-specific passes.
        Address guard = toAddr(0x40e1b6L);
        if (getByte(guard) != (byte)0x68) {
            popup("Binary identity guard failed at 0040E1B6. Refusing semantic refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        println("=== Creatures Map Editor 1.08 - semantic refinement v2 ===");

        Structure view = requireStructure("CC2ERoomEditorView_refined");
        Structure cheese = requireStructure("C2ECheeseTool");

        Structure viewRect = buildViewRect();
        Structure selectedIds = buildSelectedRoomIdSet();
        Structure selectedRefs = buildSelectedRoomRefSet();
        Structure dequeIterator = buildViewRectDequeIterator();
        Structure history = buildViewRectHistory(dequeIterator);

        correctCheeseTool(cheese);
        refineView(view, cheese, viewRect, selectedIds, selectedRefs, history);

        Pointer viewPtr = new PointerDataType(view, 4, dtm);

        for (FuncSpec spec : viewHelpers) {
            monitor.checkCancelled();

            Function f = renameAndAnnotate(spec);
            if (f != null) {
                applyThisType(f, viewPtr, "CC2ERoomEditorView_refined");

                if (spec.returnsInt) {
                    try {
                        f.setReturnType(IntegerDataType.dataType, SourceType.USER_DEFINED);
                    }
                    catch (Exception e) {
                        println("[return-skip] " + f.getEntryPoint() + " " +
                            f.getName() + ": " + e.getMessage());
                    }
                }
            }
        }

        annotateKnownExistingFunctions();
        annotateUnresolvedHiddenCommands();

        println("");
        println("Running analysis on semantic/type changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Semantic v2 summary ===");
        println("Fields refined:        " + fieldsRefined);
        println("Functions renamed:     " + functionsRenamed);
        println("Existing names kept:   " + functionsKept);
        println("this types applied:    " + thisTypesApplied);
        println("this types skipped:    " + thisTypesSkipped);
        println("");
        println("Key correction: C2ECheeseTool is 0x1c bytes; view +0x13c is currentViewRect.");
        println("Hidden toolbar IDs 32824/32826 remain intentionally unresolved.");
    }

    // ---------------------------------------------------------------------
    // Type helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run the earlier refinement passes first.");
        }

        return (Structure)dt;
    }

    private Structure getOrCreate(String name, int size) {
        DataType existing = dtm.getDataType(CAT, name);

        if (existing instanceof Structure) {
            Structure s = (Structure)existing;

            if (s.getLength() != size) {
                s.setLength(size);
            }

            return s;
        }

        StructureDataType fresh =
            new StructureDataType(CAT, name, size, dtm);

        return (Structure)dtm.addDataType(
            fresh,
            DataTypeConflictHandler.REPLACE_HANDLER);
    }

    private DataType bytes(int n) {
        return new ArrayDataType(Undefined1DataType.dataType, n, 1);
    }

    private void field(
            Structure s,
            int offset,
            DataType type,
            int length,
            String name,
            String comment) {

        s.replaceAtOffset(offset, type, length, name, comment);
    }

    private void u32(
            Structure s,
            int offset,
            String name,
            String comment) {

        field(s, offset, Undefined4DataType.dataType, 4, name, comment);
    }

    private void s32(
            Structure s,
            int offset,
            String name,
            String comment) {

        field(s, offset, IntegerDataType.dataType, 4, name, comment);
    }

    private void blob(
            Structure s,
            int offset,
            int length,
            String name,
            String comment) {

        field(s, offset, bytes(length), length, name, comment);
    }

    private void setDescriptionSafe(Structure s, String description) {
        try {
            s.setDescription(description);
        }
        catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Newly identified semantic support types
    // ---------------------------------------------------------------------

    private Structure buildViewRect() {
        Structure s = getOrCreate("C2EViewRect", 0x10);
        s.deleteAll();
        s.setLength(0x10);

        s32(s, 0x00, "x1",
            "First horizontal bound. Four-dword rectangle copied as one 0x10-byte value.");
        s32(s, 0x04, "y1",
            "First vertical bound.");
        s32(s, 0x08, "x2",
            "Second horizontal bound.");
        s32(s, 0x0c, "y2",
            "Second vertical bound.");

        setDescriptionSafe(
            s,
            "16-byte editor view/viewport rectangle. Ordering is structurally proven as " +
            "four 32-bit coordinates; x1/y1/x2/y2 avoids overclaiming Win32 RECT semantics.");

        return s;
    }

    private Structure buildSelectedRoomIdSet() {
        Structure s = getOrCreate("C2ESelectedRoomIdSet", 0x14);
        s.deleteAll();
        s.setLength(0x14);

        u32(s, 0x00, "treeState00",
            "MSVC6 tree/set implementation state.");
        u32(s, 0x04, "headOrSentinel",
            "Tree head/sentinel pointer; clear-selection code walks/frees through this state.");
        u32(s, 0x08, "treeState08",
            "MSVC6 tree/set implementation state.");
        u32(s, 0x0c, "treeState0C",
            "MSVC6 tree/set implementation state.");
        u32(s, 0x10, "count",
            "Number of selected room IDs.");

        setDescriptionSafe(
            s,
            "0x14-byte MSVC6 tree/set-like container used for selected room IDs. " +
            "Node payload is a 32-bit ID.");

        return s;
    }

    private Structure buildSelectedRoomRefSet() {
        Structure s = getOrCreate("C2ESelectedRoomRefSet", 0x14);
        s.deleteAll();
        s.setLength(0x14);

        u32(s, 0x00, "treeState00",
            "MSVC6 tree/set implementation state.");
        u32(s, 0x04, "headOrSentinel",
            "Tree head/sentinel pointer.");
        u32(s, 0x08, "treeState08",
            "MSVC6 tree/set implementation state.");
        u32(s, 0x0c, "treeState0C",
            "MSVC6 tree/set implementation state.");
        u32(s, 0x10, "count",
            "Number of selected room/object references.");

        setDescriptionSafe(
            s,
            "0x14-byte MSVC6 tree/set-like container used alongside selectedRoomIds. " +
            "Node payload is an object/reference value rather than the plain room ID.");

        return s;
    }

    private Structure buildViewRectDequeIterator() {
        Structure s = getOrCreate("C2EViewRectDequeIterator", 0x10);
        s.deleteAll();
        s.setLength(0x10);

        u32(s, 0x00, "blockBegin",
            "Beginning of current deque storage block.");
        u32(s, 0x04, "blockEnd",
            "End of current deque storage block.");
        u32(s, 0x08, "current",
            "Current 0x10-byte rectangle element.");
        u32(s, 0x0c, "mapSlot",
            "Pointer into deque block-map; advanced by 4 when crossing a block.");

        setDescriptionSafe(
            s,
            "MSVC6 deque-like iterator recovered from the zoom-history push/pop code.");

        return s;
    }

    private Structure buildViewRectHistory(Structure iterator) {
        Structure s = getOrCreate("C2EViewRectHistory", 0x30);
        s.deleteAll();
        s.setLength(0x30);

        blob(s, 0x00, 0x14, "dequeState00",
            "Opaque MSVC6 deque control state.");
        field(s, 0x14, iterator, 0x10, "iterator",
            "Iterator/state explicitly manipulated by zoom history code.");
        blob(s, 0x24, 0x08, "dequeState24",
            "Remaining opaque deque control state.");
        u32(s, 0x2c, "count",
            "Number of 0x10-byte view rectangles in this history.");

        setDescriptionSafe(
            s,
            "0x30-byte MSVC6 deque-like history of C2EViewRect values. " +
            "Element size is 0x10 and +0x2c is the count.");

        return s;
    }

    // ---------------------------------------------------------------------
    // Correct / refine the view layout
    // ---------------------------------------------------------------------

    private void correctCheeseTool(Structure cheese) {
        if (cheese.getLength() == 0x1c) {
            println("[keep] C2ECheeseTool already corrected to 0x1c bytes.");
            return;
        }

        if (cheese.getLength() != 0x2c) {
            println("[cheese-skip] unexpected C2ECheeseTool size 0x" +
                Integer.toHexString(cheese.getLength()) +
                "; refusing automatic shrink.");
            return;
        }

        DataTypeComponent tail = cheese.getComponentAt(0x1c);

        if (tail != null) {
            String tailName = tail.getFieldName();

            if (tailName != null &&
                !tailName.isBlank() &&
                !"toolSpecificState".equals(tailName)) {

                println("[cheese-skip] +0x1c has user/non-generated field '" +
                    tailName + "'; refusing automatic shrink.");
                return;
            }
        }

        cheese.setLength(0x1c);
        setDescriptionSafe(
            cheese,
            "Cheese editor tool. Corrected boundary: 0x1c bytes (the common tool base only). " +
            "The following view bytes +0x13c..+0x14b are currentViewRect, not tool state.");

        println("[correct] C2ECheeseTool size 0x2c -> 0x1c.");
    }

    private void refineView(
            Structure view,
            Structure cheese,
            Structure viewRect,
            Structure selectedIds,
            Structure selectedRefs,
            Structure history) {

        replaceGeneratedField(
            view, 0x120,
            new String[] { "cheeseTool" },
            cheese, 0x1c,
            "cheeseTool",
            "Embedded Cheese editor tool. Correct size is 0x1c; it ends at +0x13b.");

        replaceGeneratedField(
            view, 0x13c,
            new String[] { },
            viewRect, 0x10,
            "currentViewRect",
            "Current editor viewport rectangle. Zoom Back/Forward save and restore this exact 16-byte value.");

        replaceGeneratedField(
            view, 0x14c,
            new String[] { "state14C" },
            IntegerDataType.dataType, 4,
            "selectedMetaroomId",
            "Selected metaroom identifier. Constructor/ClearSelection set -1 for no metaroom.");

        replaceGeneratedField(
            view, 0x150,
            new String[] { "stateObject150" },
            selectedIds, 0x14,
            "selectedRoomIds",
            "Selected room IDs. ClearSelection empties this tree/set-like container.");

        replaceGeneratedField(
            view, 0x164,
            new String[] { "stateObject164" },
            selectedRefs, 0x14,
            "selectedRoomRefs",
            "Selected room/object references. Maintained in parallel with selectedRoomIds.");

        replaceGeneratedField(
            view, 0x178,
            new String[] { "stateObject178" },
            history, 0x30,
            "zoomBackHistory",
            "View rectangles available to the Back command (ID 32791).");

        replaceGeneratedField(
            view, 0x1a8,
            new String[] { "stateObject1A8" },
            history, 0x30,
            "zoomForwardHistory",
            "View rectangles available to the Forward command (ID 32792).");

        replaceGeneratedField(
            view, 0x1dc,
            new String[] { "state1DC" },
            IntegerDataType.dataType, 4,
            "caTimerPhase",
            "Timer path increments this modulo 20: (caTimerPhase + 1) % 20.");

        replaceGeneratedField(
            view, 0x1e0,
            new String[] { "state1E0" },
            UnsignedIntegerDataType.dataType, 4,
            "timerId",
            "Return value from SetTimer; consumed by timer/destroy paths.");

        replaceGeneratedField(
            view, 0x1e6,
            new String[] { "caStateFlag2" },
            BooleanDataType.dataType, 1,
            "updateCAFromGameEnabled",
            "Toggled by 'Update CA from Game' (ID 32829); timer path refreshes room CA data when enabled.");

        // Do not rename +0x1e5 yet.  It is toggled by hidden toolbar command 32826
        // and causes timer dispatch to document command 32824, but the exact UI
        // semantics are not recoverable from the present evidence.
        DataTypeComponent unknown = view.getComponentAt(0x1e5);
        if (unknown != null && "caStateFlag1".equals(unknown.getFieldName())) {
            try {
                unknown.setComment(
                    "UNRESOLVED: hidden toolbar command 32826 toggles this. When true, " +
                    "the timer calls document command/helper 32824. Kept generic pending stronger evidence.");
            }
            catch (Exception ignored) {
            }
        }

        setDescriptionSafe(
            view,
            "Creatures Map Editor view, semantic refinement v2. " +
            "Important corrected region: +0x120 CheeseTool[0x1c], +0x13c currentViewRect[0x10], " +
            "+0x14c selection state, +0x178/+0x1a8 zoom histories.");

        setPlateComment(
            toAddr(0x40e240L),
            "CC2ERoomEditorView constructor.\n" +
            "Corrected v2 layout:\n" +
            "+048 AddRoom[0x40], +088 AddMetaroom[0x1c], +0A4 Select[0x30],\n" +
            "+0D4 SelectMetaroom[0x30], +104 Zoom[0x1c], +120 Cheese[0x1c],\n" +
            "+13C currentViewRect[0x10], +14C selectedMetaroomId,\n" +
            "+150 selectedRoomIds[0x14], +164 selectedRoomRefs[0x14],\n" +
            "+178 zoomBackHistory[0x30], +1A8 zoomForwardHistory[0x30].");
    }

    private void replaceGeneratedField(
            Structure s,
            int offset,
            String[] generatedNames,
            DataType type,
            int length,
            String newName,
            String comment) {

        DataTypeComponent containing = s.getComponentContaining(offset);

        if (containing != null && containing.getOffset() <= offset) {
            String currentName = containing.getFieldName();

            boolean safe =
                currentName == null ||
                currentName.isBlank() ||
                newName.equals(currentName);

            if (!safe) {
                for (String generated : generatedNames) {
                    if (generated.equals(currentName)) {
                        safe = true;
                        break;
                    }
                }
            }

            // Special correction case: +0x13c was formerly inside the oversized
            // generated cheeseTool component.
            if (!safe &&
                offset == 0x13c &&
                containing.getOffset() == 0x120 &&
                "cheeseTool".equals(currentName)) {

                safe = true;
            }

            if (!safe) {
                println("[field-preserve] " + s.getName() + " +0x" +
                    Integer.toHexString(offset) +
                    " currently belongs to '" + currentName +
                    "'; not overwriting user/non-generated field.");
                return;
            }

            // If a sized component contains this offset, clear at its real start
            // so the structure length and all subsequent offsets remain fixed.
            if (containing.getFieldName() != null ||
                containing.getDataType() != DataType.DEFAULT) {

                s.clearAtOffset(containing.getOffset());
            }
        }

        try {
            s.replaceAtOffset(offset, type, length, newName, comment);
            fieldsRefined++;
            println("[field] " + s.getName() + " +0x" +
                Integer.toHexString(offset) + " -> " + newName);
        }
        catch (Exception e) {
            println("[field-fail] " + s.getName() + " +0x" +
                Integer.toHexString(offset) + " " + newName +
                ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Function naming / typing
    // ---------------------------------------------------------------------

    private Function renameAndAnnotate(FuncSpec spec) {
        Address a = toAddr(spec.address);
        Function f = getFunctionAt(a);

        if (f == null) {
            println("[function-missing] " + a + " " + spec.name);
            return null;
        }

        try {
            if (f.getName().equals(spec.name)) {
                functionsKept++;
            }
            else if (f.getName().startsWith("FUN_") ||
                     f.getName().startsWith("thunk_FUN_") ||
                     f.getSymbol().getSource() == SourceType.DEFAULT) {

                f.setName(spec.name, SourceType.USER_DEFINED);
                functionsRenamed++;
                println("[rename] " + a + " -> " + spec.name);
            }
            else {
                functionsKept++;
                println("[keep] " + a + " " + f.getName() +
                    " (candidate semantic name: " + spec.name + ")");
            }

            appendRepeatableComment(a, "[C2E semantic v2] " + spec.comment);
        }
        catch (Exception e) {
            println("[rename-fail] " + a + " " + spec.name +
                ": " + e.getMessage());
        }

        return f;
    }

    private void applyThisType(
            Function f,
            Pointer ptr,
            String typeName) {

        try {
            if (f.hasVarArgs()) {
                println("[this-skip] " + f.getEntryPoint() +
                    " " + f.getName() + " : varargs");
                thisTypesSkipped++;
                return;
            }

            Register ecx = currentProgram.getRegister("ECX");

            if (ecx == null) {
                println("[this-fail] ECX register unavailable.");
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

            newParams.add(
                new ParameterImpl(
                    "this",
                    ptr,
                    ecx,
                    currentProgram,
                    SourceType.USER_DEFINED));

            for (Parameter p : oldParams) {
                if (p.isAutoParameter()) {
                    continue;
                }

                if ("this".equals(p.getName())) {
                    continue;
                }

                VariableStorage storage = p.getVariableStorage();

                if (storage != null &&
                    storage.isRegisterStorage() &&
                    storage.getRegister() != null &&
                    storage.getRegister().equals(ecx)) {

                    println("[drop-ecx-param] " + f.getEntryPoint() +
                        " " + f.getName() + " dropping " +
                        p.getName() + "{" + storage + "}");
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

    private void appendRepeatableComment(Address a, String text) {
        String old = getRepeatableComment(a);

        if (old == null || old.isBlank()) {
            setRepeatableComment(a, text);
        }
        else if (!old.contains(text)) {
            setRepeatableComment(a, old + "\n" + text);
        }
    }

    // ---------------------------------------------------------------------
    // Evidence on functions which are already correctly named
    // ---------------------------------------------------------------------

    private void annotateKnownExistingFunctions() {
        appendRepeatableComment(
            toAddr(0x4109a0L),
            "[C2E semantic v2] OnZoomForward pushes currentViewRect into zoomBackHistory " +
            "and restores one rectangle from zoomForwardHistory.");

        appendRepeatableComment(
            toAddr(0x4111e0L),
            "[C2E semantic v2] Timer increments caTimerPhase modulo 20. timerId is at +0x1e0. " +
            "When updateCAFromGameEnabled (+0x1e6) and colourRoomsCAIndex is active, " +
            "the live-game CA refresh/render path executes.");

        appendRepeatableComment(
            toAddr(0x411760L),
            "[C2E semantic v2] 'Update CA from Game' (ID 32829) toggles " +
            "updateCAFromGameEnabled at view +0x1e6.");

        appendRepeatableComment(
            toAddr(0x411780L),
            "[C2E semantic v2] MFC update-UI handler reads updateCAFromGameEnabled (+0x1e6).");
    }

    private void annotateUnresolvedHiddenCommands() {
        appendRepeatableComment(
            toAddr(0x40b210L),
            "[C2E unresolved] Hidden toolbar command ID 32824. Timer calls this while " +
            "view +0x1e5 is enabled. It invokes world-model logic using doc.caRates (+0x138) " +
            "and doc.tailState (+0x148). Semantic command name intentionally not guessed.");

        appendRepeatableComment(
            toAddr(0x411570L),
            "[C2E unresolved] Hidden toolbar command ID 32826 toggles view +0x1e5 and " +
            "disables updateCAFromGameEnabled (+0x1e6) when activated. Exact UI meaning " +
            "remains unresolved.");
    }
}
