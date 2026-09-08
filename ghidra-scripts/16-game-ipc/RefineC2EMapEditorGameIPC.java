// Creatures Map Editor 1.08 — live-game IPC / Open From Game recovery.
//
// Prerequisites:
//   all previous recovery passes through RefineC2EMapEditorCAOSWorkflows.java.
//
// Major evidence:
//   The low-level IPC code at 00413830..00413E10 is a direct binary match for
//   the original Creature Labs common/ClientSide.cpp and ClientSide.h sources:
//
//     ClientSide::ClientSide
//     ClientSide::Cleanup
//     ClientSide::Open
//     ClientSide::StartTransaction
//     ClientSide::GetResultSize
//     ClientSide::GetResultBuffer
//     ClientSide::GetReturnCode
//     ClientSide::EndTransaction
//
// TransferHeader layout is therefore source-level exact:
//
//   +00 char MagicCookie[4]   "c2e@"
//   +04 DWORD ServerProcessId
//   +08 int ReturnCode
//   +0C uint DataSize
//   +10 uint BufferSize
//   +14 int Pad
//   +18 data bytes...
//
// Note: one historical header comment says "c2e!" but both implementation and
// this binary explicitly test "c2e@".
//
// External corroboration also exists in pyc2e, which documents the same offsets.
//
// Higher-level Map Editor code wraps ClientSide in a dual CAOS sink:
//   injectToGame=true  -> execute over c2e shared-memory IPC
//   injectToGame=false -> write generated CAOS to a file
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

public class RefineC2EMapEditorGameIPC extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure docType;
    private Structure worldType;
    private Structure metaRoomType;
    private Structure roomType;
    private Structure vc6CString;

    private Structure transferHeaderType;
    private Structure clientSideType;
    private Structure caosOutputType;

    private Pointer docPtr;
    private Pointer worldPtr;
    private Pointer metaRoomPtr;
    private Pointer roomPtr;
    private Pointer clientSidePtr;
    private Pointer transferHeaderPtr;
    private Pointer caosOutputPtr;

    private int fieldsRefined;
    private int fieldsPreserved;
    private int functionsCreated;
    private int functionsRenamed;
    private int functionsKept;
    private int thisTypesApplied;
    private int thisTypesSkipped;
    private int returnTypesApplied;
    private int labelsCreated;

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

        // Original Creature Labs common/ClientSide implementation.
        new FuncSpec(
            0x413830L,
            "C2EClientSide_ctor",
            "client",
            "Exact match for Creature Labs ClientSide::ClientSide. Clears myMutex, myRequestEvent, myResultEvent, myMappedFile and mySharedMem.",
            "clientptr"),

        new FuncSpec(
            0x413850L,
            "C2EClientSide_Cleanup",
            "client",
            "Exact match for ClientSide::Cleanup: UnmapViewOfFile(mySharedMem), CloseHandle mapped file/events/mutex, clear all five members.",
            "void"),

        new FuncSpec(
            0x4138b0L,
            "C2EClientSide_Open",
            "client",
            "Exact match for ClientSide::Open(name). Opens name_mutex, name_request, name_result and name_mem, maps the shared transfer buffer and requires MagicCookie == \"c2e@\".",
            "bool"),

        new FuncSpec(
            0x413b50L,
            "C2EClientSide_GetBufferSize",
            "client",
            "Exact source-level ClientSide accessor: return mySharedMem ? mySharedMem->BufferSize : 0/empty fallback.",
            "uint"),

        new FuncSpec(
            0x413cf0L,
            "C2EClientSide_StartTransaction",
            "client",
            "Exact match for ClientSide::StartTransaction(data,size). Checks BufferSize and c2e@ cookie, waits up to 500ms for mutex, copies request to TransferHeader+0x18, sets DataSize, resets result event, sets request event, then waits for either result event or server-process termination. Mutex remains owned on success until EndTransaction.",
            "bool"),

        new FuncSpec(
            0x413de0L,
            "C2EClientSide_GetResultSize",
            "client",
            "Exact source-level accessor: returns mySharedMem->DataSize after transaction completion.",
            "uint"),

        new FuncSpec(
            0x413df0L,
            "C2EClientSide_GetResultBuffer",
            "client",
            "Exact source-level accessor: returns byte pointer immediately after the 0x18-byte TransferHeader.",
            "byteptr"),

        new FuncSpec(
            0x413e00L,
            "C2EClientSide_GetReturnCode",
            "client",
            "Exact source-level accessor: returns mySharedMem->ReturnCode.",
            "int"),

        new FuncSpec(
            0x413e10L,
            "C2EClientSide_EndTransaction",
            "client",
            "Exact match for ClientSide::EndTransaction: ReleaseMutex(myMutex).",
            "void"),

        // Map Editor's CAOS sink around ClientSide.
        new FuncSpec(
            0x414790L,
            "C2ECAOSOutput_ctor",
            "output",
            "Constructs the Map Editor CAOS sink around embedded ClientSide. The first argument is an output/log path; the boolean selects live-game injection versus ordinary file output. Inject handlers pass game.log + true; Export handlers pass !world.cos/!addon.cos + false.",
            "outputptr"),

        new FuncSpec(
            0x4148a0L,
            "C2ECAOSOutput_dtor",
            "output",
            "Destroys the dual CAOS sink, closing live-game ClientSide state when active and releasing the output-path CString.",
            "void"),

        new FuncSpec(
            0x414980L,
            "C2ECAOSOutput_Run",
            "output",
            "Core dual-sink operation. In live-game mode it prepares/executes CAOS through C2EClientSide_StartTransaction, captures the result/return code, and ends the transaction; in file mode it writes the CAOS text to the configured output path.",
            "unknown"),

        // High-level reverse-import path.
        new FuncSpec(
            0x40b240L,
            "CC2ERoomEditorDoc_OpenFromGame",
            "doc",
            "Document-side Open From Game implementation. Creates a live CAOS output/IPC object, asks C2EWorldModel_ReadFromGame to reconstruct map/metaroom/room/door topology, then reads all 16x20 CA RATE triples from the running engine into the document caRates matrix.",
            "void"),

        new FuncSpec(
            0x424670L,
            "C2EWorldModel_ReadFromGame",
            "world",
            "Reconstructs the compact editor WorldModel from a running C2e engine. Queries MAPW/MAPH, enumerates EMID metaroom IDs, allocates/loads each metaroom, rebuilds door geometry and queries DOOR permeability for room pairs.",
            "void"),

        new FuncSpec(
            0x417320L,
            "C2EEditorMetaRoom_ReadFromGame",
            "metaroom",
            "Loads one compact metaroom from live-game CAOS queries. Reads MLOC bounds, MMSC music, BKDS backgrounds and ERID room IDs, then delegates each room to C2EEditorRoom_ReadFromGame.",
            "void"),

        new FuncSpec(
            0x41a800L,
            "C2EEditorRoom_ReadFromGame",
            "room",
            "Loads one compact room from the running engine. Queries RTYP and RLOC for roomId, parses room type plus the six canonical geometry coordinates, writes the import argument to unresolved state40, and invalidates perimeterLength.",
            "void")
    };

    private final LabelSpec[] LABELS = new LabelSpec[] {
        new LabelSpec(0x4347d0L, "C2E_IPC_MUTEX_NAME_FORMAT",
            "%s_mutex"),
        new LabelSpec(0x4347d8L, "C2E_IPC_REQUEST_EVENT_NAME_FORMAT",
            "%s_request"),
        new LabelSpec(0x4347e4L, "C2E_IPC_RESULT_EVENT_NAME_FORMAT",
            "%s_result"),
        new LabelSpec(0x4347f0L, "C2E_IPC_SHARED_MEMORY_NAME_FORMAT",
            "%s_mem"),
        new LabelSpec(0x4347fcL, "C2E_IPC_INTERNAL_MUTEX_ERROR",
            "Internal IPC mutex error"),

        new LabelSpec(0x434684L, "C2E_GAME_QUERY_RATE_FORMAT",
            "outs rate %d %d"),
        new LabelSpec(0x434698L, "C2E_GAME_READING_CA_RATES_STATUS",
            "Reading CA Rates"),

        new LabelSpec(0x434eb0L, "C2E_GAME_QUERY_ERID_FORMAT",
            "outs erid %d"),
        new LabelSpec(0x434ec0L, "C2E_GAME_QUERY_BKDS_FORMAT",
            "outs bkds %d"),
        new LabelSpec(0x434ed0L, "C2E_GAME_QUERY_MMSC_FORMAT",
            "outs mmsc %d %d"),
        new LabelSpec(0x434ee0L, "C2E_GAME_QUERY_MLOC_FORMAT",
            "outs mloc %d"),
        new LabelSpec(0x434ef0L, "C2E_GAME_QUERY_BACKGROUND_PROP_FORMAT",
            "outv prop grap %d %d va00 ..."),

        new LabelSpec(0x434fe0L, "C2E_GAME_QUERY_ROOM_TYPE_LOCATION_FORMAT",
            "outv rtyp %d outs \" \" outs rloc %d"),

        new LabelSpec(0x4353bcL, "C2E_GAME_QUERY_DOOR_FORMAT",
            "outv door %d %d outs \" \""),
        new LabelSpec(0x4353d8L, "C2E_GAME_READING_DOORS_STATUS",
            "Reading Doors"),
        new LabelSpec(0x4353e8L, "C2E_GAME_LOADING_METAROOM_STATUS_FORMAT",
            "Loading Metaroom %d"),
        new LabelSpec(0x4353fcL, "C2E_GAME_QUERY_EMID",
            "outs emid"),
        new LabelSpec(0x435408L, "C2E_GAME_QUERY_MAP_DIMENSIONS",
            "outv mapw outs \" \" outv maph")
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

        // Exact target guards.
        // 004138C1 push 004347FC ("Internal IPC mutex error")
        // 0040B2D5 push 00434698 ("Reading CA Rates")
        // 004246B1 push 00435408 (MAPW/MAPH query)
        // 0041A844 push 00434FE0 (RTYP/RLOC query)
        if (getInt(toAddr(0x4138c2L)) != 0x004347fc ||
            getInt(toAddr(0x40b2d6L)) != 0x00434698 ||
            getInt(toAddr(0x4246b2L)) != 0x00435408 ||
            getInt(toAddr(0x41a845L)) != 0x00434fe0) {

            popup("MapEditor 1.08 live-game IPC identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        docType = requireStructure("CC2ERoomEditorDoc_refined");
        worldType = requireStructure("C2EWorldModel");
        metaRoomType = requireStructure("C2EEditorMetaRoom");
        roomType = requireStructure("C2EEditorRoom");
        vc6CString = requireStructure("VC6CString");

        docPtr = new PointerDataType(docType, dtm);
        worldPtr = new PointerDataType(worldType, dtm);
        metaRoomPtr = new PointerDataType(metaRoomType, dtm);
        roomPtr = new PointerDataType(roomType, dtm);

        println("=== Creatures Map Editor 1.08 - live-game IPC recovery ===");
        println("ClientSide addresses match original Creature Labs source directly.");
        println("");

        buildIPCtypes();

        for (LabelSpec spec : LABELS) {
            labelConstant(spec);
        }

        for (FuncSpec spec : FUNCTIONS) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) continue;

            appendRepeatableComment(
                f.getEntryPoint(),
                "[C2E live-game IPC] " + spec.comment);

            if ("client".equals(spec.owner)) {
                applyThisType(f, clientSidePtr, "C2EClientSide");
            }
            else if ("output".equals(spec.owner)) {
                applyThisType(f, caosOutputPtr, "C2ECAOSOutput");
            }
            else if ("doc".equals(spec.owner)) {
                applyThisType(f, docPtr, "CC2ERoomEditorDoc_refined");
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

        annotateProtocol();
        annotateOpenFromGame();
        annotateSourceCrossMatch();

        println("");
        println("Running analysis on live-game IPC changes...");
        analyzeChanges(currentProgram);

        println("");
        println("=== Live-game IPC summary ===");
        println("Fields refined:       " + fieldsRefined);
        println("Fields preserved:     " + fieldsPreserved);
        println("Functions created:    " + functionsCreated);
        println("Functions renamed:    " + functionsRenamed);
        println("Existing names kept:  " + functionsKept);
        println("this types applied:   " + thisTypesApplied);
        println("this types skipped:   " + thisTypesSkipped);
        println("return types applied: " + returnTypesApplied);
        println("labels created:       " + labelsCreated);
        println("");
        println("Recovered exact IPC:");
        println("  C2ETransferHeader [0x18]");
        println("  C2EClientSide      [0x14]");
        println("  request/result shared buffer starts at +0x18");
        println("");
        println("Recovered reverse-import chain:");
        println("  Doc_OpenFromGame");
        println("    -> WorldModel_ReadFromGame");
        println("      -> MetaRoom_ReadFromGame");
        println("        -> Room_ReadFromGame");
        println("    -> 16x20 RATE reads");
    }

    // ---------------------------------------------------------------------
    // IPC types
    // ---------------------------------------------------------------------

    private void buildIPCtypes() {
        transferHeaderType = getOrCreate("C2ETransferHeader", 0x18);

        field(
            transferHeaderType, 0x00,
            new ArrayDataType(CharDataType.dataType, 4, 1), 4,
            "MagicCookie",
            "ASCII protocol magic. Implementation and MapEditor binary require exactly \"c2e@\".");

        field(
            transferHeaderType, 0x04,
            UnsignedIntegerDataType.dataType, 4,
            "ServerProcessId",
            "PID of the C2e engine process. ClientSide opens this process so it can wait for engine termination alongside the result event.");

        field(
            transferHeaderType, 0x08,
            IntegerDataType.dataType, 4,
            "ReturnCode",
            "Server return/error code for the completed transaction.");

        field(
            transferHeaderType, 0x0c,
            UnsignedIntegerDataType.dataType, 4,
            "DataSize",
            "Request or result byte count, depending on transaction phase.");

        field(
            transferHeaderType, 0x10,
            UnsignedIntegerDataType.dataType, 4,
            "BufferSize",
            "Allocated request/result data capacity, excluding this 0x18-byte header.");

        field(
            transferHeaderType, 0x14,
            IntegerDataType.dataType, 4,
            "Pad",
            "Historical padding field; data starts immediately after this header.");

        setDescriptionSafe(
            transferHeaderType,
            "Creature Labs C2e ClientSide/ServerSide TransferHeader. Exact source-level layout, 0x18 bytes, followed immediately by shared request/result data.");

        transferHeaderPtr =
            new PointerDataType(transferHeaderType, dtm);

        Pointer handleType =
            new PointerDataType(VoidDataType.dataType, dtm);

        clientSideType = getOrCreate("C2EClientSide", 0x14);

        field(clientSideType, 0x00, handleType, 4,
            "myMutex",
            "Named mutex <server>_mutex.");

        field(clientSideType, 0x04, handleType, 4,
            "myRequestEvent",
            "Named request event <server>_request.");

        field(clientSideType, 0x08, handleType, 4,
            "myResultEvent",
            "Named result event <server>_result.");

        field(clientSideType, 0x0c, handleType, 4,
            "myMappedFile",
            "Handle returned by OpenFileMapping for <server>_mem.");

        field(clientSideType, 0x10, transferHeaderPtr, 4,
            "mySharedMem",
            "Mapped C2ETransferHeader and following data buffer.");

        setDescriptionSafe(
            clientSideType,
            "Original Creature Labs ClientSide class, exact 0x14-byte Windows IPC layout recovered from matching source and binary.");

        clientSidePtr =
            new PointerDataType(clientSideType, dtm);

        caosOutputType = getOrCreate("C2ECAOSOutput", 0x1c);

        field(caosOutputType, 0x00, clientSideType, 0x14,
            "client",
            "Embedded Creature Labs ClientSide IPC client.");

        field(caosOutputType, 0x14, vc6CString, 4,
            "outputPath",
            "File/log path. Inject handlers construct this as game.log; Export handlers use !world.cos / !addon.cos paths.");

        field(caosOutputType, 0x18, BooleanDataType.dataType, 1,
            "injectToGame",
            "True selects live-game IPC execution; false selects generated CAOS file output.");

        field(caosOutputType, 0x19,
            new ArrayDataType(Undefined1DataType.dataType, 3, 1), 3,
            "padding19",
            "Alignment.");

        setDescriptionSafe(
            caosOutputType,
            "Map Editor dual CAOS output sink: either execute against a running C2e engine through ClientSide or write generated CAOS to outputPath.");

        caosOutputPtr =
            new PointerDataType(caosOutputType, dtm);
    }

    // ---------------------------------------------------------------------
    // Evidence annotations
    // ---------------------------------------------------------------------

    private void annotateProtocol() {
        appendRepeatableComment(
            toAddr(0x413cf0L),
            "[C2E IPC transaction protocol]\n" +
            "  require requestSize <= TransferHeader.BufferSize\n" +
            "  require MagicCookie == 'c2e@'\n" +
            "  WaitForSingleObject(myMutex, 500ms)\n" +
            "  memcpy(shared+0x18, request, requestSize)\n" +
            "  TransferHeader.DataSize = requestSize\n" +
            "  ResetEvent(myResultEvent)\n" +
            "  SetEvent(myRequestEvent)\n" +
            "  OpenProcess(TransferHeader.ServerProcessId)\n" +
            "  WaitForMultipleObjects({myResultEvent, serverProcess}, FALSE, INFINITE)\n" +
            "On success the mutex intentionally remains held until EndTransaction.");

        setEOLComment(
            toAddr(0x413d31L),
            "FIRST_TIMEOUT = 500ms from original ClientSide.h.");

        setEOLComment(
            toAddr(0x413d4dL),
            "request/result data begins at sizeof(C2ETransferHeader) = 0x18.");

        appendRepeatableComment(
            toAddr(0x413e10L),
            "[C2E IPC lock lifetime] EndTransaction releases the mutex only after callers have copied result bytes / ReturnCode.");
    }

    private void annotateOpenFromGame() {
        appendRepeatableComment(
            toAddr(0x40b240L),
            "[C2E Open From Game flow]\n" +
            "1. Show Opening from Game progress UI.\n" +
            "2. Construct live C2ECAOSOutput using game.log.\n" +
            "3. C2EWorldModel_ReadFromGame reconstructs map/metaroom/room/door state.\n" +
            "4. For roomType 0..15 and CA index 0..19 execute 'outs rate %d %d'.\n" +
            "5. Parse three floats per response into document.caRates[roomType][caIndex].\n" +
            "6. Perform validation/reporting and refresh document/view state.");

        appendRepeatableComment(
            toAddr(0x424670L),
            "[C2E live map queries]\n" +
            "MAP dimensions: outv mapw outs \" \" outv maph\n" +
            "Metaroom IDs: outs emid\n" +
            "For each metaroom -> C2EEditorMetaRoom_ReadFromGame\n" +
            "Door permeability: outv door roomId1 roomId2 outs \" \".");

        appendRepeatableComment(
            toAddr(0x417320L),
            "[C2E live metaroom queries]\n" +
            "MLOC metaRoomId -> bounds/location\n" +
            "MMSC centreX centreY -> metaroom music\n" +
            "BKDS metaRoomId -> background list/current background data\n" +
            "ERID metaRoomId -> room IDs\n" +
            "Each room ID is loaded by C2EEditorRoom_ReadFromGame.");

        appendRepeatableComment(
            toAddr(0x41a800L),
            "[C2E live room query]\n" +
            "outv rtyp <roomId> outs \" \" outs rloc <roomId>\n" +
            "Response supplies Room Type plus six canonical room geometry coordinates. perimeterLength is invalidated after installing the new geometry.");
    }

    private void annotateSourceCrossMatch() {
        appendRepeatableComment(
            toAddr(0x413830L),
            "[C2E source provenance] Direct match to common/clientside.cpp from the released C2e/Docking Station-era source tree.");

        appendRepeatableComment(
            toAddr(0x4138b0L),
            "[C2E source provenance] Original ClientSide.cpp uses exactly the same <name>_mutex / _request / _result / _mem object names and c2e@ magic test.");

        appendRepeatableComment(
            toAddr(0x413cf0L),
            "[C2E source provenance] Direct match to original ClientSide::StartTransaction including 500ms FIRST_TIMEOUT and wait-for-result-or-server-process behavior.");
    }

    // ---------------------------------------------------------------------
    // Labels
    // ---------------------------------------------------------------------

    private void labelConstant(LabelSpec spec) {
        try {
            Address a = toAddr(spec.address);
            Symbol existing = getSymbolAt(a);

            if (existing == null ||
                existing.getSource() == SourceType.DEFAULT) {

                createLabel(a, spec.name, true);
                labelsCreated++;
                println("[label] " + a + " -> " + spec.name);
            }

            String old = getEOLComment(a);

            if (old == null || old.isBlank()) {
                setEOLComment(a, spec.comment);
            }
            else if (!old.contains(spec.comment)) {
                setEOLComment(a, old + "\n" + spec.comment);
            }
        }
        catch (Exception e) {
            println("[label-skip] " +
                Long.toHexString(spec.address) +
                " " + spec.name + ": " +
                e.getMessage());
        }
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
                    " existing=0x" +
                    Integer.toHexString(s.getLength()) +
                    " expected=0x" +
                    Integer.toHexString(size));
            }

            return s;
        }

        if (existing != null) {
            throw new IllegalStateException(
                "/C2E/Refined/" + name +
                " exists but is not a Structure.");
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
            DataTypeComponent c =
                s.getComponentContaining(offset);

            if (c != null) {
                String current =
                    c.getFieldName();

                boolean safe =
                    current == null ||
                    current.isBlank() ||
                    name.equals(current) ||
                    current.startsWith("field_") ||
                    current.startsWith("undefined") ||
                    current.startsWith("padding");

                if (!safe) {
                    fieldsPreserved++;

                    println("[field-preserve] " +
                        s.getName() + " +0x" +
                        Integer.toHexString(offset) +
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
                offset,
                type,
                length,
                name,
                comment);

            fieldsRefined++;

            println("[field] " +
                s.getName() + " +0x" +
                Integer.toHexString(offset) +
                " -> " + name);
        }
        catch (Exception e) {
            println("[field-fail] " +
                s.getName() + " +0x" +
                Integer.toHexString(offset) +
                " " + name + ": " +
                e.getMessage());
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

                println("[function-overlap-skip] " +
                    a + " lies inside " +
                    containing.getName());

                return null;
            }

            if (getInstructionAt(a) == null &&
                !disassemble(a)) {

                println("[disassemble-fail] " +
                    a + " " + desiredName);

                return null;
            }

            f = createFunction(a, desiredName);

            if (f == null) {
                println("[function-create-fail] " +
                    a + " " + desiredName);

                return null;
            }

            functionsCreated++;
            println("[create] " +
                a + " -> " + desiredName);
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

            println("[rename] " +
                a + " -> " + desiredName);
        }
        else {
            functionsKept++;

            println("[keep] " +
                a + " existing=" + current +
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
                oldParams[0].getVariableStorage()
                    .isRegisterStorage()) {

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
            else if ("int".equals(kind)) {
                type = IntegerDataType.dataType;
            }
            else if ("uint".equals(kind)) {
                type = UnsignedIntegerDataType.dataType;
            }
            else if ("byteptr".equals(kind)) {
                type = new PointerDataType(
                    UnsignedCharDataType.dataType,
                    dtm);
            }
            else if ("clientptr".equals(kind)) {
                type = clientSidePtr;
            }
            else if ("outputptr".equals(kind)) {
                type = caosOutputPtr;
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

        if (old == null || old.isBlank()) {
            setRepeatableComment(a, text);
        }
        else if (!old.contains(text)) {
            setRepeatableComment(
                a,
                old + "\n" + text);
        }
    }
}
