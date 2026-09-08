// Refine proven class/subobject layouts for Creatures Map Editor 1.08.
//
// Prerequisites:
//   1) RecoverC2EMapEditorMFC.java
//   2) RecoverC2EMapEditorClassLayout.java
//
// This target-specific pass uses constructor/message-dispatch evidence from the
// exact uploaded MapEditor.exe.  It creates semantic structures and labels the
// embedded editor-tool vtables without forcing speculative function signatures.
//
// @category C2E Map Editor
// @author OpenAI

import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;

public class RefineC2EMapEditorStructures extends GhidraScript {

    private static final CategoryPath CAT =
        new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private static class ToolDef {
        final String name;
        final int offset;
        final int size;
        final long vtable;

        ToolDef(String name, int offset, int size, long vtable) {
            this.name = name;
            this.offset = offset;
            this.size = size;
            this.vtable = vtable;
        }
    }

    private final ToolDef[] tools = new ToolDef[] {
        new ToolDef("C2EAddRoomTool",       0x048, 0x40, 0x42b964L),
        new ToolDef("C2EAddMetaroomTool",   0x088, 0x1c, 0x42b940L),
        new ToolDef("C2ESelectTool",        0x0a4, 0x30, 0x42b91cL),
        new ToolDef("C2ESelectMetaroomTool",0x0d4, 0x30, 0x42b8f8L),
        new ToolDef("C2EZoomTool",          0x104, 0x1c, 0x42b8d4L),
        new ToolDef("C2ECheeseTool",        0x120, 0x2c, 0x42b8b0L),
    };

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open MapEditor.exe first.");
            return;
        }

        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This script is for the 32-bit Creatures Map Editor target.");
            return;
        }

        // A tiny binary identity guard: the CC2ERoomEditorView CreateObject helper
        // allocates exactly 0x1e8 bytes at this address in the supplied executable.
        Address guard = toAddr(0x40e1b6L);
        if (getByte(guard) != (byte)0x68) {
            popup("Binary guard failed at 0040E1B6. Refusing target-specific refinement.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        println("=== Creatures Map Editor 1.08 - semantic structure refinement ===");

        Structure toolBase = buildToolBase();

        LinkedHashMap<String, Structure> toolTypes = new LinkedHashMap<>();
        for (ToolDef t : tools) {
            toolTypes.put(t.name, buildDerivedTool(t, toolBase));
        }

        Structure doc = buildDocumentStructure();
        Structure view = buildViewStructure(toolBase, toolTypes);

        labelToolVtables();
        labelToolCoreFunctions();
        annotateConstructors();

        println("");
        println("Created/refined:");
        println("  " + doc.getPathName());
        println("  " + view.getPathName());
        for (Structure s : toolTypes.values()) {
            println("  " + s.getPathName());
        }

        println("");
        println("Tool vtables labelled at 0042B8B0..0042B988.");
        println("No this-pointer/function signatures were forced.");
        println("Next useful step: apply the refined Doc/View types to recovered thiscall methods.");
    }

    // ---------------------------------------------------------------------
    // Structure helpers
    // ---------------------------------------------------------------------

    private Structure getOrCreate(String name, int size) {
        DataType existing = dtm.getDataType(CAT, name);

        if (existing instanceof Structure) {
            Structure s = (Structure)existing;
            if (s.getLength() < size) {
                s.growStructure(size - s.getLength());
            }
            return s;
        }

        StructureDataType fresh =
            new StructureDataType(CAT, name, size, dtm);

        DataType resolved =
            dtm.addDataType(fresh, DataTypeConflictHandler.REPLACE_HANDLER);

        return (Structure)resolved;
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

        try {
            s.replaceAtOffset(offset, type, length, name, comment);
        }
        catch (Exception e) {
            println("[field-skip] " + s.getName() + " +0x" +
                Integer.toHexString(offset) + " " + name + ": " + e.getMessage());
        }
    }

    private void u8(Structure s, int off, String name, String comment) {
        field(s, off, Undefined1DataType.dataType, 1, name, comment);
    }

    private void u32(Structure s, int off, String name, String comment) {
        field(s, off, Undefined4DataType.dataType, 4, name, comment);
    }

    private void blob(Structure s, int off, int len, String name, String comment) {
        field(s, off, bytes(len), len, name, comment);
    }

    // ---------------------------------------------------------------------
    // Tool model
    // ---------------------------------------------------------------------

    private Structure buildToolBase() {
        Structure s = getOrCreate("C2EEditorToolBase", 0x1c);

        u32(s, 0x00, "vftable",
            "Pointer to one of the 9-slot editor-tool vtables.");
        u32(s, 0x04, "ownerView",
            "Back-pointer to CC2ERoomEditorView; constructor writes the containing view here.");
        u32(s, 0x08, "state08", "Common tool state.");
        u32(s, 0x0c, "state0C", "Common tool state.");
        u32(s, 0x10, "state10", "Common tool state.");
        u32(s, 0x14, "state14", "Common tool state.");
        u32(s, 0x18, "state18", "Common tool state.");

        return s;
    }

    private Structure buildDerivedTool(ToolDef def, Structure base) {
        Structure s = getOrCreate(def.name, def.size);

        field(s, 0, base, base.getLength(), "base",
            "Common C2E editor-tool base.");

        if (def.size > base.getLength()) {
            blob(s, base.getLength(), def.size - base.getLength(),
                "toolSpecificState",
                "Derived tool state; semantic fields not yet split.");
        }

        return s;
    }

    // ---------------------------------------------------------------------
    // View layout
    // ---------------------------------------------------------------------

    private Structure buildViewStructure(
            Structure toolBase,
            Map<String, Structure> toolTypes) {

        Structure s = getOrCreate("CC2ERoomEditorView_refined", 0x1e8);

        blob(s, 0x000, 0x44, "mfc_CView_base",
            "Proven derived-class boundary: CC2ERoomEditorView constructor calls CView then first writes its own state at +0x44.");

        u32(s, 0x044, "activeTool",
            "Pointer to one of the six embedded editor tools. Command handlers switch this pointer then call Deactivate/Activate virtuals.");

        for (ToolDef t : tools) {
            Structure tool = toolTypes.get(t.name);
            String memberName;

            if (t.name.equals("C2EAddRoomTool")) memberName = "addRoomTool";
            else if (t.name.equals("C2EAddMetaroomTool")) memberName = "addMetaroomTool";
            else if (t.name.equals("C2ESelectTool")) memberName = "selectTool";
            else if (t.name.equals("C2ESelectMetaroomTool")) memberName = "selectMetaroomTool";
            else if (t.name.equals("C2EZoomTool")) memberName = "zoomTool";
            else memberName = "cheeseTool";

            field(s, t.offset, tool, tool.getLength(), memberName,
                "Embedded polymorphic editor tool; exact boundary proven by CC2ERoomEditorView constructor.");
        }

        u32(s, 0x14c, "state14C",
            "Initialized to -1 in CC2ERoomEditorView constructor.");
        blob(s, 0x150, 0x14, "stateObject150",
            "Constructed by FUN_004121D0; exact 0x14-byte boundary.");
        blob(s, 0x164, 0x14, "stateObject164",
            "Constructed by FUN_0040BDA0; exact 0x14-byte boundary.");
        blob(s, 0x178, 0x30, "stateObject178",
            "Constructed by FUN_004124A0; exact 0x30-byte boundary.");
        blob(s, 0x1a8, 0x30, "stateObject1A8",
            "Constructed by FUN_004124A0; exact 0x30-byte boundary.");

        u32(s, 0x1d8, "colourRoomsCAIndex",
            "Proven by command range 0x8023..0x8036: stores commandID-0x8023, or -1 when disabled.");
        u32(s, 0x1dc, "state1DC",
            "Initialized to 10 in the view constructor.");
        u32(s, 0x1e0, "state1E0",
            "Initialized to 0; used by timer/destroy paths.");
        u8(s, 0x1e4, "showBackground",
            "Boolean toggled/read by OnShowBackground / OnUpdateShowBackground.");
        u8(s, 0x1e5, "caStateFlag1",
            "Boolean used by timer / CA update commands.");
        u8(s, 0x1e6, "caStateFlag2",
            "Boolean used by timer / CA update commands.");
        u8(s, 0x1e7, "padding1E7",
            "Tail byte/padding; object size is 0x1e8.");

        return s;
    }

    // ---------------------------------------------------------------------
    // Document layout
    // ---------------------------------------------------------------------

    private Structure buildArrayHeader(
            String name,
            String semantic,
            int elementSize,
            int knownInitialCount) {

        Structure s = getOrCreate(name, 0x10);

        u8(s, 0x00, "flag",
            semantic + " container flag/state byte.");
        blob(s, 0x01, 3, "padding01", "Alignment.");
        u32(s, 0x04, "begin",
            "Pointer to first element.");
        u32(s, 0x08, "end",
            "Pointer one past constructed elements.");
        u32(s, 0x0c, "capacityEnd",
            "Pointer one past allocated storage.");

        setDescriptionSafe(s,
            semantic + "; element size 0x" + Integer.toHexString(elementSize) +
            (knownInitialCount >= 0
                ? "; constructor initially allocates " + knownInitialCount + " elements."
                : "."));

        return s;
    }

    private Structure buildDocumentStructure() {
        Structure propertyTypes = buildArrayHeader(
            "C2EPropertyTypesContainer", "Property Types collection", 0x14, 38);

        Structure array128 = buildArrayHeader(
            "C2EArray12Container", "Unidentified 12-byte-record collection", 0x0c, 20);

        Structure caRates = buildArrayHeader(
            "C2ECARatesContainer", "CA Rates-related collection", 0x10, 16);

        Structure tail = buildArrayHeader(
            "C2EDocumentTailContainer", "Document tail collection/state", 0, -1);

        Structure s = getOrCreate("CC2ERoomEditorDoc_refined", 0x158);

        blob(s, 0x000, 0x54, "mfc_CDocument_base",
            "Proven derived-class boundary: document constructor calls CDocument then constructs first editor member at +0x54.");

        blob(s, 0x054, 0x64, "worldModel",
            "Object used directly by OnWorldProperties; constructor FUN_00422370. Internal fields not yet split.");

        u8(s, 0x0b8, "stateB8", "Document boolean/state byte.");
        blob(s, 0x0b9, 3, "paddingB9", "Alignment.");

        blob(s, 0x0bc, 0x10, "stateObjectBC",
            "Constructed by FUN_0040D320.");
        blob(s, 0x0cc, 0x10, "stateObjectCC",
            "Constructed by FUN_0040D320.");

        u32(s, 0x0dc, "stateDC", "Initialized to zero.");
        u32(s, 0x0e0, "stateE0", "Initialized to zero.");
        u32(s, 0x0e4, "stateE4", "Initialized to zero.");

        u8(s, 0x0e8, "stateE8", "Document boolean/state byte.");
        blob(s, 0x0e9, 3, "paddingE9", "Alignment.");

        blob(s, 0x0ec, 0x10, "stateObjectEC",
            "Constructed by FUN_0040D320.");
        blob(s, 0x0fc, 0x10, "stateObjectFC",
            "Constructed by FUN_0040D320.");

        u32(s, 0x10c, "state10C", "Initialized to zero.");
        u32(s, 0x110, "state110", "Initialized to zero.");
        u32(s, 0x114, "state114", "Initialized to zero.");

        field(s, 0x118, propertyTypes, 0x10, "propertyTypes",
            "OnPropertyTypes operates on this collection. Constructor allocates 0x2f8 bytes = 38 * 0x14-byte records.");

        field(s, 0x128, array128, 0x10, "recordArray128",
            "Constructor allocates 0xf0 bytes and constructs 20 * 0x0c-byte records.");

        field(s, 0x138, caRates, 0x10, "caRates",
            "OnCARates accesses this collection. Constructor allocates 0x100 bytes = 16 * 0x10-byte records.");

        field(s, 0x148, tail, 0x10, "tailState",
            "Same flag + three-pointer shape, initially empty.");

        return s;
    }

    private void setDescriptionSafe(Structure s, String description) {
        try {
            s.setDescription(description);
        }
        catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // Vtables / function evidence
    // ---------------------------------------------------------------------

    private void labelToolVtables() {
        labelAddress(0x42b988L, "C2EEditorToolBase_vftable",
            "9-slot editor tool virtual interface.");

        for (ToolDef t : tools) {
            labelAddress(t.vtable, t.name + "_vftable",
                "9-slot vtable: dtor, MouseMove, LButtonDown, LButtonUp, ContextMenu, KeyDown, Activate, Deactivate, UpdateCursor.");
        }
    }

    private void labelToolCoreFunctions() throws Exception {
        renameIfDefault(0x40e410L, "C2EEditorToolBase_DeletingDestructor");
        renameIfDefault(0x40e430L, "C2EEditorTool_DerivedDeletingDestructor");
        renameIfDefault(0x40e450L, "C2EEditorToolBase_ctor");
        renameIfDefault(0x40e480L, "C2EEditorToolBase_dtor");
        renameIfDefault(0x40e3f0L, "C2EEditorTool_NoOpWithArg");
        renameIfDefault(0x40e400L, "C2EEditorTool_NoOp");
        renameIfDefault(0x41ee40L, "C2EEditorTool_SetArrowCursor");

        // Unique high-confidence handlers from the Add Room vtable.
        renameIfDefault(0x41f070L, "C2EAddRoomTool_OnMouseMove");
        renameIfDefault(0x41f3d0L, "C2EAddRoomTool_OnLButtonDown");
        renameIfDefault(0x40eb90L, "C2EAddRoomTool_OnLButtonUp");
        renameIfDefault(0x41f5b0L, "C2EAddRoomTool_OnKeyDown");
        renameIfDefault(0x41f590L, "C2EAddRoomTool_OnActivate");
        renameIfDefault(0x41f5a0L, "C2EAddRoomTool_OnDeactivate");

        // Keep shared/default handlers generic; attach role evidence as comments.
        commentFunction(0x41ed40L,
            "[C2E tool-vtable] Used as MouseMove by multiple editor tools.");
        commentFunction(0x41ee60L,
            "[C2E tool-vtable] Used as LButtonDown by multiple editor tools.");
        commentFunction(0x41ee90L,
            "[C2E tool-vtable] Base/default LButtonUp implementation.");
    }

    private void annotateConstructors() {
        setPlateComment(toAddr(0x40e240L),
            "CC2ERoomEditorView constructor.\n" +
            "Proves CView base size 0x44 and embedded tool boundaries:\n" +
            "+48 AddRoom[0x40], +88 AddMetaroom[0x1c], +A4 Select[0x30],\n" +
            "+D4 SelectMetaroom[0x30], +104 Zoom[0x1c], +120 Cheese[0x2c].");

        setPlateComment(toAddr(0x407bc0L),
            "CC2ERoomEditorDoc constructor.\n" +
            "Proves CDocument base size 0x54 and collection layouts:\n" +
            "+118 PropertyTypes: 38 * 0x14 records,\n" +
            "+128 collection: 20 * 0x0c records,\n" +
            "+138 CA-related: 16 * 0x10 records.");
    }

    private void labelAddress(long value, String name, String comment) {
        Address a = toAddr(value);

        try {
            Symbol primary = currentProgram.getSymbolTable().getPrimarySymbol(a);

            if (primary == null ||
                primary.getName().startsWith("DAT_") ||
                primary.getSource() == SourceType.DEFAULT) {

                createLabel(a, name, true);
            }

            String old = getEOLComment(a);
            if (old == null || old.isBlank()) {
                setEOLComment(a, comment);
            }
            else if (!old.contains(comment)) {
                setEOLComment(a, old + "\n" + comment);
            }
        }
        catch (Exception e) {
            println("[label-skip] " + a + " " + name + ": " + e.getMessage());
        }
    }

    private void renameIfDefault(long value, String name) throws Exception {
        Address a = toAddr(value);
        Function f = getFunctionAt(a);

        if (f == null) {
            println("[function-missing] " + a + " " + name);
            return;
        }

        if (f.getName().startsWith("FUN_") ||
            f.getName().startsWith("thunk_FUN_") ||
            f.getSymbol().getSource() == SourceType.DEFAULT) {

            f.setName(name, SourceType.USER_DEFINED);
            println("[rename] " + a + " -> " + name);
        }
        else {
            println("[keep] " + a + " " + f.getName());
        }
    }

    private void commentFunction(long value, String text) {
        Address a = toAddr(value);
        String old = getRepeatableComment(a);

        if (old == null || old.isBlank()) {
            setRepeatableComment(a, text);
        }
        else if (!old.contains(text)) {
            setRepeatableComment(a, old + "\n" + text);
        }
    }
}
