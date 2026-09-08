// Creatures Map Editor 1.08 — residual compact-model state recovery.
//
// This is a data-flow pass, not a source-analogue naming pass. It examines the
// current decompilation of recovered Room and MetaRoom methods for direct
// +0x40 / +0x44 accesses, respectively. These are the two private model fields
// still deliberately neutral after the CA, geometry and serialisation passes.
//
// It records exact access provenance on the existing member. A name is promoted
// only if both text serializers access that member; otherwise neutral names are
// retained and the report supplies the evidence for the next semantic pass.
//
// @category C2E Map Editor

import java.io.*;
import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;

public class RefineC2EMapEditorResidualModelState extends GhidraScript {
    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");
    private static final long ROOM_STATE_OFFSET = 0x40;
    private static final long METAROOM_STATE_OFFSET = 0x44;

    private static class Evidence {
        final String owner; final long offset;
        final LinkedHashSet<String> readers = new LinkedHashSet<String>();
        final LinkedHashSet<String> writers = new LinkedHashSet<String>();
        final LinkedHashSet<String> addressOnly = new LinkedHashSet<String>();
        int decompiled, failures;
        Evidence(String owner, long offset) { this.owner = owner; this.offset = offset; }
        boolean seen(String function) { return readers.contains(function) || writers.contains(function) || addressOnly.contains(function); }
    }

    private DecompInterface decompiler;
    private DataTypeManager dtm;
    private int commentsUpdated, namesPromoted;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) { popup("Open the 32-bit recovered MapEditor.exe program first."); return; }
        Function roomCtor = currentProgram.getFunctionManager().getFunctionAt(toAddr(0x00419d40L));
        Function metaCtor = currentProgram.getFunctionManager().getFunctionAt(toAddr(0x004152d0L));
        if (roomCtor == null || metaCtor == null ||
            !roomCtor.getName().startsWith("C2EEditorRoom_") ||
            !metaCtor.getName().startsWith("C2EEditorMetaRoom_")) {
            popup("Recovered MapEditor Room/MetaRoom entry points are missing. No changes made.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();
        decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(false); decompiler.toggleSyntaxTree(true); decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }

        println("=== C2E Map Editor: residual model-state recovery ===");
        println("Read/write provenance is collected from current decompiler P-code.");
        Evidence room = scan("C2EEditorRoom_", ROOM_STATE_OFFSET, "C2EEditorRoom");
        Evidence meta = scan("C2EEditorMetaRoom_", METAROOM_STATE_OFFSET, "C2EEditorMetaRoom");
        printAssignmentContext("C2EEditorRoom_ReadFromGame", "state40");
        printCallContexts("C2EEditorRoom_ReadFromGame");
        printCallerChain("C2EEditorRoom_ReadFromGame", 3, new LinkedHashSet<Function>());
        updateMemberComment("C2EEditorRoom", ROOM_STATE_OFFSET, "state40", room);
        updateMemberComment("C2EEditorMetaRoom", METAROOM_STATE_OFFSET, "state44", meta);
        promoteIfSerializerState("C2EEditorRoom", ROOM_STATE_OFFSET, "state40", room, "serializedState", "Persistent compact-editor state directly read and written by the .2er serializers.");
        promoteIfSerializerState("C2EEditorMetaRoom", METAROOM_STATE_OFFSET, "state44", meta, "serializedState", "Persistent compact-editor state directly read and written by the .2er serializers.");
        writeReport(room, meta); printEvidence(room); printEvidence(meta);
        println("\n=== Residual model-state summary ===");
        println("Decompiler bodies examined: " + (room.decompiled + meta.decompiled));
        println("Decompiler failures:         " + (room.failures + meta.failures));
        println("Field comments updated:      " + commentsUpdated);
        println("Names promoted:              " + namesPromoted);
        println("Report: " + reportPath());
        println("No source-analogue names were applied.");
    }

    private Evidence scan(String prefix, long offset, String owner) throws Exception {
        Evidence e = new Evidence(owner, offset);
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) {
            monitor.checkCancelled(); Function f = it.next(); if (!f.getName().startsWith(prefix)) continue;
            e.decompiled++;
            try { DecompileResults r = decompiler.decompileFunction(f, 30, monitor); HighFunction hf = r == null ? null : r.getHighFunction(); if (hf == null) { e.failures++; continue; } inspectPcode(f, hf, e); }
            catch (Exception ex) { e.failures++; }
        }
        return e;
    }

    private void inspectPcode(Function f, HighFunction hf, Evidence e) {
        Iterator<PcodeOpAST> it = hf.getPcodeOps();
        while (it.hasNext()) {
            PcodeOpAST op = it.next(); int code = op.getOpcode();
            if ((code != PcodeOp.PTRSUB && code != PcodeOp.INT_ADD) || !hasOffsetConstant(op, e.offset)) continue;
            Varnode out = op.getOutput(); if (out == null) { e.addressOnly.add(f.getName()); continue; }
            boolean read = false, write = false;
            Iterator<PcodeOp> uses = out.getDescendants();
            while (uses.hasNext()) {
                PcodeOp use = uses.next();
                if (use.getOpcode() == PcodeOp.LOAD) read = true;
                else if (use.getOpcode() == PcodeOp.STORE) write = true;
            }
            if (read) e.readers.add(f.getName()); if (write) e.writers.add(f.getName()); if (!read && !write) e.addressOnly.add(f.getName());
        }
    }

    private boolean hasOffsetConstant(PcodeOp op, long wanted) {
        for (int i = 0; i < op.getNumInputs(); i++) { Varnode in = op.getInput(i); if (in != null && in.isConstant() && in.getOffset() == wanted) return true; }
        return false;
    }

    private void updateMemberComment(String typeName, long offset, String expectedName, Evidence e) {
        Structure s = structure(typeName); if (s == null) return;
        DataTypeComponent c = s.getComponentContaining((int)offset);
        if (c == null || c.getOffset() != (int)offset || !expectedName.equals(c.getFieldName())) return;
        String comment = "Direct P-code provenance: readers=" + join(e.readers) + "; writers=" + join(e.writers) + "; address-only=" + join(e.addressOnly) + ". Neutral name retained unless an independent semantic contract is proven.";
        if (!comment.equals(c.getComment())) { c.setComment(comment); commentsUpdated++; }
    }

    private void promoteIfSerializerState(String typeName, long offset, String oldName, Evidence e, String newName, String comment) {
        if (!e.seen(typeName + "_Read2ER") || !e.seen(typeName + "_Write2ER")) return;
        Structure s = structure(typeName); if (s == null) return; DataTypeComponent c = s.getComponentContaining((int)offset);
        if (c == null || c.getOffset() != (int)offset || !oldName.equals(c.getFieldName())) return;
        try { s.replaceAtOffset((int)offset, c.getDataType(), c.getLength(), newName, comment); namesPromoted++; println("[name] " + typeName + "+0x" + Long.toHexString(offset) + " -> " + newName); }
        catch (Exception ignored) { }
    }

    private Structure structure(String name) { DataType t = dtm.getDataType(CAT, name); if (t instanceof Structure) return (Structure)t; t = dtm.getDataType(name); return t instanceof Structure ? (Structure)t : null; }
    private void printEvidence(Evidence e) { println("\n[" + e.owner + "+0x" + Long.toHexString(e.offset) + "]"); println("  readers:      " + join(e.readers)); println("  writers:      " + join(e.writers)); println("  address-only: " + join(e.addressOnly)); }
    private void printAssignmentContext(String functionName, String memberName) {
        Function f = null; FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        while (it.hasNext()) { Function candidate = it.next(); if (functionName.equals(candidate.getName())) { f = candidate; break; } }
        if (f == null) { println("[context] " + functionName + " not found"); return; }
        try {
            decompiler.toggleCCode(true);
            DecompileResults r = decompiler.decompileFunction(f, 30, monitor);
            String c = r == null || r.getDecompiledFunction() == null ? null : r.getDecompiledFunction().getC();
            decompiler.toggleCCode(false);
            if (c == null) { println("[context] no C output for " + functionName); return; }
            String[] lines = c.split("\\r?\\n"); boolean shown = false;
            for (int i = 0; i < lines.length; i++) if (lines[i].indexOf(memberName) >= 0) {
                println("[context] " + functionName + ": " + lines[i].trim()); shown = true;
            }
            if (!shown) println("[context] no decompiled " + memberName + " line in " + functionName);
        }
        catch (Exception e) { println("[context] " + e.getMessage()); }
    }
    private void printCallContexts(String functionName) {
        Function target = null; FunctionIterator all = currentProgram.getFunctionManager().getFunctions(true);
        while (all.hasNext()) { Function candidate = all.next(); if (functionName.equals(candidate.getName())) { target = candidate; break; } }
        if (target == null) return;
        LinkedHashSet<Function> callers = new LinkedHashSet<Function>();
        ghidra.program.model.symbol.ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target.getEntryPoint());
        while (refs.hasNext()) { Function caller = currentProgram.getFunctionManager().getFunctionContaining(refs.next().getFromAddress()); if (caller != null) callers.add(caller); }
        for (Function caller : callers) {
            try {
                decompiler.toggleCCode(true);
                DecompileResults r = decompiler.decompileFunction(caller, 30, monitor);
                String c = r == null || r.getDecompiledFunction() == null ? null : r.getDecompiledFunction().getC();
                decompiler.toggleCCode(false);
                if (c == null) continue;
                String[] lines = c.split("\\r?\\n"); boolean shown = false;
                for (int i = 0; i < lines.length; i++) if (lines[i].indexOf(functionName) >= 0) {
                    println("[caller] " + caller.getName() + ": " + lines[i].trim()); shown = true;
                }
                if (!shown) println("[caller] " + caller.getName() + ": call present but not rendered by name");
            }
            catch (Exception e) { println("[caller] " + caller.getName() + ": " + e.getMessage()); }
        }
    }
    private void printCallerChain(String functionName, int depth, Set<Function> visited) {
        if (depth < 1) return;
        Function target = null; FunctionIterator all = currentProgram.getFunctionManager().getFunctions(true);
        while (all.hasNext()) { Function candidate = all.next(); if (functionName.equals(candidate.getName())) { target = candidate; break; } }
        if (target != null) printCallerChain(target, depth, visited);
    }
    private void printCallerChain(Function target, int depth, Set<Function> visited) {
        if (depth < 1 || !visited.add(target)) return;
        ghidra.program.model.symbol.ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target.getEntryPoint());
        while (refs.hasNext()) {
            Function caller = currentProgram.getFunctionManager().getFunctionContaining(refs.next().getFromAddress());
            if (caller == null || caller == target) continue;
            println("[chain] " + caller.getName() + " (" + caller.getEntryPoint() + ") -> " + target.getName());
            printCallerChain(caller, depth - 1, visited);
        }
    }
    private String join(Collection<String> values) { if (values.isEmpty()) return "none"; StringBuilder b = new StringBuilder(); int n = 0; for (String v : values) { if (n++ != 0) b.append(", "); b.append(v); if (n == 12 && values.size() > n) { b.append(" ..."); break; } } return b.toString(); }
    private String reportPath() { return System.getProperty("user.home") + File.separator + "C2EMapEditor_residual_model_state.txt"; }
    private void writeReport(Evidence room, Evidence meta) { try { PrintWriter out = new PrintWriter(new FileWriter(reportPath())); out.println("C2E Map Editor 1.08 — residual compact-model state provenance\n"); writeEvidence(out, room); out.println(); writeEvidence(out, meta); out.close(); } catch (Exception e) { println("[report] " + e.getMessage()); } }
    private void writeEvidence(PrintWriter out, Evidence e) { out.println(e.owner + "+0x" + Long.toHexString(e.offset)); out.println("Readers: " + join(e.readers)); out.println("Writers: " + join(e.writers)); out.println("Address-only: " + join(e.addressOnly)); out.println("Decompiler bodies: " + e.decompiled + "; failures: " + e.failures); }
}
