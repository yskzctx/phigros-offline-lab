package com.phigros.offline;
import java.util.*;
import java.math.BigDecimal;
public final class ControlsConfig {
    /** 默认 AP、目标分数、目标准确率、判定保护。 */
    public static final int MODE_AP=0,MODE_SCORE=1,MODE_ACCURACY=2,MODE_GUARD=3;
    public final int mode,target,accuracy,guardKind,guardScore;
    public final String encodedRules;
    public final Rule[] rules;
    public final int[] packed;
    public static final class Rule {
        public final int judgment,position,start,end,count,noteType;
        public final int[] indices;
        Rule(int j,int p,int s,int e,int c,int[] a,int type){judgment=j;position=p;start=s;end=e;count=c;indices=a;noteType=type;}
    }
    private ControlsConfig(int m,int t,int a,int gk,int gs,String text,List<Rule> list) {
        mode=m;target=t;accuracy=a;guardKind=gk;guardScore=gs;encodedRules=text;rules=list.toArray(new Rule[0]);
        boolean typed=false;for(Rule r:rules)if(r.noteType!=0)typed=true;
        int size=typed?2:1;for(Rule r:rules)size+=(typed?7:6)+r.indices.length;
        packed=new int[size];int i=0;if(typed)packed[i++]=-168;packed[i++]=rules.length;
        for(Rule r:rules){packed[i++]=r.judgment;packed[i++]=r.position;packed[i++]=r.start;packed[i++]=r.end;packed[i++]=r.count;if(typed)packed[i++]=r.noteType;packed[i++]=r.indices.length;for(int v:r.indices)packed[i++]=v;}
    }
    private static int bounded(String s,int low,int high,String label) {
        try {int v=Integer.parseInt(s==null?"":s.trim());if(v>=low && v<=high)return v;}
        catch(NumberFormatException ignored){}
        throw new IllegalArgumentException(label+"须为 "+low+"～"+high+" 的整数");
    }
    private static int percentage(String accuracy) {
        if(accuracy==null||!accuracy.trim().matches("[0-9]{1,3}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("准确率支持两位小数，例如 90.25");
        int a=new BigDecimal(accuracy.trim()).multiply(new BigDecimal(100)).intValueExact();
        if(a<0||a>10000)throw new IllegalArgumentException("准确率须为 0～100%");
        return a;
    }
    /** 判定保护：1 保护 AP、2 保护 FC、3 保护分数。0 表示未选择。 */
    private static int guardKind(String kind) {
        return bounded(kind,0,3,"保护类型");
    }
    public static ControlsConfig parse(int mode,String target,String accuracy,String text) {
        return parse(mode,target,accuracy,text,"0","0");
    }
    /**
     * 判定保护是第四种游玩模式。它和自动游玩共用同一套判定计划，区别只在于
     * 何时接管：分数仍在保护目标内时保留真实触摸，即将掉出目标才救判。
     *
     * 保护类型与保护分数只在 mode==3 时生效；其他模式忽略并归零，避免旧存档里
     * 残留的保护参数在 AP/分数/准确率模式下意外改变判定。
     */
    public static ControlsConfig parse(int mode,String target,String accuracy,String text,String guardKindValue,String guardScoreValue) {
        if(mode<0||mode>MODE_GUARD)throw new IllegalArgumentException("未知游玩模式");
        int t=1000000,a=10000;
        // Only the active target is required. Retain valid inactive values;
        // normalize empty/invalid hidden fields before saving or JNI dispatch.
        if(mode==MODE_SCORE)t=bounded(target,0,1000000,"目标分数");
        else try{t=bounded(target,0,1000000,"目标分数");}catch(IllegalArgumentException ignored){}
        if(mode==MODE_ACCURACY)a=percentage(accuracy);
        else try{a=percentage(accuracy);}catch(IllegalArgumentException ignored){}
        int guard=0,guardTarget=0;
        if(mode==MODE_GUARD){
            guard=guardKind(guardKindValue);
            if(guard<1||guard>3)throw new IllegalArgumentException("判定保护须选择保护 AP、保护 FC 或保护分数");
            // 保护分数是「分数下限」。保护 AP / 保护 FC 不要求填，留空即 0；
            // 保护分数类型必须给出下限，否则这条规则等于没开。
            if(guardScoreValue!=null&&!guardScoreValue.trim().isEmpty())
                guardTarget=bounded(guardScoreValue,0,1000000,"保护分数");
            if(guard==3&&guardTarget<1)throw new IllegalArgumentException("保护分数须为 1～1000000");
        }
        if(text==null||text.length()>96000)throw new IllegalArgumentException("规则列表过长");
        ArrayList<Rule> rules=new ArrayList<Rule>();
        if(!text.trim().isEmpty())for(String row:text.trim().split(";",-1)){
            String[] p=row.split(":",-1);if(p.length!=6&&p.length!=7)throw new IllegalArgumentException("规则格式损坏");
            int type=p.length==7?bounded(p[6],0,4,"按键种类"):0;
            int j=bounded(p[0],0,5,"判定类型"),pos=bounded(p[1],1,6,"位置类型"),s=bounded(p[2],1,20000,"起始音符"),e=bounded(p[3],1,20000,"结束音符"),k=bounded(p[4],1,20000,"数量");
            if(pos==2&&e<s)throw new IllegalArgumentException("范围结束不能小于起始");
            LinkedHashSet<Integer> values=new LinkedHashSet<Integer>();
            if(p[5].length()>12000)throw new IllegalArgumentException("编号列表过长");
            if(!p[5].trim().isEmpty())for(String item:p[5].replace('，',',').split(",",-1)){
                values.add(bounded(item,1,20000,"音符编号"));if(values.size()>1024)throw new IllegalArgumentException("每条规则最多 1024 个编号");
            }
            if(pos==3&&values.isEmpty())throw new IllegalArgumentException("请填写编号列表");
            int[] ids=new int[values.size()];int i=0;for(int v:values)ids[i++]=v;
            rules.add(new Rule(j,pos,s,e,k,ids,type));if(rules.size()>16)throw new IllegalArgumentException("最多添加 16 条规则");
        }
        return new ControlsConfig(mode,t,a,guard,guardTarget,text.trim(),rules);
    }
}
