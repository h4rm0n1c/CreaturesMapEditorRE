// Creatures Map Editor 1.08 — world / metaroom / room model recovery.
//
// Prerequisites:
//   - RecoverC2EMapEditorMFC.java
//   - RecoverC2EMapEditorClassLayout.java
//   - RefineC2EMapEditorStructures.java
//   - ApplyC2EMapEditorTypes.java v1.4
//   - RefineC2EMapEditorSemanticsV2.java
//   - RefineC2EMapEditorDocumentIO.java
//
// Evidence hierarchy used by this pass:
//   1. Exact MapEditor.exe machine code / current Ghidra DB.
//   2. Docking Station-era webc2e/emscripten-c2e Map source.
//   3. Earlier Creatures 3 Map source.
//   4. Supplied .2er samples.
//
// IMPORTANT:
// The editor's Room and MetaRoom are compact EDITOR objects. They are not
// binary-identical to the engine's runtime Room / MetaRoom classes. Source names
// are promoted only where the editor code and file grammar independently agree.
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

public class RefineC2EMapEditorWorldModel extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure worldType;
    private Structure vc6CString;
    private Structure treeIndex;

    private Structure intRect;
    private Structure roomGeometry;
    private Structure cStringVector;
    private Structure intVector;
    private Structure roomType;
    private Structure metaRoomType;
    private Structure doorRelationType;
    private Structure doorRelationTree;

    private Pointer worldPtr;
    private Pointer roomPtr;
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
        new FuncSpec(
            0x4152d0L,
            "C2EEditorMetaRoom_ctor",
            "metaroom",
            "Constructs the compact 0x48-byte editor metaroom. Copies a 4-int bounds rectangle, constructs the background vector/current-background string, room tree and music track.",
            "metaroomptr"),

        new FuncSpec(
            0x4164f0L,
            "C2EEditorMetaRoom_Write2ER",
            "metaroom",
            "Writes metaroom body: bounds, background count/paths, music track, room count, then each room ID followed by C2EEditorRoom_Write2ER. The metaroom ID itself is written by C2EWorldModel as the tree key.",
            "void"),

        new FuncSpec(
            0x416630L,
            "C2EEditorMetaRoom_Read2ER",
            "metaroom",
            "Reads the compact metaroom body. Packed file version >=1002 includes the music track. Allocates 0x44-byte editor Room objects and inserts them by separately read room ID.",
            "void"),

        new FuncSpec(
            0x419d40L,
            "C2EEditorRoom_ctor",
            "room",
            "Constructs the compact 0x44-byte editor Room, including six integer geometry properties, a 32-DWORD additional-property vector, music track and derived-geometry cache state.",
            "roomptr"),

        new FuncSpec(
            0x419e50L,
            "C2EEditorRoom_GetProperty",
            "room",
            "Returns editor room property by index. Indices 0..5 are the six geometry integers at +0x0C; indices >=6 come from propertyValues. Index 6 is Room Type.",
            "int"),

        new FuncSpec(
            0x419e70L,
            "C2EEditorRoom_SetProperty",
            "room",
            "Sets editor room property by index. Geometry writes (0..5) invalidate the derivedGeometryCache at +0x24; index 6 is Room Type in propertyValues[0].",
            "void"),

        new FuncSpec(
            0x41a5d0L,
            "C2EEditorRoom_Write2ER",
            "room",
            "Writes room body: six geometry integers, Room Type (property 6), then music track. Room ID is written separately by the owning metaroom.",
            "void"),

        new FuncSpec(
            0x41ab10L,
            "C2EEditorRoom_Read2ER",
            "room",
            "Reads room body. Packed version >=1001 reads Room Type; packed version >=1002 reads the music track.",
            "void"),

        new FuncSpec(
            0x41ac90L,
            "C2ERoomGeometry_Write2ER",
            "geometry",
            "Writes the six integer room geometry values in AddRoom/API order: xLeft, xRight, yLeftCeiling, yRightCeiling, yLeftFloor, yRightFloor.",
            "void"),

        new FuncSpec(
            0x41ad20L,
            "C2ERoomGeometry_Read2ER",
            "geometry",
            "Reads the six integer room geometry values in AddRoom/API order.",
            "void"),

        new FuncSpec(
            0x41a620L,
            "C2EEditorRoom_BuildInjectCAOS",
            "room",
            "Builds CAOS for one editor room using ADDR, RTYP and RMSC, then stores the resulting engine room agent/address in GAME mapeditortmp_<roomId>.",
            "unknown"),

        new FuncSpec(
            0x41a770L,
            "C2EEditorRoom_BuildDeleteCAOS",
            "room",
            "Builds 'delg \"mapeditortmp_%d\"' using this->roomId.",
            "unknown"),

        new FuncSpec(
            0x422c20L,
            "C2EWorldModel_FindDoorRelation",
            "world",
            "Searches the serialized relation tree for a compact relation whose roomId1/roomId2 match the requested pair. Returns the editor's shared/reference wrapper for that relation.",
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

        // Exact-target guards: Room ctor and relation writer payload load.
        if (getByte(toAddr(0x419d40L)) != (byte)0x6a ||
            getByte(toAddr(0x423ccbL)) != (byte)0x8b) {
            popup("MapEditor 1.08 identity guard failed. Refusing target-specific world-model refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        worldType = requireStructure("C2EWorldModel");
        vc6CString = requireStructure("VC6CString");
        treeIndex = requireStructure("C2ETreeIndex20");

        println("=== Creatures Map Editor 1.08 - world/model recovery ===");
        println("Source reference: DS-era webc2e/emscripten-c2e, validated against exact binary.");
        println("");

        buildTypes();
        refineWorld();
        labelConstantsAndFormats();

        worldPtr = new PointerDataType(worldType, dtm);
        roomPtr = new PointerDataType(roomType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) {
                continue;
            }

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E world model] " + spec.comment);

            if ("world".equals(spec.owner)) {
                applyThisType(f, worldPtr, "C2EWorldModel");
            }
            else if ("room".equals(spec.owner)) {
                applyThisType(f, roomPtr, "C2EEditorRoom");
            }
            else if ("metaroom".equals(spec.owner)) {
                applyThisType(f, metaRoomPtr, "C2EEditorMetaRoom");
            }
            else if ("geometry".equals(spec.owner)) {
                Pointer geometryPtr = new PointerDataType(roomGeometry, dtm);
                applyThisType(f, geometryPtr, "C2ERoomGeometry");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateSerializerGrammar();
        annotateSourceCrossMatch();

        println("");
        println("Running analysis on recovered world/model types...");
        analyzeChanges(currentProgram);

        println("");
        println("=== World/model summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("Created/refined:");
        println("  /C2E/Refined/C2EEditorMetaRoom       size 0x48");
        println("  /C2E/Refined/C2EEditorRoom           size 0x44");
        println("  /C2E/Refined/C2ERoomGeometry         size 0x18");
        println("  /C2E/Refined/C2EEditorDoorRelation   size 0x0C");
        println("");
        println("Engine Room/MetaRoom layouts were NOT imported wholesale.");
    }

    // ---------------------------------------------------------------------
    // Type construction
    // ---------------------------------------------------------------------

    private void buildTypes() {
        intRect = getOrCreate("C2EIntRect", 0x10);
        safeField(intRect, 0x00, IntegerDataType.dataType, 4,
            "left", "Metaroom left bound.");
        safeField(intRect, 0x04, IntegerDataType.dataType, 4,
            "top", "Metaroom top bound.");
        safeField(intRect, 0x08, IntegerDataType.dataType, 4,
            "right", "Metaroom right bound.");
        safeField(intRect, 0x0c, IntegerDataType.dataType, 4,
            "bottom", "Metaroom bottom bound.");
        setDescriptionSafe(
            intRect,
            "Four integer map bounds. Sample .2er metaroom records and constructor copying establish left/top/right/bottom ordering.");

        roomGeometry = getOrCreate("C2ERoomGeometry", 0x18);
        safeField(roomGeometry, 0x00, IntegerDataType.dataType, 4,
            "xLeft", "Left X coordinate.");
        safeField(roomGeometry, 0x04, IntegerDataType.dataType, 4,
            "xRight", "Right X coordinate.");
        safeField(roomGeometry, 0x08, IntegerDataType.dataType, 4,
            "yLeftCeiling", "Ceiling Y at left edge.");
        safeField(roomGeometry, 0x0c, IntegerDataType.dataType, 4,
            "yRightCeiling", "Ceiling Y at right edge.");
        safeField(roomGeometry, 0x10, IntegerDataType.dataType, 4,
            "yLeftFloor", "Floor Y at left edge.");
        safeField(roomGeometry, 0x14, IntegerDataType.dataType, 4,
            "yRightFloor", "Floor Y at right edge.");
        setDescriptionSafe(
            roomGeometry,
            "Six integer room bounds matching the C2e Map::AddRoom/GetRoomLocation API and the .2er room serializer.");

        Pointer cStringPtr = new PointerDataType(vc6CString, dtm);
        cStringVector = getOrCreate("C2ECStringVector", 0x10);
        buildVector(
            cStringVector,
            cStringPtr,
            "VC6 vector-like container of 4-byte string objects/pointers; used for metaroom background paths.");

        Pointer intPtr = new PointerDataType(IntegerDataType.dataType, dtm);
        intVector = getOrCreate("C2EIntVector", 0x10);
        buildVector(
            intVector,
            intPtr,
            "VC6 vector-like container of 32-bit values. Room propertyValues contains 32 additional properties; element 0 is Room Type (overall property index 6).");

        roomType = getOrCreate("C2EEditorRoom", 0x44);

        safeField(
            roomType, 0x00,
            new ArrayDataType(Undefined1DataType.dataType, 0x0c, 1),
            0x0c,
            "editorState00",
            "Unresolved compact-editor state. Intentionally not mapped to the much larger runtime C2e Room.");

        safeField(
            roomType, 0x0c,
            roomGeometry,
            0x18,
            "geometry",
            "Six directly editable geometry properties (property indices 0..5).");

        safeField(
            roomType, 0x24,
            FloatDataType.dataType,
            4,
            "derivedGeometryCache",
            "Derived geometry/cache value. Constructor and geometry writes invalidate it with float -1.0f (0xBF800000). Exact derived quantity remains unresolved.");

        safeField(
            roomType, 0x28,
            intVector,
            0x10,
            "propertyValues",
            "Additional room properties. Overall property index N>=6 maps to propertyValues[N-6]. propertyValues[0] is Room Type.");

        // +0x38 deliberately remains undefined until stronger evidence.
        safeField(
            roomType, 0x3c,
            vc6CString,
            4,
            "track",
            "Room music track string. Serialized from file version 1.2 onward and emitted through RMSC in generated CAOS.");

        safeField(
            roomType, 0x40,
            IntegerDataType.dataType,
            4,
            "roomId",
            "Editor room ID. Stored as the owning metaroom tree key on disk; used for GAME mapeditortmp_<roomId> during injection.");

        setDescriptionSafe(
            roomType,
            "Compact 0x44-byte Map Editor Room. This is NOT the engine runtime Room: runtime door collections, CA history and caches are absent.");

        metaRoomType = getOrCreate("C2EEditorMetaRoom", 0x48);

        safeField(
            metaRoomType, 0x00,
            intRect,
            0x10,
            "bounds",
            "Metaroom map bounds serialized as four integers.");

        safeField(
            metaRoomType, 0x10,
            new ArrayDataType(Undefined1DataType.dataType, 0x08, 1),
            0x08,
            "lifetimeState",
            "Reference/lifetime bookkeeping observed in constructor/destructor paths; not map payload.");

        safeField(
            metaRoomType, 0x18,
            cStringVector,
            0x10,
            "backgrounds",
            "Background path collection. Writer serializes count followed by each string.");

        safeField(
            metaRoomType, 0x28,
            vc6CString,
            4,
            "currentBackground",
            "Current/background-selection string. Source-correlated with C2e MetaRoom::background; the editor's custom .2er writer does not serialize this field directly.");

        safeField(
            metaRoomType, 0x2c,
            treeIndex,
            0x14,
            "roomsById",
            "Tree/map of room ID -> editor Room reference. Count is at metaroom +0x3C.");

        safeField(
            metaRoomType, 0x40,
            vc6CString,
            4,
            "track",
            "Metaroom music track. Serialized from packed version >=1002.");

        // +0x44 intentionally remains undefined.
        setDescriptionSafe(
            metaRoomType,
            "Compact 0x48-byte Map Editor MetaRoom. Metaroom ID is the outer WorldModel tree key, not a member of this editor body.");

        doorRelationType = getOrCreate("C2EEditorDoorRelation", 0x0c);

        safeField(
            doorRelationType, 0x00,
            IntegerDataType.dataType, 4,
            "roomId1",
            "First room ID / door endpoint.");

        safeField(
            doorRelationType, 0x04,
            IntegerDataType.dataType, 4,
            "roomId2",
            "Second room ID / door endpoint.");

        safeField(
            doorRelationType, 0x08,
            IntegerDataType.dataType, 4,
            "permeability",
            "Door permeability. Samples contain values such as 100 and 50.");

        setDescriptionSafe(
            doorRelationType,
            "Compact persistent editor relation for the CAOS DOOR connection between two rooms. .2er additionally emits a literal legacy marker value 1 between endpoint IDs and permeability.");

        doorRelationTree = getOrCreate("C2EEditorDoorRelationTree", 0x14);
        safeField(
            doorRelationTree, 0x00,
            new ArrayDataType(Undefined1DataType.dataType, 0x10, 1),
            0x10,
            "treeState",
            "Opaque VC6 ordered-tree state. Nodes contain references to C2EEditorDoorRelation payloads.");
        safeField(
            doorRelationTree, 0x10,
            UnsignedIntegerDataType.dataType, 4,
            "count",
            "Number of serialized room-to-room door relations.");
        setDescriptionSafe(
            doorRelationTree,
            "VC6 tree/map-like container for compact C2EEditorDoorRelation records.");
    }

    private void buildVector(Structure s, Pointer elementPtr, String description) {
        safeField(
            s, 0x00,
            Undefined1DataType.dataType, 1,
            "allocatorState",
            "VC6 container allocator/state byte.");

        safeField(
            s, 0x01,
            new ArrayDataType(Undefined1DataType.dataType, 3, 1),
            3,
            "padding01",
            "Alignment.");

        safeField(
            s, 0x04,
            elementPtr, 4,
            "begin",
            "Pointer to first element.");

        safeField(
            s, 0x08,
            elementPtr, 4,
            "end",
            "Pointer one past the last constructed element.");

        safeField(
            s, 0x0c,
            elementPtr, 4,
            "capacityEnd",
            "Pointer one past allocated storage.");

        setDescriptionSafe(s, description);
    }

    private void refineWorld() {
        safeField(
            worldType, 0x08,
            treeIndex, 0x14,
            "metarooms",
            "Tree/map of metaroom ID -> compact C2EEditorMetaRoom reference. Metaroom IDs are serialized as tree keys.");

        safeField(
            worldType, 0x1c,
            IntegerDataType.dataType, 4,
            "nextMetaroomId",
            "Next metaroom ID. Serialized in current format 1.4.");

        safeField(
            worldType, 0x20,
            IntegerDataType.dataType, 4,
            "nextRoomId",
            "Next room ID. Serialized in current format 1.4.");

        safeField(
            worldType, 0x24,
            doorRelationTree, 0x14,
            "doorRelations",
            "Serialized room-to-room door relation tree. Writer emits endpoint IDs, legacy marker 1, then permeability.",
            "serializedRelationIndex");

        // +0x38 and +0x4C remain deliberately generic derived indices.
        setDescriptionSafe(
            worldType,
            "Top-level compact Map Editor world model. Semantic v3 identifies metarooms and serialized room-to-room doorRelations while retaining unresolved derived indices.");

        appendRepeatableComment(
            toAddr(0x423be0L),
            "[C2E world model] World writer serializes metaroom IDs as outer tree keys, then C2EEditorMetaRoom bodies. At 00423CA9 it writes doorRelations.count.");

        appendRepeatableComment(
            toAddr(0x423d40L),
            "[C2E world model] World reader rebuilds 0x48-byte metarooms, then reads the compact door-relation section and applies permeability through C2EWorldModel_FindDoorRelation.");
    }

    // ---------------------------------------------------------------------
    // File grammar / source correlation
    // ---------------------------------------------------------------------

    private void labelConstantsAndFormats() {
        try {
            createLabel(
                toAddr(0x434f54L),
                "C2E_ROOM_INJECT_CAOS_FORMAT",
                true);
            setEOLComment(
                toAddr(0x434f54L),
                "ADDR/RTYP/RMSC room injection format; ends with GAME mapeditortmp_<roomId>.");
        }
        catch (Exception e) {
            println("[label-skip] 00434F54: " + e.getMessage());
        }

        try {
            createLabel(
                toAddr(0x434fc8L),
                "C2E_ROOM_DELETE_CAOS_FORMAT",
                true);
            setEOLComment(
                toAddr(0x434fc8L),
                "delg \"mapeditortmp_%d\" using C2EEditorRoom.roomId.");
        }
        catch (Exception e) {
            println("[label-skip] 00434FC8: " + e.getMessage());
        }
    }

    private void annotateSerializerGrammar() {
        appendRepeatableComment(
            toAddr(0x4164f0L),
            "[C2E .2er metaroom grammar]\n" +
            "  <left> <top> <right> <bottom>\\n\n" +
            "  <background_count>\\n\n" +
            "  <background_path>\\n ...\n" +
            "  <metaroom_track>\\n\n" +
            "  <room_count>\\n\n" +
            "  repeated: <room_id>\\n + C2EEditorRoom body.");

        appendRepeatableComment(
            toAddr(0x41a5d0L),
            "[C2E .2er room grammar]\n" +
            "Room ID is emitted by owning metaroom, then Room body is:\n" +
            "  <xLeft> <xRight> <yLeftCeiling> <yRightCeiling> <yLeftFloor> <yRightFloor>\\n\n" +
            "  <room_type>\\n\n" +
            "  <room_track>\\n");

        setEOLComment(
            toAddr(0x423ccbL),
            "doorRelations node payload -> C2EEditorDoorRelation {roomId1, roomId2, permeability}.");

        setEOLComment(
            toAddr(0x423cdbL),
            "Literal .2er relation marker 1. Not stored in C2EEditorDoorRelation memory object.");

        appendRepeatableComment(
            toAddr(0x423d40L),
            "[C2E .2er door relation grammar]\n" +
            "  <relation_count>\\n\n" +
            "  repeated:\n" +
            "    <roomId1> <roomId2>\\n\n" +
            "    1\\n                 // legacy marker\n" +
            "    <permeability>\\n\n" +
            "Reader consumes all four integers, finds the in-memory relation by endpoint IDs, then writes permeability at +0x08.");

        setEOLComment(
            toAddr(0x41ab3eL),
            "Room format compatibility: packed version >=1001 adds Room Type (property index 6).");

        setEOLComment(
            toAddr(0x41ab61L),
            "Room format compatibility: packed version >=1002 adds room music track.");

        // MetaRoom track version comparison is in Read2ER; exact comparison has
        // already been verified from the binary, so attach at the function start
        // rather than risk pinning a comment to a compiler-reordered instruction.
        appendRepeatableComment(
            toAddr(0x416630L),
            "[C2E .2er compatibility] Packed version >=1002 contains the metaroom music track.");
    }

    private void annotateSourceCrossMatch() {
        appendRepeatableComment(
            toAddr(0x419e50L),
            "[C2E DS-source cross-match] DS-era Map::AddRoom/GetRoomLocation use the same six semantic coordinates: xLeft, xRight, yLeftCeiling, yRightCeiling, yLeftFloor, yRightFloor. The editor binary independently maps property indices 0..5 to these six DWORDs.");

        appendRepeatableComment(
            toAddr(0x4164f0L),
            "[C2E DS-source cross-match] Runtime MetaRoom contains backgroundCollection/background/track. The editor uses a compact analogue: backgrounds vector, currentBackground string, roomsById tree, track. Runtime-only/default-camera fields are not imported.");

        appendRepeatableComment(
            toAddr(0x422c20L),
            "[C2E DS-source cross-match] Runtime Map exposes SetDoorPermiability/GetDoorPermiability and Door/Link concepts. The editor relation is deliberately named C2EEditorDoorRelation because generated CAOS uses the DOOR command and only persists room endpoints + permeability.");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run RefineC2EMapEditorDocumentIO.java first.");
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
                    "; not shrinking user/existing type.");
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
                    component.getDataType() != DataType.DEFAULT) {

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
            else if ("roomptr".equals(kind)) {
                type = roomPtr;
            }
            else if ("metaroomptr".equals(kind)) {
                type = metaRoomPtr;
            }

            if (type != null &&
                (f.getReturnType() == null ||
                 !f.getReturnType().isEquivalent(type))) {

                f.setReturnType(type, SourceType.USER_DEFINED);
                returnTypesApplied++;
            }
        }
        catch (Exception e) {
            println("[return-skip] " + f.getEntryPoint() + " " +
                f.getName() + ": " + e.getMessage());
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
