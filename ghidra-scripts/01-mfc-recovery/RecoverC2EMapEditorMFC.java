// Recover MFC 4.2 runtime classes and message maps from the Creatures Map Editor 1.08.
// Target: 32-bit VC6 / MFC42 shared-DLL build (MapEditor.exe, 2001-11-16).
//
// Conservative by design:
//   * does not overwrite user-defined function names;
//   * does not clear existing defined data;
//   * validates every discovered structure before applying it;
//   * scans for generic MFC message maps as well as the four CRuntimeClass objects.
//
// Revision 1.1: fixed Function -> Address rename helper mismatch.
// @category C2E Map Editor
// @author OpenAI

import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.*;

public class RecoverC2EMapEditorMFC extends GhidraScript {

    private static final long U32 = 0xffffffffL;
    private static final int RTC_SIZE = 0x18;       // VC6 MFC42 _AFXDLL CRuntimeClass
    private static final int MSGMAP_SIZE = 0x08;    // pfnGetBaseMap + lpEntries
    private static final int MSGENTRY_SIZE = 0x18;  // 6 x DWORD on x86
    private static final int MAX_MSG_ENTRIES = 256;

    private DataType rtcType;
    private DataType msgMapType;
    private DataType msgEntryType;

    private Namespace recoveredRoot;
    private final Map<Long, String> mapOwners = new HashMap<>();
    private final Map<Integer, String> commandNames = new HashMap<>();
    private final Map<Long, Map<Integer, String>> mapSpecificControlNames = new HashMap<>();
    private final Map<Integer, String> windowsMessages = new HashMap<>();

    private int runtimeClassCount = 0;
    private int messageMapCount = 0;
    private int messageEntryCount = 0;
    private int renamedFunctionCount = 0;
    private int skippedUserNames = 0;

    private static class RuntimeClassInfo {
        Address address;
        String name;
        long objectSize;
        long schema;
        Address createObject;
        Address getBaseClass;
        Address nextClass;
        Address messageMap;
    }

    private static class MsgEntry {
        Address address;
        long message;
        long code;
        long id;
        long lastId;
        long sig;
        Address handler;
    }

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open MapEditor.exe in CodeBrowser first.");
            return;
        }

        println("=== Creatures Map Editor 1.08 - MFC42 recovery ===");
        println("Program: " + currentProgram.getName());
        println("Language: " + currentProgram.getLanguageID());
        println("Pointer size: " + currentProgram.getDefaultPointerSize());

        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This script is deliberately limited to 32-bit MFC42 targets.");
            return;
        }

        initialiseKnownNames();
        createMfcDataTypes();
        recoveredRoot = getOrCreateNamespace(currentProgram.getGlobalNamespace(), "MFCRecovered");

        List<RuntimeClassInfo> classes = discoverRuntimeClasses();
        println("\nFound " + classes.size() + " plausible MFC42 CRuntimeClass objects.");

        for (RuntimeClassInfo rc : classes) {
            monitor.checkCancelled();
            recoverRuntimeClass(rc);
        }

        // Recover every MFC message map, including dialog classes that do not use
        // DECLARE_DYNAMIC / DECLARE_DYNCREATE and therefore have no CRuntimeClass.
        List<Address> maps = discoverMessageMaps();
        println("\nFound " + maps.size() + " plausible MFC42 message maps.");

        for (Address mapAddr : maps) {
            monitor.checkCancelled();
            String owner = mapOwners.get(mapAddr.getOffset() & U32);
            if (owner == null) {
                owner = "MessageMap_" + hex8(mapAddr.getOffset());
            }
            recoverMessageMap(mapAddr, owner);
        }

        println("\n=== Recovery summary ===");
        println("Runtime classes: " + runtimeClassCount);
        println("Message maps:    " + messageMapCount);
        println("Message entries: " + messageEntryCount);
        println("Functions named: " + renamedFunctionCount);
        println("User names kept: " + skippedUserNames);
        println("\nLook under Symbol Tree -> Namespaces -> MFCRecovered.");
        println("The Listing now has CRuntimeClass / AFX_MSGMAP / AFX_MSGMAP_ENTRY data where safe.");
        println("Re-run analysis afterwards if you want the decompiler to propagate the new labels/types.");
    }

    // ---------------------------------------------------------------------
    // Known information recovered from this exact Map Editor build.
    // ---------------------------------------------------------------------

    private void initialiseKnownNames() {
        // MFC/App command IDs from the editor's MENU resources.
        putCmd(106, "TipOfTheDay");

        putCmd(32771, "AddRoom");
        putCmd(32772, "Select");
        putCmd(32773, "SelectMetaroom");
        putCmd(32774, "AddMetaroom");
        putCmd(32784, "Delete");
        putCmd(32785, "Properties");
        putCmd(32786, "ZoomTool");
        putCmd(32787, "PropertyTypes");
        putCmd(32788, "WorldView");
        putCmd(32791, "ZoomBack");
        putCmd(32792, "ZoomForward");
        putCmd(32793, "ZoomOut");
        putCmd(32794, "ZoomIn");
        putCmd(32795, "Background");
        putCmd(32796, "SaveToGame");
        putCmd(32797, "InjectAsWorld");
        putCmd(32798, "WorldProperties");
        putCmd(32799, "Validate");
        putCmd(32800, "Options");
        putCmd(32801, "SetMetaroomInGame");
        putCmd(32802, "CARates");
        for (int i = 0; i < 20; i++) {
            putCmd(32803 + i, "ColourRoomsCA" + i);
        }
        putCmd(32823, "Cheese");
        putCmd(32825, "ShowBackground");
        putCmd(32829, "UpdateCAFromGame");
        putCmd(32831, "DataBars");
        putCmd(32832, "DataColours");
        putCmd(32835, "FloorCeilingValues");
        putCmd(32836, "Music");
        putCmd(32837, "OpenFromGame");
        putCmd(32840, "ExportAsWorld");
        putCmd(32847, "CheckHeights");
        putCmd(32848, "ExportAsAddon");
        putCmd(32849, "InjectAsAddon");
        putCmd(32851, "HelpContents");
        putCmd(32852, "CDNWebsite");

        // Standard MFC command IDs used by this program.
        putCmd(57600, "FileNew");
        putCmd(57601, "FileOpen");
        putCmd(57602, "FileClose");
        putCmd(57603, "FileSave");
        putCmd(57604, "FileSaveAs");
        putCmd(57606, "PrintSetup");
        putCmd(57607, "Print");
        putCmd(57608, "PrintDirect");
        putCmd(57609, "PrintPreview");
        putCmd(57616, "RecentFile");
        putCmd(57634, "Copy");
        putCmd(57635, "Cut");
        putCmd(57637, "Paste");
        putCmd(57643, "Undo");
        putCmd(57644, "Redo");
        putCmd(57648, "NewWindow");
        putCmd(57649, "ArrangeIcons");
        putCmd(57650, "Cascade");
        putCmd(57651, "Tile");
        putCmd(57664, "About");
        putCmd(57665, "Exit");
        putCmd(59392, "Toolbar");
        putCmd(59393, "StatusBar");

        // Win32 messages actually seen in this target's message maps.
        windowsMessages.put(0x0001, "Create");
        windowsMessages.put(0x0002, "Destroy");
        windowsMessages.put(0x0005, "Size");
        windowsMessages.put(0x0008, "KillFocus");
        windowsMessages.put(0x000f, "Paint");
        windowsMessages.put(0x0019, "CtlColor");
        windowsMessages.put(0x002b, "DrawItem");
        windowsMessages.put(0x002c, "MeasureItem");
        windowsMessages.put(0x007b, "ContextMenu");
        windowsMessages.put(0x0100, "KeyDown");
        windowsMessages.put(0x0113, "Timer");
        windowsMessages.put(0x0114, "HScroll");
        windowsMessages.put(0x0115, "VScroll");
        windowsMessages.put(0x0116, "InitMenuPopup");
        windowsMessages.put(0x0200, "MouseMove");
        windowsMessages.put(0x0201, "LButtonDown");
        windowsMessages.put(0x0202, "LButtonUp");

        // Exact map ownership proven from CRuntimeClass adjacency / resource contents.
        mapOwners.put(0x0042b0c0L, "CC2ERoomEditorDoc");
        mapOwners.put(0x0042b2e8L, "CC2ERoomEditorView");
        mapOwners.put(0x0042bb88L, "CChildFrame");
        mapOwners.put(0x0042c050L, "CMainFrame");

        // This class has no CRuntimeClass in the EXE, but its map is unmistakable:
        // FileNew/FileOpen/About/Options/OpenFromGame/help handlers.
        mapOwners.put(0x0042ae10L, "CC2ERoomEditorApp");

        // Strong dialog-map matches from the dialog control IDs in .rsrc.
        mapOwners.put(0x0042a9f8L, "CMetaroomBackgroundDlg");
        mapOwners.put(0x0042ac40L, "CBackgroundFileDlg");
        mapOwners.put(0x0042ba40L, "CCAPropertiesDlg");
        mapOwners.put(0x0042be30L, "CFloorCeilingDlg");
        mapOwners.put(0x0042c7b8L, "CPropertyTypeDlg");
        mapOwners.put(0x0042c8c8L, "CPropertyTypesDlg");
        mapOwners.put(0x0042ca00L, "CPropertiesDlg");
        mapOwners.put(0x0042cc78L, "CSwitchMetaroomDlg");
        mapOwners.put(0x0042cdc0L, "CTipDlg");

        addControlNames(0x0042a9f8L,
            1021, "BackgroundList",
            1022, "Properties",
            1023, "Remove",
            1024, "Add",
            1028, "SetAsCurrentBackground");

        addControlNames(0x0042ac40L,
            1032, "GeneratePreview");

        addControlNames(0x0042ba40L,
            1018, "RoomTypeList",
            1019, "CAPropertyList");

        addControlNames(0x0042be30L,
            1040, "Calculate");

        addControlNames(0x0042c7b8L,
            1007, "Enumerated");

        addControlNames(0x0042c8c8L,
            1001, "PropertyTypeList",
            1002, "Edit");

        addControlNames(0x0042ca00L,
            1002, "PropertyEdit",
            1015, "PropertyAction");

        addControlNames(0x0042cc78L,
            3, "Apply",
            1037, "MetaroomCombo");

        addControlNames(0x0042cdc0L,
            1002, "NextTip");
    }

    private void putCmd(int id, String name) {
        commandNames.put(id, name);
    }

    private void addControlNames(long mapAddr, Object... pairs) {
        Map<Integer, String> m = mapSpecificControlNames.computeIfAbsent(mapAddr, k -> new HashMap<>());
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((Integer)pairs[i], (String)pairs[i + 1]);
        }
    }

    // ---------------------------------------------------------------------
    // Data types
    // ---------------------------------------------------------------------

    private void createMfcDataTypes() {
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        BuiltInDataTypeManager built = BuiltInDataTypeManager.getDataTypeManager();

        DataType charType = built.getDataType("/char");
        DataType intType = built.getDataType("/int");
        DataType uintType = built.getDataType("/uint");
        DataType voidType = built.getDataType("/void");
        DataType charPtr = PointerDataType.getPointer(charType, 4);
        DataType voidPtr = PointerDataType.getPointer(voidType, 4);

        StructureDataType rtc = new StructureDataType("MFC42_CRuntimeClass_x86_AFXDLL", RTC_SIZE);
        rtc.replaceAtOffset(0x00, charPtr, 4, "m_lpszClassName", "ANSI class name");
        rtc.replaceAtOffset(0x04, intType, 4, "m_nObjectSize", null);
        rtc.replaceAtOffset(0x08, uintType, 4, "m_wSchema", "0xffff for non-serializable dynamic classes");
        rtc.replaceAtOffset(0x0c, voidPtr, 4, "m_pfnCreateObject", "CObject* (__stdcall*)(); NULL if not dynamically creatable");
        rtc.replaceAtOffset(0x10, voidPtr, 4, "m_pfnGetBaseClass", "_AFXDLL form: CRuntimeClass* (__stdcall*)()");
        rtc.replaceAtOffset(0x14, voidPtr, 4, "m_pNextClass", "linked at runtime");
        rtcType = dtm.addDataType(rtc, DataTypeConflictHandler.REPLACE_HANDLER);

        StructureDataType mm = new StructureDataType("MFC42_AFX_MSGMAP_x86_AFXDLL", MSGMAP_SIZE);
        mm.replaceAtOffset(0x00, voidPtr, 4, "pfnGetBaseMap", "_AFXDLL form: AFX_MSGMAP* (__stdcall*)()");
        mm.replaceAtOffset(0x04, voidPtr, 4, "lpEntries", "AFX_MSGMAP_ENTRY*");
        msgMapType = dtm.addDataType(mm, DataTypeConflictHandler.REPLACE_HANDLER);

        StructureDataType me = new StructureDataType("MFC42_AFX_MSGMAP_ENTRY_x86", MSGENTRY_SIZE);
        me.replaceAtOffset(0x00, uintType, 4, "nMessage", null);
        me.replaceAtOffset(0x04, uintType, 4, "nCode", "notification code; 0xffffffff is CN_UPDATE_COMMAND_UI");
        me.replaceAtOffset(0x08, uintType, 4, "nID", null);
        me.replaceAtOffset(0x0c, uintType, 4, "nLastID", "range end");
        me.replaceAtOffset(0x10, uintType, 4, "nSig", "AfxSig_*; zero terminates the table");
        me.replaceAtOffset(0x14, voidPtr, 4, "pfn", "AFX_PMSG handler");
        msgEntryType = dtm.addDataType(me, DataTypeConflictHandler.REPLACE_HANDLER);
    }

    // ---------------------------------------------------------------------
    // CRuntimeClass discovery/recovery
    // ---------------------------------------------------------------------

    private List<RuntimeClassInfo> discoverRuntimeClasses() throws Exception {
        List<RuntimeClassInfo> result = new ArrayList<>();
        Memory mem = currentProgram.getMemory();

        for (MemoryBlock block : mem.getBlocks()) {
            monitor.checkCancelled();
            if (!block.isInitialized() || block.isExecute() || block.getSize() < RTC_SIZE) {
                continue;
            }

            Address a = align4(block.getStart());
            Address last = block.getEnd().subtract(RTC_SIZE - 1);
            while (a.compareTo(last) <= 0) {
                monitor.checkCancelled();
                RuntimeClassInfo info = parseRuntimeClassCandidate(a);
                if (info != null) {
                    result.add(info);
                    // No need to test the interior dwords of the same structure.
                    a = a.add(RTC_SIZE);
                }
                else {
                    a = a.add(4);
                }
            }
        }

        return result;
    }

    private RuntimeClassInfo parseRuntimeClassCandidate(Address a) {
        try {
            Address namePtr = ptrAt(a);
            if (namePtr == null || !currentProgram.getMemory().contains(namePtr)) {
                return null;
            }

            String name = readAsciiZ(namePtr, 96);
            if (!looksLikeCppClassName(name)) {
                return null;
            }

            long objectSize = u32At(a.add(4));
            long schema = u32At(a.add(8));
            Address createObject = nullablePtrAt(a.add(0x0c));
            Address getBaseClass = nullablePtrAt(a.add(0x10));
            Address nextClass = nullablePtrAt(a.add(0x14));

            if (objectSize == 0 || objectSize > 0x10000) {
                return null;
            }
            if (!(schema == 0xffffL || schema <= 0x1000L)) {
                return null;
            }
            if (createObject != null && !isExecutable(createObject)) {
                return null;
            }
            if (getBaseClass == null || !isExecutable(getBaseClass)) {
                return null;
            }
            if (nextClass != null && !currentProgram.getMemory().contains(nextClass)) {
                return null;
            }

            RuntimeClassInfo r = new RuntimeClassInfo();
            r.address = a;
            r.name = name;
            r.objectSize = objectSize;
            r.schema = schema;
            r.createObject = createObject;
            r.getBaseClass = getBaseClass;
            r.nextClass = nextClass;

            Address possibleMap = a.add(RTC_SIZE);
            if (isMessageMapHeader(possibleMap)) {
                r.messageMap = possibleMap;
                mapOwners.put(possibleMap.getOffset() & U32, name);
            }
            return r;
        }
        catch (Exception e) {
            return null;
        }
    }

    private void recoverRuntimeClass(RuntimeClassInfo rc) throws Exception {
        runtimeClassCount++;
        Namespace classNs = getOrCreateNamespace(recoveredRoot, sanitize(rc.name));

        safeCreateData(rc.address, rtcType);
        safeLabel(rc.address, "runtimeClass", classNs);
        safeLabel(ptrAt(rc.address), "className", classNs);
        setPlateComment(rc.address,
            "MFC42 CRuntimeClass for " + rc.name +
            "\nobject size: 0x" + Long.toHexString(rc.objectSize) +
            "\nschema: 0x" + Long.toHexString(rc.schema) +
            "\n_AF XDLL layout: m_pfnGetBaseClass is a function pointer");

        println("[RTC] " + rc.name + " @ " + rc.address +
            " size=0x" + Long.toHexString(rc.objectSize) +
            (rc.messageMap != null ? " map=" + rc.messageMap : ""));

        if (rc.createObject != null) {
            safeRenameFunction(rc.createObject, rc.name + "_CreateObject");
            setRepeatableComment(rc.createObject, "MFC dynamic creation function for " + rc.name);
        }
        if (rc.getBaseClass != null) {
            safeRenameFunction(rc.getBaseClass, rc.name + "_GetBaseClass");
            setRepeatableComment(rc.getBaseClass, "MFC _AFXDLL base-class getter for " + rc.name);
        }

        Function getRuntimeClass = findTinyFunctionReferencing(rc.address, 5);
        if (getRuntimeClass != null) {
            safeRenameFunction(getRuntimeClass, rc.name + "_GetRuntimeClass");
        }

        if (rc.messageMap != null) {
            Function getMessageMap = findTinyFunctionReferencing(rc.messageMap, 5);
            if (getMessageMap != null) {
                safeRenameFunction(getMessageMap, rc.name + "_GetMessageMap");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Message-map discovery/recovery
    // ---------------------------------------------------------------------

    private List<Address> discoverMessageMaps() throws Exception {
        List<Address> result = new ArrayList<>();
        Memory mem = currentProgram.getMemory();

        for (MemoryBlock block : mem.getBlocks()) {
            monitor.checkCancelled();
            if (!block.isInitialized() || block.isExecute() || block.getSize() < MSGMAP_SIZE + MSGENTRY_SIZE) {
                continue;
            }

            Address a = align4(block.getStart());
            Address last = block.getEnd().subtract(MSGMAP_SIZE + MSGENTRY_SIZE - 1);
            while (a.compareTo(last) <= 0) {
                monitor.checkCancelled();
                if (isMessageMapHeader(a)) {
                    result.add(a);
                    int entries = countMessageEntries(a.add(MSGMAP_SIZE));
                    // Skip over the header, entries, and terminating entry.
                    a = a.add(MSGMAP_SIZE + (long)(entries + 1) * MSGENTRY_SIZE);
                }
                else {
                    a = a.add(4);
                }
            }
        }
        return result;
    }

    private boolean isMessageMapHeader(Address a) {
        try {
            if (!currentProgram.getMemory().contains(a) ||
                !currentProgram.getMemory().contains(a.add(MSGMAP_SIZE + MSGENTRY_SIZE - 1))) {
                return false;
            }

            Address baseGetter = ptrAt(a);
            Address entries = ptrAt(a.add(4));

            // In this VC6 build the compiler emits _messageEntries directly after
            // the AFX_MSGMAP object. This turns out to be a very strong signature.
            if (baseGetter == null || !isExecutable(baseGetter)) {
                return false;
            }
            if (entries == null || !entries.equals(a.add(MSGMAP_SIZE))) {
                return false;
            }

            return validateMessageTable(entries);
        }
        catch (Exception e) {
            return false;
        }
    }

    private boolean validateMessageTable(Address entries) {
        try {
            for (int i = 0; i < MAX_MSG_ENTRIES; i++) {
                Address e = entries.add((long)i * MSGENTRY_SIZE);
                if (!rangeInMemory(e, MSGENTRY_SIZE)) {
                    return false;
                }

                long msg = u32At(e);
                long code = u32At(e.add(4));
                long id = u32At(e.add(8));
                long lastId = u32At(e.add(12));
                long sig = u32At(e.add(16));
                Address handler = nullablePtrAt(e.add(20));

                if (sig == 0) {
                    // Canonical zero terminator should be entirely zero.
                    return msg == 0 && code == 0 && id == 0 && lastId == 0 && handler == null;
                }

                // Real MFC signatures in this target are small values.
                if (sig > 0x100) {
                    return false;
                }
                if (handler == null || !isExecutable(handler)) {
                    return false;
                }
                if (id > lastId && lastId != 0) {
                    return false;
                }
                if (msg > 0xffff) {
                    return false;
                }
            }
        }
        catch (Exception e) {
            return false;
        }
        return false;
    }

    private int countMessageEntries(Address entries) {
        try {
            for (int i = 0; i < MAX_MSG_ENTRIES; i++) {
                if (u32At(entries.add((long)i * MSGENTRY_SIZE + 16)) == 0) {
                    return i;
                }
            }
        }
        catch (Exception e) {
            // fall through
        }
        return 0;
    }

    private void recoverMessageMap(Address mapAddr, String owner) throws Exception {
        Address entriesAddr = ptrAt(mapAddr.add(4));
        int count = countMessageEntries(entriesAddr);
        Namespace ownerNs = getOrCreateNamespace(recoveredRoot, sanitize(owner));

        safeCreateData(mapAddr, msgMapType);
        safeLabel(mapAddr, "messageMap", ownerNs);
        setPlateComment(mapAddr,
            "MFC42 AFX_MSGMAP for " + owner +
            "\nentries: " + count +
            "\n_AF XDLL layout: first field is pfnGetBaseMap");

        Function getMessageMap = findTinyFunctionReferencing(mapAddr, 5);
        if (getMessageMap != null && !owner.startsWith("MessageMap_")) {
            safeRenameFunction(getMessageMap, owner + "_GetMessageMap");
        }

        messageMapCount++;
        println("[MAP] " + owner + " @ " + mapAddr + " entries=" + count);

        for (int i = 0; i <= count; i++) {
            monitor.checkCancelled();
            Address entryAddr = entriesAddr.add((long)i * MSGENTRY_SIZE);
            safeCreateData(entryAddr, msgEntryType);

            if (i == count) {
                safeLabel(entryAddr, "messageMapEnd", ownerNs);
                continue;
            }

            MsgEntry e = readMsgEntry(entryAddr);
            messageEntryCount++;

            String handlerName = makeHandlerName(owner, mapAddr, e);
            if (handlerName != null) {
                safeRenameFunction(e.handler, handlerName);
            }

            String entryName = "msg_" + String.format("%02d", i) + "_" + entryDescriptionToken(mapAddr, e);
            safeLabel(entryAddr, sanitize(entryName), ownerNs);

            String comment = describeEntry(owner, mapAddr, e);
            setEOLComment(entryAddr, comment);
            if (e.handler != null) {
                appendRepeatableComment(e.handler, comment);
            }
        }
    }

    private MsgEntry readMsgEntry(Address a) throws Exception {
        MsgEntry e = new MsgEntry();
        e.address = a;
        e.message = u32At(a);
        e.code = u32At(a.add(4));
        e.id = u32At(a.add(8));
        e.lastId = u32At(a.add(12));
        e.sig = u32At(a.add(16));
        e.handler = nullablePtrAt(a.add(20));
        return e;
    }

    private String makeHandlerName(String owner, Address mapAddr, MsgEntry e) {
        String suffix = null;

        if (e.message == 0x111) { // WM_COMMAND
            String idName = lookupIdName(mapAddr, (int)e.id);
            if (e.code == U32) {
                suffix = "OnUpdate" + idName;
            }
            else if (e.code == 0) {
                suffix = "On" + idName;
            }
            else {
                suffix = "On" + notificationPrefix(e.code) + idName;
            }
        }
        else {
            String msgName = windowsMessages.get((int)e.message);
            if (msgName != null) {
                suffix = "On" + msgName;
            }
            else if (e.message >= 0xc000) {
                suffix = "OnRegisteredMessage_" + hex4(e.message);
            }
            else {
                suffix = "OnMessage_" + hex4(e.message);
            }
        }

        return sanitize(owner + "_" + suffix);
    }

    private String lookupIdName(Address mapAddr, int id) {
        Map<Integer, String> specific = mapSpecificControlNames.get(mapAddr.getOffset() & U32);
        if (specific != null && specific.containsKey(id)) {
            return specific.get(id);
        }
        String known = commandNames.get(id);
        if (known != null) {
            return known;
        }
        if (id == 0) {
            return "NoID";
        }
        return "ID_" + Integer.toUnsignedString(id);
    }

    private String notificationPrefix(long code) {
        // Common control notifications encountered in this target.
        if (code == 1) return "SelChange_";        // e.g. LBN_SELCHANGE / CBN_SELCHANGE
        if (code == 2) return "DblClk_";           // e.g. LBN_DBLCLK
        if (code == 0x100) return "SetFocus_";     // EN_SETFOCUS
        if (code == 0x200) return "KillFocus_";    // EN_KILLFOCUS
        if (code == 0x300) return "Change_";       // EN_CHANGE
        if (code == 0x400) return "Update_";       // EN_UPDATE
        return "Notify_" + hex4(code) + "_";
    }

    private String entryDescriptionToken(Address mapAddr, MsgEntry e) {
        if (e.message == 0x111) {
            String idName = lookupIdName(mapAddr, (int)e.id);
            if (e.code == U32) {
                return "UPDATE_" + idName;
            }
            if (e.code == 0) {
                return "COMMAND_" + idName;
            }
            return "NOTIFY_" + notificationPrefix(e.code) + idName;
        }
        String m = windowsMessages.get((int)e.message);
        return m != null ? "WM_" + m : "MSG_" + hex4(e.message);
    }

    private String describeEntry(String owner, Address mapAddr, MsgEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("MFC ").append(owner).append(": ");
        if (e.message == 0x111) {
            String n = lookupIdName(mapAddr, (int)e.id);
            if (e.code == U32) {
                sb.append("UPDATE_COMMAND_UI ").append(n);
            }
            else {
                sb.append("WM_COMMAND ").append(n);
                if (e.code != 0) {
                    sb.append(" notify=0x").append(Long.toHexString(e.code));
                }
            }
            if (e.id != e.lastId) {
                sb.append(" range=").append(e.id).append("..").append(e.lastId);
            }
        }
        else {
            String m = windowsMessages.get((int)e.message);
            sb.append(m != null ? "WM_" + m.toUpperCase() : "message 0x" + Long.toHexString(e.message));
        }
        sb.append("; AfxSig=0x").append(Long.toHexString(e.sig));
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Safe Ghidra mutations
    // ---------------------------------------------------------------------

    private void safeCreateData(Address address, DataType dt) {
        try {
            Listing listing = currentProgram.getListing();
            CodeUnit existing = listing.getCodeUnitContaining(address);
            Address end = address.add(dt.getLength() - 1);

            if (existing != null) {
                // Preserve anything already defined. This script should improve a project,
                // not bulldoze previous analysis.
                return;
            }

            CodeUnitIterator it = listing.getCodeUnits(new AddressSet(address, end), true);
            if (it.hasNext()) {
                return;
            }

            createData(address, dt);
        }
        catch (CodeUnitInsertionException e) {
            println("  [data-skip] " + address + " " + dt.getName() + ": " + e.getMessage());
        }
        catch (Exception e) {
            println("  [data-skip] " + address + " " + dt.getName() + ": " + e.getMessage());
        }
    }

    private void safeLabel(Address address, String name, Namespace namespace) {
        if (address == null || !currentProgram.getMemory().contains(address)) {
            return;
        }
        try {
            SymbolTable st = currentProgram.getSymbolTable();
            Symbol existing = st.getPrimarySymbol(address);
            if (existing != null && existing.getSource() == SourceType.USER_DEFINED) {
                return;
            }
            createLabel(address, sanitize(name), namespace, true, SourceType.ANALYSIS);
        }
        catch (Exception e) {
            // Duplicate labels are harmless; keep going.
        }
    }

    private void safeRenameFunction(Function function, String desired) {
        if (function == null) {
            return;
        }
        safeRenameFunction(function.getEntryPoint(), desired);
    }

    private void safeRenameFunction(Address address, String desired) {
        if (address == null || !isExecutable(address)) {
            return;
        }
        Function f = getFunctionAt(address);
        if (f == null) {
            f = getFunctionContaining(address);
        }
        if (f == null || !f.getEntryPoint().equals(address)) {
            return;
        }

        Symbol sym = f.getSymbol();
        if (sym != null && sym.getSource() == SourceType.USER_DEFINED) {
            skippedUserNames++;
            return;
        }

        String old = f.getName();
        if (!(old.startsWith("FUN_") || old.startsWith("LAB_") || old.startsWith("thunk_FUN_") ||
              (sym != null && sym.getSource() == SourceType.DEFAULT))) {
            return;
        }

        String clean = sanitize(desired);
        try {
            f.setName(clean, SourceType.ANALYSIS);
            renamedFunctionCount++;
        }
        catch (DuplicateNameException e) {
            try {
                f.setName(clean + "_at_" + hex8(address.getOffset()), SourceType.ANALYSIS);
                renamedFunctionCount++;
            }
            catch (Exception ignored) {
                // Keep the existing name.
            }
        }
        catch (InvalidInputException e) {
            println("  [name-skip] " + address + " -> " + clean + ": " + e.getMessage());
        }
    }

    private Function findTinyFunctionReferencing(Address target, int maxInstructions) {
        try {
            ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target);
            Function best = null;
            int bestCount = Integer.MAX_VALUE;
            while (refs.hasNext()) {
                Reference r = refs.next();
                Address from = r.getFromAddress();
                if (!isExecutable(from)) {
                    continue;
                }
                Function f = getFunctionContaining(from);
                if (f == null) {
                    continue;
                }
                int count = instructionCount(f, maxInstructions + 1);
                if (count <= maxInstructions && count < bestCount) {
                    best = f;
                    bestCount = count;
                }
            }
            return best;
        }
        catch (Exception e) {
            return null;
        }
    }

    private int instructionCount(Function f, int stopAfter) {
        int n = 0;
        InstructionIterator it = currentProgram.getListing().getInstructions(f.getBody(), true);
        while (it.hasNext()) {
            it.next();
            n++;
            if (n >= stopAfter) {
                break;
            }
        }
        return n;
    }

    private Namespace getOrCreateNamespace(Namespace parent, String name) throws Exception {
        SymbolTable st = currentProgram.getSymbolTable();
        Namespace ns = st.getNamespace(name, parent);
        if (ns != null) {
            return ns;
        }
        return createNamespace(parent, name);
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
    // Memory helpers
    // ---------------------------------------------------------------------

    private long u32At(Address a) throws MemoryAccessException {
        return Integer.toUnsignedLong(getInt(a));
    }

    private Address ptrAt(Address a) throws MemoryAccessException {
        long v = u32At(a);
        if (v == 0) {
            return null;
        }
        return toAddr(v);
    }

    private Address nullablePtrAt(Address a) throws MemoryAccessException {
        return ptrAt(a);
    }

    private boolean isExecutable(Address a) {
        if (a == null) return false;
        MemoryBlock b = currentProgram.getMemory().getBlock(a);
        return b != null && b.isInitialized() && b.isExecute();
    }

    private boolean rangeInMemory(Address start, int length) {
        try {
            MemoryBlock b = currentProgram.getMemory().getBlock(start);
            if (b == null || !b.isInitialized()) return false;
            return b.contains(start.add(length - 1));
        }
        catch (Exception e) {
            return false;
        }
    }

    private Address align4(Address a) {
        long x = a.getOffset();
        long aligned = (x + 3) & ~3L;
        return a.getNewAddress(aligned);
    }

    private String readAsciiZ(Address a, int maxLen) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLen; i++) {
                int c = getByte(a.add(i)) & 0xff;
                if (c == 0) {
                    return sb.toString();
                }
                if (c < 0x20 || c > 0x7e) {
                    return null;
                }
                sb.append((char)c);
            }
        }
        catch (Exception e) {
            return null;
        }
        return null;
    }

    private boolean looksLikeCppClassName(String s) {
        if (s == null || s.length() < 2 || s.length() > 80) return false;
        if (!(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '$' ||
                  c == '@' || c == '?' || c == '<' || c == '>' || c == '~')) {
                return false;
            }
        }
        return true;
    }

    private String sanitize(String s) {
        if (s == null || s.isEmpty()) return "unnamed";
        String out = s.replaceAll("[^A-Za-z0-9_$]", "_");
        out = out.replaceAll("_+", "_");
        if (Character.isDigit(out.charAt(0))) {
            out = "_" + out;
        }
        return out;
    }

    private String hex4(long v) {
        return String.format("%04X", v & 0xffffL);
    }

    private String hex8(long v) {
        return String.format("%08X", v & U32);
    }
}
