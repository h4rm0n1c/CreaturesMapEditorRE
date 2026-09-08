// Creatures Map Editor 1.08 — pass 20: type all recovered MFC UI members.
//
// Prerequisites: passes 01–19, then the v1.1 class-layout pass (02) which
// creates the _observed_prefix structures for the dialogs and application.
//
// This is deliberately a broad decompiler-enablement pass.  It gives every
// recovered MFC app/frame/dialog member an explicit __thiscall this pointer in
// ECX.  The structure is either an exact runtime-sized flat layout or a
// bounded observed prefix; no unobserved object tail is claimed.
//
// It never overwrites a user-owned signature and it does not alter return
// types, ordinary parameters, field names, labels, functions or data.
//
// @category C2E Map Editor

import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;

public class ApplyC2EMapEditorMfcUiThisTypes extends GhidraScript {

    private static final CategoryPath CAT =
        new CategoryPath("/C2E/RecoveredClasses");
    private static final CategoryPath REFINED_CAT =
        new CategoryPath("/C2E/Refined");

    private static class Owner {
        final String name;
        final String layout;
        final CategoryPath category;

        Owner(String name, String layout) {
            this(name, layout, CAT);
        }

        Owner(String name, String layout, CategoryPath category) {
            this.name = name;
            this.layout = layout;
            this.category = category;
        }
    }

    private static final Owner[] OWNERS = {
        new Owner("CC2ERoomEditorApp", "CC2ERoomEditorApp_observed_prefix"),
        new Owner("CMetaroomBackgroundDlg", "CMetaroomBackgroundDlg_observed_prefix"),
        new Owner("CBackgroundFileDlg", "CBackgroundFileDlg_observed_prefix"),
        new Owner("CCAPropertiesDlg", "CCAPropertiesDlg_observed_prefix"),
        // Pass 19 recovered the stronger exact Floor/Ceiling layout; do not
        // downgrade those handlers to the generic observed-prefix type.
        new Owner("CFloorCeilingDlg", "CFloorCeilingDlg_refined", REFINED_CAT),
        new Owner("CPropertyTypesDlg", "CPropertyTypesDlg_observed_prefix"),
        new Owner("CPropertyTypeDlg", "CPropertyTypeDlg_observed_prefix"),
        new Owner("CPropertiesDlg", "CPropertiesDlg_observed_prefix"),
        new Owner("CSwitchMetaroomDlg", "CSwitchMetaroomDlg_observed_prefix"),
        new Owner("CTipDlg", "CTipDlg_observed_prefix"),
        new Owner("CChildFrame", "CChildFrame_flat_layout"),
        new Owner("CMainFrame", "CMainFrame_flat_layout")
    };

    private final Map<String, Pointer> pointers = new LinkedHashMap<>();
    private Register ecx;
    private int candidates;
    private int applied;
    private int alreadyApplied;
    private int protectedSignatures;
    private int skipped;

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the recovered MapEditor.exe database first.");
            return;
        }
        if (currentProgram.getDefaultPointerSize() != 4 ||
            getByte(toAddr(0x00425290L)) != (byte)0x51 ||
            getByte(toAddr(0x00425291L)) != (byte)0x8b ||
            getByte(toAddr(0x00417e50L)) != (byte)0x55) {
            popup("MapEditor 1.08 identity guard failed.");
            return;
        }

        ecx = currentProgram.getRegister("ECX");
        if (ecx == null) {
            popup("The x86 ECX register is unavailable in this program.");
            return;
        }
        if (!loadLayouts()) return;

        println("=== C2E Map Editor: MFC UI this-pointer recovery ===");
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) {
            monitor.checkCancelled();
            Function function = it.next();
            Owner owner = ownerFor(function.getName());
            if (owner == null || isMetadataHelper(function.getName())) continue;
            candidates++;
            applyThis(function, owner, pointers.get(owner.name));
        }

        println("\n=== MFC UI this-pointer summary ===");
        println("Candidate members:       " + candidates);
        println("Typed this pointers:     " + applied);
        println("Already correct:         " + alreadyApplied);
        println("User signatures kept:    " + protectedSignatures);
        println("Other skips/failures:    " + skipped);
        println("\nRe-run Auto Analyze after this pass so decompilation uses the recovered UI types.");
    }

    private boolean loadLayouts() {
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        ArrayList<String> missing = new ArrayList<>();
        for (Owner owner : OWNERS) {
            DataType type = dtm.getDataType(owner.category, owner.layout);
            if (!(type instanceof Structure)) {
                missing.add(owner.layout);
                continue;
            }
            pointers.put(owner.name, new PointerDataType(type, dtm));
        }
        if (!missing.isEmpty()) {
            popup("Run pass 02 v1.1 first; missing: " + join(missing));
            return false;
        }
        return true;
    }

    private Owner ownerFor(String functionName) {
        for (Owner owner : OWNERS) {
            if (functionName.startsWith(owner.name + "_")) return owner;
        }
        return null;
    }

    private boolean isMetadataHelper(String name) {
        return name.endsWith("_CreateObject") ||
               name.endsWith("_GetBaseClass") ||
               name.endsWith("_GetRuntimeClass") ||
               name.endsWith("_GetMessageMap");
    }

    private void applyThis(Function function, Owner owner, Pointer pointer) {
        try {
            if (function.hasVarArgs()) {
                skipped++;
                return;
            }

            Parameter[] old = function.getParameters();
            if (function.hasCustomVariableStorage() && old.length > 0 &&
                "this".equals(old[0].getName()) &&
                old[0].getDataType() != null && old[0].getDataType().isEquivalent(pointer) &&
                old[0].getVariableStorage() != null && old[0].getVariableStorage().isRegisterStorage()) {
                alreadyApplied++;
                return;
            }

            // A manual signature takes precedence over inferred ownership.
            if (function.hasCustomVariableStorage() && old.length > 0 &&
                old[0].getSource() == SourceType.USER_DEFINED &&
                !"this".equals(old[0].getName())) {
                protectedSignatures++;
                println("[keep-user] " + function.getEntryPoint() + " " + function.getName());
                return;
            }

            ArrayList<Variable> parameters = new ArrayList<>();
            parameters.add(new ParameterImpl("this", pointer, ecx, currentProgram,
                SourceType.USER_DEFINED));

            for (Parameter parameter : old) {
                if (parameter.isAutoParameter() || "this".equals(parameter.getName())) continue;
                VariableStorage storage = parameter.getVariableStorage();
                if (storage != null && storage.isRegisterStorage() &&
                    storage.getRegister() != null && storage.getRegister().equals(ecx)) {
                    continue;
                }
                parameters.add(new ParameterImpl(parameter, currentProgram));
            }

            function.updateFunction("__thiscall", null, parameters,
                Function.FunctionUpdateType.CUSTOM_STORAGE, true, SourceType.USER_DEFINED);
            applied++;
            println("[this] " + function.getEntryPoint() + " " + function.getName() +
                " -> " + owner.layout + " * @ ECX");
        }
        catch (Exception e) {
            skipped++;
            println("[skip] " + function.getEntryPoint() + " " + function.getName() +
                ": " + e.getMessage());
        }
    }

    private String join(Collection<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() != 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }
}
