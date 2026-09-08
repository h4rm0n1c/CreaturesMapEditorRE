// Creatures Map Editor 1.08 — typed live-game CString response recovery.
//
// The current binary decompilation proves the key representation boundary:
// C2EEditorMetaRoom_ReadRoomFromGame assigns C2ECAOSOutput_FormatAndRun, whose
// recovered return is CString *.  The bridge forwards its +0xc response formal
// to C2EEditorRoom_ReadFromGame.  This pass propagates that exact wrapper-level
// type; it does not claim that the underlying ClientSide bytes are CString.
//
// @category C2E Map Editor

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;

import java.util.*;

public class RefineC2EMapEditorGameImportCStringResponse extends GhidraScript {
    private DecompInterface decompiler;
    private int typesApplied, typesKept, commentsAdded, skipped;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function bridge = functionNamed("C2EEditorMetaRoom_ReadRoomFromGame");
        Function room = functionNamed("C2EEditorRoom_ReadFromGame");
        Function format = functionNamed("C2ECAOSOutput_FormatAndRun");
        if (bridge == null || room == null || format == null) {
            popup("Run passes 22 and 25–30 first; CString/import recovery is incomplete."); return;
        }
        decompiler = new DecompInterface(); decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(true); decompiler.toggleSyntaxTree(true); decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }
        try {
            boolean formatReturnsCString = isCStringPointer(format.getReturnType());
            boolean bridgeAssignsFormat = decompileC(bridge).matches("(?s).*=[\\s\\r\\n]*C2ECAOSOutput_FormatAndRun\\s*\\(.*");
            boolean bridgeCallsRoom = directCallees(bridge).contains(room);
            Parameter bridgeResponse = stackParameter(bridge, 0xc);
            Parameter roomResponse = parameterNamed(room, "caosResponse");
            println("=== C2E Map Editor: typed live-game CString response ===");
            println("FormatAndRun return: " + format.getReturnType().getName());
            println("FormatAndRun -> CString *: " + formatReturnsCString);
            println("Bridge assigns FormatAndRun: " + bridgeAssignsFormat);
            println("Bridge -> Room importer: " + bridgeCallsRoom);
            println("Bridge +0xc formal: " + describe(bridgeResponse));
            println("Room response formal: " + describe(roomResponse));

            if (!formatReturnsCString || !bridgeAssignsFormat || !bridgeCallsRoom || bridgeResponse == null || roomResponse == null) {
                skipped++; println("[skip] CString return/bridge/Room chain is incomplete; parameter types unchanged.");
            } else {
                // Preserve the exact existing alias used by FormatAndRun (the
                // current database prints it as CString *).  VC6CString is the
                // structural counterpart, but it is not necessarily the alias
                // selected by this function's recovered signature.
                DataType cstringPtr = format.getReturnType();
                apply(bridge, bridgeResponse, cstringPtr, "bridge");
                apply(room, roomResponse, cstringPtr, "Room importer");
                append(bridge, "[C2E live-game import bridge] caosResponse is the CString response produced by C2ECAOSOutput_FormatAndRun and forwarded to C2EEditorRoom_ReadFromGame.");
                append(room, "[C2E live-game room response] caosResponse is the CString RTYP/RLOC reply produced by C2ECAOSOutput_FormatAndRun through C2EEditorMetaRoom_ReadRoomFromGame.");
            }
            println("\n=== Typed live-game CString response summary ===");
            println("Types applied:  " + typesApplied);
            println("Types kept:     " + typesKept);
            println("Comments added: " + commentsAdded);
            println("Skipped:        " + skipped);
            println("Run Auto Analyze after this pass.");
        } finally { decompiler.dispose(); }
    }

    private Function functionNamed(String name) {
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) { Function f = it.next(); if (name.equals(f.getName())) return f; }
        return null;
    }
    private Parameter parameterNamed(Function f, String wanted) {
        for (Parameter p : f.getParameters()) if (wanted.equals(p.getName())) return p;
        return null;
    }
    private Parameter stackParameter(Function f, int wantedOffset) {
        for (Parameter p : f.getParameters()) try {
            if (!p.isAutoParameter() && p.isStackVariable() && p.getStackOffset() == wantedOffset) return p;
        } catch (Exception ignored) { }
        return null;
    }
    private boolean isCStringPointer(DataType candidate) {
        if (!(candidate instanceof Pointer)) return false;
        String name = candidate.getName();
        DataType base = ((Pointer) candidate).getDataType();
        return (name != null && name.toLowerCase().contains("cstring")) ||
            (base != null && base.getName() != null && base.getName().toLowerCase().contains("cstring"));
    }
    private String decompileC(Function f) throws Exception {
        DecompileResults r = decompiler.decompileFunction(f, 45, monitor);
        return r == null || r.getDecompiledFunction() == null ? "" : r.getDecompiledFunction().getC();
    }
    private Set<Function> directCallees(Function f) throws Exception {
        LinkedHashSet<Function> out = new LinkedHashSet<Function>();
        DecompileResults r = decompiler.decompileFunction(f, 45, monitor);
        HighFunction hf = r == null ? null : r.getHighFunction(); if (hf == null) return out;
        Iterator<PcodeOpAST> it = hf.getPcodeOps();
        while (it.hasNext()) {
            PcodeOpAST op = it.next(); if (op.getOpcode() != PcodeOp.CALL || op.getNumInputs() == 0) continue;
            Varnode dst = op.getInput(0);
            if (dst != null && dst.isAddress()) {
                Function callee = currentProgram.getFunctionManager().getFunctionAt(dst.getAddress());
                if (callee != null) out.add(callee);
            }
        }
        return out;
    }
    private String describe(Parameter p) { return p == null ? "missing" : p.getName() + " : " + p.getDataType().getName(); }
    private void apply(Function owner, Parameter p, DataType wanted, String role) {
        try {
            if (p.getDataType().isEquivalent(wanted)) { typesKept++; println("[keep] " + role + " " + p.getName() + " : " + p.getDataType().getName()); return; }
            p.setDataType(wanted, SourceType.USER_DEFINED); typesApplied++;
            println("[type] " + owner.getEntryPoint() + " " + role + " " + p.getName() + " -> " + wanted.getName());
        } catch (Exception e) { skipped++; println("[skip] " + role + " type: " + e.getMessage()); }
    }
    private void append(Function f, String text) {
        try {
            String old = getPlateComment(f.getEntryPoint()); if (old != null && old.indexOf(text) >= 0) return;
            setPlateComment(f.getEntryPoint(), old == null || old.length() == 0 ? text : old + "\n\n" + text); commentsAdded++;
        } catch (Exception ignored) { }
    }
}
