// Recover candidate class/member layouts from the Creatures Map Editor 1.08
// after RecoverC2EMapEditorMFC.java has named the MFC message handlers.
//
// Target: MapEditor.exe, 32-bit VC6 / MFC42.
//
// This pass is intentionally evidence-first:
//   * scans recovered member handlers with Ghidra's decompiler/P-code;
//   * follows ECX ("this") through COPY/CAST/ADD/PTRADD/PTRSUB/MULTIEQUAL;
//   * records reads, writes, and addresses of this+offset;
//   * creates full flat structures when CRuntimeClass proves an exact object
//     size, and safe observed-prefix structures for the remaining MFC UI
//     classes;
//   * preserves manually edited structure fields;
//   * annotates functions, but does NOT force guessed function signatures.
//
// @category C2E Map Editor
// @author OpenAI

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;

public class RecoverC2EMapEditorClassLayout extends GhidraScript {

    private static final int DECOMPILE_TIMEOUT_SECONDS = 30;
    private static final int MAX_UNKNOWN_OFFSET = 0x800;
    private static final int MAX_TRACE_DEPTH = 24;

    private static final CategoryPath CLASS_CATEGORY =
        new CategoryPath("/C2E/RecoveredClasses");

    // Exact sizes recovered from this target's MFC42 CRuntimeClass records.
    private final LinkedHashMap<String, Integer> exactClassSizes = new LinkedHashMap<>();

    // Owners named by the first recovery pass.
    private final List<String> owners = new ArrayList<>();

    private final Map<String, ClassStats> classes = new LinkedHashMap<>();

    private DecompInterface decompiler;
    private Register ecx;

    private int scannedFunctions;
    private int decompileFailures;
    private int pcodeFunctions;
    private int textFallbackFunctions;
    private int structureFieldsAdded;
    private int structureFieldsPreserved;

    private enum AccessKind {
        READ,
        WRITE,
        ADDRESS
    }

    private static class FieldAccess {
        final int offset;
        int reads;
        int writes;
        int addresses;
        int pcodeHits;
        int textHits;

        final Map<Integer, Integer> widthCounts = new TreeMap<>();
        final LinkedHashSet<String> methods = new LinkedHashSet<>();
        final LinkedHashSet<String> evidenceAddresses = new LinkedHashSet<>();

        FieldAccess(int offset) {
            this.offset = offset;
        }

        void add(AccessKind kind, int width, String method, Address at, boolean pcode) {
            if (kind == AccessKind.READ) reads++;
            else if (kind == AccessKind.WRITE) writes++;
            else addresses++;

            if (width > 0 && width <= 32) {
                widthCounts.put(width, widthCounts.getOrDefault(width, 0) + 1);
            }

            methods.add(method);

            if (at != null && evidenceAddresses.size() < 24) {
                evidenceAddresses.add(at.toString());
            }

            if (pcode) pcodeHits++;
            else textHits++;
        }

        int total() {
            return reads + writes + addresses;
        }

        int bestWidth() {
            int bestWidth = 0;
            int bestCount = -1;
            for (Map.Entry<Integer, Integer> e : widthCounts.entrySet()) {
                if (e.getValue() > bestCount ||
                    (e.getValue() == bestCount && e.getKey() > bestWidth)) {
                    bestWidth = e.getKey();
                    bestCount = e.getValue();
                }
            }
            return bestWidth;
        }

        String widthsString() {
            if (widthCounts.isEmpty()) return "-";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<Integer, Integer> e : widthCounts.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(e.getKey()).append("x").append(e.getValue());
            }
            return sb.toString();
        }
    }

    private static class ClassStats {
        final String owner;
        final Integer exactSize;
        final TreeMap<Integer, FieldAccess> fields = new TreeMap<>();
        final LinkedHashSet<String> scannedMethods = new LinkedHashSet<>();
        int failedMethods;

        ClassStats(String owner, Integer exactSize) {
            this.owner = owner;
            this.exactSize = exactSize;
        }

        FieldAccess field(int offset) {
            return fields.computeIfAbsent(offset, FieldAccess::new);
        }
    }

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the recovered MapEditor.exe program first.");
            return;
        }

        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This script is deliberately limited to the 32-bit Map Editor target.");
            return;
        }

        initialiseTargets();

        println("=== Creatures Map Editor 1.08 - class layout recovery ===");
        println("Program: " + currentProgram.getName());
        println("Language: " + currentProgram.getLanguageID());
        println("Pointer size: " + currentProgram.getDefaultPointerSize());
        println("");

        ecx = currentProgram.getRegister("ECX");

        setUpDecompiler();

        try {
            List<Function> targets = collectRecoveredMemberFunctions();

            println("Recovered member functions selected: " + targets.size());

            for (Function function : targets) {
                monitor.checkCancelled();
                scanFunction(function);
            }

            println("");
            printSummaryToConsole();

            createKnownClassStructures();
            annotateFunctions(targets);

            File reportFile = writeReport();

            println("");
            println("=== Layout pass summary ===");
            println("Functions scanned:          " + scannedFunctions);
            println("Decompiler failures:        " + decompileFailures);
            println("P-code-backed functions:    " + pcodeFunctions);
            println("Text-fallback functions:    " + textFallbackFunctions);
            println("Structure fields added:     " + structureFieldsAdded);
            println("Existing fields preserved:  " + structureFieldsPreserved);
            println("Report: " + reportFile.getAbsolutePath());
            println("");
            println("Data Type Manager -> /C2E/RecoveredClasses");
            println("No function signatures were forced by this pass.");
        }
        finally {
            if (decompiler != null) {
                decompiler.dispose();
            }
        }
    }

    private void initialiseTargets() {
        // These four sizes are proved by this target's MFC CRuntimeClass data.
        // Other MFC UI classes do not expose a usable runtime-size record, so
        // they receive an observed-prefix type below rather than a falsely
        // complete class layout.
        exactClassSizes.put("CC2ERoomEditorDoc",  0x158);
        exactClassSizes.put("CC2ERoomEditorView", 0x1e8);
        exactClassSizes.put("CChildFrame",        0x0c8);
        exactClassSizes.put("CMainFrame",         0x38c);

        // Longest/specific names first is useful when matching prefixes.
        owners.add("CC2ERoomEditorView");
        owners.add("CC2ERoomEditorDoc");
        owners.add("CC2ERoomEditorApp");
        owners.add("CMetaroomBackgroundDlg");
        owners.add("CBackgroundFileDlg");
        owners.add("CCAPropertiesDlg");
        owners.add("CFloorCeilingDlg");
        owners.add("CPropertyTypesDlg");
        owners.add("CPropertyTypeDlg");
        owners.add("CPropertiesDlg");
        owners.add("CSwitchMetaroomDlg");
        owners.add("CChildFrame");
        owners.add("CMainFrame");
        owners.add("CTipDlg");

        for (String owner : owners) {
            classes.put(owner, new ClassStats(owner, exactClassSizes.get(owner)));
        }
    }

    private void setUpDecompiler() {
        DecompileOptions options = new DecompileOptions();

        decompiler = new DecompInterface();
        decompiler.setOptions(options);
        decompiler.toggleCCode(true);
        decompiler.toggleSyntaxTree(true);
        decompiler.setSimplificationStyle("decompile");

        if (!decompiler.openProgram(currentProgram)) {
            throw new RuntimeException("Decompiler could not open the current program.");
        }
    }

    private List<Function> collectRecoveredMemberFunctions() {
        ArrayList<Function> result = new ArrayList<>();

        FunctionIterator it =
            currentProgram.getFunctionManager().getFunctions(true);

        while (it.hasNext()) {
            Function f = it.next();
            String owner = ownerForFunction(f);
            if (owner == null) {
                continue;
            }

            String name = f.getName();

            // These are MFC metadata/factory helpers, not normal instance methods.
            if (name.endsWith("_GetBaseClass") ||
                name.endsWith("_GetRuntimeClass") ||
                name.endsWith("_GetMessageMap") ||
                name.endsWith("_CreateObject")) {
                continue;
            }

            // The first recovery pass primarily names handlers as Owner_OnXxx.
            // Accept any Owner_ function so future manual/recovered names are included too.
            result.add(f);
        }

        result.sort(Comparator.comparing(Function::getEntryPoint));
        return result;
    }

    private String ownerForFunction(Function function) {
        String name = function.getName();

        for (String owner : owners) {
            if (name.startsWith(owner + "_")) {
                return owner;
            }
        }
        return null;
    }

    private void scanFunction(Function function) throws Exception {
        String owner = ownerForFunction(function);
        if (owner == null) return;

        ClassStats cs = classes.get(owner);
        String method = function.getName();

        cs.scannedMethods.add(method);
        scannedFunctions++;

        DecompileResults results =
            decompiler.decompileFunction(function, DECOMPILE_TIMEOUT_SECONDS, monitor);

        if (results == null || !results.decompileCompleted()) {
            decompileFailures++;
            cs.failedMethods++;
            println("[decompile-fail] " + function.getEntryPoint() + " " + method +
                (results == null ? "" : " : " + results.getErrorMessage()));
            return;
        }

        HighFunction highFunction = results.getHighFunction();

        int strongHits = 0;
        if (highFunction != null) {
            strongHits = scanPcode(function, owner, cs, highFunction);
        }

        if (strongHits > 0) {
            pcodeFunctions++;
        }
        else {
            DecompiledFunction df = results.getDecompiledFunction();
            if (df != null && df.getC() != null) {
                int fallbackHits = scanDecompiledText(function, owner, cs, df.getC());
                if (fallbackHits > 0) {
                    textFallbackFunctions++;
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // P-code member access recovery
    // ---------------------------------------------------------------------

    private int scanPcode(
            Function function,
            String owner,
            ClassStats cs,
            HighFunction highFunction) throws Exception {

        int hits = 0;
        Iterator<PcodeOpAST> it = highFunction.getPcodeOps();

        while (it.hasNext()) {
            monitor.checkCancelled();

            PcodeOpAST op = it.next();
            int opcode = op.getOpcode();
            Address evidence = op.getSeqnum() == null ? null : op.getSeqnum().getTarget();

            if (opcode == PcodeOp.LOAD && op.getNumInputs() >= 2) {
                Long off = resolveThisOffset(op.getInput(1), 0, new HashSet<Varnode>());
                if (acceptOffset(owner, off)) {
                    int width = op.getOutput() == null ? 0 : op.getOutput().getSize();
                    record(cs, off.intValue(), AccessKind.READ, width,
                        function.getName(), evidence, true);
                    hits++;
                }
            }
            else if (opcode == PcodeOp.STORE && op.getNumInputs() >= 3) {
                Long off = resolveThisOffset(op.getInput(1), 0, new HashSet<Varnode>());
                if (acceptOffset(owner, off)) {
                    int width = op.getInput(2) == null ? 0 : op.getInput(2).getSize();
                    record(cs, off.intValue(), AccessKind.WRITE, width,
                        function.getName(), evidence, true);
                    hits++;
                }
            }
            else if ((opcode == PcodeOp.CALL || opcode == PcodeOp.CALLIND) &&
                     op.getNumInputs() >= 2) {

                // Any explicit this+offset passed to another routine is good evidence for
                // an embedded object/string/container/member whose address is being used.
                for (int i = 1; i < op.getNumInputs(); i++) {
                    Long off = resolveThisOffset(
                        op.getInput(i), 0, new HashSet<Varnode>());

                    if (acceptOffset(owner, off) && off.longValue() != 0) {
                        record(cs, off.intValue(), AccessKind.ADDRESS, 0,
                            function.getName(), evidence, true);
                        hits++;
                    }
                }
            }
        }

        return hits;
    }

    private Long resolveThisOffset(
            Varnode vnode,
            int depth,
            Set<Varnode> seen) {

        if (vnode == null || depth > MAX_TRACE_DEPTH || seen.contains(vnode)) {
            return null;
        }
        seen.add(vnode);

        if (isThisRoot(vnode)) {
            return 0L;
        }

        PcodeOp def = vnode.getDef();
        if (def == null) {
            return null;
        }

        int op = def.getOpcode();

        switch (op) {
            case PcodeOp.COPY:
            case PcodeOp.CAST:
            case PcodeOp.INT_ZEXT:
            case PcodeOp.INT_SEXT:
            case PcodeOp.INDIRECT:
                if (def.getNumInputs() >= 1) {
                    return resolveThisOffset(def.getInput(0), depth + 1, seen);
                }
                return null;

            case PcodeOp.SUBPIECE:
                if (def.getNumInputs() >= 2 &&
                    def.getInput(1).isConstant() &&
                    def.getInput(1).getOffset() == 0) {
                    return resolveThisOffset(def.getInput(0), depth + 1, seen);
                }
                return null;

            case PcodeOp.INT_ADD:
                if (def.getNumInputs() >= 2) {
                    Long r = resolveAddLike(
                        def.getInput(0), def.getInput(1), depth, seen, false);
                    if (r != null) return r;

                    return resolveAddLike(
                        def.getInput(1), def.getInput(0), depth, seen, false);
                }
                return null;

            case PcodeOp.INT_SUB:
                if (def.getNumInputs() >= 2 && def.getInput(1).isConstant()) {
                    Long base = resolveThisOffset(
                        def.getInput(0), depth + 1, new HashSet<Varnode>(seen));
                    if (base != null) {
                        return base - signedConstant(def.getInput(1));
                    }
                }
                return null;

            case PcodeOp.PTRADD:
                if (def.getNumInputs() >= 3 &&
                    def.getInput(1).isConstant() &&
                    def.getInput(2).isConstant()) {

                    Long base = resolveThisOffset(
                        def.getInput(0), depth + 1, new HashSet<Varnode>(seen));

                    if (base != null) {
                        long index = signedConstant(def.getInput(1));
                        long scale = signedConstant(def.getInput(2));
                        return base + index * scale;
                    }
                }
                return null;

            case PcodeOp.PTRSUB:
                if (def.getNumInputs() >= 2 && def.getInput(1).isConstant()) {
                    Long base = resolveThisOffset(
                        def.getInput(0), depth + 1, new HashSet<Varnode>(seen));

                    if (base != null) {
                        // In decompiler P-code PTRSUB is the "pointer to field/subcomponent"
                        // operation; the constant is a positive byte offset.
                        return base + signedConstant(def.getInput(1));
                    }
                }
                return null;

            case PcodeOp.MULTIEQUAL:
                Long common = null;
                for (int i = 0; i < def.getNumInputs(); i++) {
                    Long value = resolveThisOffset(
                        def.getInput(i), depth + 1, new HashSet<Varnode>(seen));

                    if (value == null) return null;

                    if (common == null) {
                        common = value;
                    }
                    else if (!common.equals(value)) {
                        return null;
                    }
                }
                return common;

            default:
                return null;
        }
    }

    private Long resolveAddLike(
            Varnode possibleBase,
            Varnode possibleConstant,
            int depth,
            Set<Varnode> seen,
            boolean subtract) {

        if (possibleConstant == null || !possibleConstant.isConstant()) {
            return null;
        }

        Long base = resolveThisOffset(
            possibleBase, depth + 1, new HashSet<Varnode>(seen));

        if (base == null) {
            return null;
        }

        long delta = signedConstant(possibleConstant);
        return subtract ? base - delta : base + delta;
    }

    private boolean isThisRoot(Varnode vnode) {
        HighVariable high = vnode.getHigh();

        if (high != null) {
            String n = high.getName();

            if (n != null &&
                (n.equals("this") ||
                 n.equalsIgnoreCase("in_ECX") ||
                 n.equalsIgnoreCase("ECX"))) {
                return true;
            }

            if (high instanceof HighParam) {
                Varnode representative = high.getRepresentative();
                if (representative != null && isEcxRegister(representative)) {
                    return true;
                }
            }
        }

        return isEcxRegister(vnode);
    }

    private boolean isEcxRegister(Varnode vnode) {
        if (ecx == null || vnode == null || !vnode.isRegister()) {
            return false;
        }

        Address a = vnode.getAddress();
        return a != null && a.equals(ecx.getAddress()) && vnode.getSize() == 4;
    }

    private long signedConstant(Varnode vnode) {
        long value = vnode.getOffset();

        switch (vnode.getSize()) {
            case 1:
                return (byte)value;
            case 2:
                return (short)value;
            case 4:
                return (int)value;
            default:
                return value;
        }
    }

    // ---------------------------------------------------------------------
    // Text fallback
    // ---------------------------------------------------------------------

    private int scanDecompiledText(
            Function function,
            String owner,
            ClassStats cs,
            String c) {

        // This is only used when P-code produced no member hits.
        // Prefer ECX/this spellings. Include param_1 only if Ghidra says thiscall.
        String roots = "(?:this|in_ECX|in_ecx)";

        String cc = function.getCallingConventionName();
        if (cc != null && cc.toLowerCase(Locale.ROOT).contains("thiscall")) {
            roots = "(?:this|in_ECX|in_ecx|param_1)";
        }

        Pattern p = Pattern.compile(
            "\\b" + roots + "\\s*\\+\\s*(0x[0-9a-fA-F]+|[0-9]+)");

        Matcher m = p.matcher(c);
        int hits = 0;

        while (m.find()) {
            try {
                int offset = parseInteger(m.group(1));
                if (!acceptOffset(owner, (long)offset)) {
                    continue;
                }

                int width = inferWidthFromNearbyText(c, m.start());
                record(cs, offset, AccessKind.ADDRESS, width,
                    function.getName(), function.getEntryPoint(), false);
                hits++;
            }
            catch (Exception ignored) {
                // Ignore malformed fallback evidence.
            }
        }

        return hits;
    }

    private int inferWidthFromNearbyText(String c, int position) {
        int start = Math.max(0, position - 72);
        String before = c.substring(start, position).toLowerCase(Locale.ROOT);

        if (before.matches("(?s).*(undefined8|double|longlong|long long|qword)[^\\n]*$"))
            return 8;
        if (before.matches("(?s).*(undefined4|dword|float|uint|int|ulong|long)[^\\n]*$"))
            return 4;
        if (before.matches("(?s).*(undefined2|word|ushort|short)[^\\n]*$"))
            return 2;
        if (before.matches("(?s).*(undefined1|byte|uchar|char)[^\\n]*$"))
            return 1;

        return 0;
    }

    private int parseInteger(String text) {
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Integer.parseUnsignedInt(text.substring(2), 16);
        }
        return Integer.parseInt(text);
    }

    // ---------------------------------------------------------------------
    // Evidence recording / filtering
    // ---------------------------------------------------------------------

    private boolean acceptOffset(String owner, Long offset) {
        if (offset == null) return false;
        if (offset < 0) return false;

        // this+0 is the object itself, not a member.
        if (offset == 0) return false;

        Integer exact = exactClassSizes.get(owner);
        long limit = exact == null ? MAX_UNKNOWN_OFFSET : exact;

        return offset < limit;
    }

    private void record(
            ClassStats cs,
            int offset,
            AccessKind kind,
            int width,
            String method,
            Address evidence,
            boolean pcode) {

        FieldAccess f = cs.field(offset);
        f.add(kind, width, method, evidence, pcode);
    }

    // ---------------------------------------------------------------------
    // Structure creation
    // ---------------------------------------------------------------------

    private void createKnownClassStructures() {
        DataTypeManager dtm = currentProgram.getDataTypeManager();

        // The original pass only iterated exactClassSizes, which meant that the
        // collected evidence for the dialogs, app and other UI classes was
        // reported but never materialised in the Data Type Manager.  Emit a
        // deliberately bounded prefix for those classes: it is useful to the
        // decompiler without pretending to know the object tail or inheritance.
        for (ClassStats cs : classes.values()) {
            String owner = cs.owner;

            // An exact CRuntimeClass size is itself enough to materialise a
            // safe opaque flat layout.  In particular CChildFrame has no
            // directly observed members in this build, but its 0xc8 runtime
            // size is proven and its handlers still benefit from a this type.
            if (cs == null || (cs.fields.isEmpty() && cs.exactSize == null)) {
                println("[struct] " + owner + ": no member evidence or exact size; skipped");
                continue;
            }

            boolean exact = cs.exactSize != null;
            int size = exact ? cs.exactSize.intValue() : observedPrefixSize(cs);
            String typeName = owner + (exact ? "_flat_layout" : "_observed_prefix");

            DataType existing = dtm.getDataType(CLASS_CATEGORY, typeName);
            Structure structure = null;
            boolean newStructure = false;

            if (existing instanceof Structure) {
                structure = (Structure)existing;

                if (structure.getLength() < size) {
                    structure.growStructure(size - structure.getLength());
                }
            }
            else if (existing == null) {
                structure = new StructureDataType(
                    CLASS_CATEGORY, typeName, size, dtm);
                newStructure = true;
            }
            else {
                println("[struct-skip] " + typeName +
                    " already exists but is not a Structure.");
                continue;
            }

            boolean[] occupied = new boolean[size];

            // Respect defined/manual fields already present.
            for (DataTypeComponent component : structure.getDefinedComponents()) {
                int start = component.getOffset();
                int len = Math.max(1, component.getLength());

                String fieldName = component.getFieldName();
                boolean ours = fieldName != null && fieldName.startsWith("field_");

                if (!ours) {
                    for (int i = start; i < Math.min(size, start + len); i++) {
                        occupied[i] = true;
                    }
                }
            }

            for (FieldAccess field : cs.fields.values()) {
                int offset = field.offset;
                int width = field.bestWidth();

                // Address-only evidence does not prove member width.
                if (width <= 0) width = 1;

                if (offset < 0 || offset >= size) continue;
                width = Math.min(width, size - offset);

                boolean collision = false;
                for (int i = offset; i < offset + width; i++) {
                    if (occupied[i]) {
                        collision = true;
                        break;
                    }
                }

                if (collision) {
                    structureFieldsPreserved++;
                    continue;
                }

                DataTypeComponent current = structure.getComponentAt(offset);
                if (current != null) {
                    String currentName = current.getFieldName();

                    if (currentName != null &&
                        !currentName.isBlank() &&
                        !currentName.startsWith("field_")) {

                        structureFieldsPreserved++;
                        continue;
                    }
                }

                String fieldName = String.format("field_%03X", offset);
                String comment = makeFieldComment(field);

                try {
                    structure.replaceAtOffset(
                        offset,
                        undefinedType(width),
                        width,
                        fieldName,
                        comment);

                    structureFieldsAdded++;

                    for (int i = offset; i < offset + width; i++) {
                        occupied[i] = true;
                    }
                }
                catch (IllegalArgumentException ex) {
                    println("[field-skip] " + owner + " +0x" +
                        Integer.toHexString(offset) + ": " + ex.getMessage());
                }
            }

            if (newStructure) {
                dtm.addDataType(structure, DataTypeConflictHandler.REPLACE_HANDLER);
            }

            println("[struct] " + owner + " " +
                (exact ? "exact-size=0x" : "observed-prefix=0x") +
                Integer.toHexString(size) + " fields=" + cs.fields.size());
        }
    }

    private int observedPrefixSize(ClassStats cs) {
        int end = 1;

        for (FieldAccess field : cs.fields.values()) {
            int width = field.bestWidth();
            if (width <= 0) width = 1;
            end = Math.max(end, field.offset + width);
        }

        // Preserve the exact highest observed member, then round only the
        // allocation boundary.  The type is explicitly a prefix, never an
        // assertion about the full object size.
        return (end + 3) & ~3;
    }

    private DataType undefinedType(int width) {
        switch (width) {
            case 1: return Undefined1DataType.dataType;
            case 2: return Undefined2DataType.dataType;
            case 4: return Undefined4DataType.dataType;
            case 8: return Undefined8DataType.dataType;
            default:
                return new ArrayDataType(
                    Undefined1DataType.dataType, width, 1);
        }
    }

    private String makeFieldComment(FieldAccess f) {
        StringBuilder sb = new StringBuilder();

        sb.append("C2E inferred member evidence: ");
        sb.append("R=").append(f.reads);
        sb.append(" W=").append(f.writes);
        sb.append(" Addr=").append(f.addresses);
        sb.append("; widths=").append(f.widthsString());

        if (!f.methods.isEmpty()) {
            sb.append("; methods=");
            sb.append(joinLimited(f.methods, 6));
        }

        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Function annotations
    // ---------------------------------------------------------------------

    private void annotateFunctions(List<Function> functions) {
        for (Function function : functions) {
            String owner = ownerForFunction(function);
            if (owner == null) continue;

            ClassStats cs = classes.get(owner);
            if (cs == null) continue;

            ArrayList<FieldAccess> used = new ArrayList<>();

            for (FieldAccess f : cs.fields.values()) {
                if (f.methods.contains(function.getName())) {
                    used.add(f);
                }
            }

            if (used.isEmpty()) continue;

            StringBuilder sb = new StringBuilder();
            sb.append("[C2E class-layout] ");
            sb.append(owner);
            sb.append(" member evidence:");

            for (FieldAccess f : used) {
                sb.append(" +0x");
                sb.append(Integer.toHexString(f.offset));
            }

            appendRepeatableComment(function.getEntryPoint(), sb.toString());
        }
    }

    private void appendRepeatableComment(Address address, String text) {
        String old = getRepeatableComment(address);

        if (old == null || old.isBlank()) {
            setRepeatableComment(address, text);
        }
        else if (!old.contains(text)) {
            setRepeatableComment(address, old + "\n" + text);
        }
    }

    // ---------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------

    private void printSummaryToConsole() {
        for (ClassStats cs : classes.values()) {
            if (cs.scannedMethods.isEmpty()) continue;

            println("[CLASS] " + cs.owner +
                (cs.exactSize == null
                    ? " size=unknown"
                    : " size=0x" + Integer.toHexString(cs.exactSize)) +
                " methods=" + cs.scannedMethods.size() +
                " offsets=" + cs.fields.size());

            for (FieldAccess f : cs.fields.values()) {
                println(String.format(
                    "  +0x%03X  hits=%-3d R=%-2d W=%-2d Addr=%-2d widths=%-12s  %s",
                    f.offset,
                    f.total(),
                    f.reads,
                    f.writes,
                    f.addresses,
                    f.widthsString(),
                    joinLimited(f.methods, 4)));
            }
        }
    }

    private File writeReport() throws IOException {
        File outFile = new File(
            System.getProperty("user.home"),
            "C2EMapEditor_class_layout_report.txt");

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outFile),
                    StandardCharsets.UTF_8))) {

            out.println("Creatures Map Editor 1.08 - inferred class/member layout");
            out.println("========================================================");
            out.println("Program: " + currentProgram.getName());
            out.println("Functions scanned: " + scannedFunctions);
            out.println("Decompiler failures: " + decompileFailures);
            out.println("P-code-backed functions: " + pcodeFunctions);
            out.println("Text-fallback functions: " + textFallbackFunctions);
            out.println();

            for (ClassStats cs : classes.values()) {
                if (cs.scannedMethods.isEmpty()) continue;

                out.println(cs.owner);
                out.println(repeat('-', cs.owner.length()));

                if (cs.exactSize == null) {
                    int prefixSize = cs.fields.isEmpty() ? 0 : observedPrefixSize(cs);
                    out.printf("Object size: unknown; observed prefix: 0x%X (%d)%n",
                        prefixSize, prefixSize);
                    out.println("Data type: /C2E/RecoveredClasses/" +
                        cs.owner + "_observed_prefix");
                }
                else {
                    out.printf("Object size: 0x%X (%d)%n",
                        cs.exactSize, cs.exactSize);
                    out.println("Data type: /C2E/RecoveredClasses/" +
                        cs.owner + "_flat_layout");
                }

                out.println("Methods scanned: " + cs.scannedMethods.size());
                out.println("Failed methods: " + cs.failedMethods);
                out.println();

                out.println("Offset   Width evidence   Read Write Addr  Pcode Text  Methods");
                out.println("------   --------------   ---- ----- ----  ----- ----  -------");

                for (FieldAccess f : cs.fields.values()) {
                    out.printf(
                        "+0x%03X   %-14s   %4d %5d %4d  %5d %4d  %s%n",
                        f.offset,
                        f.widthsString(),
                        f.reads,
                        f.writes,
                        f.addresses,
                        f.pcodeHits,
                        f.textHits,
                        joinLimited(f.methods, 12));

                    if (!f.evidenceAddresses.isEmpty()) {
                        out.println("         evidence: " +
                            joinLimited(f.evidenceAddresses, 12));
                    }
                }

                out.println();
            }

            out.println("Notes");
            out.println("-----");
            out.println("* Offsets are evidence, not semantic field names.");
            out.println("* Address-only evidence does not prove field width.");
            out.println("* Full flat layouts include the MFC base-object portion; inheritance is not yet split.");
            out.println("* An _observed_prefix ends at the highest directly observed member; it is not a full-size claim.");
            out.println("* Existing manually named structure fields are preserved.");
            out.println("* This pass does not force function signatures or this-pointer types.");
        }

        return outFile;
    }

    private String joinLimited(Collection<String> values, int limit) {
        StringBuilder sb = new StringBuilder();
        int n = 0;

        for (String value : values) {
            if (n > 0) sb.append(", ");
            if (n >= limit) {
                sb.append("...");
                break;
            }
            sb.append(value);
            n++;
        }

        return sb.toString();
    }

    private String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
