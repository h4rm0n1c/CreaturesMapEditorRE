// Creatures Map Editor 1.08 — pass 23 v1.1: core member-signature sweep.
//
// This is a broad, idempotent decompiler pass over every already-named core
// model, geometry, tool, action and CAOS/IPC member.  Earlier passes only
// typed the functions they introduced; this one closes the gaps between them.
// It changes neither names, return values, ordinary arguments, data nor
// comments, and leaves manually-owned signatures untouched.
//
// @category C2E Map Editor

import java.util.*;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;

public class ApplyC2EMapEditorCoreThisTypes extends GhidraScript {

    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private static class Owner {
        final String functionPrefix;
        final String layout;
        Pointer pointer;

        Owner(String functionPrefix, String layout) {
            this.functionPrefix = functionPrefix;
            this.layout = layout;
        }
    }

    // Longest prefixes must come first: they are the most-derived ownership
    // claims available from the recovered names.
    private static final Owner[] OWNERS = {
        new Owner("CC2ERoomEditorDoc_", "CC2ERoomEditorDoc_refined"),
        new Owner("CC2ERoomEditorView_", "CC2ERoomEditorView_refined"),
        new Owner("C2EAddMetaRoomBackgroundAction_", "C2EAddMetaRoomBackgroundAction"),
        new Owner("C2EChangeMetaRoomBackgroundAction_", "C2EChangeMetaRoomBackgroundAction"),
        new Owner("C2ERemoveMetaRoomBackgroundAction_", "C2ERemoveMetaRoomBackgroundAction"),
        new Owner("C2ESetMetaRoomBackgroundAction_", "C2ESetMetaRoomBackgroundAction"),
        new Owner("C2EChangeWorldPropertiesAction_", "C2EChangeWorldPropertiesAction"),
        new Owner("C2ESetMetaRoomMusicAction_", "C2ESetMetaRoomMusicAction"),
        new Owner("C2EMoveMetaRoomAction_", "C2EMoveMetaRoomAction"),
        new Owner("C2ERemoveMetaRoomAction_", "C2ERemoveMetaRoomAction"),
        new Owner("C2EMoveRoomAction_", "C2EMoveRoomAction"),
        new Owner("C2ERemoveRoomAction_", "C2ERemoveRoomAction"),
        new Owner("C2ESetDoorOpeningAction_", "C2ESetDoorOpeningAction"),
        new Owner("C2ESetRoomPropertyAction_", "C2ESetRoomPropertyAction"),
        new Owner("C2ESetRoomMusicAction_", "C2ESetRoomMusicAction"),
        new Owner("C2EAddRoomAction_", "C2EAddRoomAction"),
        new Owner("C2EAddMetaRoomAction_", "C2EAddMetaRoomAction"),
        new Owner("C2EEditAction_", "C2EEditAction"),
        new Owner("C2EAddRoomTool_", "C2EAddRoomTool"),
        new Owner("C2EAddMetaroomTool_", "C2EAddMetaroomTool"),
        new Owner("C2ESelectMetaroomTool_", "C2ESelectMetaroomTool"),
        new Owner("C2ESelectTool_", "C2ESelectTool"),
        new Owner("C2EZoomTool_", "C2EZoomTool"),
        new Owner("C2ECheeseTool_", "C2ECheeseTool"),
        new Owner("C2EEditorToolBase_", "C2EEditorToolBase"),
        new Owner("C2EEditorTool_", "C2EEditorToolBase"),
        new Owner("C2EWorldModel_", "C2EWorldModel"),
        new Owner("C2EEditorMetaRoom_", "C2EEditorMetaRoom"),
        new Owner("C2EEditorRoom_", "C2EEditorRoom"),
        new Owner("C2ERoomGeometry_", "C2ERoomGeometry"),
        new Owner("C2ECAOSOutput_", "C2ECAOSOutput"),
        new Owner("C2EClientSide_", "C2EClientSide")
    };

    private Register ecx;
    private int candidates;
    private int applied;
    private int alreadyApplied;
    private int userOwned;
    private int varArgSkipped;
    private int otherSkipped;

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
        loadPointers();

        println("=== C2E Map Editor: core this-type sweep ===");
        FunctionIterator functions = currentProgram.getFunctionManager().getFunctions(true);
        while (functions.hasNext()) {
            monitor.checkCancelled();
            Function function = functions.next();
            Owner owner = ownerFor(function.getName());
            if (owner == null || owner.pointer == null) continue;
            candidates++;
            applyThis(function, owner);
        }
        if (applied != 0) analyzeChanges(currentProgram);

        println("\n=== Core this-type sweep summary ===");
        println("Named core members seen:  " + candidates);
        println("this types applied:       " + applied);
        println("this types already held:  " + alreadyApplied);
        println("User signatures kept:     " + userOwned);
        println("Variadic functions kept:  " + varArgSkipped);
        println("Other skips:              " + otherSkipped);
        println("No names, comments or data were changed.");
    }

    private void loadPointers() {
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        for (Owner owner : OWNERS) {
            DataType type = dtm.getDataType(CAT, owner.layout);
            if (type instanceof Structure) owner.pointer = new PointerDataType(type, dtm);
        }
    }

    private Owner ownerFor(String name) {
        for (Owner owner : OWNERS) {
            if (name.startsWith(owner.functionPrefix)) return owner;
        }
        return null;
    }

    private void applyThis(Function function, Owner owner) {
        try {
            if (function.hasVarArgs()) {
                varArgSkipped++;
                return;
            }
            Parameter[] old = function.getParameters();
            if (function.hasCustomVariableStorage() && old.length > 0 &&
                "this".equals(old[0].getName()) && old[0].getDataType() != null &&
                old[0].getDataType().isEquivalent(owner.pointer) &&
                old[0].getVariableStorage() != null && old[0].getVariableStorage().isRegisterStorage()) {
                alreadyApplied++;
                return;
            }
            if (function.hasCustomVariableStorage() && old.length > 0 &&
                old[0].getSource() == SourceType.USER_DEFINED && !"this".equals(old[0].getName())) {
                userOwned++;
                return;
            }
            ArrayList<Variable> parameters = new ArrayList<>();
            parameters.add(new ParameterImpl("this", owner.pointer, ecx, currentProgram,
                SourceType.USER_DEFINED));
            for (Parameter parameter : old) {
                if (parameter.isAutoParameter() || "this".equals(parameter.getName())) continue;
                VariableStorage storage = parameter.getVariableStorage();
                if (storage != null && storage.isRegisterStorage() && storage.getRegister() != null &&
                    storage.getRegister().equals(ecx)) continue;
                parameters.add(new ParameterImpl(parameter, currentProgram));
            }
            function.updateFunction("__thiscall", null, parameters,
                Function.FunctionUpdateType.CUSTOM_STORAGE, true, SourceType.USER_DEFINED);
            applied++;
            println("[this] " + function.getEntryPoint() + " " + function.getName() +
                " -> " + owner.layout + " * @ ECX");
        }
        catch (Exception e) {
            otherSkipped++;
            println("[skip] " + function.getEntryPoint() + " " + function.getName() + ": " + e.getMessage());
        }
    }
}
