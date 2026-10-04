package com.phigros.offline;
import java.util.*;
import java.math.BigDecimal;
public final class ControlsConfig {
    public final int mode,target,accuracy;
    public final String encodedRules;
    public final Rule[] rules;
    public final int[] packed;
    public static final class Rule {
        public final int judgment,position,start,end,count;
        public final int[] indices;
        Rule(int j,int p,int s,int e,int c,int[] a){judgment=j;position=p;start=s;end=e;count=c;indices=a;}
    }
    private ControlsConfig(int m,int t,int a,String text,List<Rule> list) {
        mode=m;target=t;accuracy=a;encodedRules=text;rules=list.toArray(new Rule[0]);
        int size=1;for(Rule r:rules)size+=6+r.indices.length;
        packed=new int[size];packed[0]=rules.length;int i=1;
        for(Rule r:rules){packed[i++]=r.judgment;packed[i++]=r.position;packed[i++]=r.start;packed[i++]=r.end;packed[i++]=r.count;packed[i++]=r.indices.length;for(int v:r.indices)packed[i++]=v;}
    }
    private static int bounded(String s,int low,int high,String label) {
        try {int v=Integer.parseInt(s.trim());if(v>=low && v<=high)return v;}
        catch(NumberFormatException ignored){}
        throw new IllegalArgumentException(label+"须为 "+low+"～"+high+" 的整数");
    }
    public static ControlsConfig parse(int mode,String target,String accuracy,String text) {
        if(mode<0||mode>2)throw new IllegalArgumentException("未知游玩模式");
        int t=bounded(target,0,1000000,"目标分数");
        if(accuracy==null||!accuracy.trim().matches("[0-9]{1,3}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("准确率支持两位小数，例如 90.25");
        int a=new BigDecimal(accuracy.trim()).multiply(new BigDecimal(100)).intValueExact();
        if(a<0||a>10000)throw new IllegalArgumentException("准确率须为 0～100%");
        if(text==null||text.length()>96000)throw new IllegalArgumentException("规则列表过长");
        ArrayList<Rule> rules=new ArrayList<Rule>();
        if(!text.trim().isEmpty())for(String row:text.trim().split(";",-1)){
            String[] p=row.split(":",-1);if(p.length!=6)throw new IllegalArgumentException("规则格式损坏");
            int j=bounded(p[0],1,3,"判定类型"),pos=bounded(p[1],1,6,"位置类型"),s=bounded(p[2],1,20000,"起始音符"),e=bounded(p[3],1,20000,"结束音符"),k=bounded(p[4],1,20000,"数量");
            if(pos==2&&e<s)throw new IllegalArgumentException("范围结束不能小于起始");
            LinkedHashSet<Integer> values=new LinkedHashSet<Integer>();
            if(p[5].length()>12000)throw new IllegalArgumentException("编号列表过长");
            if(!p[5].trim().isEmpty())for(String item:p[5].replace('，',',').split(",",-1)){
                values.add(bounded(item,1,20000,"音符编号"));if(values.size()>1024)throw new IllegalArgumentException("每条规则最多 1024 个编号");
            }
            if(pos==3&&values.isEmpty())throw new IllegalArgumentException("请填写编号列表");
            int[] ids=new int[values.size()];int i=0;for(int v:values)ids[i++]=v;
            rules.add(new Rule(j,pos,s,e,k,ids));if(rules.size()>16)throw new IllegalArgumentException("最多添加 16 条规则");
        }
        return new ControlsConfig(mode,t,a,text.trim(),rules);
    }
}
