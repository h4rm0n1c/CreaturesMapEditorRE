// Creatures Map Editor 1.08 — live CA sync / Cheese CA simulation recovery.
//
// Prerequisites:
//   all previous passes through RefineC2EMapEditorGameIPC.java.
//
// This pass intentionally corrects several older medium-confidence fields.
//
// IMPORTANT ROOM CORRECTION
// -------------------------
// C2EEditorRoom does NOT store its room ID at +0x40. Room IDs are keys in the
// owning MetaRoom room tree and are passed explicitly to helpers which need an
// ID (including BuildInjectCAOS / BuildDeleteCAOS).
//
// Exact compact Room CA state:
//
//   +00 float caValue
//   +04 float caInput
//   +08 float caTempValue
//   +0C C2ERoomGeometry [0x1C]
//   +28 propertyValues vector
//   +38 float caTotalDoorage
//   +3C VC6CString track
//   +40 unresolved state40
//
// Room Type is NOT +08. It is overall room property index 6, i.e.
// propertyValues[0], proven by C2EEditorRoom_GetProperty.
//
// CHEESE TOOL / LOCAL CA
// ----------------------
// Doc +0x148 is a vector<C2EIntPoint>. C2ECheeseTool_OnLButtonUp inserts an
// 8-byte point directly into this vector.
//
// Hidden toolbar ID 32824 performs one local CA0 simulation step using:
//   Doc +0x138 caRates
//   Doc +0x148 cheeseSources
//
// Hidden toolbar ID 32826 toggles continuous timer-driven local simulation.
// It is mutually exclusive with ID 32829 Update CA from Game.
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

public class RefineC2EMapEditorLiveSyncCA extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure docType;
    private Structure viewType;
    private Structure worldType;
    private Structure metaRoomType;
    private Structure roomType;
    private Structure intPointType;
    private Structure intPointVectorType;

    private Pointer docPtr;
    private Pointer viewPtr;
    private Pointer worldPtr;
    private Pointer metaRoomPtr;
    private Pointer roomPtr;

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
        final String[] replaceNames;

        LabelSpec(long address, String name, String comment, String... replaceNames) {
            this.address = address;
            this.name = name;
            this.comment = comment;
            this.replaceNames = replaceNames;
        }
    }

    private final FuncSpec[] FUNCTIONS = new FuncSpec[] {

        // Hidden local Cheese / CA0 simulation controls.
        new FuncSpec(
            0x40b210L,
            "CC2ERoomEditorDoc_StepCheeseCASimulation",
            "doc",
            "Hidden toolbar command ID 32824. Performs one local Cheese/CA0 simulation step using document caRates (+0x138) and cheeseSources (+0x148), then refreshes all views.",
            "void",
            "CC2ERoomEditorDoc_OnID_32824"),

        new FuncSpec(
            0x411570L,
            "CC2ERoomEditorView_OnToggleCheeseCASimulation",
            "view",
            "Hidden toolbar command ID 32826. Toggles simulateCheeseCAEnabled (+0x1E5). Enabling it disables updateCAFromGameEnabled (+0x1E6), making local simulation and live-engine CA sampling mutually exclusive.",
            "void",
            "CC2ERoomEditorView_OnID_32826"),

        new FuncSpec(
            0x411590L,
            "CC2ERoomEditorView_OnUpdateToggleCheeseCASimulation",
            "view",
            "MFC UPDATE_COMMAND_UI handler for hidden ID 32826. Checkmark reflects simulateCheeseCAEnabled (+0x1E5).",
            "void",
            "CC2ERoomEditorView_OnUpdateID_32826"),

        new FuncSpec(
            0x411760L,
            "CC2ERoomEditorView_OnToggleUpdateCAFromGame",
            "view",
            "Command ID 32829. Toggles updateCAFromGameEnabled (+0x1E6). Enabling live sampling disables simulateCheeseCAEnabled (+0x1E5).",
            "void",
            "CC2ERoomEditorView_OnUpdateCAFromGame"),

        // Compact editor local CA implementation.
        new FuncSpec(
            0x424eb0L,
            "C2EWorldModel_StepCheeseCASimulation",
            "world",
            "Compact-editor adaptation of C2e CA update logic for CA index 0. Seeds caInput=1.0 in rooms containing cheeseSources, prepares each room with CA rate row selected by Room Type property index 6, diffuses through generated doors using permeability/relative door size, accumulates caTotalDoorage, then applies the undiffused remainder.",
            "void"),

        new FuncSpec(
            0x417b30L,
            "C2EEditorMetaRoom_PrepareCheeseCAStep",
            "metaroom",
            "For every room, reads Room Type via GetProperty(6), selects the first C2ECARate in that room-type row (CA index 0), calls C2ECA_UpdateRoomCA(caInput,caValue,caTempValue), clears caInput through that helper, and resets caTotalDoorage before door diffusion.",
            "void"),

        new FuncSpec(
            0x417c20L,
            "C2EEditorMetaRoom_FinalizeCheeseCAStep",
            "metaroom",
            "For every room applies caValue += caTempValue * (1 - caTotalDoorage), completing the local CA0 step after door diffusion.",
            "void"),

        new FuncSpec(
            0x41c880L,
            "C2ECA_UpdateRoomCA",
            "none",
            "Direct match for Creature Labs RoomCA.cpp UpdateRoomCA(CARates,input,newValue,tempValue): clamps input below at zero, maps input to 0..1, chooses loss or gain rate, computes tempValue, then clears newValue.",
            "void"),

        new FuncSpec(
            0x41c8c0L,
            "C2ECA_ApplyUndiffusedRoomRemainder",
            "none",
            "Editor helper implementing caValue += caTempValue * (1 - caTotalDoorage). This is the final per-room operation in the compact local CA step.",
            "void"),

        new FuncSpec(
            0x41c8e0L,
            "C2ECA_UpdateDoorCA",
            "none",
            "Direct match for Creature Labs RoomCA.cpp UpdateDoorCA: combines the two room diffusion rates, average temporary CA values and relative door sizes, accumulating transferred CA into both room values.",
            "void"),

        // Live engine CA query/parse helpers.
        new FuncSpec(
            0x425220L,
            "C2EWorldModel_AppendRoomCAQueryCAOS",
            "world",
            "Walks all metarooms and appends one live-engine room-CA query per compact Room by delegating to C2EEditorMetaRoom_AppendRoomCAQueryCAOS.",
            "void"),

        new FuncSpec(
            0x417d70L,
            "C2EEditorMetaRoom_AppendRoomCAQueryCAOS",
            "metaroom",
            "For every room computes its centre point and appends 'outv prop grap <x> <y> va00' plus newline. VA00 is set by the timer to colourRoomsCAIndex before these queries are emitted.",
            "void"),

        new FuncSpec(
            0x4251b0L,
            "C2EWorldModel_ParseRoomCAValues",
            "world",
            "Walks all metarooms and parses one returned float per room into compact Room.caValue.",
            "void"),

        new FuncSpec(
            0x417d00L,
            "C2EEditorMetaRoom_ParseRoomCAValues",
            "metaroom",
            "For every room performs stream >> float directly into the Room object address, proving Room+0x00 is caValue. Used by timer-driven Update CA from Game.",
            "void"),

        // Small exact helper useful to Set Metaroom in Game.
        new FuncSpec(
            0x415ad0L,
            "C2EEditorMetaRoom_GetBounds",
            "metaroom",
            "Copies this metaroom's four-int bounds into the caller-provided C2EIntRect result.",
            "unknown")
    };

    private final LabelSpec[] LABELS = new LabelSpec[] {
        new LabelSpec(
            0x434ef0L,
            "C2E_GAME_QUERY_ROOM_CA_AT_POINT_FORMAT",
            "Live CA query: outv prop grap %d %d va00; outs newline. Used once per room at its centre.",
            "C2E_GAME_QUERY_BACKGROUND_PROP_FORMAT"),

        new LabelSpec(
            0x434770L,
            "C2E_GAME_SET_CA_INDEX_FORMAT",
            "SETV VA00 %d newline. Timer sets VA00 to colourRoomsCAIndex before querying each room centre."),

        new LabelSpec(
            0x43475cL,
            "C2E_GAME_ERROR_UPDATING_CA",
            "Error updating CA"),

        new LabelSpec(
            0x43504cL,
            "C2E_GAME_SET_METAROOM_FORMAT",
            "META GMAP centreX centreY -1 -1 0. Used by Switch Metaroom dialog."),

        new LabelSpec(
            0x435034L,
            "C2E_GAME_SET_BACKGROUND_FORMAT",
            "BKGD GMAP centreX centreY \"background\" 0. Used by Switch Metaroom dialog."),

        new LabelSpec(
            0x43502cL,
            "C2E_GAME_DEBUG_MAP_FORMAT",
            "DMAP %d. C2e source defines 1=debug map on, 0=off."),

        new LabelSpec(
            0x435010L,
            "C2E_GAME_UNKNOWN_ERROR",
            "An unknown error occurred")
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
        // 0040B210: 56 8B F1 ...
        // 00411570: 8A 81 E5 01 00 00
        // 00411760: 8A 81 E6 01 00 00
        // 00424EB0: 64 A1 00 00 00 00
        // 00420AD9: 8B 4A 40 then access Doc+0x150 / +0x148
        if (getByte(toAddr(0x40b210L)) != (byte)0x56 ||
            getByte(toAddr(0x40b211L)) != (byte)0x8b ||
            getByte(toAddr(0x411570L)) != (byte)0x8a ||
            getByte(toAddr(0x411572L)) != (byte)0xe5 ||
            getByte(toAddr(0x411760L)) != (byte)0x8a ||
            getByte(toAddr(0x411762L)) != (byte)0xe6 ||
            getByte(toAddr(0x424eb0L)) != (byte)0x64 ||
            getByte(toAddr(0x424eb1L)) != (byte)0xa1 ||
            getByte(toAddr(0x420ad9L)) != (byte)0x8b ||
            getByte(toAddr(0x420adaL)) != (byte)0x4a ||
            getByte(toAddr(0x420adbL)) != (byte)0x40) {

            popup("MapEditor 1.08 live-sync/CA identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        docType = requireStructure("CC2ERoomEditorDoc_refined");
        viewType = requireStructure("CC2ERoomEditorView_refined");
        worldType = requireStructure("C2EWorldModel");
        metaRoomType = requireStructure("C2EEditorMetaRoom");
        roomType = requireStructure("C2EEditorRoom");
        intPointType = requireStructure("C2EIntPoint");

        docPtr = new PointerDataType(docType, dtm);
        viewPtr = new PointerDataType(viewType, dtm);
        worldPtr = new PointerDataType(worldType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);
        roomPtr = new PointerDataType(roomType, dtm);

        println("=== Creatures Map Editor 1.08 - live sync / CA recovery ===");
        println("");
        println("Correcting compact Room CA fields and resolving the hidden Cheese CA controls.");
        println("");

        buildPointVector();
        refineDocumentCheeseSources();
        correctRoomCAFields();
        refineViewCAFlags();

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
                "[C2E live sync / CA] " + spec.comment);

            if ("doc".equals(spec.owner)) {
                applyThisType(f, docPtr, "CC2ERoomEditorDoc_refined");
            }
            else if ("view".equals(spec.owner)) {
                applyThisType(f, viewPtr, "CC2ERoomEditorView_refined");
            }
            else if ("world".equals(spec.owner)) {
                applyThisType(f, worldPtr, "C2EWorldModel");
            }
            else if ("metaroom".equals(spec.owner)) {
                applyThisType(f, metaRoomPtr, "C2EEditorMetaRoom");
            }
            else if ("room".equals(spec.owner)) {
                applyThisType(f, roomPtr, "C2EEditorRoom");
            }

            applyReturnType(f, spec.returnKind);
        }

        correctOldRoomIdComments();
        annotateCheeseTool();
        annotateTimer();
        annotateLiveCAFlow();
        annotateSwitchMetaRoom();
        annotateSourceCrossMatches();

        println("");
        println("Running analysis on live-sync / CA corrections...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Live sync / CA summary ===");
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
        println("Corrected compact Room:");
        println("  +00 caValue");
        println("  +04 caInput");
        println("  +08 caTempValue");
        println("  +38 caTotalDoorage");
        println("  +40 state40 (unresolved; explicitly NOT roomId)");
        println("");
        println("Document:");
        println("  +148 cheeseSources = vector<C2EIntPoint>");
        println("");
        println("View:");
        println("  +1E5 simulateCheeseCAEnabled");
        println("  +1E6 updateCAFromGameEnabled");
    }

    // ---------------------------------------------------------------------
    // Data-type corrections
    // ---------------------------------------------------------------------

    private void buildPointVector() {
        intPointVectorType = getOrCreate("C2EIntPointVector", 0x10);

        Pointer p = new PointerDataType(intPointType, dtm);

        safeField(
            intPointVectorType, 0x00,
            Undefined1DataType.dataType, 1,
            "allocatorState",
            "VC6 vector allocator/state byte.");

        safeField(
            intPointVectorType, 0x01,
            new ArrayDataType(Undefined1DataType.dataType, 3, 1), 3,
            "padding01",
            "Alignment.");

        safeField(
            intPointVectorType, 0x04,
            p, 4,
            "begin",
            "First C2EIntPoint.");

        safeField(
            intPointVectorType, 0x08,
            p, 4,
            "end",
            "One past last point.");

        safeField(
            intPointVectorType, 0x0c,
            p, 4,
            "capacityEnd",
            "One past allocated storage.");

        setDescriptionSafe(
            intPointVectorType,
            "0x10-byte VC6 vector of 8-byte C2EIntPoint values. Used by document.cheeseSources.");
    }

    private void refineDocumentCheeseSources() {
        replaceRangeField(
            docType,
            0x148,
            0x10,
            intPointVectorType,
            "cheeseSources",
            "Points inserted by C2ECheeseTool_OnLButtonUp. Local Cheese/CA0 simulation seeds caInput=1.0 in the room containing each point.",
            new String[] {
                "tailState", "state148", "state0x148",
                "field_148", "undefined148"
            });
    }

    private void correctRoomCAFields() {
        // +0..+0B was previously kept as one medium-confidence editorState00 blob.
        DataTypeComponent c0 = roomType.getComponentContaining(0x00);

        if (c0 != null) {
            String n = c0.getFieldName();

            boolean safe =
                n == null ||
                n.isBlank() ||
                "editorState00".equals(n) ||
                "caValue".equals(n);

            if (!safe) {
                fieldsPreserved++;
                println("[room-ca-preserve] C2EEditorRoom +0x00 existing='" +
                    n + "'; not splitting +0..+0B.");
            }
            else {
                try {
                    if (c0.getOffset() == 0x00) {
                        roomType.clearAtOffset(0x00);
                    }

                    roomType.replaceAtOffset(
                        0x00,
                        FloatDataType.dataType,
                        4,
                        "caValue",
                        "Current/displayed CA scalar. Live Update CA parses one float directly into Room+0. Local Cheese simulation also reads/writes this value.");

                    roomType.replaceAtOffset(
                        0x04,
                        FloatDataType.dataType,
                        4,
                        "caInput",
                        "CA input accumulator. Cheese source points seed this to 1.0. C2ECA_UpdateRoomCA consumes it during a local CA step.");

                    roomType.replaceAtOffset(
                        0x08,
                        FloatDataType.dataType,
                        4,
                        "caTempValue",
                        "Temporary CA value produced by C2ECA_UpdateRoomCA and consumed by door diffusion/finalization.");

                    fieldsRefined += 3;
                    println("[correct] C2EEditorRoom +0x00/+0x04/+0x08 -> caValue/caInput/caTempValue");
                }
                catch (Exception e) {
                    println("[room-ca-fail] " + e.getMessage());
                }
            }
        }

        safeField(
            roomType,
            0x38,
            FloatDataType.dataType,
            4,
            "caTotalDoorage",
            "Total relative door contribution accumulated for the current local CA step. Finalization applies caTempValue * (1 - caTotalDoorage).",
            "state38",
            "derivedState38",
            "unresolved38");

        safeField(
            roomType,
            0x40,
            Undefined4DataType.dataType,
            4,
            "state40",
            "UNRESOLVED compact-editor state. Earlier recovery incorrectly named this roomId. Room IDs are owning-tree keys and are passed explicitly to ID-dependent helpers; C2EEditorRoom_ctor does not initialize +0x40.",
            "roomId",
            "state40",
            "state0x40",
            "field_40");

        setDescriptionSafe(
            roomType,
            "Compact 0x44-byte Map Editor Room. CA state: +0 caValue, +4 caInput, +8 caTempValue; +0x0C complete geometry; +0x28 additional property vector (property index 6 / element 0 is Room Type); +0x38 caTotalDoorage; +0x3C track; +0x40 unresolved state. Room ID is NOT stored here; it is the owning map/tree key.");
    }

    private void refineViewCAFlags() {
        safeField(
            viewType,
            0x1e5,
            BooleanDataType.dataType,
            1,
            "simulateCheeseCAEnabled",
            "Hidden toolbar ID 32826 toggle. Timer calls CC2ERoomEditorDoc_StepCheeseCASimulation while true. Mutually exclusive with updateCAFromGameEnabled.",
            "caStateFlag1",
            "simulateCheeseCAEnabled");

        // Refresh the existing +1E6 comment while preserving its established name.
        DataTypeComponent live = viewType.getComponentContaining(0x1e6);
        if (live != null && "updateCAFromGameEnabled".equals(live.getFieldName())) {
            try {
                live.setComment(
                    "Command ID 32829 live-engine CA sampling toggle. Timer queries room-centre CA for colourRoomsCAIndex while enabled. Mutually exclusive with simulateCheeseCAEnabled.");
            }
            catch (Exception ignored) {
            }
        }

        setDescriptionSafe(
            viewType,
            "Creatures Map Editor view. CA modes: +0x1E5 simulateCheeseCAEnabled (local Cheese/CA0 simulation), +0x1E6 updateCAFromGameEnabled (live-engine room CA sampling). The two modes are mutually exclusive.");
    }

    // ---------------------------------------------------------------------
    // Label correction / creation
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
                // already correct
            }
            else {
                boolean replace =
                    s.getSource() == SourceType.DEFAULT ||
                    s.getName().startsWith("DAT_") ||
                    s.getName().startsWith("LAB_");

                if (!replace && spec.replaceNames != null) {
                    for (String old : spec.replaceNames) {
                        if (old != null && old.equals(s.getName())) {
                            replace = true;
                            break;
                        }
                    }
                }

                if (replace) {
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
    // Evidence corrections / annotations
    // ---------------------------------------------------------------------

    private void correctOldRoomIdComments() {
        appendRepeatableComment(
            toAddr(0x41a620L),
            "[C2E ROOM-ID CORRECTION] The room ID used for ADDR/GAME mapeditortmp_<roomId> is an explicit function argument/tree key. It is NOT read from C2EEditorRoom +0x40.");

        appendRepeatableComment(
            toAddr(0x41a770L),
            "[C2E ROOM-ID CORRECTION] The room ID formatted into DELG mapeditortmp_<roomId> is an explicit function argument/tree key. It is NOT a C2EEditorRoom member.");

        appendRepeatableComment(
            toAddr(0x41a800L),
            "[C2E ROOM-ID CORRECTION] Live Room loading receives roomId as an argument so it can query RTYP/RLOC. The compact C2EEditorRoom object itself does not store the owning tree key as +0x40.");
    }

    private void annotateCheeseTool() {
        appendRepeatableComment(
            toAddr(0x420a70L),
            "[C2E Cheese source proof]\n" +
            "On LButtonUp the Cheese tool converts the click to an 8-byte C2EIntPoint, obtains the document, and inserts one element into document.cheeseSources at Doc+0x148. The vector end pointer is Doc+0x150.");

        setEOLComment(
            toAddr(0x420adcL),
            "Doc +0x150 = cheeseSources.end.");

        setEOLComment(
            toAddr(0x420ae2L),
            "Doc +0x148 = cheeseSources vector.");
    }

    private void annotateTimer() {
        appendRepeatableComment(
            toAddr(0x4111e0L),
            "[C2E CA timer]\n" +
            "Timer ID 0x70 increments caTimerPhase modulo 20.\n" +
            "If simulateCheeseCAEnabled (+0x1E5): call document StepCheeseCASimulation.\n" +
            "If updateCAFromGameEnabled (+0x1E6) and colourRoomsCAIndex != -1:\n" +
            "  create live C2ECAOSOutput(game.log,true)\n" +
            "  emit SETV VA00 <colourRoomsCAIndex>\n" +
            "  append one PROP GRAP room-centre query per room\n" +
            "  execute over IPC\n" +
            "  parse one float per room into Room.caValue\n" +
            "  invalidate/redraw the view.\n" +
            "Local and live modes cannot both be enabled because each toggle clears the other.");

        setEOLComment(
            toAddr(0x411281L),
            "simulateCheeseCAEnabled -> document StepCheeseCASimulation.");

        setEOLComment(
            toAddr(0x4112e9L),
            "SETV VA00 colourRoomsCAIndex before room-centre PROP GRAP queries.");

        setEOLComment(
            toAddr(0x411301L),
            "Append one live CA query per room.");

        setEOLComment(
            toAddr(0x4113c6L),
            "Parse one returned float per room into C2EEditorRoom.caValue.");
    }

    private void annotateLiveCAFlow() {
        appendRepeatableComment(
            toAddr(0x417d70L),
            "[C2E live CA query grammar]\n" +
            "For each compact Room:\n" +
            "  centre = room.geometry.GetCentrePoint()\n" +
            "  append: outv prop grap <centre.x> <centre.y> va00\n" +
            "  append newline\n" +
            "VA00 was set by the View timer to colourRoomsCAIndex.");

        appendRepeatableComment(
            toAddr(0x417d00L),
            "[C2E Room+0 proof] The stream extraction operator is called with the C2EEditorRoom object pointer itself as the destination float address. Therefore the returned live CA scalar lands at Room+0x00 = caValue.");

        appendRepeatableComment(
            toAddr(0x424eb0L),
            "[C2E local Cheese/CA0 algorithm]\n" +
            "1. Ensure derived door caches.\n" +
            "2. For each document.cheeseSources point, find containing room and set room.caInput=1.0.\n" +
            "3. Each metaroom prepares room CA using Room Type property index 6 and rates[row][0].\n" +
            "4. For each generated door, derive relative door-size factors from door geometry/perimeter and permeability; call C2ECA_UpdateDoorCA; accumulate each room.caTotalDoorage.\n" +
            "5. Each metaroom finalizes caValue += caTempValue*(1-caTotalDoorage).\n" +
            "The absence of an inner CA-index offset when selecting C2ECARate proves this compact simulation specifically uses CA index 0.");

        setEOLComment(
            toAddr(0x424f07L),
            "Cheese source room found: room.caInput = 1.0f.");

        setEOLComment(
            toAddr(0x417bb2L),
            "GetProperty(6) => Room Type; propertyValues[0].");

        setEOLComment(
            toAddr(0x417bd8L),
            "Reset room.caTotalDoorage before door diffusion.");

        setEOLComment(
            toAddr(0x42506eL),
            "Accumulate relative door contribution into room1.caTotalDoorage.");

        setEOLComment(
            toAddr(0x425082L),
            "Accumulate relative door contribution into room2.caTotalDoorage.");
    }

    private void annotateSwitchMetaRoom() {
        appendRepeatableComment(
            toAddr(0x41e190L),
            "[C2E Set Metaroom in Game]\n" +
            "CSwitchMetaroomDlg::OnApply resolves the selected editor metaroom, gets its bounds and centre, then uses live C2ECAOSOutput to emit:\n" +
            "  meta gmap <centreX> <centreY> -1 -1 0\n" +
            "  bkgd gmap <centreX> <centreY> \"<selected background>\" 0\n" +
            "  dmap <debugMapEnabled>\n" +
            "This switches the running game's current metaroom/background/debug-map presentation; it does not rebuild room geometry.");

        setEOLComment(
            toAddr(0x41e27cL),
            "META GMAP: switch running game to selected metaroom centre.");

        setEOLComment(
            toAddr(0x41e2d9L),
            "BKGD GMAP: select requested background in that metaroom.");

        setEOLComment(
            toAddr(0x41e30cL),
            "DMAP checkbox state: 1 shows C2e debug map, 0 hides it.");
    }

    private void annotateSourceCrossMatches() {
        appendRepeatableComment(
            toAddr(0x41c880L),
            "[C2E source provenance] Direct algorithm match to released engine/Map/RoomCA.cpp UpdateRoomCA.");

        appendRepeatableComment(
            toAddr(0x41c8e0L),
            "[C2E source provenance] Direct algorithm match to released engine/Map/RoomCA.cpp UpdateDoorCA.");

        appendRepeatableComment(
            toAddr(0x424eb0L),
            "[C2E source provenance] High-level structure closely follows engine/Map/MapCA.cpp Map::UpdateCurrentCAProperty, but the editor is a compact adaptation: rates are passed from the document, cheese-source points provide CA input, and only one CA scalar is stored per compact Room.");

        appendRepeatableComment(
            toAddr(0x41e190L),
            "[C2E source provenance] Released CAOS table defines DMAP argument 'debug_map': 1 turns the debug map image on, 0 turns it off.");
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
                    name.equals(currentName) ||
                    currentName.startsWith("field_") ||
                    currentName.startsWith("undefined") ||
                    currentName.startsWith("padding");

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

    private void replaceRangeField(
            Structure s,
            int offset,
            int length,
            DataType type,
            String name,
            String comment,
            String[] acceptedNames) {

        int end = offset + length;
        HashSet<String> accepted = new HashSet<>();

        accepted.add(name);
        if (acceptedNames != null) {
            accepted.addAll(Arrays.asList(acceptedNames));
        }

        for (DataTypeComponent c : s.getComponents()) {
            int c0 = c.getOffset();
            int c1 = c0 + c.getLength();

            if (c1 <= offset || c0 >= end) {
                continue;
            }

            String n = c.getFieldName();

            if (n == null || n.isBlank()) {
                continue;
            }

            boolean okay =
                accepted.contains(n) ||
                n.startsWith("field_") ||
                n.startsWith("undefined") ||
                n.startsWith("padding") ||
                n.startsWith("state");

            if (!okay) {
                fieldsPreserved++;
                println("[range-preserve] " + s.getName() +
                    " +0x" + Integer.toHexString(offset) +
                    " intersects unexpected field '" + n +
                    "' at +0x" + Integer.toHexString(c0));
                return;
            }
        }

        try {
            ArrayList<Integer> starts = new ArrayList<>();

            for (DataTypeComponent c : s.getComponents()) {
                int c0 = c.getOffset();

                if (c0 >= offset && c0 < end) {
                    starts.add(c0);
                }
            }

            Collections.sort(starts, Collections.reverseOrder());

            for (Integer c0 : starts) {
                s.clearAtOffset(c0);
            }

            s.replaceAtOffset(
                offset,
                type,
                length,
                name,
                comment);

            fieldsRefined++;

            println("[correct] " + s.getName() +
                " +0x" + Integer.toHexString(offset) +
                " -> " + name + "[0x" +
                Integer.toHexString(length) + "]");
        }
        catch (Exception e) {
            println("[range-fail] " + s.getName() +
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
                if (p.isAutoParameter() ||
                    "this".equals(p.getName())) {
                    continue;
                }

                VariableStorage storage = p.getVariableStorage();

                if (storage != null &&
                    storage.isRegisterStorage() &&
                    storage.getRegister() != null &&
                    storage.getRegister().equals(ecx)) {

                    println("[drop-ecx-param] " + f.getEntryPoint() +
                        " dropping old " + p.getName() +
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

            println("[this] " + f.getEntryPoint() + " " +
                f.getName() + " -> " + typeName + " * @ ECX");
        }
        catch (Exception e) {
            thisTypesSkipped++;

            println("[this-fail] " + f.getEntryPoint() +
                " " + f.getName() + ": " +
                e.getMessage());
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
                f.getEntryPoint() + " " +
                f.getName() + ": " +
                e.getMessage());
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
