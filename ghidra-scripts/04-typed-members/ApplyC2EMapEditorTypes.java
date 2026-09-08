// Complete the Creatures Map Editor 1.08 editor-tool functions and
// apply refined class pointer types to recovered member functions.
//
// Prerequisites:
//   1) RecoverC2EMapEditorMFC.java
//   2) RecoverC2EMapEditorClassLayout.java
//   3) RefineC2EMapEditorStructures.java
//
// Target: MapEditor.exe, 32-bit VC6 / MFC42.
//
// This pass:
//   * creates missing, independently verified editor-tool functions;
//   * assigns semantic names to unique vtable handlers;
//   * refines editor-tool pointer fields;
//   * applies __thiscall plus typed explicit ECX "this" pointers using
//     Ghidra custom variable storage while preserving existing formal args;
//   * applies refined return types to MFC CreateObject factories;
//   * asks Auto Analysis to process the resulting changes.
//
// It deliberately does not rebuild arbitrary function bodies, clear existing
// code/data, or overwrite manually supplied function names.
//
// Revision 1.4: drop pre-existing formal parameters occupying ECX when rebuilding thiscall signatures.
// Revision 1.3: added missing ghidra.program.model.lang.Register import.
// Revision 1.2: use CUSTOM_STORAGE signatures to type explicit ECX 'this'.
// Revision 1.1: fixed checked CancelledException propagation in member scan.
// @category C2E Map Editor
// @author OpenAI

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;

public class ApplyC2EMapEditorTypes extends GhidraScript {

    private static final CategoryPath CAT =
        new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;

    private Structure docType;
    private Structure viewType;
    private Structure toolBaseType;
    private Structure toolVtableType;

    private Pointer docPtr;
    private Pointer viewPtr;
    private Pointer toolBasePtr;
    private Pointer toolVtablePtr;

    private int functionsCreated;
    private int functionsRenamed;
    private int functionsKept;
    private int thisTypesApplied;
    private int thisTypesSkipped;
    private int returnTypesApplied;
    private int pointerFieldsRefined;

    private static class FuncSpec {
        final long address;
        final String name;
        final String ownerType;

        FuncSpec(long address, String name, String ownerType) {
            this.address = address;
            this.name = name;
            this.ownerType = ownerType;
        }
    }

    // Every address here was checked against the exact supplied executable.
    // "ownerType" is the safest structure to use for ECX/this.
    private final FuncSpec[] toolFunctions = new FuncSpec[] {
        new FuncSpec(0x40e3f0L, "C2EEditorTool_NoOpWithArg",              "C2EEditorToolBase"),
        new FuncSpec(0x40e400L, "C2EEditorTool_NoOp",                     "C2EEditorToolBase"),
        new FuncSpec(0x40e410L, "C2EEditorToolBase_DeletingDestructor",   "C2EEditorToolBase"),
        new FuncSpec(0x40e430L, "C2EEditorTool_DerivedDeletingDestructor","C2EEditorToolBase"),
        new FuncSpec(0x40e450L, "C2EEditorToolBase_ctor",                 "C2EEditorToolBase"),
        new FuncSpec(0x40e480L, "C2EEditorToolBase_dtor",                 "C2EEditorToolBase"),

        new FuncSpec(0x41ed40L, "C2EEditorTool_DefaultMouseMove",         "C2EEditorToolBase"),
        new FuncSpec(0x41ee60L, "C2EEditorTool_DefaultLButtonDown",       "C2EEditorToolBase"),
        new FuncSpec(0x41ee90L, "C2EEditorTool_DefaultLButtonUp",         "C2EEditorToolBase"),
        new FuncSpec(0x41ee40L, "C2EEditorTool_SetArrowCursor",           "C2EEditorToolBase"),

        new FuncSpec(0x41f070L, "C2EAddRoomTool_OnMouseMove",             "C2EAddRoomTool"),
        new FuncSpec(0x41f3d0L, "C2EAddRoomTool_OnLButtonDown",           "C2EAddRoomTool"),
        new FuncSpec(0x40eb90L, "C2EAddRoomTool_OnLButtonUp",             "C2EAddRoomTool"),
        new FuncSpec(0x41f5b0L, "C2EAddRoomTool_OnKeyDown",               "C2EAddRoomTool"),
        new FuncSpec(0x41f590L, "C2EAddRoomTool_OnActivate",              "C2EAddRoomTool"),
        new FuncSpec(0x41f5a0L, "C2EAddRoomTool_OnDeactivate",            "C2EAddRoomTool"),

        new FuncSpec(0x41f5e0L, "C2EAddMetaroomTool_OnLButtonUp",         "C2EAddMetaroomTool"),
        new FuncSpec(0x41f9b0L, "C2EAddMetaroomTool_UpdateCursor",        "C2EAddMetaroomTool"),

        new FuncSpec(0x420280L, "C2ESelectTool_OnMouseMove",               "C2ESelectTool"),
        new FuncSpec(0x41ffd0L, "C2ESelectTool_OnLButtonUp",               "C2ESelectTool"),
        new FuncSpec(0x4209a0L, "C2ESelectTool_OnContextMenu",             "C2ESelectTool"),

        new FuncSpec(0x41fd70L, "C2ESelectMetaroomTool_OnMouseMove",       "C2ESelectMetaroomTool"),
        new FuncSpec(0x41fbd0L, "C2ESelectMetaroomTool_OnLButtonUp",       "C2ESelectMetaroomTool"),

        new FuncSpec(0x41f9e0L, "C2EZoomTool_OnLButtonUp",                 "C2EZoomTool"),
        new FuncSpec(0x41fb00L, "C2EZoomTool_OnContextMenu",               "C2EZoomTool"),
        new FuncSpec(0x41fad0L, "C2EZoomTool_UpdateCursor",                "C2EZoomTool"),

        new FuncSpec(0x420a70L, "C2ECheeseTool_OnLButtonUp",               "C2ECheeseTool"),
    };

    private static final long[] TOOL_VTABLES = {
        0x42b988L, // base
        0x42b964L, // Add Room
        0x42b940L, // Add Metaroom
        0x42b91cL, // Select
        0x42b8f8L, // Select Metaroom
        0x42b8d4L, // Zoom
        0x42b8b0L, // Cheese
    };

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the recovered MapEditor.exe program first.");
            return;
        }

        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This script is deliberately limited to the 32-bit Map Editor target.");
            return;
        }

        // Same identity guard as the prior target-specific pass.
        Address guard = toAddr(0x40e1b6L);
        if (getByte(guard) != (byte)0x68) {
            popup("Binary guard failed at 0040E1B6. Refusing target-specific typing.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();

        requireRefinedTypes();
        buildVtableType();
        refinePointerFields();

        println("=== Creatures Map Editor 1.08 - typed member pass ===");
        println("");

        for (FuncSpec spec : toolFunctions) {
            monitor.checkCancelled();

            Function f = ensureFunction(spec.address, spec.name);
            if (f == null) {
                continue;
            }

            DataType owner = getRefined(spec.ownerType);
            if (owner != null) {
                applyThisType(f, new PointerDataType(owner, dtm), spec.ownerType);
            }
        }

        // The constructor starts are independently proven by the prior passes.
        Function docCtor = ensureFunction(0x407bc0L, "CC2ERoomEditorDoc_ctor");
        if (docCtor != null) {
            applyThisType(docCtor, docPtr, "CC2ERoomEditorDoc_refined");
        }

        Function viewCtor = ensureFunction(0x40e240L, "CC2ERoomEditorView_ctor");
        if (viewCtor != null) {
            applyThisType(viewCtor, viewPtr, "CC2ERoomEditorView_refined");
        }

        applyRecoveredMemberTypes();
        applyFactoryReturnTypes();

        println("");
        println("Running analysis on changed code/signatures...");
        analyzeChanges(currentProgram);

        File report = writeReport();

        println("");
        println("=== Typed member pass summary ===");
        println("Functions created:       " + functionsCreated);
        println("Functions renamed:       " + functionsRenamed);
        println("Existing names kept:     " + functionsKept);
        println("this types applied:      " + thisTypesApplied);
        println("this types skipped:      " + thisTypesSkipped);
        println("factory returns applied: " + returnTypesApplied);
        println("pointer fields refined:  " + pointerFieldsRefined);
        println("Report: " + report.getAbsolutePath());
        println("");
        println("Open a recovered Doc/View handler in the Decompiler.");
        println("Expected improvement: named members such as this->activeTool,");
        println("this->propertyTypes, this->caRates and embedded tool objects.");
    }

    // ---------------------------------------------------------------------
    // Type lookup / refinement
    // ---------------------------------------------------------------------

    private void requireRefinedTypes() {
        docType = requireStructure("CC2ERoomEditorDoc_refined");
        viewType = requireStructure("CC2ERoomEditorView_refined");
        toolBaseType = requireStructure("C2EEditorToolBase");

        docPtr = new PointerDataType(docType, dtm);
        viewPtr = new PointerDataType(viewType, dtm);
        toolBasePtr = new PointerDataType(toolBaseType, dtm);
    }

    private Structure requireStructure(String name) {
        DataType type = dtm.getDataType(CAT, name);

        if (!(type instanceof Structure)) {
            throw new IllegalStateException(
                "Missing /C2E/Refined/" + name +
                ". Run RefineC2EMapEditorStructures.java first.");
        }

        return (Structure)type;
    }

    private DataType getRefined(String name) {
        return dtm.getDataType(CAT, name);
    }

    private void buildVtableType() {
        DataType existing = dtm.getDataType(CAT, "C2EEditorToolVTable");

        if (existing instanceof Structure) {
            toolVtableType = (Structure)existing;
            if (toolVtableType.getLength() < 0x24) {
                toolVtableType.growStructure(0x24 - toolVtableType.getLength());
            }
        }
        else {
            StructureDataType fresh =
                new StructureDataType(CAT, "C2EEditorToolVTable", 0x24, dtm);

            toolVtableType = (Structure)dtm.addDataType(
                fresh, DataTypeConflictHandler.REPLACE_HANDLER);
        }

        Pointer genericCodePointer =
            new PointerDataType(VoidDataType.dataType, dtm);

        vslot(0x00, genericCodePointer, "DeletingDestructor",
            "VC6 scalar/deleting destructor slot.");
        vslot(0x04, genericCodePointer, "MouseMove",
            "Editor tool mouse-move handler.");
        vslot(0x08, genericCodePointer, "LButtonDown",
            "Editor tool left-button-down handler.");
        vslot(0x0c, genericCodePointer, "LButtonUp",
            "Editor tool left-button-up handler.");
        vslot(0x10, genericCodePointer, "ContextMenu",
            "Editor tool context-menu handler.");
        vslot(0x14, genericCodePointer, "KeyDown",
            "Editor tool key-down handler.");
        vslot(0x18, genericCodePointer, "Activate",
            "Called when this tool becomes active.");
        vslot(0x1c, genericCodePointer, "Deactivate",
            "Called before switching away from this tool.");
        vslot(0x20, genericCodePointer, "UpdateCursor",
            "Cursor-selection/update virtual.");

        toolVtablePtr = new PointerDataType(toolVtableType, dtm);
    }

    private void vslot(
            int offset,
            DataType type,
            String name,
            String comment) {

        try {
            toolVtableType.replaceAtOffset(
                offset, type, 4, name, comment);
        }
        catch (Exception e) {
            println("[vtable-field-skip] +0x" +
                Integer.toHexString(offset) + " " + name + ": " + e.getMessage());
        }
    }

    private void refinePointerFields() {
        replaceField(
            toolBaseType, 0x00, toolVtablePtr, 4,
            "vftable",
            "Pointer to C2EEditorToolVTable.");

        replaceField(
            toolBaseType, 0x04, viewPtr, 4,
            "ownerView",
            "Back-pointer to owning CC2ERoomEditorView.");

        replaceField(
            viewType, 0x044, toolBasePtr, 4,
            "activeTool",
            "Pointer to currently active embedded editor tool.");
    }

    private void replaceField(
            Structure s,
            int offset,
            DataType type,
            int length,
            String name,
            String comment) {

        try {
            s.replaceAtOffset(offset, type, length, name, comment);
            pointerFieldsRefined++;
        }
        catch (Exception e) {
            println("[pointer-field-skip] " + s.getName() +
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
                    " lies inside " + containing.getName() +
                    " @ " + containing.getEntryPoint());
                return null;
            }

            Instruction instruction = getInstructionAt(a);

            if (instruction == null) {
                boolean ok = disassemble(a);

                if (!ok) {
                    println("[disassemble-fail] " + a + " " + desiredName);
                    return null;
                }

                instruction = getInstructionAt(a);
            }

            if (instruction == null) {
                println("[instruction-missing] " + a + " " + desiredName);
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

        renameIfDefault(f, desiredName);
        return f;
    }

    private void renameIfDefault(Function f, String desiredName) throws Exception {
        String current = f.getName();

        boolean defaultName =
            current.startsWith("FUN_") ||
            current.startsWith("thunk_FUN_") ||
            f.getSymbol().getSource() == SourceType.DEFAULT;

        if (defaultName) {
            f.setName(desiredName, SourceType.USER_DEFINED);
            functionsRenamed++;
            println("[rename] " + f.getEntryPoint() + " -> " + desiredName);
        }
        else {
            functionsKept++;
            if (!current.equals(desiredName)) {
                println("[keep] " + f.getEntryPoint() +
                    " existing=" + current +
                    " suggested=" + desiredName);
            }
        }
    }

    // ---------------------------------------------------------------------
    // this-pointer typing
    // ---------------------------------------------------------------------

    private void applyRecoveredMemberTypes() throws Exception {
        FunctionIterator it =
            currentProgram.getFunctionManager().getFunctions(true);

        while (it.hasNext()) {
            monitor.checkCancelled();

            Function f = it.next();
            String name = f.getName();

            if (isNormalMember(name, "CC2ERoomEditorDoc")) {
                applyThisType(f, docPtr, "CC2ERoomEditorDoc_refined");
            }
            else if (isNormalMember(name, "CC2ERoomEditorView")) {
                applyThisType(f, viewPtr, "CC2ERoomEditorView_refined");
            }
        }
    }

    private boolean isNormalMember(String name, String owner) {
        if (!name.startsWith(owner + "_")) {
            return false;
        }

        return !name.endsWith("_CreateObject") &&
               !name.endsWith("_GetBaseClass") &&
               !name.endsWith("_GetRuntimeClass") &&
               !name.endsWith("_GetMessageMap");
    }

    private void applyThisType(
            Function f,
            Pointer ptr,
            String typeName) {

        try {
            if (f.hasVarArgs()) {
                println("[this-skip] " + f.getEntryPoint() +
                    " " + f.getName() +
                    " : varargs function; custom-storage typing deliberately skipped");
                thisTypesSkipped++;
                return;
            }

            Register ecx = currentProgram.getRegister("ECX");
            if (ecx == null) {
                println("[this-fail] " + f.getEntryPoint() +
                    " " + f.getName() + ": ECX register not found");
                thisTypesSkipped++;
                return;
            }

            Parameter[] oldParams = f.getParameters();

            // Idempotence: after a successful v1.2 run, 'this' is an explicit
            // custom-storage parameter in ECX rather than an injected auto-param.
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

            // Ghidra does not permit retyping an injected auto 'this' parameter
            // directly.  Rebuild the effective signature with explicit storage:
            //
            //     this -> ECX
            //     all existing formal args -> their existing storage
            //
            // This is the programmatic equivalent of "Use Custom Storage".
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

                // If this script is rerun after a partial/custom-storage attempt,
                // do not duplicate an already-explicit this parameter.
                if ("this".equals(p.getName())) {
                    continue;
                }

                // Some functions were previously modelled with an ordinary formal
                // parameter already occupying ECX (for example param_1_00{ECX:4}).
                // In a VC6 __thiscall member function that storage is the this
                // pointer, not a separate user argument. Keeping it would collide
                // with the explicit typed this{ECX:4} we are creating.
                VariableStorage storage = p.getVariableStorage();
                if (storage != null &&
                    storage.isRegisterStorage() &&
                    storage.getRegister() != null &&
                    storage.getRegister().equals(ecx)) {

                    println("[drop-ecx-param] " + f.getEntryPoint() +
                        " " + f.getName() +
                        " dropping " + p.getName() +
                        "{" + storage + "} before explicit this");
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

            Parameter appliedThis = f.getParameter(0);

            if (appliedThis == null ||
                !"this".equals(appliedThis.getName()) ||
                appliedThis.getDataType() == null ||
                !appliedThis.getDataType().isEquivalent(ptr)) {

                throw new IllegalStateException(
                    "signature update completed but typed this parameter was not retained");
            }

            thisTypesApplied++;
            println("[this] " + f.getEntryPoint() +
                " " + f.getName() +
                " -> " + typeName + " * @ ECX" +
                " (custom storage, params=" + f.getParameterCount() + ")");
        }
        catch (Exception e) {
            thisTypesSkipped++;
            println("[this-fail] " + f.getEntryPoint() +
                " " + f.getName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // MFC factory return typing
    // ---------------------------------------------------------------------

    private void applyFactoryReturnTypes() {
        applyFactoryReturn("CC2ERoomEditorDoc_CreateObject", docPtr);
        applyFactoryReturn("CC2ERoomEditorView_CreateObject", viewPtr);
    }

    private void applyFactoryReturn(String name, Pointer returnType) {
        Function f = findFunctionByExactName(name);

        if (f == null) {
            println("[factory-missing] " + name);
            return;
        }

        try {
            if (!f.getReturnType().isEquivalent(returnType)) {
                f.setReturnType(returnType, SourceType.USER_DEFINED);
                returnTypesApplied++;
                println("[return] " + f.getEntryPoint() +
                    " " + name + " -> " + returnType.getDisplayName());
            }
        }
        catch (Exception e) {
            println("[return-fail] " + name + ": " + e.getMessage());
        }
    }

    private Function findFunctionByExactName(String name) {
        FunctionIterator it =
            currentProgram.getFunctionManager().getFunctions(true);

        while (it.hasNext()) {
            Function f = it.next();
            if (f.getName().equals(name)) {
                return f;
            }
        }

        return null;
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    private File writeReport() throws IOException {
        File outFile = new File(
            System.getProperty("user.home"),
            "C2EMapEditor_typed_members_report.txt");

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outFile),
                    StandardCharsets.UTF_8))) {

            out.println("Creatures Map Editor 1.08 - typed member pass");
            out.println("============================================");
            out.println("Program: " + currentProgram.getName());
            out.println();
            out.println("Functions created:       " + functionsCreated);
            out.println("Functions renamed:       " + functionsRenamed);
            out.println("Existing names kept:     " + functionsKept);
            out.println("this types applied:      " + thisTypesApplied + " (explicit ECX custom-storage this)");
            out.println("this types skipped:      " + thisTypesSkipped);
            out.println("factory returns applied: " + returnTypesApplied);
            out.println("pointer fields refined:  " + pointerFieldsRefined);
            out.println();

            out.println("Refined pointer fields");
            out.println("----------------------");
            out.println("C2EEditorToolBase +0x00 -> C2EEditorToolVTable *vftable");
            out.println("C2EEditorToolBase +0x04 -> CC2ERoomEditorView_refined *ownerView");
            out.println("CC2ERoomEditorView_refined +0x44 -> C2EEditorToolBase *activeTool");
            out.println();

            out.println("Verified vtables");
            out.println("----------------");
            for (long a : TOOL_VTABLES) {
                out.printf("%08X%n", a);
            }
            out.println();

            out.println("Current Doc/View signatures");
            out.println("---------------------------");

            FunctionIterator it =
                currentProgram.getFunctionManager().getFunctions(true);

            while (it.hasNext()) {
                Function f = it.next();
                String n = f.getName();

                if (n.startsWith("CC2ERoomEditorDoc_") ||
                    n.startsWith("CC2ERoomEditorView_")) {

                    out.println(f.getEntryPoint() + "  " +
                        f.getPrototypeString(false, true));
                }
            }
        }

        return outFile;
    }
}
