// Creatures Map Editor 1.08 — derived geometry / door cache recovery.
//
// Prerequisites:
//   RecoverC2EMapEditorMFC.java
//   RecoverC2EMapEditorClassLayout.java
//   RefineC2EMapEditorStructures.java
//   ApplyC2EMapEditorTypes.java v1.4
//   RefineC2EMapEditorSemanticsV2.java
//   RefineC2EMapEditorDocumentIO.java
//   RefineC2EMapEditorWorldModel.java
//   RefineC2EMapEditorOperations.java
//
// IMPORTANT CORRECTION:
// The previous C2EEditorDoorRelation[0x0c] was only the SERIALIZED SUBSET.
// The in-memory objects are 0x24-byte geometric boundary segments:
//
//   +00 roomId1
//   +04 roomId2
//   +08 permeability
//   +0c edgeCode
//   +10 length
//   +14 start {x,y}
//   +1c end   {x,y}
//
// The .2er writer persists only +00, +04, literal marker 1, and +08.
//
// The same 0x24 structural shape is used for generated door openings and
// impermeable wall segments, so this pass calls the common type
// C2EEditorBoundarySegment rather than importing the larger runtime C2e Door.
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

public class RefineC2EMapEditorDerivedGeometry extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure worldType;
    private Structure metaRoomType;
    private Structure roomType;
    private Structure geometryType;

    private Structure intPointType;
    private Structure boundaryType;
    private Structure boundaryTreeType;

    private Pointer worldPtr;
    private Pointer metaRoomPtr;
    private Pointer roomPtr;
    private Pointer geometryPtr;

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

    private final FuncSpec[] FUNCTIONS = new FuncSpec[] {
        new FuncSpec(
            0x416a80L,
            "C2EEditorMetaRoom_BuildDoorSegments",
            "metaroom",
            "Compares room pairs in roomsById. When two room geometries share a usable boundary, allocates a 0x24 C2EEditorBoundarySegment with default permeability 100, room IDs, edge code, integer length and segment endpoints, then inserts it into the supplied temporary collection.",
            "void"),

        new FuncSpec(
            0x416c50L,
            "C2EEditorMetaRoom_BuildWallSegments",
            "metaroom",
            "Iterates roomsById and asks each room geometry to emit the remaining impermeable room-edge segments after subtracting door openings.",
            "void"),

        new FuncSpec(
            0x41a580L,
            "C2EEditorRoom_FindSharedBoundarySegment",
            "room",
            "Thin room wrapper over C2ERoomGeometry_FindSharedBoundarySegment. Supplies this->geometry and the other room's geometry, plus output endpoints and edge code.",
            "bool"),

        new FuncSpec(
            0x41b2d0L,
            "C2ERoomGeometry_FindSharedBoundarySegment",
            "geometry",
            "Tests two six-integer room geometries for a shared/overlapping boundary. On success writes integer start/end points plus an edge code 0..3. This is the geometric basis for generated doors.",
            "bool"),

        new FuncSpec(
            0x41b570L,
            "C2ERoomGeometry_BuildWallSegments",
            "geometry",
            "Builds the four room boundary edges, subtracts/splits them around generated door openings, and emits the remaining 0x24 boundary segments with permeability 0 into the supplied wall collection.",
            "void"),

        new FuncSpec(
            0x422c20L,
            "C2EWorldModel_FindDoorByRoomPair",
            "world",
            "Ensures derived caches exist, then searches current doorSegments for the unordered roomId1/roomId2 pair and returns/copies the editor reference wrapper for the matching geometric door.",
            "unknown",
            "C2EWorldModel_FindDoorRelation"),

        new FuncSpec(
            0x423470L,
            "C2EWorldModel_EnsureDerivedCaches",
            "world",
            "Rebuilds generated door geometry and wall geometry when derivedCachesValid is false. Existing door permeability is preserved; inactiveDoorCache allows permeability to survive temporary adjacency loss during geometry edits.",
            "void"),

        new FuncSpec(
            0x4237b0L,
            "C2EWorldModel_Draw",
            "world",
            "View drawing helper. Ensures derived caches, draws current doorSegments using permeability-dependent colouring and start/end coordinates, then draws wallSegments using their start/end coordinates.",
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
        // 00416AF7: push 0x24    (generated boundary object allocation)
        // 0042348E: mov al,[edi+0x60] (derivedCachesValid)
        if (getByte(toAddr(0x416af7L)) != (byte)0x6a ||
            getByte(toAddr(0x416af8L)) != (byte)0x24 ||
            getByte(toAddr(0x42348eL)) != (byte)0x8a ||
            getByte(toAddr(0x423490L)) != (byte)0x60) {

            popup("MapEditor 1.08 identity guard failed. Refusing derived-geometry refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        worldType = requireStructure("C2EWorldModel");
        metaRoomType = requireStructure("C2EEditorMetaRoom");
        roomType = requireStructure("C2EEditorRoom");
        geometryType = requireStructure("C2ERoomGeometry");

        worldPtr = new PointerDataType(worldType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);
        roomPtr = new PointerDataType(roomType, dtm);
        geometryPtr = new PointerDataType(geometryType, dtm);

        println("=== Creatures Map Editor 1.08 - derived geometry recovery ===");
        println("Correcting the earlier 0x0c door-relation assumption.");
        println("");

        buildCorrectedTypes();
        refineWorldFields();
        deprecateOldRelationType();

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(
                spec.address,
                spec.name,
                spec.replaceNames);

            if (f == null) {
                continue;
            }

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E derived geometry] " + spec.comment);

            if ("world".equals(spec.owner)) {
                applyThisType(f, worldPtr, "C2EWorldModel");
            }
            else if ("metaroom".equals(spec.owner)) {
                applyThisType(f, metaRoomPtr, "C2EEditorMetaRoom");
            }
            else if ("room".equals(spec.owner)) {
                applyThisType(f, roomPtr, "C2EEditorRoom");
            }
            else if ("geometry".equals(spec.owner)) {
                applyThisType(f, geometryPtr, "C2ERoomGeometry");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateCacheAlgorithm();
        annotateSerializationCorrection();
        annotateDrawingEvidence();
        annotateSourceCrossMatch();

        println("");
        println("Running analysis on derived-geometry corrections...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Derived geometry summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("Corrected WorldModel:");
        println("  +0x24 doorSegments       current generated doors; permeability persists");
        println("  +0x38 wallSegments       derived impermeable boundary pieces");
        println("  +0x4c inactiveDoorCache  permeability memory for vanished adjacencies");
        println("  +0x60 derivedCachesValid");
        println("");
        println("The old C2EEditorDoorRelation type is retained only as a deprecated artifact");
        println("so existing DB references are not destructively deleted.");
    }

    // ---------------------------------------------------------------------
    // Corrected data model
    // ---------------------------------------------------------------------

    private void buildCorrectedTypes() {
        intPointType = getOrCreate("C2EIntPoint", 0x08);

        safeField(
            intPointType, 0x00,
            IntegerDataType.dataType, 4,
            "x",
            "Integer world X coordinate.");

        safeField(
            intPointType, 0x04,
            IntegerDataType.dataType, 4,
            "y",
            "Integer world Y coordinate.");

        setDescriptionSafe(
            intPointType,
            "Integer point used by the compact editor's generated door/wall geometry.");

        boundaryType = getOrCreate("C2EEditorBoundarySegment", 0x24);

        safeField(
            boundaryType, 0x00,
            IntegerDataType.dataType, 4,
            "roomId1",
            "First parent room ID. For current door segments, the lower/ordered room ID is normally stored here.");

        safeField(
            boundaryType, 0x04,
            IntegerDataType.dataType, 4,
            "roomId2",
            "Second parent room ID. Wall-segment bookkeeping may use the same structural slot with no second traversable room.");

        safeField(
            boundaryType, 0x08,
            IntegerDataType.dataType, 4,
            "permeability",
            "Door permeability. Generated shared boundaries default to 100; derived solid wall segments are emitted with 0.");

        safeField(
            boundaryType, 0x0c,
            IntegerDataType.dataType, 4,
            "edgeCode",
            "Room-edge/orientation code in range 0..3. When parent ordering is reversed, MapEditor stores 3-edgeCode. Exact runtime DIRECTION_* enum mapping is deliberately not forced.");

        safeField(
            boundaryType, 0x10,
            IntegerDataType.dataType, 4,
            "length",
            "Integer Euclidean length computed from start/end delta.");

        safeField(
            boundaryType, 0x14,
            intPointType, 0x08,
            "start",
            "Boundary-segment start point.");

        safeField(
            boundaryType, 0x1c,
            intPointType, 0x08,
            "end",
            "Boundary-segment end point.");

        setDescriptionSafe(
            boundaryType,
            "0x24 compact editor boundary segment. Shared-room instances are doors; permeability-0 instances in wallSegments are solid room-edge pieces. The .2er writer persists only roomId1, roomId2 and permeability (plus a literal legacy marker 1).");

        boundaryTreeType = getOrCreate("C2EEditorBoundarySegmentTree", 0x14);

        safeField(
            boundaryTreeType, 0x00,
            new ArrayDataType(Undefined1DataType.dataType, 0x10, 1),
            0x10,
            "treeState",
            "Opaque VC6 ordered-tree state. Tree nodes carry editor reference wrappers to C2EEditorBoundarySegment.");

        safeField(
            boundaryTreeType, 0x10,
            UnsignedIntegerDataType.dataType, 4,
            "count",
            "Number of boundary-segment entries.");

        setDescriptionSafe(
            boundaryTreeType,
            "20-byte VC6 tree/map-like container holding references to C2EEditorBoundarySegment objects.");
    }

    private void refineWorldFields() {
        safeField(
            worldType, 0x24,
            boundaryTreeType, 0x14,
            "doorSegments",
            "Current generated geometric doors between adjacent rooms. Rebuilt from room geometry; existing/inactive cached permeability is merged into generated segments. The .2er writer serializes this collection's room IDs and permeability.",
            "doorRelations",
            "serializedRelationIndex");

        safeField(
            worldType, 0x38,
            boundaryTreeType, 0x14,
            "wallSegments",
            "Derived impermeable room-edge segments left after subtracting door openings. Rebuilt by C2ERoomGeometry_BuildWallSegments and drawn as solid boundaries.",
            "derivedIndex38");

        safeField(
            worldType, 0x4c,
            boundaryTreeType, 0x14,
            "inactiveDoorCache",
            "Editor-specific cache for door settings whose room adjacency temporarily disappears. During cache rebuild it can restore a cached segment's permeability when the same room pair becomes adjacent again.",
            "derivedIndex4C");

        setDescriptionSafe(
            worldType,
            "Top-level compact Map Editor world model. Derived geometry: +0x24 current doorSegments, +0x38 wallSegments, +0x4C inactiveDoorCache, +0x60 derivedCachesValid.");
    }

    private void deprecateOldRelationType() {
        DataType old = dtm.getDataType(CAT, "C2EEditorDoorRelation");

        if (old instanceof Structure) {
            Structure s = (Structure)old;

            setDescriptionSafe(
                s,
                "DEPRECATED/INCOMPLETE type from an earlier recovery pass. Its 0x0c fields describe only the subset serialized to .2er. The real in-memory object is C2EEditorBoundarySegment[0x24]. Do not use this type for live object layout.");

            println("[correction] Marked C2EEditorDoorRelation as deprecated serialized-subset type.");
        }

        DataType oldTree = dtm.getDataType(CAT, "C2EEditorDoorRelationTree");

        if (oldTree instanceof Structure) {
            setDescriptionSafe(
                (Structure)oldTree,
                "DEPRECATED name from earlier pass. Use C2EEditorBoundarySegmentTree. The world +0x24 field is corrected by this script.");
        }
    }

    // ---------------------------------------------------------------------
    // Evidence annotations
    // ---------------------------------------------------------------------

    private void annotateCacheAlgorithm() {
        appendRepeatableComment(
            toAddr(0x423470L),
            "[C2E cache algorithm]\n" +
            "1. Build a temporary collection of geometric doors by comparing room pairs.\n" +
            "2. Match generated doors against current doorSegments by roomId pair.\n" +
            "3. Matching current door -> copy its permeability into generated door.\n" +
            "4. Unmatched generated door -> search inactiveDoorCache and restore cached permeability if present.\n" +
            "5. Unmatched old/current door -> preserve it in inactiveDoorCache.\n" +
            "6. Replace current doorSegments with the generated collection.\n" +
            "7. Rebuild wallSegments by subtracting door openings from all room edges.\n" +
            "8. derivedCachesValid = true.");

        setEOLComment(
            toAddr(0x423569L),
            "Matched current door: preserve permeability (+0x08) on newly generated geometric segment.");

        setEOLComment(
            toAddr(0x4235a9L),
            "Search world.inactiveDoorCache (+0x4C) for a reappearing room-pair door.");

        setEOLComment(
            toAddr(0x4235f8L),
            "Restore cached permeability into the newly generated door.");

        setEOLComment(
            toAddr(0x423613L),
            "Unmatched old/current door is inserted into inactiveDoorCache.");

        setEOLComment(
            toAddr(0x423673L),
            "world.wallSegments (+0x38) rebuild begins.");

        setEOLComment(
            toAddr(0x42369eL),
            "Per-metaroom wall build: subtract generated door openings from room edges.");
    }

    private void annotateSerializationCorrection() {
        appendRepeatableComment(
            toAddr(0x423be0L),
            "[C2E corrected .2er door persistence]\n" +
            "World writer calls EnsureDerivedCaches, then serializes each full 0x24 doorSegments object using only:\n" +
            "  segment.roomId1\n" +
            "  segment.roomId2\n" +
            "  literal 1            // legacy/file marker\n" +
            "  segment.permeability\n" +
            "Geometry fields edgeCode/length/start/end are derived and are NOT stored.");

        appendRepeatableComment(
            toAddr(0x423d40L),
            "[C2E corrected .2er door loading]\n" +
            "World reader reconstructs room/metaroom geometry, invalidates/rebuilds derived doors, reads persisted room-pair/permeability records, finds the generated door by room pair, then sets its +0x08 permeability.");

        setEOLComment(
            toAddr(0x423ccbL),
            "Full 0x24 door object; .2er persists roomId1/roomId2/permeability only.");

        setEOLComment(
            toAddr(0x423cdbL),
            "Literal legacy marker 1; not an in-memory C2EEditorBoundarySegment field.");
    }

    private void annotateDrawingEvidence() {
        appendRepeatableComment(
            toAddr(0x4237b0L),
            "[C2E independent layout proof]\n" +
            "Drawing traverses world.doorSegments (+0x24), reads +0x08 permeability for colouring and +0x14/+0x1C start/end points for line geometry. It then traverses world.wallSegments (+0x38) and draws the same +0x14/+0x1C endpoint layout.");

        setEOLComment(
            toAddr(0x42397fL),
            "Begin traversal of world.doorSegments (+0x24).");

        setEOLComment(
            toAddr(0x4239a7L),
            "Door-segment permeability at +0x08 drives colour selection.");

        setEOLComment(
            toAddr(0x4239dfL),
            "Door-segment start point at +0x14.");

        setEOLComment(
            toAddr(0x423a0dL),
            "Door-segment end point at +0x1C.");

        setEOLComment(
            toAddr(0x423a72L),
            "Begin traversal of world.wallSegments (+0x38).");

        setEOLComment(
            toAddr(0x423a85L),
            "Wall-segment start point at +0x14.");

        setEOLComment(
            toAddr(0x423ab3L),
            "Wall-segment end point at +0x1C.");
    }

    private void annotateSourceCrossMatch() {
        appendRepeatableComment(
            toAddr(0x416a80L),
            "[C2E DS-source cross-match] Docking Station Map::BuildCache has a temporary myConstructedDoorCollection and later copies newly built doors into the usable myDoorCollection. The editor performs the same conceptual rebuild but with its compact 0x24 boundary-segment objects.");

        appendRepeatableComment(
            toAddr(0x41b570L),
            "[C2E DS-source cross-match] Runtime C2e Door contains parent-room identity, permeability and geometric start/end information. The editor's compact object has the same concepts but is not binary-identical and therefore retains editor-specific C2EEditorBoundarySegment naming.");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run RefineC2EMapEditorOperations.java first.");
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
                    " existing size=0x" + Integer.toHexString(s.getLength()) +
                    " expected=0x" + Integer.toHexString(size) +
                    "; not shrinking.");
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
        catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Function naming / correction
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
            else if ("bool".equals(kind)) {
                type = BooleanDataType.dataType;
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
