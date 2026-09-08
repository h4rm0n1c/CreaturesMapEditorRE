// Creatures Map Editor 1.08 — CAOS output response-return recovery.
//
// The per-room bridge's current C has an assignment from FormatAndRun, so the
// wrapper is non-void.  Its concrete pointee remains unproven; this pass only
// restores the exact opaque-pointer contract needed for dataflow recovery.
//
// @category C2E Map Editor

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorCAOSOutputResponseReturn extends GhidraScript {
    private DecompInterface decompiler;
    private int applied, kept, commentsAdded, skipped;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function bridge = functionNamed("C2EEditorMetaRoom_ReadRoomFromGame");
        Function format = functionNamed("C2ECAOSOutput_FormatAndRun");
        if (bridge == null || format == null) {
            popup("Run passes 22 and 25 first; the recovered FormatAndRun bridge is missing."); return;
        }
        decompiler = new DecompInterface(); decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(true); decompiler.toggleSyntaxTree(false); decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }
        try {
            String bridgeC = decompileC(bridge);
            boolean assigned = bridgeC.matches("(?s).*=[\\s\\r\\n]*C2ECAOSOutput_FormatAndRun\\s*\\(.*");
            DataType old = format.getReturnType();
            println("=== C2E Map Editor: CAOS output response-return recovery ===");
            println("Bridge assigns FormatAndRun result: " + assigned);
            println("FormatAndRun current return: " + (old == null ? "missing" : old.getName()));

            if (!assigned) {
                skipped++; println("[skip] Current bridge C no longer assigns FormatAndRun; return type unchanged.");
            } else if (!isDefaultScalar(old)) {
                kept++; println("[keep] " + format.getEntryPoint() + " return " + old.getName());
            } else {
                try {
                    DataType opaqueResponse = new PointerDataType(VoidDataType.dataType, currentProgram.getDataTypeManager());
                    format.setReturnType(opaqueResponse, SourceType.USER_DEFINED);
                    applied++; println("[return] " + format.getEntryPoint() + " -> void *");
                    append(format, "[C2E live-game output contract] C2ECAOSOutput_FormatAndRun returns the opaque response value assigned by C2EEditorMetaRoom_ReadRoomFromGame before per-room import. The binary does not yet prove its concrete pointee type.");
                } catch (Exception e) { skipped++; println("[skip] return type: " + e.getMessage()); }
            }
            println("\n=== CAOS output response-return summary ===");
            println("Returns applied:  " + applied);
            println("Returns kept:     " + kept);
            println("Comments added:   " + commentsAdded);
            println("Skipped:          " + skipped);
            println("Run Auto Analyze after this pass.");
        } finally { decompiler.dispose(); }
    }

    private Function functionNamed(String name) {
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) { Function f = it.next(); if (name.equals(f.getName())) return f; }
        return null;
    }
    private String decompileC(Function f) throws Exception {
        DecompileResults r = decompiler.decompileFunction(f, 45, monitor);
        return r == null || r.getDecompiledFunction() == null ? "" : r.getDecompiledFunction().getC();
    }
    private boolean isDefaultScalar(DataType t) {
        if (t == null) return true;
        String n = t.getName();
        return "undefined4".equals(n) || "int".equals(n) || "uint".equals(n) || "void".equals(n);
    }
    private void append(Function f, String text) {
        try {
            String old = getPlateComment(f.getEntryPoint()); if (old != null && old.indexOf(text) >= 0) return;
            setPlateComment(f.getEntryPoint(), old == null || old.length() == 0 ? text : old + "\n\n" + text); commentsAdded++;
        } catch (Exception ignored) { }
    }
}
