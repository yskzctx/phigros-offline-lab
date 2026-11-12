package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
public final class NativeControls {
    private NativeControls(){}
    private static native boolean configure(int mode,int target,int accuracy,boolean blockTouch,int assistGuard,int guardScore,int[] rules);
    private static native int[] status();
    public static SharedPreferences preferences(Context c) {return c.getSharedPreferences("offline_controls",Context.MODE_PRIVATE);}
    public static ControlsConfig load(Context c) {
        SharedPreferences p=preferences(c);
        try {
            String score=p.getString("score","1000000"),rules=p.getString("rules",null);
            if(rules==null){int j=p.getInt("judgment",0),position=p.getInt("position",0);rules=j>0&&position>0?j+":"+position+":"+p.getString("start","1")+":"+p.getString("end","1")+":"+p.getString("count","1")+":"+p.getString("indices",""):"";}
            return ControlsConfig.parse(p.getInt("goal_mode",Integer.parseInt(score)<1000000?1:0),score,p.getString("accuracy","100"),rules);
        }catch(IllegalArgumentException error){return ControlsConfig.parse(0,"1000000","100","");}
    }
    public static boolean blockTouch(Context c){return preferences(c).getBoolean("block_touch",true);}
    public static int assistGuard(Context c){int v=preferences(c).getInt("assist_guard",0);return v>=0&&v<=3?v:0;}
    public static int guardScore(Context c){int v=preferences(c).getInt("guard_score",0);return v>=0&&v<=1000000?v:0;}
    public static void apply(ControlsConfig c) {apply(c,true,0,0);}
    public static void apply(ControlsConfig c,boolean block,int guard,int score) {
        if(guard<0||guard>3||score<0||score>1000000)throw new IllegalArgumentException("成绩保护参数无效");
        if(LaunchActivity.isNativeLoaded() && !configure(c.mode,c.target,c.accuracy,block,guard,score,c.packed))
            throw new IllegalArgumentException("原生控制拒绝此配置");
    }
    public static void save(Context context,ControlsConfig c){
        save(context,c,blockTouch(context),assistGuard(context),guardScore(context));
    }
    public static void save(Context context,ControlsConfig c,boolean block,int guard,int score){
        apply(c,block,guard,score);
        if(!preferences(context).edit().putInt("goal_mode",c.mode).putString("score",Integer.toString(c.target)).putString("accuracy",new java.math.BigDecimal(c.accuracy).divide(new java.math.BigDecimal(100)).toPlainString()).putString("rules",c.encodedRules).putBoolean("block_touch",block).putInt("assist_guard",guard).putInt("guard_score",score).commit())throw new IllegalStateException("配置保存失败");
    }
    public static int[] getStatus() {return LaunchActivity.isNativeLoaded()?status():new int[21];}
}
