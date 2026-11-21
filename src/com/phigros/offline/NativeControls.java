package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
public final class NativeControls {
    private NativeControls(){}
    private static native boolean configure(int mode,int target,int accuracy,boolean blockTouch,int assistGuard,int guardScore,int[] rules);
    static native boolean configureForcedResult(boolean enabled,int score,int accuracy,int perfect,int good,int bad,int miss,int combo);
    private static native int[] status();
    public static SharedPreferences preferences(Context c) {return c.getSharedPreferences("offline_controls",Context.MODE_PRIVATE);}
    public static ControlsConfig load(Context c) {
        SharedPreferences p=preferences(c);
        String rules=p.getString("rules",null);
        try {
            String score=p.getString("score","1000000");
            if(rules==null){int j=p.getInt("judgment",0),position=p.getInt("position",0);rules=j>0&&position>0?j+":"+position+":"+p.getString("start","1")+":"+p.getString("end","1")+":"+p.getString("count","1")+":"+p.getString("indices",""):"";}
            // 判定保护的类型与分数分开存储，只有 goal_mode==3 时才会被 parse 采纳。
            return ControlsConfig.parse(
                p.getInt("goal_mode",Integer.parseInt(score)<1000000?1:0),
                score,
                p.getString("accuracy","100"),
                rules,
                Integer.toString(assistGuard(c)),
                Integer.toString(guardScore(c)));
        }catch(IllegalArgumentException error){
            // 旧存档可能残留 mode=3 但缺少保护参数。退回手动模式而不是丢掉整份规则。
            try {return ControlsConfig.parse(ControlsConfig.MODE_AP,"1000000","100",rules==null?"":rules);}
            catch(IllegalArgumentException ignored){return ControlsConfig.parse(ControlsConfig.MODE_AP,"1000000","100","");}
        }
    }
    public static boolean blockTouch(Context c){return preferences(c).getBoolean("block_touch",true);}
    public static int assistGuard(Context c){int v=preferences(c).getInt("assist_guard",0);return v>=0&&v<=3?v:0;}
    public static int guardScore(Context c){int v=preferences(c).getInt("guard_score",0);return v>=0&&v<=1000000?v:0;}
    public static void apply(ControlsConfig c) {apply(c,true,c.guardKind,c.guardScore);}
    public static void apply(ControlsConfig c,boolean block,int guard,int score) {
        if(guard<0||guard>3||score<0||score>1000000)throw new IllegalArgumentException("成绩保护参数无效");
        if(LaunchActivity.isNativeLoaded() && !configure(c.mode,c.target,c.accuracy,block,guard,score,c.packed))
            throw new IllegalArgumentException("原生控制拒绝此配置");
    }
    public static void save(Context context,ControlsConfig c){
        save(context,c,blockTouch(context),c.guardKind,c.guardScore);
    }
    public static void save(Context context,ControlsConfig c,boolean block,int guard,int score){
        apply(c,block,guard,score);
        if(!preferences(context).edit().putInt("goal_mode",c.mode).putString("score",Integer.toString(c.target)).putString("accuracy",new java.math.BigDecimal(c.accuracy).divide(new java.math.BigDecimal(100)).toPlainString()).putString("rules",c.encodedRules).putBoolean("block_touch",block).putInt("assist_guard",guard).putInt("guard_score",score).commit())throw new IllegalStateException("配置保存失败");
    }
    public static int[] getStatus() {return LaunchActivity.isNativeLoaded()?status():new int[21];}
    /**
     * 结算后把强制结果写进本地历史。
     *
     * 只在强制显示开启时调用；失败只记录日志，不影响本局结算已经显示的内容。
     * chartKey 由调用方从当前谱面取得，缺失时直接跳过而不是猜一个。
     */
    public static void commitForcedResult(Context c,String chartKey,int score,float accuracy,int combo){
        if(chartKey==null||chartKey.isEmpty())return;
        try{RksEditor.applyForcedResult(c,chartKey,score,accuracy,combo);}
        catch(Exception error){android.util.Log.e("PhigrosOffline","forced result history skipped",error);}
    }
}
