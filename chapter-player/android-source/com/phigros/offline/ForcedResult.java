package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
import java.math.BigDecimal;

/**
 * 结算页强制显示（独立开关）。
 *
 * 与自动游玩、判定保护完全解耦：这三者各自有自己的开关与偏好键，任意组合都
 * 不影响另外两者。开启后结算页显示这里配置的数值，包括真实一局不可能出现的
 * 组合。关闭时行为与未实现此功能时完全一致。
 */
public final class ForcedResult {
    public static final String PREFIX="force_result_";
    private ForcedResult(){}

    public static final class Values {
        public final boolean enabled;
        public final int score,accuracy,perfect,good,bad,miss,combo;
        Values(boolean e,int s,int a,int p,int g,int b,int m,int c){
            enabled=e;score=s;accuracy=a;perfect=p;good=g;bad=b;miss=m;combo=c;
        }
    }

    private static int bounded(SharedPreferences p,String key,int low,int high,String label){
        int v;
        try{v=p.getInt(key,0);}
        catch(ClassCastException error){throw new IllegalArgumentException(label+"保存类型异常");}
        if(v<low||v>high)throw new IllegalArgumentException(label+"须为 "+low+"～"+high);
        return v;
    }

    public static Values load(Context c){
        SharedPreferences p=NativeControls.preferences(c);
        boolean enabled=p.getBoolean(PREFIX+"enabled",false);
        int score=bounded(p,PREFIX+"score",0,99999999,"结算分数");
        int accuracy=bounded(p,PREFIX+"accuracy",0,10000,"结算准确率");
        int perfect=bounded(p,PREFIX+"perfect",0,999999,"Perfect 数量");
        int good=bounded(p,PREFIX+"good",0,999999,"Good 数量");
        int bad=bounded(p,PREFIX+"bad",0,999999,"Bad 数量");
        int miss=bounded(p,PREFIX+"miss",0,999999,"Miss 数量");
        int combo=bounded(p,PREFIX+"combo",0,999999,"最大连击");
        return new Values(enabled,score,accuracy,perfect,good,bad,miss,combo);
    }

    /** 准确率用「百分数两位小数」字符串输入，内部存万分位整数。 */
    public static int parseAccuracy(String text){
        if(text==null||!text.trim().matches("[0-9]{1,3}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("准确率支持两位小数，例如 99.50");
        int value=new BigDecimal(text.trim()).multiply(new BigDecimal(100)).intValueExact();
        if(value<0||value>10000)throw new IllegalArgumentException("准确率须为 0～100%");
        return value;
    }

    public static String formatAccuracy(int stored){
        return new BigDecimal(stored).divide(new BigDecimal(100)).toPlainString();
    }

    public static Values parse(boolean enabled,String score,String accuracy,String perfect,
                               String good,String bad,String miss,String combo){
        return new Values(enabled,
            boundedText(score,0,99999999,"结算分数"),
            accuracy==null||accuracy.trim().isEmpty()?0:parseAccuracy(accuracy),
            boundedText(perfect,0,999999,"Perfect 数量"),
            boundedText(good,0,999999,"Good 数量"),
            boundedText(bad,0,999999,"Bad 数量"),
            boundedText(miss,0,999999,"Miss 数量"),
            boundedText(combo,0,999999,"最大连击"));
    }

    private static int boundedText(String text,int low,int high,String label){
        if(text==null||text.trim().isEmpty())return 0;
        int v;
        try{v=Integer.parseInt(text.trim());}
        catch(NumberFormatException error){throw new IllegalArgumentException(label+"须为整数");}
        if(v<low||v>high)throw new IllegalArgumentException(label+"须为 "+low+"～"+high);
        return v;
    }

    public static void save(Context c,Values v){
        apply(v);
        SharedPreferences.Editor edit=NativeControls.preferences(c).edit()
            .putBoolean(PREFIX+"enabled",v.enabled)
            .putInt(PREFIX+"score",v.score)
            .putInt(PREFIX+"accuracy",v.accuracy)
            .putInt(PREFIX+"perfect",v.perfect)
            .putInt(PREFIX+"good",v.good)
            .putInt(PREFIX+"bad",v.bad)
            .putInt(PREFIX+"miss",v.miss)
            .putInt(PREFIX+"combo",v.combo);
        if(!edit.commit())throw new IllegalStateException("结算显示设置保存失败");
    }

    public static void apply(Values v){
        if(LaunchActivity.isNativeLoaded()&&!NativeControls.configureForcedResult(
                v.enabled,v.score,v.accuracy,v.perfect,v.good,v.bad,v.miss,v.combo))
            throw new IllegalArgumentException("原生层拒绝该结算显示配置");
    }

    /** 启动时把已保存的配置推给原生层；失败只记录，不影响启动。 */
    public static void initialize(Context c){
        try{apply(load(c));}
        catch(IllegalArgumentException error){android.util.Log.e("PhigrosOffline","forced result config ignored",error);}
    }
}
