// Creatures Map Editor 1.08 — room geometry semantic recovery.
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
//
// Revision 1.1: fully qualify Ghidra Enum to avoid java.lang.Enum ambiguity.
// Exact-binary findings promoted here:
//
// Editor edge code:
//   0 LEFT
//   1 CEILING
//   2 FLOOR
//   3 RIGHT
//
// This differs from the Docking Station runtime DIRECTION_* numeric ordering.
// The binary proves the editor ordering independently.
//
// Room +0x24 is a cached perimeter length. -1.0f means invalid/uncomputed.
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

public class RefineC2EMapEditorGeometrySemantics extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;
    private Structure roomType;
    private Structure geometryType;
    private Structure boundaryType;
    private Structure intRectType;

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
        new FuncSpec(
            0x4210f0L,
            "C2EGeometry_ComputeLineEquation",
            "none",
            "Given two integer points, computes y = slope*x + intercept as two doubles. Returns false for a vertical line.",
            "bool"),

        new FuncSpec(
            0x41ac10L,
            "C2ERoomGeometry_GetCeilingLineEquation",
            "geometry",
            "Builds the line equation through (xLeft,yLeftCeiling) and (xRight,yRightCeiling).",
            "bool"),

        new FuncSpec(
            0x41ac50L,
            "C2ERoomGeometry_GetFloorLineEquation",
            "geometry",
            "Builds the line equation through (xLeft,yLeftFloor) and (xRight,yRightFloor).",
            "bool"),

        new FuncSpec(
            0x41ad70L,
            "C2EGeometry_IntersectIntervalsStrict",
            "none",
            "Computes [max(a0,b0), min(a1,b1)] and returns true only when the resulting interval has positive length.",
            "bool"),

        new FuncSpec(
            0x41baa0L,
            "C2ERoomGeometry_ContainsPoint",
            "geometry",
            "Tests whether an integer point lies inside the room polygon. First checks an expanded bounding rectangle, then verifies the point is below the ceiling line and above the floor line.",
            "bool"),

        new FuncSpec(
            0x41b1b0L,
            "C2ERoomGeometry_SetFloorFromLine",
            "geometry",
            "Given two integer endpoints, computes their line equation and updates yLeftFloor/yRightFloor at this room's xLeft/xRight.",
            "void"),

        new FuncSpec(
            0x41b210L,
            "C2ERoomGeometry_SetCeilingFromLine",
            "geometry",
            "Given two integer endpoints, computes their line equation and updates yLeftCeiling/yRightCeiling at this room's xLeft/xRight.",
            "void"),

        new FuncSpec(
            0x41b270L,
            "C2ERoomGeometry_ClampToBounds",
            "geometry",
            "Clamps xLeft/xRight and all four ceiling/floor Y coordinates to an integer metaroom rectangle. Right/bottom limits are exclusive and become bound-1.",
            "void"),

        new FuncSpec(
            0x41b2d0L,
            "C2ERoomGeometry_FindSharedBoundarySegment",
            "geometry",
            "Finds a positive-length shared boundary between two rooms. Returns start/end points and C2EEditorEdge. Proven mapping: LEFT=0, CEILING=1, FLOOR=2, RIGHT=3.",
            "bool"),

        new FuncSpec(
            0x41bc50L,
            "C2ERoomGeometry_GetPerimeterLength",
            "geometry",
            "Returns cached perimeter length at geometry+0x18 (Room+0x24). If cache is negative, computes ceiling length + floor length + left wall length + right wall length and stores it.",
            "float"),

        new FuncSpec(
            0x41bcf0L,
            "C2ERoomGeometry_GetCentrePoint",
            "geometry",
            "Computes the editor's room centre point as midpoint of the bounding diagonal from (xLeft,yLeftCeiling) to (xRight,yRightFloor).",
            "void"),

        // Thin compact Room wrappers
        new FuncSpec(
            0x41a4d0L,
            "C2EEditorRoom_ContainsPoint",
            "room",
            "Thin wrapper over C2ERoomGeometry_ContainsPoint using this->geometry.",
            "bool"),

        new FuncSpec(
            0x41a540L,
            "C2EEditorRoom_SetFloorFromLine",
            "room",
            "Thin wrapper over C2ERoomGeometry_SetFloorFromLine.",
            "void"),

        new FuncSpec(
            0x41a560L,
            "C2EEditorRoom_SetCeilingFromLine",
            "room",
            "Thin wrapper over C2ERoomGeometry_SetCeilingFromLine.",
            "void"),

        new FuncSpec(
            0x41a5b0L,
            "C2EEditorRoom_TranslateVertices",
            "room",
            "Thin wrapper around the geometry vertex-translation helper; applies dx/dy to selected room vertices and invalidates perimeterLength.",
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

        // Exact-target guards:
        // 0041B2D0 = push ebp
        // 0041BC50 = sub esp,14h
        if (getByte(toAddr(0x41b2d0L)) != (byte)0x55 ||
            getByte(toAddr(0x41bc50L)) != (byte)0x83 ||
            getByte(toAddr(0x41bc51L)) != (byte)0xec ||
            getByte(toAddr(0x41bc52L)) != (byte)0x14) {

            popup("MapEditor 1.08 identity guard failed. Refusing geometry-semantic refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        roomType = requireStructure("C2EEditorRoom");
        geometryType = requireStructure("C2ERoomGeometry");
        boundaryType = requireStructure("C2EEditorBoundarySegment");
        intRectType = requireStructure("C2EIntRect");

        roomPtr = new PointerDataType(roomType, dtm);
        geometryPtr = new PointerDataType(geometryType, dtm);

        println("=== Creatures Map Editor 1.08 - geometry semantics ===");
        println("");

        ghidra.program.model.data.Enum edgeEnum = buildEdgeEnum();
        refineBoundaryEdgeType(edgeEnum);
        refinePerimeterField();

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) {
                continue;
            }

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E geometry] " + spec.comment);

            if ("geometry".equals(spec.owner)) {
                applyThisType(f, geometryPtr, "C2ERoomGeometry");
            }
            else if ("room".equals(spec.owner)) {
                applyThisType(f, roomPtr, "C2EEditorRoom");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateEdgeProof();
        annotatePerimeterProof();
        annotateContainmentProof();
        annotateSourceCrossMatch();

        println("");
        println("Running analysis on geometry semantic changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Geometry semantic summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("");
        println("Editor edge enum:");
        println("  0 LEFT");
        println("  1 CEILING");
        println("  2 FLOOR");
        println("  3 RIGHT");
        println("");
        println("Room +0x24 promoted to perimeterLength (cached float, -1 = invalid).");
    }

    // ---------------------------------------------------------------------
    // Edge enum / fields
    // ---------------------------------------------------------------------

    private ghidra.program.model.data.Enum buildEdgeEnum() {
        DataType existing = dtm.getDataType(CAT, "C2EEditorEdge");

        if (existing instanceof ghidra.program.model.data.Enum) {
            return (ghidra.program.model.data.Enum)existing;
        }

        if (existing != null) {
            throw new IllegalStateException(
                "/C2E/Refined/C2EEditorEdge exists but is not an Enum.");
        }

        EnumDataType e =
            new EnumDataType(CAT, "C2EEditorEdge", 4, dtm);

        e.add("LEFT", 0);
        e.add("CEILING", 1);
        e.add("FLOOR", 2);
        e.add("RIGHT", 3);

        ghidra.program.model.data.Enum added = (ghidra.program.model.data.Enum)dtm.addDataType(
            e,
            DataTypeConflictHandler.REPLACE_HANDLER);

        println("[type] created C2EEditorEdge {LEFT=0, CEILING=1, FLOOR=2, RIGHT=3}");
        return added;
    }

    private void refineBoundaryEdgeType(ghidra.program.model.data.Enum edgeEnum) {
        safeField(
            boundaryType,
            0x0c,
            edgeEnum,
            4,
            "edge",
            "Edge of roomId1 on which this boundary segment lies. Opposite-room orientation is computed as 3-edge.",
            "edgeCode");
    }

    private void refinePerimeterField() {
        safeField(
            roomType,
            0x24,
            FloatDataType.dataType,
            4,
            "perimeterLength",
            "Cached sum of ceiling + floor + left wall + right wall lengths. Geometry edits set this to -1.0f; C2ERoomGeometry_GetPerimeterLength recomputes lazily.",
            "derivedGeometryCache");
    }

    // ---------------------------------------------------------------------
    // Evidence annotations
    // ---------------------------------------------------------------------

    private void annotateEdgeProof() {
        appendRepeatableComment(
            toAddr(0x41b2d0L),
            "[C2E edge-code proof]\n" +
            "0 LEFT: this.xLeft == other.xRight and vertical Y ranges overlap.\n" +
            "3 RIGHT: this.xRight == other.xLeft and vertical Y ranges overlap.\n" +
            "1 CEILING: this ceiling line coincides with other's floor over a positive X interval.\n" +
            "2 FLOOR: this floor line coincides with other's ceiling over a positive X interval.\n" +
            "Reversing parent-room ordering uses 3-edge, yielding LEFT<->RIGHT and CEILING<->FLOOR.");

        setEOLComment(
            toAddr(0x41b32dL),
            "shared boundary is this room's LEFT edge (C2EEditorEdge.LEFT = 0)");

        setEOLComment(
            toAddr(0x41b395L),
            "shared boundary is this room's RIGHT edge (C2EEditorEdge.RIGHT = 3)");

        setEOLComment(
            toAddr(0x41b487L),
            "shared boundary is this room's CEILING (C2EEditorEdge.CEILING = 1)");

        setEOLComment(
            toAddr(0x41b551L),
            "shared boundary is this room's FLOOR (C2EEditorEdge.FLOOR = 2)");

        appendRepeatableComment(
            toAddr(0x41b570L),
            "[C2E wall seed order] Complete room edges are seeded as CEILING=1, RIGHT=3, FLOOR=2, LEFT=0 before door openings are subtracted.");
    }

    private void annotatePerimeterProof() {
        appendRepeatableComment(
            toAddr(0x41bc50L),
            "[C2E perimeter proof]\n" +
            "If cached value at geometry+0x18 is negative, calculate:\n" +
            "  hypot(xRight-xLeft, yRightCeiling-yLeftCeiling)\n" +
            "+ hypot(xRight-xLeft, yRightFloor-yLeftFloor)\n" +
            "+ (yLeftFloor-yLeftCeiling)\n" +
            "+ (yRightFloor-yRightCeiling)\n" +
            "and cache the float result.");

        appendRepeatableComment(
            toAddr(0x41bbd0L),
            "[C2E cache invalidation] Vertex translation writes -1.0f to geometry+0x18 (Room+0x24), invalidating perimeterLength.");

        setEOLComment(
            toAddr(0x41bc38L),
            "perimeterLength = -1.0f after geometry translation");
    }

    private void annotateContainmentProof() {
        appendRepeatableComment(
            toAddr(0x41baa0L),
            "[C2E point containment]\n" +
            "Build bounding rectangle from xLeft/xRight and ceiling/floor extrema; optionally inflate by margin; reject if point is outside. Then evaluate ceiling and floor line equations at point.x and require point.y to lie between them.");
    }

    private void annotateSourceCrossMatch() {
        appendRepeatableComment(
            toAddr(0x41bc50L),
            "[C2E DS-source cross-match] Docking Station runtime Room explicitly has float perimeterLength and calculates it from floor/ceiling lengths plus vertical room edges. The editor binary independently implements the same quantity as a lazy compact-room cache.");

        appendRepeatableComment(
            toAddr(0x41baa0L),
            "[C2E DS-source cross-match] Docking Station Map.cpp contains IsPointInsideRoom(position, room). The compact editor has the same geometric concept, implemented directly on C2ERoomGeometry.");

        appendRepeatableComment(
            toAddr(0x41b2d0L),
            "[C2E source caution] Do NOT reuse runtime DIRECTION_* numeric values here. Editor C2EEditorEdge ordering is independently proven as LEFT=0, CEILING=1, FLOOR=2, RIGHT=3.");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure requireStructure(String name) {
        DataType dt = dtm.getDataType(CAT, name);

        if (!(dt instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run RefineC2EMapEditorDerivedGeometry.java first.");
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
    // this pointer / return types
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
            else if ("float".equals(kind)) {
                type = FloatDataType.dataType;
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
