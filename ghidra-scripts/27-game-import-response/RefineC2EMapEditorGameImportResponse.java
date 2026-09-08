// Creatures Map Editor 1.08 — live-game room-response parameter recovery.
//
// Completes the proven ReadRoomFromGame -> C2ECAOSOutput_FormatAndRun ->
// C2EEditorRoom_ReadFromGame chain by naming the untyped third Room-import
// argument.
// The exact RTYP/RLOC request and the existing gameRoomId argument distinguish
// this from an arbitrary CString parameter.
//
// @category C2E Map Editor

import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorGameImportResponse extends GhidraScript {
    private DecompInterface decompiler;
    private int renamed, kept, commentsAdded, skipped;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function bridge = functionNamed("C2EEditorMetaRoom_ReadRoomFromGame");
        Function room = functionNamed("C2EEditorRoom_ReadFromGame");
        Function formatAndRun = functionNamed("C2ECAOSOutput_FormatAndRun");
        if (bridge == null || room == null || formatAndRun == null) {
            popup("Run passes 25 and 26 first; the recovered live-game import chain is incomplete."); return;
        }
        decompiler = new DecompInterface(); decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(false); decompiler.toggleSyntaxTree(true); decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }

        println("=== C2E Map Editor: live-game room-response recovery ===");
        Set<Function> bridgeCalls = directCallees(bridge);
        boolean formatAndRunThenRoom = bridgeCalls.contains(formatAndRun) && bridgeCalls.contains(room);
        Parameter response = parameterNamed(room, "param_3");
        Parameter roomId = parameterNamed(room, "gameRoomId");
        println("Bridge -> CAOS output and Room importer: " + formatAndRunThenRoom);
        println("Room importer formal parameters: " + parameters(room));
        println("Bridge -> Room call operands: " + roomCallOperands(bridge, room));
        println("Room importer response parameter: " + (response == null ? "missing" : response.getName()));
        println("Room importer ID parameter: " + (roomId == null ? "missing" : roomId.getName()));

        if (!formatAndRunThenRoom || roomId == null || response == null) {
            skipped++; println("[skip] The output-to-Room import chain is incomplete; no parameter renamed.");
        } else if (!isDefault(response)) {
            kept++; println("[keep] " + room.getEntryPoint() + " " + response.getName());
        } else {
            try {
                response.setName("caosResponse", SourceType.USER_DEFINED);
                renamed++; println("[param] " + room.getEntryPoint() + " param_3 -> caosResponse");
                append(room, "[C2E live-game room response] caosResponse is the RTYP/RLOC reply produced by " +
                    "C2EEditorMetaRoom_ReadRoomFromGame through C2ECAOSOutput_FormatAndRun. " +
                    "It is decoded with gameRoomId to populate the compact Room geometry and type.");
            } catch (Exception e) { skipped++; println("[skip] parameter rename: " + e.getMessage()); }
        }

        println("\n=== Live-game room-response summary ===");
        println("Parameters renamed: " + renamed);
        println("Existing names kept: " + kept);
        println("Comments added:     " + commentsAdded);
        println("Ambiguous skipped:  " + skipped);
        println("Run Auto Analyze after this pass.");
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
    private boolean isDefault(Parameter p) { return p.getName() != null && p.getName().matches("param_[0-9]+"); }
    private String parameters(Function f) {
        StringBuilder b = new StringBuilder();
        for (Parameter p : f.getParameters()) {
            if (b.length() != 0) b.append(", ");
            b.append(p.getName()).append(" : ").append(p.getDataType().getName());
            if (p.isAutoParameter()) b.append(" [auto]");
        }
        return b.length() == 0 ? "none" : b.toString();
    }
    private Set<Function> directCallees(Function f) throws Exception {
        LinkedHashSet<Function> result = new LinkedHashSet<Function>();
        DecompileResults r = decompiler.decompileFunction(f, 30, monitor);
        HighFunction hf = r == null ? null : r.getHighFunction(); if (hf == null) return result;
        Iterator<PcodeOpAST> it = hf.getPcodeOps();
        while (it.hasNext()) {
            PcodeOpAST op = it.next(); if (op.getOpcode() != PcodeOp.CALL || op.getNumInputs() < 1) continue;
            Varnode dst = op.getInput(0); if (dst == null || !dst.isAddress()) continue;
            Function callee = currentProgram.getFunctionManager().getFunctionAt(dst.getAddress());
            if (callee != null) result.add(callee);
        }
        return result;
    }
    private String roomCallOperands(Function caller, Function target) throws Exception {
        DecompileResults r = decompiler.decompileFunction(caller, 30, monitor);
        HighFunction hf = r == null ? null : r.getHighFunction(); if (hf == null) return "no decompilation";
        Iterator<PcodeOpAST> it = hf.getPcodeOps();
        while (it.hasNext()) {
            PcodeOpAST op = it.next(); if (op.getOpcode() != PcodeOp.CALL || op.getNumInputs() < 1) continue;
            Varnode dst = op.getInput(0);
            if (dst == null || !dst.isAddress() || !target.getEntryPoint().equals(dst.getAddress())) continue;
            StringBuilder b = new StringBuilder();
            for (int i = 1; i < op.getNumInputs(); i++) {
                if (i != 1) b.append(", ");
                Varnode arg = op.getInput(i); HighVariable high = arg == null ? null : arg.getHigh();
                b.append(high != null && high.getName() != null ? high.getName() : String.valueOf(arg));
            }
            return b.toString();
        }
        return "no direct P-code call";
    }
    private void append(Function f, String text) {
        try {
            String old = getPlateComment(f.getEntryPoint());
            if (old != null && old.indexOf(text) >= 0) return;
            setPlateComment(f.getEntryPoint(), old == null || old.length() == 0 ? text : old + "\n\n" + text);
            commentsAdded++;
        } catch (Exception ignored) { }
    }
}
