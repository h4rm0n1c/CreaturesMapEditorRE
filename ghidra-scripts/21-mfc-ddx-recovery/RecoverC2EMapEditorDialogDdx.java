// Creatures Map Editor 1.08 — pass 21: recover MFC dialog DDX overrides.
//
// Run after passes 01-20 and a fresh analysis. This discovers dialog
// DoDataExchange methods from actual DDX_* calls. Ownership is assigned only
// when those calls use control IDs unique to one recovered dialog.
//
// It applies that dialog's ECX this type and refines only members whose exact
// DDX overload proves CString or integer storage. DDX_Control bindings are named but retain their opaque
// storage, because the concrete MFC control subclass is not proven here.
//
// @category C2E Map Editor

import java.util.*;
import ghidra.app.decompiler.*;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.SourceType;

public class RecoverC2EMapEditorDialogDdx extends GhidraScript {
    private static final CategoryPath RECOVERED=new CategoryPath("/C2E/RecoveredClasses");
    private static final CategoryPath REFINED=new CategoryPath("/C2E/Refined");
    private static final int TIMEOUT=30, MAX_DEPTH=24;

    private static class Dialog {
        final String owner,layout; final CategoryPath category;
        final Map<Integer,String> controls=new LinkedHashMap<Integer,String>();
        Dialog(String o,String l,CategoryPath c,Object... pairs) { owner=o;layout=l;category=c;
            for(int i=0;i+1<pairs.length;i+=2) controls.put((Integer)pairs[i],(String)pairs[i+1]); }
    }
    private final Dialog[] dialogs={
        new Dialog("CMetaroomBackgroundDlg","CMetaroomBackgroundDlg_observed_prefix",RECOVERED,1021,"BackgroundList",1022,"Properties",1023,"Remove",1024,"Add",1028,"SetAsCurrentBackground"),
        new Dialog("CBackgroundFileDlg","CBackgroundFileDlg_observed_prefix",RECOVERED,1032,"GeneratePreview"),
        new Dialog("CCAPropertiesDlg","CCAPropertiesDlg_observed_prefix",RECOVERED,1018,"RoomTypeList",1019,"CAPropertyList"),
        new Dialog("CFloorCeilingDlg","CFloorCeilingDlg_refined",REFINED,1040,"Calculate"),
        new Dialog("CPropertyTypeDlg","CPropertyTypeDlg_observed_prefix",RECOVERED,1007,"Enumerated"),
        new Dialog("CPropertyTypesDlg","CPropertyTypesDlg_observed_prefix",RECOVERED,1001,"PropertyTypeList",1002,"Edit"),
        new Dialog("CPropertiesDlg","CPropertiesDlg_observed_prefix",RECOVERED,1002,"PropertyEdit",1015,"PropertyAction"),
        new Dialog("CSwitchMetaroomDlg","CSwitchMetaroomDlg_observed_prefix",RECOVERED,3,"Apply",1037,"MetaroomCombo"),
        new Dialog("CTipDlg","CTipDlg_observed_prefix",RECOVERED,1002,"NextTip") };
    private final Map<String,Pointer> pointers=new HashMap<String,Pointer>();
    private final Map<String,Structure> layouts=new HashMap<String,Structure>();
    private DecompInterface decompiler; private Register ecx; private DataTypeManager dtm; private DataType cstring;
    private int candidates,identified,renamed,kept,typedThis,fields,preserved,ambiguous,failures;

    @Override public void run() throws Exception {
        if(currentProgram==null || currentProgram.getDefaultPointerSize()!=4 || getByte(toAddr(0x00425290L))!=(byte)0x51 || getByte(toAddr(0x00417e50L))!=(byte)0x55) {
            popup("Open the recovered 32-bit MapEditor 1.08 database first."); return; }
        dtm=currentProgram.getDataTypeManager(); ecx=currentProgram.getRegister("ECX");
        if(ecx==null || !loadTypes()) return;
        cstring=dtm.getDataType(REFINED,"VC6CString");
        if(cstring==null) { popup("Missing /C2E/Refined/VC6CString; run pass 06 first."); return; }
        decompiler=new DecompInterface(); decompiler.openProgram(currentProgram);
        println("=== C2E Map Editor: MFC DDX recovery ===");
        FunctionIterator it=currentProgram.getFunctionManager().getFunctions(true);
        while(it.hasNext()) { monitor.checkCancelled(); recover(it.next()); }
        decompiler.dispose();
        println("\n=== MFC DDX recovery summary ===");
        println("DDX-bearing functions: " + candidates); println("Dialog ownership proved: " + identified);
        println("DoDataExchange renamed:  " + renamed); println("Existing names kept:     " + kept);
        println("this types applied:      " + typedThis); println("DDX members refined:     " + fields);
        println("Manual members kept:     " + preserved); println("Ambiguous DDX callers:   " + ambiguous);
        println("Decompiler failures:     " + failures); println("\nRun Auto Analyze after this pass.");
    }
    private boolean loadTypes() {
        ArrayList<String> missing=new ArrayList<String>();
        for(Dialog d:dialogs) { DataType t=dtm.getDataType(d.category,d.layout);
            if(!(t instanceof Structure)) missing.add(d.layout); else { layouts.put(d.owner,(Structure)t); pointers.put(d.owner,new PointerDataType(t,dtm)); } }
        if(!missing.isEmpty()) { popup("Missing recovered dialog layouts: " + missing); return false; } return true;
    }
    private static class Binding { final String helper; final Long id; final Varnode member; final DataType valueType;
        Binding(String h,Long i,Varnode m,DataType t){helper=h;id=i;member=m;valueType=t;} }
    private void recover(Function f) {
        DecompileResults r=decompiler.decompileFunction(f,TIMEOUT,monitor); HighFunction hf=r==null?null:r.getHighFunction();
        if(hf==null) { failures++; return; }
        ArrayList<Binding> bindings=new ArrayList<Binding>(); Set<Dialog> owners=new LinkedHashSet<Dialog>();
        Iterator<PcodeOpAST> ops=hf.getPcodeOps();
        while(ops.hasNext()) { PcodeOpAST op=ops.next(); if(op.getOpcode()!=PcodeOp.CALL || op.getNumInputs()<4) continue;
            Function callee=functionAt(op.getInput(0)); String helper=callee==null?"":callee.getName();
            if(!helper.startsWith("DDX_")&&!helper.startsWith("DDV_")) continue;
            Long id=constant(op.getInput(2),0,new HashSet<Varnode>()); bindings.add(new Binding(helper,id,op.getInput(3),ddxValueType(callee,helper)));
            if(id!=null) for(Dialog d:dialogs) if(d.controls.containsKey(id.intValue())) owners.add(d); }
        if(bindings.isEmpty()) return; candidates++;
        if(owners.size()!=1) { ambiguous++; return; }
        Dialog owner=owners.iterator().next(); identified++; rename(f,owner.owner+"_DoDataExchange"); setThis(f,pointers.get(owner.owner));
        for(Binding b:bindings) refineBinding(owner,b);
    }
    private Function functionAt(Varnode v) { if(v==null||!v.isAddress()) return null; Function f=getFunctionAt(v.getAddress()); return f==null?getFunctionContaining(v.getAddress()):f; }
    private void rename(Function f,String wanted) { try { if(wanted.equals(f.getName())) {kept++;return;}
        if(f.getName().startsWith("FUN_")||f.getSymbol().getSource()==SourceType.DEFAULT) { f.setName(wanted,SourceType.USER_DEFINED);renamed++;println("[ddx] "+f.getEntryPoint()+" -> "+wanted); } else kept++; } catch(Exception e){failures++;} }
    private void setThis(Function f,Pointer p) { try { Parameter[] old=f.getParameters();
        if(f.hasCustomVariableStorage()&&old.length>0&&"this".equals(old[0].getName())&&old[0].getDataType()!=null&&old[0].getDataType().isEquivalent(p)) return;
        if(f.hasCustomVariableStorage()&&old.length>0&&old[0].getSource()==SourceType.USER_DEFINED&&!"this".equals(old[0].getName())) return;
        ArrayList<Variable> ps=new ArrayList<Variable>(); ps.add(new ParameterImpl("this",p,ecx,currentProgram,SourceType.USER_DEFINED));
        for(Parameter q:old) { if(q.isAutoParameter()||"this".equals(q.getName()))continue; VariableStorage s=q.getVariableStorage(); if(s!=null&&s.isRegisterStorage()&&ecx.equals(s.getRegister()))continue; ps.add(new ParameterImpl(q,currentProgram)); }
        f.updateFunction("__thiscall",null,ps,Function.FunctionUpdateType.CUSTOM_STORAGE,true,SourceType.USER_DEFINED);typedThis++; }catch(Exception e){failures++;} }
    private void refineBinding(Dialog d,Binding b) { if(b.id==null) return; Long off=thisOffset(b.member,0,new HashSet<Varnode>());
        if(off==null||off<=0||off>=layouts.get(d.owner).getLength())return; String control=d.controls.get(b.id.intValue());if(control==null)return;
        DataType type=b.valueType;String suffix=""; if(b.helper.startsWith("DDX_Check")||b.helper.startsWith("DDX_CBIndex")||b.helper.startsWith("DDV_MinMaxInt")){suffix="Value";}
        else if(b.helper.startsWith("DDX_Text")){suffix="Text";} else if(b.helper.startsWith("DDX_Control")){suffix="Control";} else return;
        if(type==null&&!b.helper.startsWith("DDX_Control"))return; String name=Character.toLowerCase(control.charAt(0))+control.substring(1)+suffix;Structure s=layouts.get(d.owner);int at=off.intValue();DataTypeComponent old=s.getComponentContaining(at);
        if(old!=null){String n=old.getFieldName();if(n!=null&&!n.isEmpty()&&!n.startsWith("field_")&&!n.startsWith("undefined")&&!n.equals(name)){preserved++;return;}}
        try { if(type!=null){if(old!=null&&old.getOffset()!=at){preserved++;return;}if(old!=null)s.clearAtOffset(at);s.replaceAtOffset(at,type,type.getLength(),name,"Proved by "+b.helper+" for dialog control "+b.id+" ("+control+").");}
            else if(old!=null)s.replaceAtOffset(at,old.getDataType(),old.getLength(),name,"Embedded MFC control binding proved by DDX_Control for ID "+b.id+" ("+control+")."); else return;
            fields++;println("[field] "+d.owner+" +0x"+Integer.toHexString(at)+" -> "+name);}catch(Exception e){failures++;} }
    private Long thisOffset(Varnode v,int depth,Set<Varnode> seen){if(v==null||depth>MAX_DEPTH||!seen.add(v))return null;if(isThis(v))return 0L;PcodeOp d=v.getDef();if(d==null)return null;switch(d.getOpcode()){
        case PcodeOp.COPY:case PcodeOp.CAST:case PcodeOp.INT_ZEXT:case PcodeOp.INT_SEXT:case PcodeOp.INDIRECT:return thisOffset(d.getInput(0),depth+1,seen);
        case PcodeOp.PTRADD:if(d.getNumInputs()<3)return null;Long b=thisOffset(d.getInput(0),depth+1,new HashSet<Varnode>(seen)),i=constant(d.getInput(1),depth+1,new HashSet<Varnode>()),e=constant(d.getInput(2),depth+1,new HashSet<Varnode>());return b==null||i==null||e==null?null:b+i*e;
        case PcodeOp.PTRSUB:case PcodeOp.INT_ADD:Long q=add(d.getInput(0),d.getInput(1),depth,seen,false);return q==null&&d.getOpcode()==PcodeOp.INT_ADD?add(d.getInput(1),d.getInput(0),depth,seen,false):q;case PcodeOp.INT_SUB:return add(d.getInput(0),d.getInput(1),depth,seen,true);
        default:return null;}}
    private Long add(Varnode a,Varnode c,int depth,Set<Varnode> seen,boolean sub){Long b=thisOffset(a,depth+1,new HashSet<Varnode>(seen)),n=constant(c,depth+1,new HashSet<Varnode>());return b==null||n==null?null:(sub?b-n:b+n);}
    private DataType ddxValueType(Function callee,String helper) {
        if(helper.startsWith("DDX_Check")||helper.startsWith("DDX_CBIndex")||helper.startsWith("DDV_MinMaxInt")) return IntegerDataType.dataType;
        if(!helper.startsWith("DDX_Text") || callee==null || callee.getParameterCount()<3) return null;
        DataType t=callee.getParameter(2).getDataType(); String n=t==null?"":t.getName().toLowerCase(Locale.ROOT);
        if(n.contains("cstring")) return cstring;
        if(n.equals("int") || n.contains("int *") || n.contains("int*")) return IntegerDataType.dataType;
        return null;
    }
    private Long constant(Varnode v,int depth,Set<Varnode> seen){if(v==null||depth>MAX_DEPTH||!seen.add(v))return null;if(v.isConstant())return v.getOffset();PcodeOp d=v.getDef();if(d==null)return null;switch(d.getOpcode()){case PcodeOp.COPY:case PcodeOp.CAST:case PcodeOp.INT_ZEXT:case PcodeOp.INT_SEXT:return constant(d.getInput(0),depth+1,seen);default:return null;}}
    private boolean isThis(Varnode v){HighVariable h=v.getHigh();if(h!=null){String n=h.getName();if("this".equals(n)||"in_ECX".equalsIgnoreCase(n)||"ECX".equalsIgnoreCase(n))return true;if(h instanceof HighParam){Varnode r=h.getRepresentative();if(r!=null&&isEcx(r))return true;}}return isEcx(v);}
    private boolean isEcx(Varnode v){return v!=null&&v.isRegister()&&v.getSize()==4&&ecx.getAddress().equals(v.getAddress());}
}
