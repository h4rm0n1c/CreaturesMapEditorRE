// Creatures Map Editor 1.08 — high-level map operation recovery.
//
// Prerequisites:
//   RecoverC2EMapEditorMFC.java
//   RecoverC2EMapEditorClassLayout.java
//   RefineC2EMapEditorStructures.java
//   ApplyC2EMapEditorTypes.java v1.4
//   RefineC2EMapEditorSemanticsV2.java
//   RefineC2EMapEditorDocumentIO.java
//   RefineC2EMapEditorWorldModel.java
//
// Evidence hierarchy:
//   1. Exact MapEditor.exe machine code / current Ghidra DB.
//   2. Docking Station-era webc2e/emscripten-c2e engine/Map source.
//   3. Earlier C3 source and supplied .2er samples.
//
// This pass recovers the compact editor's high-level add/remove/find operations.
// It does NOT import the much larger runtime C2e Map/Room/MetaRoom layouts.
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

public class RefineC2EMapEditorOperations extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;
    private Structure docType;
    private Structure worldType;
    private Structure metaRoomType;

    private Pointer docPtr;
    private Pointer worldPtr;
    private Pointer metaRoomPtr;

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

        // -------------------------------------------------------------
        // Thin CDocument wrappers around this->worldModel (+0x54)
        // -------------------------------------------------------------
        new FuncSpec(
            0x408bd0L,
            "CC2ERoomEditorDoc_AddMetaRoom",
            "doc",
            "Thin document wrapper around C2EWorldModel_AddMetaRoom. Returns the newly allocated metaroom ID, or -1 on failure.",
            "int"),

        new FuncSpec(
            0x408bf0L,
            "CC2ERoomEditorDoc_InsertMetaRoomWithId",
            "doc",
            "Document wrapper used when restoring/inserting an existing compact metaroom with an explicit ID and shared/reference wrapper.",
            "unknown"),

        new FuncSpec(
            0x408d50L,
            "CC2ERoomEditorDoc_RemoveMetaRoom",
            "doc",
            "Thin document wrapper around C2EWorldModel_RemoveMetaRoom(metaRoomId).",
            "unknown"),

        new FuncSpec(
            0x408d60L,
            "CC2ERoomEditorDoc_AddRoom",
            "doc",
            "Thin document wrapper around C2EWorldModel_AddRoom. Returns the first available integer room ID.",
            "int"),

        new FuncSpec(
            0x408d70L,
            "CC2ERoomEditorDoc_InsertRoomWithId",
            "doc",
            "Document wrapper used by undo/restore-style paths to reinsert an existing editor Room under an explicit room ID.",
            "unknown"),

        new FuncSpec(
            0x408d90L,
            "CC2ERoomEditorDoc_RemoveRoom",
            "doc",
            "Thin document wrapper around C2EWorldModel_RemoveRoom(roomId).",
            "unknown"),

        // -------------------------------------------------------------
        // C2EWorldModel operations
        // -------------------------------------------------------------
        new FuncSpec(
            0x422530L,
            "C2EWorldModel_AddMetaRoom",
            "world",
            "Scans metaroom IDs from zero for the first free key, allocates a 0x48 C2EEditorMetaRoom, inserts it into world.metarooms and returns the chosen ID.",
            "int"),

        new FuncSpec(
            0x422770L,
            "C2EWorldModel_InsertMetaRoomWithId",
            "world",
            "Inserts an existing compact metaroom/reference under an explicit ID. Refuses duplicate IDs.",
            "unknown"),

        new FuncSpec(
            0x422820L,
            "C2EWorldModel_RemoveMetaRoom",
            "world",
            "Finds and erases a metaroom by integer ID, then invalidates the derived world caches.",
            "unknown"),

        new FuncSpec(
            0x422890L,
            "C2EWorldModel_FindMetaRoomById",
            "world",
            "Finds a compact metaroom by its outer world-tree key and returns/copies the editor's shared/reference wrapper.",
            "unknown"),

        new FuncSpec(
            0x422930L,
            "C2EWorldModel_FindMetaRoomIdAtPoint",
            "world",
            "Walks metarooms, obtains each 4-int bounds rectangle and returns the first containing metaroom tree key, else -1. The three scalar arguments are the editor's point/test parameters; their exact formal names remain unforced.",
            "int"),

        new FuncSpec(
            0x4229e0L,
            "C2EWorldModel_FindRoomIdAtPoint",
            "world",
            "Walks metarooms and delegates point containment to C2EEditorMetaRoom_FindRoomIdAtPoint; returns a room ID or -1.",
            "int"),

        new FuncSpec(
            0x422cc0L,
            "C2EWorldModel_AddRoom",
            "world",
            "Finds the first unused room ID, allocates a 0x44 C2EEditorRoom from supplied geometry/state, inserts it into the containing metaroom and returns the chosen room ID.",
            "int"),

        new FuncSpec(
            0x422de0L,
            "C2EWorldModel_InsertRoomWithId",
            "world",
            "Determines the containing metaroom from the room's geometry, inserts the existing room/reference under the supplied room ID, and invalidates derived caches on success.",
            "unknown"),

        new FuncSpec(
            0x422f90L,
            "C2EWorldModel_RemoveRoom",
            "world",
            "Walks metarooms until one removes the requested room ID; invalidates derived caches on success.",
            "unknown"),

        new FuncSpec(
            0x423010L,
            "C2EWorldModel_IsRoomIdValid",
            "world",
            "Returns true if any metaroom contains the supplied room ID. AddRoom uses this to search for the first free ID.",
            "unknown"),

        new FuncSpec(
            0x423080L,
            "C2EWorldModel_FindRoomById",
            "world",
            "Walks metarooms and returns/copies the shared/reference wrapper for the requested room ID, or an empty wrapper if absent.",
            "unknown"),

        new FuncSpec(
            0x423230L,
            "C2EWorldModel_GetMetaRoomIdForRoomId",
            "world",
            "Walks metarooms and returns the outer metaroom tree key containing roomId, or -1.",
            "int"),

        new FuncSpec(
            0x423460L,
            "C2EWorldModel_InvalidateDerivedCaches",
            "world",
            "Sets world.derivedCachesValid (+0x60) to false.",
            "void"),

        new FuncSpec(
            0x423470L,
            "C2EWorldModel_EnsureDerivedCaches",
            "world",
            "Returns immediately when derivedCachesValid is true. Otherwise rebuilds the derived indices from metaroom/room and door-relation state, then sets derivedCachesValid to true.",
            "void"),

        // -------------------------------------------------------------
        // Compact editor metaroom operations
        // -------------------------------------------------------------
        new FuncSpec(
            0x415e50L,
            "C2EEditorMetaRoom_HasRoomId",
            "metaroom",
            "Tests roomsById for an integer room ID.",
            "unknown"),

        new FuncSpec(
            0x415eb0L,
            "C2EEditorMetaRoom_FindRoomById",
            "metaroom",
            "Finds roomId in roomsById and returns/copies the editor's room reference wrapper.",
            "unknown"),

        new FuncSpec(
            0x415f70L,
            "C2EEditorMetaRoom_FindRoomIdAtPoint",
            "metaroom",
            "Walks roomsById and asks each compact Room whether its geometry contains the supplied point/test parameters. Returns the room tree key or -1.",
            "int"),

        new FuncSpec(
            0x416310L,
            "C2EEditorMetaRoom_RemoveRoom",
            "metaroom",
            "Finds roomId in roomsById and erases that tree entry. Returns success/failure.",
            "unknown"),

        new FuncSpec(
            0x416380L,
            "C2EEditorMetaRoom_InsertRoomWithId",
            "metaroom",
            "Inserts the supplied compact Room/reference into roomsById under an explicit integer ID.",
            "unknown")
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
        //   00422CC0 = FS exception-prologue byte 0x64
        //   00423460 = mov byte ptr [ecx+60],0  => C6 41 60 00
        if (getByte(toAddr(0x422cc0L)) != (byte)0x64 ||
            getByte(toAddr(0x423460L)) != (byte)0xc6 ||
            getByte(toAddr(0x423461L)) != (byte)0x41 ||
            getByte(toAddr(0x423462L)) != (byte)0x60 ||
            getByte(toAddr(0x423463L)) != (byte)0x00) {

            popup("MapEditor 1.08 identity guard failed. Refusing target-specific operation refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        docType = requireStructure("CC2ERoomEditorDoc_refined");
        worldType = requireStructure("C2EWorldModel");
        metaRoomType = requireStructure("C2EEditorMetaRoom");

        docPtr = new PointerDataType(docType, dtm);
        worldPtr = new PointerDataType(worldType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);

        println("=== Creatures Map Editor 1.08 - map operation recovery ===");
        println("Using exact binary as ground truth; DS Map source only as semantic cross-reference.");
        println("");

        refineWorldCacheFlag();

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) {
                continue;
            }

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E map operations] " + spec.comment);

            if ("doc".equals(spec.owner)) {
                applyThisType(f, docPtr, "CC2ERoomEditorDoc_refined");
            }
            else if ("world".equals(spec.owner)) {
                applyThisType(f, worldPtr, "C2EWorldModel");
            }
            else if ("metaroom".equals(spec.owner)) {
                applyThisType(f, metaRoomPtr, "C2EEditorMetaRoom");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateOperationRelationships();
        annotateSourceCrossMatch();

        println("");
        println("Running analysis on map-operation changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Map operation summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("High-level operation chain now named:");
        println("  Doc -> WorldModel -> EditorMetaRoom");
        println("  Add/insert/remove/find Room and MetaRoom");
        println("  point lookup");
        println("  derived-cache invalidation/rebuild");
    }

    // ---------------------------------------------------------------------
    // Field refinement
    // ---------------------------------------------------------------------

    private void refineWorldCacheFlag() {
        safeField(
            worldType,
            0x60,
            BooleanDataType.dataType,
            1,
            "derivedCachesValid",
            "False means WorldModel +0x38/+0x4C derived indices must be rebuilt. " +
            "00423460 clears this byte; 00423470 rebuilds and sets it true.",
            "cacheValid",
            "derivedCacheValid",
            "field_060",
            "field_60");

        setDescriptionSafe(
            worldType,
            "Top-level compact Map Editor world model. +0x60 is a proven derived-cache validity flag; " +
            "+0x38/+0x4C remain intentionally opaque derived indices.");
    }

    // ---------------------------------------------------------------------
    // Evidence comments
    // ---------------------------------------------------------------------

    private void annotateOperationRelationships() {
        appendRepeatableComment(
            toAddr(0x422cc0L),
            "[C2E operation chain]\n" +
            "AddRoom:\n" +
            "  1. scan room IDs with IsRoomIdValid\n" +
            "  2. allocate C2EEditorRoom[0x44]\n" +
            "  3. C2EEditorRoom_ctor\n" +
            "  4. InsertRoomWithId\n" +
            "  5. return chosen integer ID.");

        appendRepeatableComment(
            toAddr(0x422de0L),
            "[C2E operation chain]\n" +
            "InsertRoomWithId derives a point from room.geometry (xLeft/yLeftCeiling), " +
            "finds the containing metaroom, calls C2EEditorMetaRoom_InsertRoomWithId, " +
            "then invalidates world derived caches.");

        appendRepeatableComment(
            toAddr(0x423470L),
            "[C2E cache lifecycle]\n" +
            "If derivedCachesValid != 0, return.\n" +
            "Otherwise rebuild derived indices from metaroom/room geometry and doorRelations, " +
            "then set derivedCachesValid = 1.");

        setEOLComment(
            toAddr(0x423460L),
            "world->derivedCachesValid = false;");

        setEOLComment(
            toAddr(0x42378eL),
            "world->derivedCachesValid = true;");

        appendRepeatableComment(
            toAddr(0x408bd0L),
            "[C2E document facade] CC2ERoomEditorDoc keeps the MFC/UI layer thin: this wrapper simply forwards to worldModel at document +0x54.");

        appendRepeatableComment(
            toAddr(0x408d60L),
            "[C2E document facade] Add Room UI/tool code reaches the compact map model through this document wrapper.");
    }

    private void annotateSourceCrossMatch() {
        appendRepeatableComment(
            toAddr(0x422820L),
            "[C2E DS-source cross-match] Docking Station-era Map exposes RemoveMetaRoom(metaRoomID); " +
            "the editor has the same semantic operation over its compact metaroom tree.");

        appendRepeatableComment(
            toAddr(0x422cc0L),
            "[C2E DS-source cross-match] Docking Station-era Map exposes AddRoom(metaRoomID, " +
            "xLeft, xRight, yLeftCeiling, yRightCeiling, yLeftFloor, yRightFloor, roomID). " +
            "The editor performs the same conceptual operation using C2EEditorRoom and its six-int geometry.");

        appendRepeatableComment(
            toAddr(0x423010L),
            "[C2E DS-source cross-match] Runtime Map calls the concept IsRoomIDValid(roomID). " +
            "This editor implementation tests all compact metaroom room trees for the ID.");

        appendRepeatableComment(
            toAddr(0x422930L),
            "[C2E DS-source cross-match] Runtime Map exposes GetMetaRoomIDForPoint. " +
            "This compact editor helper returns the containing metaroom's tree key or -1.");

        appendRepeatableComment(
            toAddr(0x4229e0L),
            "[C2E DS-source cross-match] Runtime Map exposes GetRoomIDForPoint. " +
            "This compact editor helper delegates point containment to each metaroom and returns room ID or -1.");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run RefineC2EMapEditorWorldModel.java first.");
        }

        return (Structure)dt;
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
        catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Function creation / naming
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

    // ---------------------------------------------------------------------
    // this pointer / return typing
    // ---------------------------------------------------------------------

    private void applyThisType(Function f, Pointer ptr, String typeName) {
        try {
            if (f.hasVarArgs()) {
                thisTypesSkipped++;
                println("[this-skip] " + f.getEntryPoint() +
                    " " + f.getName() + ": varargs");
                return;
            }

            Register ecx = currentProgram.getRegister("ECX");

            if (ecx == null) {
                thisTypesSkipped++;
                println("[this-fail] ECX unavailable.");
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

            if ("void".equals(kind)) {
                type = VoidDataType.dataType;
            }
            else if ("int".equals(kind)) {
                type = IntegerDataType.dataType;
            }

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
