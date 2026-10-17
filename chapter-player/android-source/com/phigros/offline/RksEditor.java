package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.*;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public final class RksEditor {
    private static final Pattern ENTRY=Pattern.compile("<string\\b[^>]*\\bname=\"([^\"]+)\"[^>]*>(.*?)</string>",Pattern.DOTALL);
    private RksEditor(){}
    private static byte[] derive(String s) {
        byte[] raw=s.getBytes(StandardCharsets.UTF_8),out=new byte[raw.length];
        for(int i=0;i<raw.length;i++){int v=raw[i]&255;out[i]=(byte)((raw[(i+1)%raw.length]&255)^(Integer.reverse(v)>>>24));}
        return out;
    }
    private static String encrypt(String value)throws Exception {
        // Compatibility with the game's existing local PlayerPrefs format, not new credential storage.
        Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(derive("Phigros.enc.j57vnvr8wlZssXM7eWpa"),"AES"),new IvParameterSpec(derive("Q4zHm5vUEMJJ3iS9")));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8))),"UTF-8");
    }
    private static String decrypt(String value)throws Exception {
        Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(derive("Phigros.enc.j57vnvr8wlZssXM7eWpa"),"AES"),new IvParameterSpec(derive("Q4zHm5vUEMJJ3iS9")));
        return new String(c.doFinal(Base64.getDecoder().decode(URLDecoder.decode(value,"UTF-8"))),StandardCharsets.UTF_8);
    }
    static List<RksPlanner.Chart> catalog(Context c)throws IOException {
        ArrayList<RksPlanner.Chart> result=new ArrayList<RksPlanner.Chart>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("offline/chart-catalog.tsv"),"UTF-8"))){
            String s;while((s=r.readLine())!=null){String[] p=s.split("\t");if(p.length!=2)throw new IOException("谱面定数目录损坏");result.add(new RksPlanner.Chart(p[0],Float.parseFloat(p[1])));}
        }
        if(result.size()!=1037)throw new IOException("谱面目录版本不一致");return result;
    }
    public static float validate(Context c,String input)throws IOException {
        if(!input.trim().matches("[0-9]+(\\.[0-9]{1,4})?"))throw new IllegalArgumentException("RKS 支持最多四位小数，例如 16.25");
        List<RksPlanner.Chart> charts=catalog(c);
        float value=Float.parseFloat(input.trim()),max=Math.round(RksPlanner.maximum(charts)*100f)/100f;
        if(!Float.isFinite(value) || value<0 || value>max || (value>0 && value<RksPlanner.minimumPositive(charts)))
            throw new IllegalArgumentException("RKS 超出真实可达范围；上限 17.32 表示恢复全 AP");
        return value;
    }
    private static String read(File f)throws IOException {
        if(f.length()>4*1024*1024)throw new IOException("存档文件过大");
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static void copy(File a,File b)throws IOException {try(InputStream in=new FileInputStream(a)){LaunchActivity.copyAtomic(in,b);}}
    private static String replace(String raw,Map<String,String> changes)throws IOException {
        Matcher m=ENTRY.matcher(raw);StringBuffer out=new StringBuffer();int found=0;
        while(m.find()) {
            String value=changes.get(m.group(1));
            if(value==null)m.appendReplacement(out,Matcher.quoteReplacement(m.group()));
            else {String entry=m.group();int a=entry.indexOf('>')+1,b=entry.lastIndexOf("</string>");
                m.appendReplacement(out,Matcher.quoteReplacement(entry.substring(0,a)+value+entry.substring(b)));found++;}
        }
        m.appendTail(out);if(found!=changes.size())throw new IOException("实际存档字段与版本不一致，未写入："+found+" / "+changes.size());
        return out.toString();
    }
    private static Map<String,String> entries(String raw){Map<String,String> result=new HashMap<String,String>();Matcher m=ENTRY.matcher(raw);while(m.find())result.put(m.group(1),m.group(2));return result;}
    private static String seedData(String raw)throws Exception {
        Map<String,String> saved=entries(raw),changes=new HashMap<String,String>();long amount=0,factor=1;
        for(int i=0;i<5;i++){String key=encrypt("NumOfMoney"+i),value=saved.get(key);if(value==null)throw new IOException("本地 Data 字段缺失");int part=Integer.parseInt(decrypt(value));if(part<0)throw new IOException("本地 Data 数量异常");amount=Math.addExact(amount,Math.multiplyExact((long)part,factor));factor=Math.multiplyExact(factor,1024);}
        long minimum=10000L*1024L;
        if(amount>=minimum)return raw;
        for(int i=0;i<5;i++){changes.put(encrypt("NumOfMoney"+i),encrypt(Long.toString(minimum%1024)));minimum/=1024;}
        return replace(raw,changes);
    }
    public static synchronized void applyPending(Context c)throws IOException {
        if(!"com.PigeonGames.Phigros.offline".equals(c.getPackageName()))throw new IOException("拒绝修改其他包的存档");
        SharedPreferences prefs=NativeControls.preferences(c);
        File state=new File(c.getFilesDir(),"offline-rks"),backups=new File(state,"backups"),txn=new File(state,"transaction");
        File marker=new File(txn,"ready.properties");
        boolean seedData=!prefs.getBoolean("data_10000mb_v165",false);
        if(!marker.isFile() && !prefs.getBoolean("rks_pending",false) && !prefs.getBoolean("rks_restore",false)&&!prefs.getBoolean("all_ap_pending",false)&&!seedData)return;
        String[] names={c.getPackageName()+".v2.playerprefs.xml","com.PigeonGames.Phigros.v2.playerprefs.xml"};
        File games=new File(c.getApplicationInfo().dataDir,"shared_prefs");
        Properties meta=new Properties();
        try {
            if(marker.isFile()) {try(InputStream in=new FileInputStream(marker)){meta.load(in);}}
            else {
                if(!txn.isDirectory() && !txn.mkdirs())throw new IOException("无法创建 RKS 事务目录");
                boolean restore=prefs.getBoolean("rks_restore",false),allAP=prefs.getBoolean("all_ap_pending",false);
                Map<String,String> updates=new HashMap<String,String>();
                List<RksPlanner.Chart> charts=catalog(c);
                if(restore) {
                    File backup=new File(prefs.getString("rks_backup",""));
                    if(!backup.getCanonicalPath().startsWith(backups.getCanonicalPath()+File.separator))throw new IOException("无有效历史备份");
                    for(String name:names){Map<String,String> previous=entries(read(new File(backup,name)));updates.clear();for(RksPlanner.Chart chart:charts){String key=encrypt(chart.key);String value=previous.get(key);if(value==null)throw new IOException("历史备份内容不完整");updates.put(key,value);}
                        String edited=replace(read(new File(games,name)),updates);if(seedData)edited=seedData(edited);
                        try(InputStream in=new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8))){LaunchActivity.copyAtomic(in,new File(txn,name));}}
                    meta.setProperty("status","已恢复修改前历史成绩");
                } else {
                    List<RksPlanner.Record> records=allAP?RksPlanner.allAP(charts):prefs.getBoolean("rks_pending",false)?RksPlanner.generate(charts,validate(c,prefs.getString("rks_target",""))):null;
                    int ap=0;
                    if(records!=null)for(RksPlanner.Record r:records){updates.put(encrypt(r.chart.key),encrypt(r.json()));if(r.ap)ap++;}
                    File backup=new File(backups,Long.toString(System.currentTimeMillis()));
                    for(String name:names) {
                        File source=new File(games,name);String edited=replace(read(source),updates);if(seedData)edited=seedData(edited);
                        copy(source,new File(backup,name));
                        try(InputStream in=new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8))){LaunchActivity.copyAtomic(in,new File(txn,name));}
                    }
                    meta.setProperty("backup",backup.getAbsolutePath());
                    meta.setProperty("status",records==null?"初始 Data 已准备：至少 10000 MB":String.format(Locale.ROOT,"历史 RKS %.4f，AP %d / 1037",RksPlanner.calculate(records),ap));
                }
                if(seedData)meta.setProperty("dataSeeded","true");
                ByteArrayOutputStream out=new ByteArrayOutputStream();meta.store(out,"Prepared independent local history transaction");
                try(InputStream in=new ByteArrayInputStream(out.toByteArray())){LaunchActivity.copyAtomic(in,marker);}
            }
            // Commit both prepared files. A killed process resumes from the durable journal.
            for(String name:names){copy(new File(txn,name),new File(games,name));new File(games,name+".bak").delete();}
            SharedPreferences.Editor update=prefs.edit().putBoolean("rks_pending",false).putBoolean("rks_restore",false).putBoolean("all_ap_pending",false).putString("rks_status",meta.getProperty("status","历史成绩已更新"));
            if(meta.getProperty("dataSeeded","false").equals("true"))update.putBoolean("data_10000mb_v165",true);
            if(meta.containsKey("backup"))update.putString("rks_backup",meta.getProperty("backup"));
            if(!update.commit())throw new IOException("无法保存 RKS 完成状态");
            if(!marker.delete())throw new IOException("无法清除 RKS 事务标记");
            Log.i("PhigrosOffline","rks.history.applied; "+meta.getProperty("status"));
        } catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("历史成绩生成失败："+e.getClass().getSimpleName(),e);}
    }
}
