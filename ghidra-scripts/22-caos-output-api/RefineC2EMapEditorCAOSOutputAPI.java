// Creatures Map Editor 1.08 — pass 22: complete the CAOS-output API.
//
// Pass 16 established C2ECAOSOutput as the map editor's dual file/IPC CAOS
// sink.  The remaining high-fanout entry at 00414900 sits between its
// constructor/destructor and Run implementation and is the printf-style
// dispatch wrapper used by the CAOS generation and live-game import paths.
//
// This pass makes no speculative type claims for its variadic stack tail.
// It only names that evidenced wrapper and gives the entire four-function API
// the already-recovered C2ECAOSOutput * this parameter in ECX.  It never
// replaces user-owned signatures, return types, ordinary parameters, data, or
// comments.
//
// @category C2E Map Editor

import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;

public class RefineC2EMapEditorCAOSOutputAPI extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private static class Entry {
        final long address;
        final String name;
        final boolean rename;

        Entry(long address, String name, boolean rename) {
            this.address = address;
            this.name = name;
            this.rename = rename;
        }
    }

    private static final Entry[] API = {
        new Entry(0x00414790L, "C2ECAOSOutput_ctor", false),
        new Entry(0x004148a0L, "C2ECAOSOutput_dtor", false),
        new Entry(0x00414900L, "C2ECAOSOutput_FormatAndRun", true),
        new Entry(0x00414980L, "C2ECAOSOutput_Run", false)
    };

    private Register ecx;
    private Pointer outputPtr;
    private int renamed;
    private int namesKept;
    private int thisApplied;
    private int thisAlreadyApplied;
    private int protectedSignatures;
    private int skipped;

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the recovered MapEditor.exe database first.");
            return;
        }
        if (currentProgram.getDefaultPointerSize() != 4 ||
            getByte(toAddr(0x00425290L)) != (byte) 0x51 ||
            getByte(toAddr(0x00425291L)) != (byte) 0x8b ||
            getByte(toAddr(0x00417e50L)) != (byte) 0x55) {
            popup("MapEditor 1.08 identity guard failed.");
            return;
        }

        DataType type = currentProgram.getDataTypeManager().getDataType(CAT, "C2ECAOSOutput");
        if (!(type instanceof Structure)) {
            popup("Missing /C2E/Refined/C2ECAOSOutput. Run pass 16 first.");
            return;
        }
        outputPtr = new PointerDataType(type, currentProgram.getDataTypeManager());
        ecx = currentProgram.getRegister("ECX");
        if (ecx == null) {
            popup("The x86 ECX register is unavailable in this program.");
            return;
        }

        println("=== C2E Map Editor: CAOS output API completion ===");
        for (Entry entry : API) {
            Function function = currentProgram.getFunctionManager().getFunctionAt(toAddr(entry.address));
            if (function == null) {
                skipped++;
                println("[skip] no function at " + toAddr(entry.address));
                continue;
            }
            if (entry.rename) renameDefault(function, entry.name);
            applyThis(function);
        }

        if (thisApplied != 0 || renamed != 0) {
            analyzeChanges(currentProgram);
        }
        println("\n=== CAOS output API summary ===");
        println("Wrapper renamed:          " + renamed);
        println("Existing names kept:      " + namesKept);
        println("this types applied:       " + thisApplied);
        println("this types already held:  " + thisAlreadyApplied);
        println("User signatures kept:     " + protectedSignatures);
        println("Other skips:              " + skipped);
        println("No comments or data were changed.");
    }

    private void renameDefault(Function function, String name) {
        String old = function.getName();
        if (!old.startsWith("FUN_") && !old.startsWith("thunk_FUN_")) {
            namesKept++;
            return;
        }
        try {
            function.setName(name, SourceType.USER_DEFINED);
            renamed++;
            println("[name] " + function.getEntryPoint() + " " + old + " -> " + name);
        }
        catch (Exception e) {
            skipped++;
            println("[skip-name] " + function.getEntryPoint() + ": " + e.getMessage());
        }
    }

    private void applyThis(Function function) {
        try {
            if (function.hasVarArgs()) {
                skipped++;
                println("[skip-varargs] " + function.getEntryPoint() + " " + function.getName());
                return;
            }
            Parameter[] old = function.getParameters();
            if (function.hasCustomVariableStorage() && old.length > 0 &&
                "this".equals(old[0].getName()) &&
                old[0].getDataType() != null && old[0].getDataType().isEquivalent(outputPtr) &&
                old[0].getVariableStorage() != null && old[0].getVariableStorage().isRegisterStorage()) {
                thisAlreadyApplied++;
                return;
            }
            if (function.hasCustomVariableStorage() && old.length > 0 &&
                old[0].getSource() == SourceType.USER_DEFINED &&
                !"this".equals(old[0].getName())) {
                protectedSignatures++;
                println("[keep-user] " + function.getEntryPoint() + " " + function.getName());
                return;
            }

            ArrayList<Variable> parameters = new ArrayList<>();
            parameters.add(new ParameterImpl("this", outputPtr, ecx, currentProgram,
                SourceType.USER_DEFINED));
            for (Parameter parameter : old) {
                if (parameter.isAutoParameter() || "this".equals(parameter.getName())) continue;
                VariableStorage storage = parameter.getVariableStorage();
                if (storage != null && storage.isRegisterStorage() &&
                    storage.getRegister() != null && storage.getRegister().equals(ecx)) continue;
                parameters.add(new ParameterImpl(parameter, currentProgram));
            }
            function.updateFunction("__thiscall", null, parameters,
                Function.FunctionUpdateType.CUSTOM_STORAGE, true, SourceType.USER_DEFINED);
            thisApplied++;
            println("[this] " + function.getEntryPoint() + " " + function.getName() +
                " -> C2ECAOSOutput * @ ECX");
        }
        catch (Exception e) {
            skipped++;
            println("[skip-this] " + function.getEntryPoint() + " " + function.getName() +
                ": " + e.getMessage());
        }
    }
}
