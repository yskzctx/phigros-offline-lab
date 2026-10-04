package com.phigros.offline;
import java.util.LinkedHashSet;
public final class ControlsConfig {
    public final int target,judgment,position,start,end,count;
    public final int[] indices;
    private ControlsConfig(int t,int j,int p,int s,int e,int c,int[] list) {
        target=t;judgment=j;position=p;start=s;end=e;count=c;indices=list;
    }
    private static int bounded(String s,int low,int high,String label) {
        try {int v=Integer.parseInt(s.trim());if(v>=low && v<=high)return v;}
        catch(NumberFormatException ignored){}
        throw new IllegalArgumentException(label+"须为 "+low+"～"+high+" 的整数");
    }
    public static ControlsConfig parse(String target,int judgment,int position,String start,String end,String count,String list) {
        if(judgment<0 || judgment>3 || position<0 || position>6)throw new IllegalArgumentException("未知判定模式");
        int t=bounded(target,0,1000000,"目标分数");
        int s=bounded(start,1,20000,"起始音符"),e=bounded(end,1,20000,"结束音符"),c=bounded(count,1,20000,"数量");
        if(position==2 && e<s)throw new IllegalArgumentException("范围结束不能小于起始");
        LinkedHashSet<Integer> values=new LinkedHashSet<Integer>();
        if(list.length()>12000)throw new IllegalArgumentException("编号列表过长");
        if(!list.trim().isEmpty())for(String item:list.replace('，',',').split(",",-1)) {
            values.add(bounded(item,1,20000,"音符编号"));
            if(values.size()>1024)throw new IllegalArgumentException("最多指定 1024 个音符");
        }
        if(position==3 && judgment!=0 && values.isEmpty())throw new IllegalArgumentException("请填写音符编号列表");
        int[] indices=new int[values.size()];int i=0;for(int v:values)indices[i++]=v;
        return new ControlsConfig(t,judgment,position,s,e,c,indices);
    }
}
