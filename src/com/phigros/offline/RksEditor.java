package com.phigros.offline;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.*;
import java.net.URLEncoder;
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
        float value=Float.parseFloat(input.trim()),max=RksPlanner.maximum(charts);
        if(!Float.isFinite(value) || value<0 || value>max || (value>0 && value<RksPlanner.minimumPositive(charts)))
            throw new IllegalArgumentException("RKS 超出此版本真实可达范围；最高约 17.3166");
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
        m.appendTail(out);if(found!=1037)throw new IOException("实际成绩字段与 157 目录不一致，未写入："+found);
        return out.toString();
    }
    public static synchronized void applyPending(Context c)throws IOException {
        if(!"com.PigeonGames.Phigros.offline".equals(c.getPackageName()))throw new IOException("拒绝修改其他包的存档");
        SharedPreferences prefs=NativeControls.preferences(c);
        File state=new File(c.getFilesDir(),"offline-rks"),backups=new File(state,"backups"),txn=new File(state,"transaction");
        File marker=new File(txn,"ready.properties");
        if(!marker.isFile() && !prefs.getBoolean("rks_pending",false) && !prefs.getBoolean("rks_restore",false))return;
        String[] names={c.getPackageName()+".v2.playerprefs.xml","com.PigeonGames.Phigros.v2.playerprefs.xml"};
        File games=new File(c.getApplicationInfo().dataDir,"shared_prefs");
        Properties meta=new Properties();
        try {
            if(marker.isFile()) {try(InputStream in=new FileInputStream(marker)){meta.load(in);}}
            else {
                if(!txn.isDirectory() && !txn.mkdirs())throw new IOException("无法创建 RKS 事务目录");
                boolean restore=prefs.getBoolean("rks_restore",false);
                if(restore) {
                    File backup=new File(prefs.getString("rks_backup",""));
                    if(!backup.getCanonicalPath().startsWith(backups.getCanonicalPath()+File.separator))throw new IOException("无有效历史备份");
                    for(String name:names)copy(new File(backup,name),new File(txn,name));
                    meta.setProperty("status","已恢复修改前历史成绩");
                } else {
                    float target=validate(c,prefs.getString("rks_target",""));
                    List<RksPlanner.Record> records=RksPlanner.generate(catalog(c),target);
                    Map<String,String> updates=new HashMap<String,String>();int ap=0;
                    for(RksPlanner.Record r:records){updates.put(encrypt(r.chart.key),encrypt(r.json()));if(r.ap)ap++;}
                    File backup=new File(backups,Long.toString(System.currentTimeMillis()));
                    for(String name:names) {
                        File source=new File(games,name);String edited=replace(read(source),updates);
                        copy(source,new File(backup,name));
                        try(InputStream in=new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8))){LaunchActivity.copyAtomic(in,new File(txn,name));}
                    }
                    meta.setProperty("backup",backup.getAbsolutePath());
                    meta.setProperty("status",String.format(Locale.ROOT,"历史 RKS %.4f，AP %d / 1037",RksPlanner.calculate(records),ap));
                }
                ByteArrayOutputStream out=new ByteArrayOutputStream();meta.store(out,"Prepared independent local history transaction");
                try(InputStream in=new ByteArrayInputStream(out.toByteArray())){LaunchActivity.copyAtomic(in,marker);}
            }
            // Commit both prepared files. A killed process resumes from the durable journal.
            for(String name:names){copy(new File(txn,name),new File(games,name));new File(games,name+".bak").delete();}
            SharedPreferences.Editor update=prefs.edit().putBoolean("rks_pending",false).putBoolean("rks_restore",false).putString("rks_status",meta.getProperty("status","历史成绩已更新"));
            if(meta.containsKey("backup"))update.putString("rks_backup",meta.getProperty("backup"));
            if(!update.commit())throw new IOException("无法保存 RKS 完成状态");
            if(!marker.delete())throw new IOException("无法清除 RKS 事务标记");
            Log.i("PhigrosOffline","rks.history.applied; "+meta.getProperty("status"));
        } catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("历史成绩生成失败："+e.getClass().getSimpleName(),e);}
    }
}
