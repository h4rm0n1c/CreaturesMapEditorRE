// Creatures Map Editor 1.08 — live-game CAOS response-buffer recovery.
//
// The released C3/DS ClientSide implementation establishes that an IPC reply
// lives in a const unsigned-char buffer returned by GetResultBuffer().  This
// pass does not assume that the Map Editor's FormatAndRun wrapper exposes that
// buffer directly: it checks the current bridge P-code and the Room-importer
// C before replacing the deliberately neutral void * parameter.
//
// @category C2E Map Editor

import java.io.*;
import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorCAOSResponseBuffer extends GhidraScript {
    private DecompInterface decompiler;
    private int typesApplied, typesKept, commentsAdded, skipped;
    private PrintWriter report;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function bridge = functionNamed("C2EEditorMetaRoom_ReadRoomFromGame");
        Function room = functionNamed("C2EEditorRoom_ReadFromGame");
        Function format = functionNamed("C2ECAOSOutput_FormatAndRun");
        Function getBuffer = functionNamed("C2EClientSide_GetResultBuffer");
        if (bridge == null || room == null || format == null || getBuffer == null) {
            popup("Run passes 16 and 25–27 first; the IPC/import chain is incomplete."); return;
        }
        decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(true); decompiler.toggleSyntaxTree(true);
        decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }

        File output = new File(System.getProperty("user.home"), "C2EMapEditor_caos_response_buffer.txt");
        report = new PrintWriter(new FileWriter(output));
        try {
            println("=== C2E Map Editor: CAOS response-buffer recovery ===");
            println("Report: " + output.getAbsolutePath());
            reportln("=== C2E Map Editor: CAOS response-buffer recovery ===");

            Parameter response = parameterNamed(room, "caosResponse");
            boolean bridgeCallsFormat = directCallees(bridge).contains(format);
            boolean bridgeCallsRoom = directCallees(bridge).contains(room);
            boolean formatUsesClientBuffer = directCallees(format).contains(getBuffer);
            String roomC = decompileC(room);
            String bridgeC = decompileC(bridge);
            String responseUse = relevantLines(roomC, "caosResponse");
            String bridgeLines = relevantLines(bridgeC, "C2ECAOSOutput_FormatAndRun|C2EEditorRoom_ReadFromGame|C2EClientSide_GetResultBuffer");
            String callFlow = roomCallFlow(bridge, room);

            line("Bridge -> FormatAndRun: " + bridgeCallsFormat);
            line("Bridge -> Room importer: " + bridgeCallsRoom);
            line("FormatAndRun -> GetResultBuffer: " + formatUsesClientBuffer);
            line("Room response formal: " + (response == null ? "missing" : response.getName() + " : " + response.getDataType().getName()));
            line("\nRoom importer uses of caosResponse:\n" + responseUse);
            line("\nBridge calls/argument provenance:\n" + callFlow);
            line("\nRelevant bridge C:\n" + bridgeLines);

            // A source match alone is not enough: a wrapper could copy the reply into
            // CString.  Promote only when the current Room C treats the exact formal
            // as a byte/char buffer and the bridge directly participates in the IPC
            // query -> room-import sequence.
            boolean roomTreatsAsBuffer = responseUse.matches("(?s).*(char|byte|unsigned|\\[|\\*).*" );
            boolean currentChain = bridgeCallsFormat && bridgeCallsRoom && formatUsesClientBuffer;
            if (response == null || !currentChain || !roomTreatsAsBuffer) {
                skipped++;
                line("\n[skip] No exact current-binary proof that caosResponse is the ClientSide result buffer; type unchanged.");
            } else if (!isVoidPointer(response.getDataType())) {
                typesKept++;
                line("\n[keep] " + response.getName() + " already has " + response.getDataType().getName());
            } else {
                try {
                    DataType charPointer = new PointerDataType(CharDataType.dataType, currentProgram.getDataTypeManager());
                    response.setDataType(charPointer, SourceType.USER_DEFINED);
                    typesApplied++;
                    line("\n[type] " + room.getEntryPoint() + " caosResponse -> char *");
                    append(room, "[C2E live-game response buffer] caosResponse is the textual RTYP/RLOC reply buffer supplied by the ClientSide IPC transaction. The original ClientSide API returns a const unsigned-char result buffer; MapEditor parses it as text.");
                } catch (Exception e) { skipped++; line("\n[skip] parameter type: " + e.getMessage()); }
            }

            println("\n=== CAOS response-buffer summary ===");
            println("Types applied:  " + typesApplied);
            println("Types kept:     " + typesKept);
            println("Comments added: " + commentsAdded);
            println("Skipped:        " + skipped);
            println("Run Auto Analyze only if a type was applied.");
        } finally {
            if (report != null) report.close();
            decompiler.dispose();
        }
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
    private boolean isVoidPointer(DataType type) {
        return type instanceof Pointer && ((Pointer) type).getDataType() instanceof VoidDataType;
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
    private String decompileC(Function f) throws Exception {
        DecompileResults r = decompiler.decompileFunction(f, 45, monitor);
        return r == null || r.getDecompiledFunction() == null ? "<decompilation unavailable>" : r.getDecompiledFunction().getC();
    }
    private String relevantLines(String c, String expression) {
        StringBuilder out = new StringBuilder();
        for (String line : c.split("\\r?\\n")) if (line.matches("(?s).*" + expression + ".*")) out.append(line.trim()).append('\n');
        return out.length() == 0 ? "<none>" : out.toString();
    }
    private String roomCallFlow(Function caller, Function target) throws Exception {
        DecompileResults r = decompiler.decompileFunction(caller, 45, monitor);
        HighFunction hf = r == null ? null : r.getHighFunction(); if (hf == null) return "<decompilation unavailable>";
        Iterator<PcodeOpAST> it = hf.getPcodeOps();
        while (it.hasNext()) {
            PcodeOpAST op = it.next(); if (op.getOpcode() != PcodeOp.CALL || op.getNumInputs() < 4) continue;
            Varnode dst = op.getInput(0);
            if (dst == null || !dst.isAddress() || !target.getEntryPoint().equals(dst.getAddress())) continue;
            StringBuilder out = new StringBuilder();
            out.append(op.getSeqnum().getTarget()).append(" -> ").append(target.getName()).append('\n');
            for (int i = 1; i < op.getNumInputs(); i++) out.append("  arg ").append(i).append(": ").append(describe(op.getInput(i), 0, new HashSet<Varnode>())).append('\n');
            return out.toString();
        }
        return "<no direct Room-import call>";
    }
    private String describe(Varnode v, int depth, Set<Varnode> seen) {
        if (v == null) return "<null>";
        HighVariable h = v.getHigh();
        String base = h != null && h.getName() != null ? h.getName() + " [" + h.getDataType().getName() + "]" : v.toString();
        if (depth >= 4 || !seen.add(v) || v.getDef() == null) return base;
        PcodeOp def = v.getDef();
        StringBuilder out = new StringBuilder(base).append(" <- ").append(def.getMnemonic()).append('(');
        for (int i = 0; i < def.getNumInputs(); i++) { if (i != 0) out.append(", "); out.append(describe(def.getInput(i), depth + 1, seen)); }
        return out.append(')').toString();
    }
    private void append(Function f, String text) {
        try {
            String old = getPlateComment(f.getEntryPoint()); if (old != null && old.indexOf(text) >= 0) return;
            setPlateComment(f.getEntryPoint(), old == null || old.length() == 0 ? text : old + "\n\n" + text); commentsAdded++;
        } catch (Exception ignored) { }
    }
    private void line(String s) { println(s); reportln(s); }
    private void reportln(String s) { report.println(s); report.flush(); }
}
