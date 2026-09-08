// Creatures Map Editor 1.08 — editing geometry / snapping recovery.
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
//   RefineC2EMapEditorDerivedGeometry.java
//   RefineC2EMapEditorGeometrySemantics.java v1.1
//
// Exact-binary findings promoted here:
//
// 1. C2ERoomGeometry is really 0x1c bytes:
//      +00..+17 six integer room coordinates
//      +18      cached float perimeterLength
//
//    Several editor paths copy exactly seven DWORDs from Room+0x0c, proving the
//    cached perimeter belongs to the compact geometry value copied during edits.
//
// 2. Common editor-tool state:
//      +08 C2EIntPoint dragStartPoint
//      +10 C2EIntPoint currentPoint
//      +18 DWORD       mouseDownTick
//
// 3. Select-tool +0x1c is a room-coordinate drag mask:
//
//      01 xLeft
//      02 xRight
//      04 yLeftCeiling
//      08 yLeftFloor
//      10 yRightCeiling
//      20 yRightFloor
//
//    Corner masks:
//      05 top-left
//      09 bottom-left
//      12 top-right
//      22 bottom-right
//      3f whole room
//
// 4. Snapping stack:
//      WorldModel -> MetaRoom -> Room -> RoomGeometry
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

public class RefineC2EMapEditorEditingGeometry extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure worldType;
    private Structure metaRoomType;
    private Structure roomType;
    private Structure geometryType;
    private Structure toolBaseType;
    private Structure selectToolType;
    private Structure intPointType;

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

        // Compact geometry edit primitives
        new FuncSpec(
            0x41adb0L,
            "C2ERoomGeometry_SnapVerticalEdgesToRoom",
            "geometry",
            "Snaps this room's xLeft/xRight to the other room's opposing vertical edge when within tolerance and their vertical ranges overlap. Ceiling/floor Y values at the moved X are recomputed from this room's existing line equations.",
            "void"),

        new FuncSpec(
            0x41af60L,
            "C2ERoomGeometry_AccumulateCeilingFloorSnapCandidates",
            "geometry",
            "Examines another room and a tolerance, updating caller-supplied best endpoint pairs for ceiling/floor line snapping. Also aligns xLeft/xRight to nearby matching X boundaries. The owning MetaRoom later applies the winning pairs with SetCeilingFromLine / SetFloorFromLine.",
            "void"),

        new FuncSpec(
            0x41bbd0L,
            "C2ERoomGeometry_TranslateMaskedCoordinates",
            "geometry",
            "Applies an integer {dx,dy} translation to only the coordinate components selected by C2ERoomEditMask, then invalidates perimeterLength with -1.0f.",
            "void"),

        // Compact Room wrappers
        new FuncSpec(
            0x41a4f0L,
            "C2EEditorRoom_SnapVerticalEdgesToRoom",
            "room",
            "Thin wrapper over C2ERoomGeometry_SnapVerticalEdgesToRoom using this->geometry and other->geometry.",
            "void"),

        new FuncSpec(
            0x41a510L,
            "C2EEditorRoom_AccumulateCeilingFloorSnapCandidates",
            "room",
            "Thin wrapper over C2ERoomGeometry_AccumulateCeilingFloorSnapCandidates.",
            "void"),

        // MetaRoom / World orchestration
        new FuncSpec(
            0x416170L,
            "C2EEditorMetaRoom_SnapRoomGeometry",
            "metaroom",
            "Clamps an edited Room geometry to this metaroom's bounds, snaps vertical edges against other non-excluded rooms, accumulates best ceiling/floor line candidates, and applies the winning snapped lines. The optional exclusion collection is used by multi-room edits.",
            "void"),

        new FuncSpec(
            0x4232a0L,
            "C2EWorldModel_SnapRoomGeometry",
            "world",
            "High-level snapping facade. Uses the supplied metaroom ID, or derives one from the edited room when -1, resolves the compact metaroom, then calls C2EEditorMetaRoom_SnapRoomGeometry. Used by Select-tool dragging after masked coordinate translation.",
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
        // 0041BBD0  mov dl,[esp+8]
        // 00416170  sub esp,4Ch
        // 004232A0  mov eax,fs:[0]
        if (getByte(toAddr(0x41bbd0L)) != (byte)0x8a ||
            getByte(toAddr(0x41bbd1L)) != (byte)0x54 ||
            getByte(toAddr(0x416170L)) != (byte)0x83 ||
            getByte(toAddr(0x416171L)) != (byte)0xec ||
            getByte(toAddr(0x416172L)) != (byte)0x4c ||
            getByte(toAddr(0x4232a0L)) != (byte)0x64) {

            popup("MapEditor 1.08 identity guard failed. Refusing edit-geometry refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        worldType = requireStructure("C2EWorldModel");
        metaRoomType = requireStructure("C2EEditorMetaRoom");
        roomType = requireStructure("C2EEditorRoom");
        geometryType = requireStructure("C2ERoomGeometry");
        toolBaseType = requireStructure("C2EEditorToolBase");
        selectToolType = requireStructure("C2ESelectTool");
        intPointType = requireStructure("C2EIntPoint");

        worldPtr = new PointerDataType(worldType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);
        roomPtr = new PointerDataType(roomType, dtm);
        geometryPtr = new PointerDataType(geometryType, dtm);

        println("=== Creatures Map Editor 1.08 - editing geometry recovery ===");
        println("");

        correctNestedGeometryLayout();
        refineToolBase();
        ghidra.program.model.data.Enum editMask = buildEditMaskEnum();
        refineSelectTool(editMask);

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) {
                continue;
            }

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E editing geometry] " + spec.comment);

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

        annotateMaskProof();
        annotateToolStateProof();
        annotateSnappingPipeline();
        annotateRoom38();

        println("");
        println("Running analysis on editing-geometry changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Editing geometry summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("Corrected:");
        println("  C2ERoomGeometry size 0x18 -> 0x1c (includes perimeterLength)");
        println("  C2EEditorRoom +0x0c geometry now spans +0x0c..+0x27");
        println("");
        println("Select drag masks:");
        println("  05 top-left, 09 bottom-left, 12 top-right, 22 bottom-right, 3f whole room");
        println("");
        println("Room +0x38 intentionally remains unresolved.");
    }

    // ---------------------------------------------------------------------
    // Room geometry correction
    // ---------------------------------------------------------------------

    private void correctNestedGeometryLayout() {
        if (geometryType.getLength() < 0x1c) {
            geometryType.growStructure(0x1c - geometryType.getLength());
            println("[correct] C2ERoomGeometry size -> 0x1c.");
        }
        else if (geometryType.getLength() > 0x1c) {
            println("[geometry-size-preserve] existing C2ERoomGeometry size=0x" +
                Integer.toHexString(geometryType.getLength()) +
                "; not shrinking.");
        }

        safeField(
            geometryType,
            0x18,
            FloatDataType.dataType,
            4,
            "perimeterLength",
            "Cached room perimeter. -1.0f means invalid/uncomputed. This field is copied together with the six geometry DWORDs during editing.",
            "derivedGeometryCache");

        // Previous pass represented Room+0x24 as a sibling perimeter field.
        // The exact editing code copies 7 DWORDs starting at Room+0x0c, so the
        // better source-level model is one nested 0x1c geometry object.
        DataTypeComponent geomComp = roomType.getComponentContaining(0x0c);
        DataTypeComponent perimeterComp = roomType.getComponentContaining(0x24);

        String geomName = geomComp == null ? null : geomComp.getFieldName();
        String perimeterName = perimeterComp == null ? null : perimeterComp.getFieldName();

        boolean safeGeom =
            geomComp == null ||
            geomName == null ||
            geomName.isBlank() ||
            "geometry".equals(geomName);

        boolean safePerimeter =
            perimeterComp == null ||
            perimeterName == null ||
            perimeterName.isBlank() ||
            "perimeterLength".equals(perimeterName) ||
            (geomComp != null && perimeterComp == geomComp);

        if (!safeGeom || !safePerimeter) {
            fieldsPreserved++;
            println("[geometry-nesting-preserve] Room +0x0c/+0x24 has unexpected user fields; " +
                "not changing nesting.");
            return;
        }

        try {
            if (perimeterComp != null &&
                perimeterComp != geomComp &&
                perimeterComp.getOffset() == 0x24) {

                roomType.clearAtOffset(0x24);
            }

            if (geomComp != null && geomComp.getOffset() == 0x0c) {
                roomType.clearAtOffset(0x0c);
            }

            roomType.replaceAtOffset(
                0x0c,
                geometryType,
                0x1c,
                "geometry",
                "Complete compact room geometry including lazy perimeterLength cache.");

            fieldsRefined++;
            println("[correct] C2EEditorRoom +0x0c -> C2ERoomGeometry[0x1c].");
        }
        catch (Exception e) {
            println("[geometry-nesting-fail] " + e.getMessage());
        }

        setDescriptionSafe(
            geometryType,
            "Compact room geometry value: six integer coordinates plus cached float perimeterLength. Total size 0x1c.");

        setDescriptionSafe(
            roomType,
            "Compact 0x44-byte Map Editor Room. +0x0c contains the complete 0x1c C2ERoomGeometry including perimeterLength.");
    }

    // ---------------------------------------------------------------------
    // Tool-base / Select-tool state
    // ---------------------------------------------------------------------

    private void refineToolBase() {
        replacePairWithPoint(
            toolBaseType,
            0x08,
            "dragStartPoint",
            "Mouse/world point captured on LButtonDown. Default LButtonDown copies currentPoint here.",
            "state08",
            "state0C");

        replacePairWithPoint(
            toolBaseType,
            0x10,
            "currentPoint",
            "Most recently processed mouse/world point. Default MouseMove updates this after processing.",
            "state10",
            "state14");

        safeField(
            toolBaseType,
            0x18,
            UnsignedIntegerDataType.dataType,
            4,
            "mouseDownTick",
            "GetTickCount() captured by the default LButtonDown handler.",
            "state18");
    }

    private void replacePairWithPoint(
            Structure s,
            int offset,
            String name,
            String comment,
            String firstOld,
            String secondOld) {

        try {
            DataTypeComponent a = s.getComponentContaining(offset);
            DataTypeComponent b = s.getComponentContaining(offset + 4);

            if (!isExpectedField(a, offset, firstOld, name) ||
                !isExpectedField(b, offset + 4, secondOld, name)) {

                fieldsPreserved++;
                println("[point-preserve] " + s.getName() +
                    " +0x" + Integer.toHexString(offset) +
                    " has unexpected user fields.");
                return;
            }

            if (b != null && b != a && b.getOffset() == offset + 4) {
                s.clearAtOffset(offset + 4);
            }

            if (a != null && a.getOffset() == offset) {
                s.clearAtOffset(offset);
            }

            s.replaceAtOffset(
                offset,
                intPointType,
                0x08,
                name,
                comment);

            fieldsRefined++;
            println("[field] " + s.getName() +
                " +0x" + Integer.toHexString(offset) +
                " -> " + name);
        }
        catch (Exception e) {
            println("[point-fail] " + s.getName() +
                " +0x" + Integer.toHexString(offset) +
                ": " + e.getMessage());
        }
    }

    private boolean isExpectedField(
            DataTypeComponent c,
            int expectedOffset,
            String oldName,
            String newName) {

        if (c == null) return true;

        if (c.getOffset() != expectedOffset &&
            c.getOffset() < expectedOffset) {
            // Already a larger semantic component spanning the point.
            String n = c.getFieldName();
            return newName.equals(n);
        }

        String n = c.getFieldName();
        return n == null ||
               n.isBlank() ||
               oldName.equals(n) ||
               newName.equals(n);
    }

    private ghidra.program.model.data.Enum buildEditMaskEnum() {
        DataType existing = dtm.getDataType(CAT, "C2ERoomEditMask");

        if (existing instanceof ghidra.program.model.data.Enum) {
            return (ghidra.program.model.data.Enum)existing;
        }

        if (existing != null) {
            throw new IllegalStateException(
                "/C2E/Refined/C2ERoomEditMask exists but is not an Enum.");
        }

        EnumDataType e =
            new EnumDataType(CAT, "C2ERoomEditMask", 4, dtm);

        e.add("NONE", 0x00);

        e.add("X_LEFT", 0x01);
        e.add("X_RIGHT", 0x02);
        e.add("Y_LEFT_CEILING", 0x04);
        e.add("Y_LEFT_FLOOR", 0x08);
        e.add("Y_RIGHT_CEILING", 0x10);
        e.add("Y_RIGHT_FLOOR", 0x20);

        e.add("TOP_LEFT_CORNER", 0x05);
        e.add("BOTTOM_LEFT_CORNER", 0x09);
        e.add("TOP_RIGHT_CORNER", 0x12);
        e.add("BOTTOM_RIGHT_CORNER", 0x22);
        e.add("WHOLE_ROOM", 0x3f);

        ghidra.program.model.data.Enum added =
            (ghidra.program.model.data.Enum)dtm.addDataType(
                e,
                DataTypeConflictHandler.REPLACE_HANDLER);

        println("[type] created C2ERoomEditMask.");
        return added;
    }

    private void refineSelectTool(ghidra.program.model.data.Enum editMask) {
        try {
            DataTypeComponent c = selectToolType.getComponentContaining(0x1c);
            String n = c == null ? null : c.getFieldName();

            boolean safe =
                c == null ||
                n == null ||
                n.isBlank() ||
                "toolSpecificState".equals(n) ||
                "dragMask".equals(n);

            if (!safe) {
                fieldsPreserved++;
                println("[select-preserve] C2ESelectTool +0x1c existing='" + n + "'");
                return;
            }

            if (c != null) {
                selectToolType.clearAtOffset(c.getOffset());
            }

            selectToolType.replaceAtOffset(
                0x1c,
                editMask,
                4,
                "dragMask",
                "Coordinate components currently being dragged. Hover detection sets a corner mask; dragging inside a selected room uses WHOLE_ROOM (0x3f).");

            // Retain the remaining derived-state area explicitly, but do not guess it.
            selectToolType.replaceAtOffset(
                0x20,
                new ArrayDataType(Undefined1DataType.dataType, 0x10, 1),
                0x10,
                "toolSpecificState20",
                "Remaining Select-tool state; intentionally unresolved.");

            fieldsRefined += 2;
            println("[field] C2ESelectTool +0x1c -> dragMask");
        }
        catch (Exception e) {
            println("[select-field-fail] " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Evidence annotations
    // ---------------------------------------------------------------------

    private void annotateMaskProof() {
        appendRepeatableComment(
            toAddr(0x41bbd0L),
            "[C2E edit-mask proof]\n" +
            "mask 0x01 -> xLeft += dx\n" +
            "mask 0x02 -> xRight += dx\n" +
            "mask 0x04 -> yLeftCeiling += dy\n" +
            "mask 0x08 -> yLeftFloor += dy\n" +
            "mask 0x10 -> yRightCeiling += dy\n" +
            "mask 0x20 -> yRightFloor += dy\n" +
            "then perimeterLength = -1.0f.");

        setEOLComment(
            toAddr(0x4206d1L),
            "Select hover: TOP_LEFT_CORNER = X_LEFT | Y_LEFT_CEILING = 0x05.");

        setEOLComment(
            toAddr(0x420709L),
            "Select hover: BOTTOM_LEFT_CORNER = X_LEFT | Y_LEFT_FLOOR = 0x09.");

        setEOLComment(
            toAddr(0x42073eL),
            "Select hover: TOP_RIGHT_CORNER = X_RIGHT | Y_RIGHT_CEILING = 0x12.");

        setEOLComment(
            toAddr(0x420773L),
            "Select hover: BOTTOM_RIGHT_CORNER = X_RIGHT | Y_RIGHT_FLOOR = 0x22.");

        setEOLComment(
            toAddr(0x42087aL),
            "Pointer inside selected room: WHOLE_ROOM drag mask = 0x3f.");

        appendRepeatableComment(
            toAddr(0x420280L),
            "[C2E Select-tool drag state] Select MouseMove uses this->dragMask (+0x1c). Corner hit tests select 0x05/0x09/0x12/0x22; room-interior hit selects 0x3f. The chosen mask is passed to C2EEditorRoom_TranslateVertices / C2ERoomGeometry_TranslateMaskedCoordinates.");
    }

    private void annotateToolStateProof() {
        appendRepeatableComment(
            toAddr(0x41ee60L),
            "[C2E tool-base state] Default LButtonDown stores GetTickCount() in mouseDownTick (+0x18) and copies currentPoint (+0x10) into dragStartPoint (+0x08).");

        appendRepeatableComment(
            toAddr(0x41ed40L),
            "[C2E tool-base state] Default MouseMove processes the previous/current drag geometry, then stores the incoming point into currentPoint (+0x10).");
    }

    private void annotateSnappingPipeline() {
        appendRepeatableComment(
            toAddr(0x416170L),
            "[C2E room snapping pipeline]\n" +
            "1. Copy 7 DWORDs from Room+0x0c (complete C2ERoomGeometry including perimeterLength).\n" +
            "2. Clamp geometry to MetaRoom bounds.\n" +
            "3. Iterate other rooms, optionally excluding the caller's selected/multi-edit collection.\n" +
            "4. Snap xLeft/xRight to nearby opposing vertical edges.\n" +
            "5. Accumulate best ceiling and floor line endpoint candidates from nearby rooms.\n" +
            "6. Apply winning ceiling/floor lines.\n" +
            "The incoming geometry already carries perimeterLength=-1 after drag translation.");

        appendRepeatableComment(
            toAddr(0x4232a0L),
            "[C2E world snapping facade] If metaroomId == -1, derive a metaroom from the edited room's approximate centre; otherwise use the supplied ID. Then resolve the compact metaroom and call its SnapRoomGeometry.");

        appendRepeatableComment(
            toAddr(0x41adb0L),
            "[C2E vertical snap] A candidate vertical snap is accepted only when vertical room ranges overlap and the opposing X coordinates differ by less than the supplied tolerance.");

        appendRepeatableComment(
            toAddr(0x41af60L),
            "[C2E ceiling/floor snap] Candidate sloped ceiling/floor lines are compared over overlapping X intervals. Caller-provided endpoint pairs retain the best/nearest eligible snap and are later applied as whole lines.");
    }

    private void annotateRoom38() {
        DataTypeComponent c = roomType.getComponentContaining(0x38);

        if (c != null) {
            try {
                c.setComment(
                    "UNRESOLVED/possibly reserved editor state. Current evidence only shows this DWORD being copied as part of full Room snapshots/undo-style edit state (e.g. Select MouseMove); no semantic read/write operation has been proven.");
            }
            catch (Exception ignored) {
            }
        }

        appendRepeatableComment(
            toAddr(0x420460L),
            "[C2E unresolved Room+0x38] Select-tool editing copies this DWORD into a temporary full-room snapshot, but no operation in the recovered Room methods gives it independent semantics. It remains intentionally unnamed.");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run the previous recovery passes first.");
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
    // Function naming / this typing
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
