package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
public final class NativeControls {
    private NativeControls(){}
    private static native boolean configure(int target,int judgment,int position,int start,int end,int count,int[] indices);
    private static native int[] status();
    public static SharedPreferences preferences(Context c) {return c.getSharedPreferences("offline_controls",Context.MODE_PRIVATE);}
    public static ControlsConfig load(Context c) {
        SharedPreferences p=preferences(c);
        try {return ControlsConfig.parse(p.getString("score","1000000"),p.getInt("judgment",0),p.getInt("position",0),
            p.getString("start","1"),p.getString("end","1"),p.getString("count","1"),p.getString("indices",""));}
        catch(IllegalArgumentException error){return ControlsConfig.parse("1000000",0,0,"1","1","1","");}
    }
    public static void apply(ControlsConfig c) {
        if(LaunchActivity.isNativeLoaded() && !configure(c.target,c.judgment,c.position,c.start,c.end,c.count,c.indices))
            throw new IllegalArgumentException("原生控制拒绝此配置");
    }
    public static int[] getStatus() {return LaunchActivity.isNativeLoaded()?status():new int[]{0,0,0,0,0,0};}
}
