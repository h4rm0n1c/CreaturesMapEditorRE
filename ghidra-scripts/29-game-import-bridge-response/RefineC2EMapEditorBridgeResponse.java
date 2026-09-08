// Creatures Map Editor 1.08 — per-room bridge response provenance.
//
// Pass 28 establishes that the per-room bridge forwards its third stack
// argument directly into C2EEditorRoom_ReadFromGame.  This pass names that
// neutral bridge formal without pretending to know whether FormatAndRun owns,
// returns, or merely populates the buffer.
//
// @category C2E Map Editor

import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorBridgeResponse extends GhidraScript {
    private DecompInterface decompiler;
    private int renamed, kept, commentsAdded, skipped;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function bridge = functionNamed("C2EEditorMetaRoom_ReadRoomFromGame");
        Function room = functionNamed("C2EEditorRoom_ReadFromGame");
        if (bridge == null || room == null) {
            popup("Run passes 25–27 first; the recovered Room-import path is missing."); return;
        }
        decompiler = new DecompInterface(); decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(false); decompiler.toggleSyntaxTree(true); decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }
        try {
            println("=== C2E Map Editor: per-room bridge response provenance ===");
            println("Bridge formals: " + formals(bridge));
            String evidence = roomThirdArgument(bridge, room);
            println("Bridge -> Room third argument: " + evidence);

            Parameter response = stackParameter(bridge, 0xc);
            if (!evidence.contains("[stack +0xc]")) {
                skipped++;
                println("[skip] The Room response is not the bridge's +0xc stack formal; no name applied.");
            } else if (response == null && bridge.getParameters().length == 0) {
                // The current function has no recovered signature at all, while the
                // P-code has an exact caller-stack load at +0xc. Preserve that
                // nonstandard storage instead of letting Ghidra allocate +0x8.
                try {
                    DataType voidPtr = new PointerDataType(VoidDataType.dataType, currentProgram.getDataTypeManager());
                    ArrayList<Variable> formals = new ArrayList<Variable>();
                    formals.add(new ParameterImpl("caosResponse", voidPtr, 0xc, currentProgram, SourceType.USER_DEFINED));
                    bridge.updateFunction(bridge.getCallingConventionName(), null, formals,
                        Function.FunctionUpdateType.CUSTOM_STORAGE, true, SourceType.USER_DEFINED);
                    renamed++;
                    println("[param] " + bridge.getEntryPoint() + " added caosResponse : void * @ stack +0xc");
                    append(bridge, "[C2E live-game import bridge] caosResponse is read from the proven +0xc caller stack slot and forwarded unchanged as the third argument to C2EEditorRoom_ReadFromGame. Its concrete representation remains deliberately unresolved here.");
                } catch (Exception e) { skipped++; println("[skip] parameter recovery: " + e.getMessage()); }
            } else if (response == null) {
                skipped++;
                println("[skip] Bridge has other recovered formals but none at +0xc; signature left unchanged.");
            } else if (!isDefault(response)) {
                kept++;
                println("[keep] " + bridge.getEntryPoint() + " " + response.getName());
            } else {
                try {
                    response.setName("caosResponse", SourceType.USER_DEFINED);
                    renamed++;
                    println("[param] " + bridge.getEntryPoint() + " " + response.getName() + " -> caosResponse");
                    append(bridge, "[C2E live-game import bridge] caosResponse is forwarded unchanged as the third argument to C2EEditorRoom_ReadFromGame. Its concrete representation remains deliberately unresolved here.");
                } catch (Exception e) { skipped++; println("[skip] parameter rename: " + e.getMessage()); }
            }
            println("\n=== Bridge response provenance summary ===");
            println("Parameters renamed: " + renamed);
            println("Existing names kept: " + kept);
            println("Comments added:     " + commentsAdded);
            println("Skipped:            " + skipped);
            println("Run Auto Analyze after this pass.");
        } finally { decompiler.dispose(); }
    }

    private Function functionNamed(String name) {
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) { Function f = it.next(); if (name.equals(f.getName())) return f; }
        return null;
    }
    private Parameter stackParameter(Function f, int wantedOffset) {
        for (Parameter p : f.getParameters()) {
            try { if (!p.isAutoParameter() && p.isStackVariable() && p.getStackOffset() == wantedOffset) return p; }
            catch (Exception ignored) { }
        }
        return null;
    }
    private boolean isDefault(Parameter p) { return p.getName() != null && p.getName().matches("param_[0-9]+"); }
    private String formals(Function f) {
        StringBuilder b = new StringBuilder();
        for (Parameter p : f.getParameters()) {
            if (b.length() != 0) b.append(", ");
            b.append(p.getName()).append(" : ").append(p.getDataType().getName());
        }
        return b.length() == 0 ? "none" : b.toString();
    }
    private String roomThirdArgument(Function caller, Function target) throws Exception {
        DecompileResults r = decompiler.decompileFunction(caller, 45, monitor);
        HighFunction hf = r == null ? null : r.getHighFunction(); if (hf == null) return "<decompilation unavailable>";
        Iterator<PcodeOpAST> it = hf.getPcodeOps();
        while (it.hasNext()) {
            PcodeOpAST op = it.next(); if (op.getOpcode() != PcodeOp.CALL || op.getNumInputs() < 4) continue;
            Varnode dst = op.getInput(0);
            if (dst == null || !dst.isAddress() || !target.getEntryPoint().equals(dst.getAddress())) continue;
            Varnode response = op.getInput(3);
            String storage = stackLoadOffset(response);
            return describe(response) + (storage == null ? "" : " [stack +0x" + storage + "]");
        }
        return "<no direct Room-import call>";
    }
    private String stackLoadOffset(Varnode v) {
        if (v == null || v.getDef() == null || v.getDef().getOpcode() != PcodeOp.LOAD || v.getDef().getNumInputs() < 2) return null;
        Varnode address = v.getDef().getInput(1);
        if (address == null || address.getDef() == null || address.getDef().getOpcode() != PcodeOp.CAST) return null;
        Varnode castIn = address.getDef().getInput(0);
        if (castIn == null || castIn.getDef() == null || castIn.getDef().getOpcode() != PcodeOp.INT_ADD) return null;
        Varnode offset = castIn.getDef().getInput(1);
        return offset != null && offset.isConstant() ? Long.toHexString(offset.getOffset() & 0xffffffffL) : null;
    }
    private String describe(Varnode v) {
        if (v == null) return "<null>";
        HighVariable high = v.getHigh();
        return high != null && high.getName() != null ? high.getName() + " : " + high.getDataType().getName() : v.toString();
    }
    private void append(Function f, String text) {
        try {
            String old = getPlateComment(f.getEntryPoint()); if (old != null && old.indexOf(text) >= 0) return;
            setPlateComment(f.getEntryPoint(), old == null || old.length() == 0 ? text : old + "\n\n" + text); commentsAdded++;
        } catch (Exception ignored) { }
    }
}
