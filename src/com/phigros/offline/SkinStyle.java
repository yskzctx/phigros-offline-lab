package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;

/**
 * 皮肤包的「非图片」部分：音效、击中特效、判定线颜色与音符缩放。
 *
 * 图片替换走 {@link SkinController} 的原生贴图链路；这里处理的是游戏自己用
 * PlayerPrefs 保存的那组开关与数值，因此不需要新的原生偏移，也不会因游戏版本
 * 升级而失效。
 *
 * 每一项都对应 4.0.1 里真实存在的键（已在 global-metadata.dat 中确认）：
 *   hitFxIsOn            击中特效开关
 *   HitFXVolume          击中特效音量
 *   SEVolume             音效音量
 *   noteScale            音符缩放
 *   lineColor            判定线颜色
 *
 * 未在包里提供的项保持游戏当前设置，不会被清成默认值。
 */
public final class SkinStyle {
    /** 包内 info.yml 支持的样式键，与 Phira 图包字段同名。 */
    static final String[] NUMERIC_KEYS={"hitFxDuration","hitFxScale"};
    static final String[] COLOR_KEYS={"colorPerfect","colorGood","lineColor"};
    static final String[] BOOL_KEYS={"hitFxRotate","hideParticles","hitFxTinted"};

    private SkinStyle(){}

    /** 一个皮肤包想应用到游戏设置的样式集合；缺省项为 null 表示不改。 */
    public static final class Style {
        public final Boolean hitFxEnabled;
        public final Integer hitFxVolume;
        public final Integer soundVolume;
        public final Integer noteScale;
        public final Integer lineColor;
        public final Integer colorPerfect;
        public final Integer colorGood;
        Style(Boolean h,int hv,int sv,int ns,Integer lc,Integer cp,Integer cg){
            hitFxEnabled=h;hitFxVolume=hv;soundVolume=sv;noteScale=ns;lineColor=lc;colorPerfect=cp;colorGood=cg;
        }
        public boolean isEmpty(){return hitFxEnabled==null&&hitFxVolume<0&&soundVolume<0&&noteScale<0&&lineColor==null&&colorPerfect==null&&colorGood==null;}
    }

    private static SharedPreferences playerPrefs(Context c){
        return c.getSharedPreferences(c.getPackageName()+".v2.playerprefs",Context.MODE_PRIVATE);
    }

    private static Integer parseColor(String raw){
        if(raw==null||raw.trim().isEmpty())return null;
        String v=raw.trim();
        try{
            long value=v.startsWith("0x")||v.startsWith("0X")?Long.parseLong(v.substring(2),16):Long.parseLong(v);
            if(value<0||value>0xffffffffL)throw new NumberFormatException();
            return (int)(value&0xffffffffL);
        }catch(NumberFormatException error){throw new IllegalArgumentException("颜色须为 32 位数值："+raw);}
    }

    private static int parsePercent(String raw,String label){
        return parseBounded(raw,label,0,100);
    }

    /** 音符缩放允许放大，所以上限比音量高。 */
    private static int parseScale(String raw){
        return parseBounded(raw,"音符缩放",0,300);
    }

    private static int parseBounded(String raw,String label,int low,int high){
        if(raw==null||raw.trim().isEmpty())return -1;
        int value;
        try{value=Integer.parseInt(raw.trim());}
        catch(NumberFormatException error){throw new IllegalArgumentException(label+"须为 "+low+"～"+high+" 的整数");}
        if(value<low||value>high)throw new IllegalArgumentException(label+"须为 "+low+"～"+high+" 的整数");
        return value;
    }

    /** 从皮肤包的 info.yml 读取样式；缺失的键留空表示沿用游戏当前值。 */
    public static Style fromMeta(java.util.Map<String,String> meta){
        Boolean hitFx=null;
        if(meta.containsKey("hitFxIsOn"))hitFx="true".equals(meta.get("hitFxIsOn"));
        return new Style(
            hitFx,
            parsePercent(meta.get("hitFxVolume"),"击中特效音量"),
            parsePercent(meta.get("soundVolume"),"音效音量"),
            parseScale(meta.get("noteScale")),
            parseColor(meta.get("lineColor")),
            parseColor(meta.get("colorPerfect")),
            parseColor(meta.get("colorGood")));
    }

    /** 把样式写进游戏设置；只写包内提供的项。 */
    public static void apply(Context c,Style style)throws IOException{
        if(style.isEmpty())return;
        SharedPreferences.Editor edit=playerPrefs(c).edit();
        if(style.hitFxEnabled!=null)edit.putBoolean("hitFxIsOn",style.hitFxEnabled);
        if(style.hitFxVolume>=0)edit.putFloat("HitFXVolume",style.hitFxVolume/100f);
        if(style.soundVolume>=0)edit.putFloat("SEVolume",style.soundVolume/100f);
        if(style.noteScale>=0)edit.putFloat("noteScale",style.noteScale/100f);
        if(style.lineColor!=null)edit.putInt("lineColor",style.lineColor);
        if(style.colorPerfect!=null)edit.putInt("perfectColor",style.colorPerfect);
        if(style.colorGood!=null)edit.putInt("goodColor",style.colorGood);
        if(!edit.commit())throw new IOException("皮肤样式写入游戏设置失败");
    }

    /** 描述当前皮肤会改变哪些设置，供面板显示。 */
    public static String summary(Style style){
        if(style==null||style.isEmpty())return "图片皮肤：不改动音效、特效或颜色";
        StringBuilder text=new StringBuilder("同时应用：");
        int count=0;
        if(style.hitFxEnabled!=null){text.append(style.hitFxEnabled?"特效开 ":"特效关 ");count++;}
        if(style.hitFxVolume>=0){text.append("特效音量 ").append(style.hitFxVolume).append("% ");count++;}
        if(style.soundVolume>=0){text.append("音效音量 ").append(style.soundVolume).append("% ");count++;}
        if(style.noteScale>=0){text.append("音符缩放 ").append(style.noteScale).append("% ");count++;}
        if(style.lineColor!=null){text.append("判定线颜色 ");count++;}
        if(style.colorPerfect!=null){text.append("Perfect 颜色 ");count++;}
        if(style.colorGood!=null){text.append("Good 颜色 ");count++;}
        return count==0?"图片皮肤：不改动音效、特效或颜色":text.toString().trim();
    }
}
