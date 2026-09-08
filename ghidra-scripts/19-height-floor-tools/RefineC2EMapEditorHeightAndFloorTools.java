// Creatures Map Editor 1.08 — pass 19: Height Check / Floor-Ceiling tools.
// Run after RefineC2EMapEditorValidation.java.
// @category C2E Map Editor

import java.util.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class RefineC2EMapEditorHeightAndFloorTools extends GhidraScript {
    private static final CategoryPath CAT = new CategoryPath("/C2E/Refined");

    private DataTypeManager dtm;
    private Structure view, doc, world, meta, boundary;
    private Structure boundaryRef, boundarySet, heightDlg, floorDlg;
    private Pointer pView, pDoc, pWorld, pMeta, pHeightDlg, pFloorDlg;

    private int fields, preserved, created, renamed, kept, typedThis, typedRet, labels;

    private static class F {
        long a; String n, owner, ret;
        F(long a, String n, String owner, String ret) {
            this.a=a; this.n=n; this.owner=owner; this.ret=ret;
        }
    }

    private static class L {
        long a; String n, c;
        L(long a, String n, String c) { this.a=a; this.n=n; this.c=c; }
    }

    private final F[] funcs = {
        new F(0x411ba0L, "CC2ERoomEditorView_OnCheckHeights", "view", "void"),
        new F(0x414de0L, "CHeightCheckDlg_ctor", "height", "heightptr"),
        new F(0x414e10L, "CHeightCheckDlg_DoDataExchange", "height", "void"),
        new F(0x40b900L, "CC2ERoomEditorDoc_CheckHeights", "doc", "void"),
        new F(0x425290L, "C2EWorldModel_CheckHeights", "world", "void"),
        new F(0x417e50L, "C2EEditorMetaRoom_CheckHeights", "meta", "void"),

        new F(0x4115b0L, "CC2ERoomEditorView_OnFloorCeilingValues", "view", "void"),
        new F(0x411710L, "CC2ERoomEditorView_OnUpdateFloorCeilingValues", "view", "void"),
        new F(0x414620L, "CFloorCeilingDlg_ctor", "floor", "floorptr"),
        new F(0x4146c0L, "CFloorCeilingDlg_DoDataExchange", "floor", "void"),
        new F(0x414700L, "CFloorCeilingDlg_OnCalculate", "floor", "void")
    };

    private final L[] resourceLabels = {
        new L(0x43bcc6L, "C2E_UI_HEIGHT_CHECK_TITLE", "UTF-16: Height Check"),
        new L(0x43bd16L, "C2E_UI_HEIGHT_CHECK_MIN_PERMABILITY_LABEL",
              "UTF-16: Minimum &Permability (original executable spelling)"),
        new L(0x43bd76L, "C2E_UI_HEIGHT_CHECK_MIN_HEIGHT_LABEL", "UTF-16: Minimum &Height"),
        new L(0x43baf6L, "C2E_UI_FLOOR_CEILING_TITLE", "UTF-16: Floor/Ceiling values"),
        new L(0x43bb56L, "C2E_UI_FLOOR_CEILING_X_LABEL", "UTF-16: &X"),
        new L(0x43bb92L, "C2E_UI_FLOOR_CEILING_Y_LABEL", "UTF-16: &Y"),
        new L(0x43bbceL, "C2E_UI_FLOOR_CEILING_CALCULATE_LABEL", "UTF-16: Calculate"),
        new L(0x434834L, "C2E_FLOOR_CEILING_RESULT_FORMAT", "Result format: %.6f")
    };

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            popup("Open the latest recovered MapEditor.exe database first.");
            return;
        }
        if (currentProgram.getDefaultPointerSize() != 4) {
            popup("This pass targets the 32-bit Map Editor only.");
            return;
        }

        if (getInt(toAddr(0x414de9L)) != 0x9d ||
            getInt(toAddr(0x414e1eL)) != 0x414 ||
            getInt(toAddr(0x414e3dL)) != 0x413 ||
            getInt(toAddr(0x414640L)) != 0x98 ||
            getInt(toAddr(0x414754L)) != 0x00434834 ||
            getByte(toAddr(0x425290L)) != (byte)0x51 ||
            getByte(toAddr(0x425291L)) != (byte)0x8b ||
            getByte(toAddr(0x417e50L)) != (byte)0x55 ||
            getByte(toAddr(0x417e51L)) != (byte)0x8b) {
            popup("MapEditor 1.08 Height/Floor-tools identity guard failed.");
            return;
        }

        dtm = currentProgram.getDataTypeManager();
        view = req("CC2ERoomEditorView_refined");
        doc = req("CC2ERoomEditorDoc_refined");
        world = req("C2EWorldModel");
        meta = req("C2EEditorMetaRoom");
        boundary = req("C2EEditorBoundarySegment");

        pView = ptr(view); pDoc = ptr(doc); pWorld = ptr(world); pMeta = ptr(meta);

        println("=== Creatures Map Editor 1.08 - Height / Floor-Ceiling tools ===");
        buildTypes();
        for (L l : resourceLabels) label(l);

        for (F s : funcs) {
            Function f = function(s.a, s.n);
            if (f == null) continue;
            if ("view".equals(s.owner)) setThis(f, pView, "CC2ERoomEditorView_refined");
            else if ("doc".equals(s.owner)) setThis(f, pDoc, "CC2ERoomEditorDoc_refined");
            else if ("world".equals(s.owner)) setThis(f, pWorld, "C2EWorldModel");
            else if ("meta".equals(s.owner)) setThis(f, pMeta, "C2EEditorMetaRoom");
            else if ("height".equals(s.owner)) setThis(f, pHeightDlg, "CHeightCheckDlg");
            else if ("floor".equals(s.owner)) setThis(f, pFloorDlg, "CFloorCeilingDlg_refined");
            setReturn(f, s.ret);
        }

        comments();

        println("\nRunning analysis on Height / Floor-Ceiling changes...");
        analyzeChanges(currentProgram);

        println("\n=== Height / Floor-Ceiling summary ===");
        println("Fields refined:       " + fields);
        println("Fields preserved:     " + preserved);
        println("Functions created:    " + created);
        println("Functions renamed:    " + renamed);
        println("Existing names kept:  " + kept);
        println("this types applied:   " + typedThis);
        println("return types applied: " + typedRet);
        println("labels refined:       " + labels);
        println("\nView +0x164 -> selectedBoundarySegments");
        println("Height Check: minimumHeight 0..500, minimumPermeability 0..100");
        println("Floor/Ceiling Values: Y = slope * X + intercept, format %.6f");
    }

    private void buildTypes() {
        boundaryRef = struct("C2EEditorBoundarySegmentRef", 0x08);
        field(boundaryRef, 0x00, ptr(boundary), 4, "segment",
              "Selected generated boundary segment.");
        field(boundaryRef, 0x04, ptr(UnsignedIntegerDataType.dataType), 4, "refCount",
              "Reference-count pointer copied by Floor/Ceiling Values.");

        boundarySet = struct("C2ESelectedBoundarySegmentSet", 0x14);
        field(boundarySet, 0x00, bytes(0x10), 0x10, "treeState",
              "Opaque VC6 ordered-tree/set state; node value is C2EEditorBoundarySegmentRef.");
        field(boundarySet, 0x10, UnsignedIntegerDataType.dataType, 4, "count",
              "Selected boundary count.");

        replaceViewSelection();

        heightDlg = struct("CHeightCheckDlg", 0x68);
        field(heightDlg, 0x00, bytes(0x60), 0x60, "cDialogBase", "Opaque MFC42 CDialog base.");
        field(heightDlg, 0x60, IntegerDataType.dataType, 4, "minimumHeight",
              "Control 0x414; DDX range 0..500.");
        field(heightDlg, 0x64, IntegerDataType.dataType, 4, "minimumPermeability",
              "Control 0x413; DDX range 0..100.");
        pHeightDlg = ptr(heightDlg);

        floorDlg = struct("CFloorCeilingDlg_refined", 0xe8);
        field(floorDlg, 0x00, bytes(0x60), 0x60, "cDialogBase", "Opaque MFC42 CDialog base.");
        field(floorDlg, 0x60, FloatDataType.dataType, 4, "lineSlope", "Selected line slope.");
        field(floorDlg, 0x64, FloatDataType.dataType, 4, "lineIntercept", "Selected line intercept.");
        field(floorDlg, 0x68, bytes(0x40), 0x40, "yEdit", "Embedded Y result edit control.");
        field(floorDlg, 0xa8, bytes(0x40), 0x40, "xEdit", "Embedded X input edit control.");
        pFloorDlg = ptr(floorDlg);

        describe(boundaryRef, "Shared/reference wrapper for C2EEditorBoundarySegment.");
        describe(boundarySet, "View selected generated-boundary set.");
        describe(heightDlg, "Resource 157 Height Check dialog.");
        describe(floorDlg, "Resource 152 Floor/Ceiling coordinate calculator.");
    }

    private void replaceViewSelection() {
        try {
            DataTypeComponent c = view.getComponentContaining(0x164);
            String n = c == null ? null : c.getFieldName();
            if (c != null && n != null &&
                !n.isBlank() &&
                !"selectedDoorPairs".equals(n) &&
                !"selectedRoomRefs".equals(n) &&
                !"selectedBoundarySegments".equals(n)) {
                preserved++;
                println("[field-preserve] View +0x164 existing='" + n + "'");
                return;
            }
            if (c != null) view.clearAtOffset(c.getOffset());
            view.replaceAtOffset(
                0x164, boundarySet, 0x14, "selectedBoundarySegments",
                "Selected reference-counted internal/external boundary segments. " +
                "Earlier selectedDoorPairs name described room IDs consumed through segment*, not the stored node value.");
            fields++;
            println("[correct] View +0x164 -> selectedBoundarySegments");
        }
        catch (Exception e) {
            println("[field-fail] View +0x164: " + e.getMessage());
        }

        deprecate("C2ESelectedDoorPairSet",
            "DEPRECATED: View+0x164 stores C2EEditorBoundarySegmentRef nodes; use C2ESelectedBoundarySegmentSet.");
        deprecate("C2ESelectedRoomRefSet",
            "DEPRECATED: View+0x164 stores C2EEditorBoundarySegmentRef nodes.");
    }

    private void comments() {
        repeat(0x411ba0L,
            "[C2E Height Check] Opens resource 157, clears View selection, then calls " +
            "Doc_CheckHeights(minimumPermeability, minimumHeight, &selectedRoomIds).");

        repeat(0x40b900L,
            "[C2E Height Check] Thin wrapper over embedded WorldModel at Doc+0x54.");

        repeat(0x425290L,
            "[C2E Height Check] Walks metarooms and passes minimumPermeability, minimumHeight, " +
            "selectedRoomIds, internalDoorSegments and externalDoorSegments.");

        repeat(0x417e50L,
            "[C2E Height Check] For each candidate Room, compare its FLOOR line with overlapping " +
            "qualifying CEILING/FLOOR boundary lines. Internal candidates require permeability < " +
            "minimumPermeability. Endpoint separations <= 1.0 are ignored; if a positive separation " +
            "is >1.0 and <= minimumHeight, insert the current roomId into selectedRoomIds.");

        eol(0x417ef2L, "Internal-boundary permeability < minimumPermeability.");
        eol(0x418020L, "Build current Room FLOOR line.");
        eol(0x418086L, "Build candidate boundary line.");
        eol(0x4180c6L, "Compare endpoint separation against double 1.0.");
        eol(0x4180d3L, "Compare qualifying separation against minimumHeight.");
        eol(0x4182ccL, "Height violation: current room is selected.");

        repeat(0x411710L,
            "[C2E Floor/Ceiling] Enable only when selectedBoundarySegments.count == 1 and " +
            "selected segment.edge is CEILING(1) or FLOOR(2).");

        repeat(0x4115b0L,
            "[C2E selection correction] View+0x164 stores C2EEditorBoundarySegmentRef values. " +
            "This handler copies segment*/refCount*, reads segment.start/end, computes line slope/intercept, " +
            "and opens CFloorCeilingDlg.");

        repeat(0x402010L,
            "[C2E selection correction] Set Door Opening receives selected boundary references and " +
            "reads segment.roomId1/roomId2; the View does not store raw room-ID pairs.");

        repeat(0x414700L,
            "[C2E Floor/Ceiling] X = parsed xEdit text; Y = lineSlope*X + lineIntercept; " +
            "format with %.6f into yEdit. No geometry is modified.");
    }

    private Structure req(String name) {
        DataType d = dtm.getDataType(CAT, name);
        if (!(d instanceof Structure))
            throw new IllegalStateException("Missing /C2E/Refined/" + name);
        return (Structure)d;
    }

    private Structure struct(String name, int size) {
        DataType d = dtm.getDataType(CAT, name);
        if (d instanceof Structure) {
            Structure s=(Structure)d;
            if (s.getLength() < size) s.growStructure(size-s.getLength());
            return s;
        }
        if (d != null) throw new IllegalStateException(name + " exists but is not a Structure");
        return (Structure)dtm.addDataType(
            new StructureDataType(CAT, name, size, dtm),
            DataTypeConflictHandler.REPLACE_HANDLER);
    }

    private Pointer ptr(DataType d) { return new PointerDataType(d, dtm); }
    private ArrayDataType bytes(int n) {
        return new ArrayDataType(Undefined1DataType.dataType, n, 1);
    }

    private void field(Structure s, int off, DataType t, int len, String name, String comment) {
        try {
            DataTypeComponent c=s.getComponentContaining(off);
            if (c != null) {
                String n=c.getFieldName();
                boolean safe=n==null || n.isBlank() || name.equals(n) ||
                    n.startsWith("field_") || n.startsWith("undefined") || n.startsWith("padding");
                if (!safe) {
                    preserved++;
                    println("[field-preserve] " + s.getName() + " +0x" +
                        Integer.toHexString(off) + " existing='" + n + "'");
                    return;
                }
                if (c.getOffset()!=off || c.getLength()!=len || !c.getDataType().isEquivalent(t))
                    s.clearAtOffset(c.getOffset());
            }
            s.replaceAtOffset(off,t,len,name,comment);
            fields++;
            println("[field] " + s.getName() + " +0x" + Integer.toHexString(off) + " -> " + name);
        }
        catch(Exception e) {
            println("[field-fail] " + s.getName() + " +0x" + Integer.toHexString(off) + ": " + e.getMessage());
        }
    }

    private void describe(Structure s, String d) {
        try { s.setDescription(d); } catch(Exception ignored) {}
    }

    private void deprecate(String name, String d) {
        DataType t=dtm.getDataType(CAT,name);
        if (t instanceof Structure) describe((Structure)t,d);
    }

    private Function function(long va, String wanted) throws Exception {
        Address a=toAddr(va);
        Function f=getFunctionAt(a);
        if (f==null) {
            Function inside=getFunctionContaining(a);
            if (inside!=null && !inside.getEntryPoint().equals(a)) {
                println("[function-skip] " + a + " inside " + inside.getName());
                return null;
            }
            if (getInstructionAt(a)==null && !disassemble(a)) return null;
            f=createFunction(a,wanted);
            if (f==null) return null;
            created++;
            println("[create] " + a + " -> " + wanted);
            return f;
        }
        String old=f.getName();
        if (wanted.equals(old)) { kept++; return f; }
        if (old.startsWith("FUN_") || old.startsWith("thunk_FUN_") ||
            f.getSymbol().getSource()==SourceType.DEFAULT) {
            f.setName(wanted,SourceType.USER_DEFINED);
            renamed++;
            println("[rename] " + a + " " + old + " -> " + wanted);
        } else {
            kept++;
            println("[keep] " + a + " existing=" + old + " suggested=" + wanted);
        }
        return f;
    }

    private void setThis(Function f, Pointer p, String typeName) {
        try {
            if (f.hasVarArgs()) return;
            Register ecx=currentProgram.getRegister("ECX");
            if (ecx==null) return;

            Parameter[] old=f.getParameters();
            if (f.hasCustomVariableStorage() && old.length>0 &&
                "this".equals(old[0].getName()) &&
                old[0].getDataType()!=null && old[0].getDataType().isEquivalent(p) &&
                old[0].getVariableStorage()!=null &&
                old[0].getVariableStorage().isRegisterStorage()) return;

            ArrayList<Variable> ps=new ArrayList<>();
            ps.add(new ParameterImpl("this",p,ecx,currentProgram,SourceType.USER_DEFINED));
            for (Parameter q: old) {
                if (q.isAutoParameter() || "this".equals(q.getName())) continue;
                VariableStorage st=q.getVariableStorage();
                if (st!=null && st.isRegisterStorage() && st.getRegister()!=null &&
                    st.getRegister().equals(ecx)) continue;
                ps.add(new ParameterImpl(q,currentProgram));
            }
            f.updateFunction("__thiscall",null,ps,
                Function.FunctionUpdateType.CUSTOM_STORAGE,true,SourceType.USER_DEFINED);
            typedThis++;
            println("[this] " + f.getEntryPoint() + " " + f.getName() + " -> " + typeName + " * @ ECX");
        }
        catch(Exception e) {
            println("[this-fail] " + f.getEntryPoint() + " " + f.getName() + ": " + e.getMessage());
        }
    }

    private void setReturn(Function f, String kind) {
        try {
            DataType t=null;
            if ("void".equals(kind)) t=VoidDataType.dataType;
            else if ("heightptr".equals(kind)) t=pHeightDlg;
            else if ("floorptr".equals(kind)) t=pFloorDlg;
            if (t!=null && (f.getReturnType()==null || !f.getReturnType().isEquivalent(t))) {
                f.setReturnType(t,SourceType.USER_DEFINED);
                typedRet++;
            }
        }
        catch(Exception e) {
            println("[return-skip] " + f.getEntryPoint() + ": " + e.getMessage());
        }
    }

    private void label(L l) {
        try {
            Address a=toAddr(l.a);
            Symbol s=getSymbolAt(a);
            if (s==null || s.getSource()==SourceType.DEFAULT ||
                s.getName().startsWith("s_") || s.getName().startsWith("DAT_")) {
                if (s==null) createLabel(a,l.n,true);
                else s.setName(l.n,SourceType.USER_DEFINED);
                labels++;
                println("[label] " + a + " -> " + l.n);
            }
            eol(l.a,l.c);
        }
        catch(Exception e) {
            println("[label-skip] " + Long.toHexString(l.a) + ": " + e.getMessage());
        }
    }

    private void repeat(long va, String text) {
        Address a=toAddr(va);
        String old=getRepeatableComment(a);
        if (old==null || old.isBlank()) setRepeatableComment(a,text);
        else if (!old.contains(text)) setRepeatableComment(a,old+"\n"+text);
    }

    private void eol(long va, String text) {
        Address a=toAddr(va);
        String old=getEOLComment(a);
        if (old==null || old.isBlank()) setEOLComment(a,text);
        else if (!old.contains(text)) setEOLComment(a,old+"\n"+text);
    }
}
