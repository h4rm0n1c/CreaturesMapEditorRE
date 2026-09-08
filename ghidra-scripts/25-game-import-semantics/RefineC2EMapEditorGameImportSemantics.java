// Creatures Map Editor 1.08 — live-game import semantic recovery.
//
// Validates the exact World -> MetaRoom -> per-room reader -> Room ReadFromGame
// chain. The per-room reader is named only when it is MetaRoom-private, issues
// the recovered CAOS request, and passes the response into the Room importer.
//
// @category C2E Map Editor

import java.io.*;
import java.util.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorGameImportSemantics extends GhidraScript {
    private DecompInterface decompiler;
    private int commentsAdded, renamed, kept, skipped;

    @Override public void run() throws Exception {
        if (currentProgram == null || currentProgram.getDefaultPointerSize() != 4) {
            popup("Open the 32-bit recovered MapEditor.exe program first."); return;
        }
        Function world = functionNamed("C2EWorldModel_ReadFromGame");
        Function meta = functionNamed("C2EEditorMetaRoom_ReadFromGame");
        Function room = functionNamed("C2EEditorRoom_ReadFromGame");
        Function adapter = currentProgram.getFunctionManager().getFunctionAt(toAddr(0x004177adL));
        if (world == null || meta == null || room == null || adapter == null) {
            popup("The recovered ReadFromGame chain is incomplete. No changes made."); return;
        }

        decompiler = new DecompInterface();
        decompiler.setOptions(new DecompileOptions());
        decompiler.toggleCCode(false); decompiler.toggleSyntaxTree(true);
        decompiler.setSimplificationStyle("decompile");
        if (!decompiler.openProgram(currentProgram)) { popup("Could not initialise the Ghidra decompiler."); return; }

        println("=== C2E Map Editor: live-game import semantic recovery ===");
        Set<Function> adapterCallees = directCallees(adapter);
        Set<Function> adapterCallers = directCallers(adapter);
        Set<Function> metaCallers = directCallers(meta);
        boolean adapterCallsRoom = adapterCallees.contains(room);
        Function caosRun = functionNamed("C2ECAOSOutput_FormatAndRun");
        boolean adapterCallsCAOS = caosRun != null && adapterCallees.contains(caosRun);
        boolean metaCallsAdapter = adapterCallers.contains(meta);
        boolean worldCallsMeta = metaCallers.contains(world);

        println("World -> MetaRoom: " + worldCallsMeta);
        println("MetaRoom -> adapter: " + metaCallsAdapter);
        println("Adapter -> Room: " + adapterCallsRoom);
        println("Adapter -> CAOS output: " + adapterCallsCAOS);
        println("Adapter direct callees: " + names(adapterCallees));
        println("Adapter direct callers: " + names(adapterCallers));

        if (worldCallsMeta && metaCallsAdapter && adapterCallsRoom && adapterCallsCAOS && adapterCallers.size() == 1) {
            renameAdapter(adapter);
            append(adapter, "[C2E live-game room reader]\n" +
                "MetaRoom-private per-room reader. It issues a CAOS query through C2ECAOSOutput_FormatAndRun, " +
                "parses one game-room response, then passes that record to C2EEditorRoom_ReadFromGame. " +
                "The imported second argument is retained in Room.state40, but no recovered Room method consumes it.");
            append(meta, "[C2E live-game import chain] Iterates game room IDs through " + adapter.getName() + " before inserting the resulting compact Rooms into the metaroom.");
            append(world, "[C2E live-game import chain] Delegates each parsed metaroom and its room records to C2EEditorMetaRoom_ReadFromGame.");
        } else {
            skipped++;
            println("[skip] Per-room reader evidence is incomplete; no name applied.");
        }

        writeReport(world, meta, adapter, room, adapterCallees, adapterCallers, metaCallers);
        println("\n=== Live-game import summary ===");
        println("Adapter renamed:  " + renamed);
        println("Names kept:       " + kept);
        println("Comments added:   " + commentsAdded);
        println("Ambiguous skipped: " + skipped);
        println("Report: " + System.getProperty("user.home") + File.separator + "C2EMapEditor_game_import_chain.txt");
        println("No data or speculative field names were changed.");
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
            Varnode destination = op.getInput(0); if (destination == null || !destination.isAddress()) continue;
            Function callee = currentProgram.getFunctionManager().getFunctionAt(destination.getAddress());
            if (callee != null) result.add(callee);
        }
        return result;
    }

    private Set<Function> directCallers(Function target) {
        LinkedHashSet<Function> result = new LinkedHashSet<Function>();
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(target.getEntryPoint());
        while (refs.hasNext()) { Function f = currentProgram.getFunctionManager().getFunctionContaining(refs.next().getFromAddress()); if (f != null) result.add(f); }
        return result;
    }

    private void renameAdapter(Function adapter) {
        String wanted = "C2EEditorMetaRoom_ReadRoomFromGame";
        try {
            if (adapter.getName().startsWith("FUN_") || adapter.getSymbol().getSource() == SourceType.DEFAULT) {
                adapter.setName(wanted, SourceType.USER_DEFINED); renamed++;
                println("[name] " + adapter.getEntryPoint() + " -> " + wanted);
            } else kept++;
        } catch (Exception e) { skipped++; println("[skip] rename: " + e.getMessage()); }
    }

    private void append(Function f, String text) {
        try {
            String old = getPlateComment(f.getEntryPoint());
            if (old != null && old.indexOf(text) >= 0) return;
            setPlateComment(f.getEntryPoint(), old == null || old.length() == 0 ? text : old + "\n\n" + text);
            commentsAdded++;
        } catch (Exception ignored) { }
    }

    private String names(Collection<Function> fs) {
        if (fs.isEmpty()) return "none"; StringBuilder b = new StringBuilder();
        for (Function f : fs) { if (b.length() != 0) b.append(", "); b.append(f.getName()); } return b.toString();
    }

    private void writeReport(Function world, Function meta, Function adapter, Function room,
                             Set<Function> adapterCallees, Set<Function> adapterCallers, Set<Function> metaCallers) {
        try {
            PrintWriter out = new PrintWriter(new FileWriter(System.getProperty("user.home") + File.separator + "C2EMapEditor_game_import_chain.txt"));
            out.println("C2E Map Editor 1.08 — live-game import call chain");
            out.println(world.getName() + " -> " + meta.getName() + " -> " + adapter.getName() + " -> " + room.getName());
            out.println("Adapter callees: " + names(adapterCallees));
            out.println("Adapter callers: " + names(adapterCallers));
            out.println("MetaRoom callers: " + names(metaCallers));
            out.close();
        } catch (Exception e) { println("[report] " + e.getMessage()); }
    }
}
