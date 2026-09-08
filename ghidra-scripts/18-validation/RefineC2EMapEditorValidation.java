// Creatures Map Editor 1.08 — validation / door terminology recovery.
//
// Prerequisites:
//   all previous recovery passes through RefineC2EMapEditorLiveSyncCA.java.
//
// This pass is primarily a validation-semantic pass, but validation provides
// stronger terminology for the two derived boundary collections:
//
//   World +0x24  internalDoorSegments
//   World +0x38  externalDoorSegments
//
// Earlier recovery called +0x38 "wallSegments". That described how the
// geometry was constructed (room perimeter left after subtracting shared
// openings) but not how the editor itself treats the result. The executable's
// exact validation strings call these generated pieces "external door[s]".
//
// Internal doors are shared boundaries between two Rooms.
// External doors are remaining one-Room boundary pieces against outside space.
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

public class RefineC2EMapEditorValidation extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;
    private Structure worldType;
    private Structure metaRoomType;

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
    private int labelsCreated;
    private int labelsRenamed;

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

    private static class LabelSpec {
        final long address;
        final String name;
        final String comment;

        LabelSpec(long address, String name, String comment) {
            this.address = address;
            this.name = name;
            this.comment = comment;
        }
    }

    private final FuncSpec[] FUNCTIONS = new FuncSpec[] {
        new FuncSpec(
            0x424050L,
            "C2EWorldModel_Validate",
            "world",
            "Validates the complete editor map. Ensures derived doors are current; checks metaroom bounds against the world, metaroom rectangle overlap, per-metaroom room validity, and minimum generated internal/external door length. Optional selectedRoomIds / selectedDoorPairs sets are populated before a validation exception is raised so the View can highlight offending objects.",
            "void"),

        new FuncSpec(
            0x4155e0L,
            "C2EEditorMetaRoom_ValidateRooms",
            "metaroom",
            "Validates Rooms owned by one metaroom. Checks basic trapezoid shape, all four room corners inside the metaroom rectangle, and pairwise room overlap. When an optional selectedRoomIds set is supplied, offending room IDs are inserted before the validation exception is raised.",
            "void"),

        new FuncSpec(
            0x416a80L,
            "C2EEditorMetaRoom_BuildInternalDoorSegments",
            "metaroom",
            "Builds generated shared-boundary doors between pairs of Rooms. Earlier name BuildDoorSegments is refined to InternalDoorSegments using the editor's own validation terminology.",
            "void",
            "C2EEditorMetaRoom_BuildDoorSegments"),

        new FuncSpec(
            0x416c50L,
            "C2EEditorMetaRoom_BuildExternalDoorSegments",
            "metaroom",
            "Builds one-parent external door boundary pieces remaining around Room perimeters after internal shared openings are removed. Earlier name BuildWallSegments is corrected using the executable's 'external door' validation terminology.",
            "void",
            "C2EEditorMetaRoom_BuildWallSegments"),

        new FuncSpec(
            0x41b570L,
            "C2ERoomGeometry_BuildExternalDoorSegments",
            "none",
            "Geometry primitive that starts from all four room edges, subtracts internal/shared boundary pieces and emits the remaining one-parent external-door pieces.",
            "void",
            "C2ERoomGeometry_BuildWallSegments"),

        new FuncSpec(
            0x422c20L,
            "C2EWorldModel_FindInternalDoorByRoomPair",
            "world",
            "Finds a generated two-room internal door using its room-ID pair. Earlier generic FindDoorByRoomPair name is refined now that internal/external generated door terminology is proven.",
            "unknown",
            "C2EWorldModel_FindDoorByRoomPair")
    };

    private final LabelSpec[] LABELS = new LabelSpec[] {
        new LabelSpec(
            0x4348f8L,
            "C2E_VALIDATION_ROOM_BAD_SHAPE",
            "Validation error: Room bad shape"),

        new LabelSpec(
            0x434908L,
            "C2E_VALIDATION_ROOM_OUTSIDE_METAROOM",
            "Validation error: Room not inside metaroom"),

        new LabelSpec(
            0x434924L,
            "C2E_VALIDATION_ROOMS_OVERLAP",
            "Validation error: Rooms must not overlap"),

        new LabelSpec(
            0x435318L,
            "C2E_VALIDATION_METAROOM_OUTSIDE_WORLD",
            "Validation error: Metaroom outside world."),

        new LabelSpec(
            0x435330L,
            "C2E_VALIDATION_METAROOMS_OVERLAP",
            "Validation error: Metarooms overlap."),

        new LabelSpec(
            0x4352b8L,
            "C2E_VALIDATION_INTERNAL_DOOR_TOO_SMALL",
            "Validation error: generated internal door length must be at least 5 units."),

        new LabelSpec(
            0x435258L,
            "C2E_VALIDATION_EXTERNAL_DOOR_TOO_SMALL",
            "Validation error: generated external door length must be at least 5 units.")
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
        // 00424050 starts World validation.
        // 004155E0 starts MetaRoom room validation.
        // Error string immediates provide an independent semantic guard.
        if (getByte(toAddr(0x424050L)) != (byte)0x83 ||
            getByte(toAddr(0x424051L)) != (byte)0xec ||
            getByte(toAddr(0x4155e0L)) != (byte)0x83 ||
            getByte(toAddr(0x4155e1L)) != (byte)0xec ||
            getInt(toAddr(0x42412bL)) != 0x00435330 ||
            getInt(toAddr(0x41589cL)) != 0x00434924 ||
            getInt(toAddr(0x4241adL)) != 0x004352b8 ||
            getInt(toAddr(0x4242b5L)) != 0x00435258) {

            popup("MapEditor 1.08 validation identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        worldType = requireStructure("C2EWorldModel");
        metaRoomType = requireStructure("C2EEditorMetaRoom");

        worldPtr = new PointerDataType(worldType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);

        println("=== Creatures Map Editor 1.08 - validation recovery ===");
        println("");
        println("Promoting editor terminology: internal doors / external doors.");
        println("");

        correctWorldDoorFields();

        for (LabelSpec spec : LABELS) {
            refineLabel(spec);
        }

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
                "[C2E validation] " + spec.comment);

            if ("world".equals(spec.owner)) {
                applyThisType(f, worldPtr, "C2EWorldModel");
            }
            else if ("metaroom".equals(spec.owner)) {
                applyThisType(f, metaRoomPtr, "C2EEditorMetaRoom");
            }

            applyReturnType(f, spec.returnKind);
        }

        annotateWorldRules();
        annotateMetaRoomRules();
        annotateDoorTerminology();
        annotateValidateUI();

        println("");
        println("Running analysis on validation changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Validation summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("labels created:       " + labelsCreated);
        println("labels renamed:       " + labelsRenamed);
        println("");
        println("World derived-door terminology:");
        println("  +0x24 internalDoorSegments");
        println("  +0x38 externalDoorSegments");
        println("  +0x4C inactiveInternalDoorCache");
        println("");
        println("Minimum generated door length: 5 units.");
    }

    // ---------------------------------------------------------------------
    // Structure corrections
    // ---------------------------------------------------------------------

    private void correctWorldDoorFields() {
        safeField(
            worldType,
            0x24,
            componentTypeAt(worldType, 0x24),
            componentLengthAt(worldType, 0x24),
            "internalDoorSegments",
            "Generated shared Room-to-Room boundary segments. These are the editor's 'internal doors'; CAOS DOOR commands are emitted from this collection.",
            "doorSegments",
            "internalDoorSegments");

        safeField(
            worldType,
            0x38,
            componentTypeAt(worldType, 0x38),
            componentLengthAt(worldType, 0x38),
            "externalDoorSegments",
            "Generated one-parent Room-to-outside boundary segments left around room perimeters after internal shared openings are removed. Earlier recovery called these wallSegments; validation strings prove the editor calls them external doors.",
            "wallSegments",
            "externalDoorSegments");

        safeField(
            worldType,
            0x4c,
            componentTypeAt(worldType, 0x4c),
            componentLengthAt(worldType, 0x4c),
            "inactiveInternalDoorCache",
            "Preserves permeability for internal Room-pair doors which temporarily disappear after geometry edits and may reappear later.",
            "inactiveDoorCache",
            "inactiveInternalDoorCache");

        setDescriptionSafe(
            worldType,
            "Compact Map Editor WorldModel. Derived boundary collections: +0x24 internalDoorSegments (two-room shared boundaries), +0x38 externalDoorSegments (one-room outside boundaries), +0x4C inactiveInternalDoorCache; +0x60 derivedCachesValid.");
    }

    private DataType componentTypeAt(Structure s, int offset) {
        DataTypeComponent c = s.getComponentContaining(offset);
        if (c == null) {
            return Undefined1DataType.dataType;
        }
        return c.getDataType();
    }

    private int componentLengthAt(Structure s, int offset) {
        DataTypeComponent c = s.getComponentContaining(offset);
        if (c == null) {
            return 1;
        }
        return c.getLength();
    }

    // ---------------------------------------------------------------------
    // Evidence
    // ---------------------------------------------------------------------

    private void annotateWorldRules() {
        appendRepeatableComment(
            toAddr(0x424050L),
            "[C2E World validation rules]\n" +
            "1. EnsureDerivedCaches().\n" +
            "2. For every MetaRoom bounds {left,top,right,bottom}:\n" +
            "     left >= 0\n" +
            "     top >= 0\n" +
            "     right <= world.mapWidth\n" +
            "     bottom <= world.mapHeight\n" +
            "   otherwise: 'Metaroom outside world.'\n" +
            "3. Compare MetaRoom rectangles pairwise with IntersectRect; any non-empty intersection -> 'Metarooms overlap.'\n" +
            "4. Call each MetaRoom's room validator.\n" +
            "5. Every internalDoorSegments element must have length >= 5.\n" +
            "6. Every externalDoorSegments element must have length >= 5.\n" +
            "The function raises the editor validation exception on the first failure rather than returning a boolean.");

        setEOLComment(
            toAddr(0x4240aaL),
            "World bounds rule starts: left/top must be non-negative.");

        setEOLComment(
            toAddr(0x4240beL),
            "MetaRoom right must be <= world.mapWidth.");

        setEOLComment(
            toAddr(0x4240c6L),
            "MetaRoom bottom must be <= world.mapHeight.");

        setEOLComment(
            toAddr(0x4240ffL),
            "IntersectRect between two MetaRoom bounds; non-empty intersection is forbidden.");

        setEOLComment(
            toAddr(0x42417eL),
            "internalDoorSegments: segment.length compared against minimum 5.");

        setEOLComment(
            toAddr(0x4241deL),
            "externalDoorSegments: segment.length compared against minimum 5.");
    }

    private void annotateMetaRoomRules() {
        appendRepeatableComment(
            toAddr(0x4155e0L),
            "[C2E MetaRoom room-validation rules]\n" +
            "For each Room geometry:\n" +
            "  xLeft < xRight\n" +
            "  yLeftCeiling < yLeftFloor\n" +
            "  yRightCeiling < yRightFloor\n" +
            "otherwise: 'Room bad shape'.\n" +
            "All four Room corner points are checked with PtInRect against the owning MetaRoom bounds; failure -> 'Room not inside metaroom'.\n" +
            "Rooms are then checked pairwise. If any corner of one room lies inside another room, the pair is rejected as 'Rooms must not overlap'.\n" +
            "When selectedRoomIds is non-null, offending room IDs are inserted before the validation exception is raised.");

        setEOLComment(
            toAddr(0x415624L),
            "Room shape: require xLeft < xRight.");

        setEOLComment(
            toAddr(0x415631L),
            "Room shape: require yLeftFloor > yLeftCeiling.");

        setEOLComment(
            toAddr(0x41563fL),
            "Room shape: require yRightFloor > yRightCeiling.");

        setEOLComment(
            toAddr(0x41564eL),
            "PtInRect(metaRoom.bounds, left-floor corner). All four corners are tested.");

        setEOLComment(
            toAddr(0x4156c2L),
            "Pairwise overlap test begins using C2EEditorRoom_ContainsPoint on room corners.");
    }

    private void annotateDoorTerminology() {
        appendRepeatableComment(
            toAddr(0x416a80L),
            "[C2E terminology correction] Shared two-Room boundary pieces are internal doors. Their persisted permeability is the value written by generated CAOS DOOR room1 room2 permeability.");

        appendRepeatableComment(
            toAddr(0x416c50L),
            "[C2E terminology correction] The pieces formerly called wallSegments are generated external doors: one-Room boundary pieces against outside space. Validation independently calls this collection 'external door[s]' and enforces minimum length 5.");

        appendRepeatableComment(
            toAddr(0x41b570L),
            "[C2E external-door construction] Starts with the complete four-edge Room perimeter and subtracts matching internal/shared openings. The residual boundary pieces are emitted as generated external doors.");

        appendRepeatableComment(
            toAddr(0x423470L),
            "[C2E derived-cache terminology] EnsureDerivedCaches rebuilds both internalDoorSegments and externalDoorSegments. Existing internal-door permeability is preserved/restored through inactiveInternalDoorCache.");
    }

    private void annotateValidateUI() {
        appendRepeatableComment(
            toAddr(0x410ff0L),
            "[C2E Validate command] CC2ERoomEditorView_OnValidate passes View.selectedRoomIds and View.selectedDoorPairs into C2EWorldModel_Validate. Validation may populate those sets with offending objects before throwing; the View catches the validation exception, displays its text and redraws the resulting selection/highlight state.");

        appendRepeatableComment(
            toAddr(0x408980L),
            "[C2E post-edit validation] After a successful edit action is applied, the document also calls C2EWorldModel_Validate with null selection sets when validation/debug state is enabled. This checks integrity without changing View selections.");
    }

    // ---------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------

    private void refineLabel(LabelSpec spec) {
        try {
            Address a = toAddr(spec.address);
            Symbol s = getSymbolAt(a);

            if (s == null) {
                createLabel(a, spec.name, true);
                labelsCreated++;
                println("[label] " + a + " -> " + spec.name);
            }
            else if (spec.name.equals(s.getName())) {
                // already right
            }
            else if (s.getSource() == SourceType.DEFAULT ||
                     s.getName().startsWith("s_") ||
                     s.getName().startsWith("DAT_")) {

                String old = s.getName();
                s.setName(spec.name, SourceType.USER_DEFINED);
                labelsRenamed++;
                println("[label-rename] " + a + " " + old + " -> " + spec.name);
            }
            else {
                println("[label-preserve] " + a +
                    " existing='" + s.getName() +
                    "' candidate='" + spec.name + "'");
            }

            String oldComment = getEOLComment(a);
            if (oldComment == null || oldComment.isBlank()) {
                setEOLComment(a, spec.comment);
            }
            else if (!oldComment.contains(spec.comment)) {
                setEOLComment(a, oldComment + "\n" + spec.comment);
            }
        }
        catch (Exception e) {
            println("[label-fail] " + Long.toHexString(spec.address) +
                " " + spec.name + ": " + e.getMessage());
        }
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

    private void safeField(
            Structure s,
            int offset,
            DataType type,
            int length,
            String name,
            String comment,
            String... acceptedOldNames) {

        try {
            DataTypeComponent c = s.getComponentContaining(offset);

            if (c == null) {
                fieldsPreserved++;
                println("[field-preserve] " + s.getName() +
                    " +0x" + Integer.toHexString(offset) +
                    " has no component to refine.");
                return;
            }

            String current = c.getFieldName();
            boolean safe =
                current == null ||
                current.isBlank() ||
                name.equals(current) ||
                current.startsWith("field_") ||
                current.startsWith("undefined") ||
                current.startsWith("padding");

            if (!safe && acceptedOldNames != null) {
                for (String old : acceptedOldNames) {
                    if (old != null && old.equals(current)) {
                        safe = true;
                        break;
                    }
                }
            }

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
    // Function helpers
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
            println("[keep] " + a +
                " existing=" + current +
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

                VariableStorage storage = p.getVariableStorage();

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

            println("[this] " + f.getEntryPoint() +
                " " + f.getName() +
                " -> " + typeName + " * @ ECX");
        }
        catch (Exception e) {
            thisTypesSkipped++;

            println("[this-fail] " +
                f.getEntryPoint() +
                " " + f.getName() +
                ": " + e.getMessage());
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

                f.setReturnType(
                    type,
                    SourceType.USER_DEFINED);

                returnTypesApplied++;
            }
        }
        catch (Exception e) {
            println("[return-skip] " +
                f.getEntryPoint() +
                " " + f.getName() +
                ": " + e.getMessage());
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
