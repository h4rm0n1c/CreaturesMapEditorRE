// Creatures Map Editor 1.08 — live-game import parameter recovery.
//
// This pass promotes only the room-ID argument proven by the current
// decompiler: C2EEditorRoom_ReadFromGame stores param_2 at Room+0x40, and
// C2EEditorMetaRoom_ReadRoomFromGame directly calls it as part of the ERID
// import loop.  It intentionally does not name the local response helpers.
//
// @category C2E Map Editor

import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorGameImportParameters extends GhidraScript {
    private DecompInterface decompiler;
    private int parametersRenamed, namesKept, commentsAdded, skipped;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function bridge = functionNamed("C2EEditorMetaRoom_ReadRoomFromGame");
        Function room = functionNamed("C2EEditorRoom_ReadFromGame");
        if (bridge == null || room == null) {
            popup("Run pass 25 first; the recovered per-room import bridge is missing."); return;
        }
        decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(false); decompiler.toggleSyntaxTree(true);
        decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }

        println("=== C2E Map Editor: live-game import parameter recovery ===");
        boolean bridgeCallsRoom = directCallees(bridge).contains(room);
        String stateWriter = state40Writer(room);
        println("Per-room bridge -> Room importer: " + bridgeCallsRoom);
        println("Room+0x40 writer: " + (stateWriter == null ? "none" : stateWriter));

        // This exact expression was established by pass 24 after full analysis.
        // Do not infer a name from a bare function address or parameter ordinal.
        if (!bridgeCallsRoom || !"param_2".equals(stateWriter)) {
            skipped++;
            println("[skip] Expected Room.state40 = param_2 import dataflow not present; no parameter renamed.");
        } else {
            Parameter p = parameterNamed(room, "param_2");
            if (p == null) {
                skipped++;
                println("[skip] Room importer has no formal param_2; no change made.");
            } else if (!isDefaultParameterName(p)) {
                namesKept++;
                println("[keep] " + room.getEntryPoint() + " " + p.getName());
            } else {
                try {
                    p.setName("gameRoomId", SourceType.USER_DEFINED);
                    parametersRenamed++;
                    println("[param] " + room.getEntryPoint() + " param_2 -> gameRoomId");
                } catch (Exception e) {
                    skipped++; println("[skip] parameter rename: " + e.getMessage());
                }
            }
            append(room, "[C2E live-game import parameter] gameRoomId is supplied by the metaroom ERID loop. " +
                "This importer writes it to Room.state40; recovered Room methods do not consume state40, " +
                "so it is not promoted to a persistent Room-ID field.");
        }

        println("\n=== Live-game import parameter summary ===");
        println("Parameters renamed: " + parametersRenamed);
        println("Existing names kept: " + namesKept);
        println("Comments added:     " + commentsAdded);
        println("Ambiguous skipped:  " + skipped);
        println("Run Auto Analyze after this pass.");
    }

    private Function functionNamed(String name) {
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) { Function f = it.next(); if (name.equals(f.getName())) return f; }
        return null;
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

    // The current normalized decompilation is the evidence retained by pass 24:
    //     this->state40 = param_2;
    // It is deliberately used as an exact guard, rather than reconstructing a
    // potentially simplified STORE/PTRSUB expression from P-code.
    private String state40Writer(Function f) throws Exception {
        decompiler.toggleCCode(true);
        DecompileResults r = decompiler.decompileFunction(f, 30, monitor);
        try {
            String c = r == null || r.getDecompiledFunction() == null ? null : r.getDecompiledFunction().getC();
            if (c == null) return null;
            if (c.matches("(?s).*\\bstate40\\s*=\\s*param_2\\s*;.*")) return "param_2";
        } finally {
            decompiler.toggleCCode(false);
        }
        return null;
    }

    private boolean isDefaultParameterName(Parameter p) {
        return p.getName() != null && p.getName().matches("param_[0-9]+");
    }

    private Parameter parameterNamed(Function f, String wanted) {
        for (Parameter p : f.getParameters()) {
            if (wanted.equals(p.getName())) return p;
        }
        return null;
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
