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
            String s;while((s=r.readLine())!=null){
                // Two columns: key and difficulty constant. A blank constant is a chart
                // the game has but whose constant this build's data does not expose -
                // kept for unlocking, excluded from RKS arithmetic.
                String[] p=s.split("\t",-1);if(p.length!=2)throw new IOException("谱面定数目录损坏");
                if(p[0].trim().isEmpty())continue;
                result.add(new RksPlanner.Chart(p[0],p[1].trim().isEmpty()?Float.NaN:Float.parseFloat(p[1])));
            }
        }
        // The entry count is not fixed: a game update adds songs, and the catalog is
        // regenerated for each supported build. What must hold is that the table is
        // non-empty and internally consistent (unique keys, finite constants), which
        // catches a truncated or corrupt asset without pinning one version's size.
        if(result.isEmpty())throw new IOException("谱面定数目录为空");
        HashSet<String> seen=new HashSet<String>();
        for(RksPlanner.Chart chart:result){
            if(!seen.add(chart.key))throw new IOException("谱面定数目录有重复条目："+chart.key);
            // NaN is the documented "unknown constant" marker; anything else must be a
            // plausible constant so a corrupt table is still caught.
            if(!Float.isNaN(chart.difficulty)
               &&(!Float.isFinite(chart.difficulty)||chart.difficulty<=0f||chart.difficulty>100f))
                throw new IOException("谱面定数超出合理范围："+chart.key);
        }
        return result;
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

    private static final Set<String> PRESERVED_KEYS = new HashSet<String>(Arrays.asList(
        "playerID", "playerIDUpdated", "PlayerIdAppear", "selfIntro",
        "UserIconKeyName", "UserIllustrationKeyName", "UserIllustrationKeyLevel",
        "ChallengeModeRank", "autoSync", "offset", "bright", "noteScale",
        "musicVolume", "SEVolume", "HitFXVolume", "hitFxIsOn", "chordSupport",
        "aPfCisOn", "chartMirror", "IsLowRes", "IsVisionReplayEnable",
        "IsVisionReplayOn", "SortDirectionInSongSelector", "SortTypeInSongSelector",
        "dspSliderValue", "AntiAddictionRestrictType", "readPrivacyPolicy",
        "splashWarningDisplayedV3_0", "IsFirstRun", "IsOldScoreClearedV390",
        "songsUpdateInfoVersionCode"
    ));

    public static Map<String,String> unlockEntries(String allApXml) throws Exception {
        Map<String,String> result = new LinkedHashMap<String,String>();
        Matcher m = ENTRY.matcher(allApXml);
        while (m.find()) {
            String encName = m.group(1);
            String encVal = m.group(2);
            String plainName = decrypt(encName);
            if (plainName.contains(".Record.") || plainName.startsWith("NumOfMoney")) continue;
            if (PRESERVED_KEYS.contains(plainName)) continue;
            result.put(encName, encVal);
        }
        return result;
    }

    /**
     * Record entries for catalog charts the save has no entry for.
     *
     * "全解锁" copies unlock flags out of the shipped all-ap dump, but that dump is a
     * 157-era snapshot: 1037 Record entries against a catalog of 1072 charts. The 35 charts
     * added later had no entry anywhere, so they stayed locked. A chart counts as unlocked
     * once its Record key exists, so a zero-score record is enough - it unlocks the song
     * without claiming a result. Existing records are left alone so real scores survive.
     */
    public static Map<String,String> missingUnlockEntries(Map<String,String> existing,
                                                          java.util.List<RksPlanner.Chart> charts)
            throws Exception {
        Map<String,String> result = new LinkedHashMap<String,String>();
        for (RksPlanner.Chart chart : charts) {
            if (chart.key == null || !chart.key.contains(".Record.")) continue;
            String encKey = encrypt(chart.key);
            if (existing.containsKey(encKey)) continue;
            result.put(encKey, encrypt("{\"s\":0,\"a\":0,\"c\":0}"));
        }
        return result;
    }

    /**
     * Unlock flags for songs the shipped dump does not cover.
     *
     * A song is gated by a `1key<Song>` / `2key<Song>` preference as well as its Record keys.
     * The dump carries only 646 such flags against 329 catalog songs, and 191 songs have no
     * flag at all - so those stay locked even when every Record exists.
     *
     * The value is copied from an existing flag of the same prefix rather than invented, so
     * whatever encoding the game uses is preserved.
     */
    public static Map<String,String> missingUnlockFlags(Map<String,String> dump,
                                                        java.util.List<RksPlanner.Chart> charts)
            throws Exception {
        // Learn what an unlock flag looks like from the dump itself.
        String sampleOne = null, sampleTwo = null;
        for (Map.Entry<String,String> entry : dump.entrySet()) {
            String plain = decrypt(entry.getKey());
            if (plain.startsWith("1key") && sampleOne == null) sampleOne = entry.getValue();
            if (plain.startsWith("2key") && sampleTwo == null) sampleTwo = entry.getValue();
        }
        java.util.Set<String> covered = new java.util.HashSet<String>();
        for (String enc : dump.keySet()) {
            String plain = decrypt(enc);
            if (plain.startsWith("1key")) covered.add(plain.substring(4));
            else if (plain.startsWith("2key")) covered.add(plain.substring(4));
        }
        Map<String,String> result = new LinkedHashMap<String,String>();
        for (RksPlanner.Chart chart : charts) {
            if (chart.key == null) continue;
            String song = chart.key.split("\\.")[0];
            if (song.isEmpty() || covered.contains(song)) continue;
            covered.add(song);   // only emit once per song
            if (sampleOne != null) result.put(encrypt("1key" + song), sampleOne);
            if (sampleTwo != null) result.put(encrypt("2key" + song), sampleTwo);
        }
        return result;
    }

    public static String replaceOrAppend(String raw, Map<String,String> changes) throws IOException {
        Matcher m = ENTRY.matcher(raw); StringBuffer out = new StringBuffer();
        Set<String> matched = new HashSet<String>();
        while(m.find()) {
            String key = m.group(1);
            String value = changes.get(key);
            if(value == null) m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            else {
                matched.add(key);
                String entry = m.group(); int a = entry.indexOf('>') + 1, b = entry.lastIndexOf("</string>");
                m.appendReplacement(out, Matcher.quoteReplacement(entry.substring(0, a) + value + entry.substring(b)));
            }
        }
        m.appendTail(out);
        if(matched.size() != changes.size()) {
            Set<String> existing = entries(out.toString()).keySet();
            StringBuilder additions = new StringBuilder();
            for(Map.Entry<String,String> e : changes.entrySet()) {
                if(!existing.contains(e.getKey())) {
                    additions.append("<string name=\"").append(e.getKey()).append("\">").append(e.getValue()).append("</string>\n");
                }
            }
            int end = out.lastIndexOf("</map>");
            if(end < 0) throw new IOException("存档结构格式异常");
            out.insert(end, additions);
        }
        return out.toString();
    }

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
    /**
     * 把一局「强制结算」写进本地历史，并按新历史重算 RKS。
     *
     * 只写这一张谱面，其它曲目记录保持不变；写入前先备份，走与 RKS 编辑相同的
     * 事务目录，中途被杀可以恢复。调用方负责在结算后触发。
     */
    public static synchronized void applyForcedResult(Context c,String chartKey,int score,float accuracy,int combo)throws IOException{
        if(!"com.PigeonGames.Phigros.offline".equals(c.getPackageName())&&!"com.PigeonGames.Phigros".equals(c.getPackageName()))
            throw new IOException("拒绝修改其他包的存档");
        if(chartKey==null||chartKey.trim().isEmpty())throw new IOException("缺少谱面标识");
        if(accuracy<0f||accuracy>100f)throw new IOException("准确率超出 0～100");
        if(score<0)throw new IOException("分数不能为负");
        SharedPreferences prefs=NativeControls.preferences(c);
        File state=new File(c.getFilesDir(),"offline-rks"),backups=new File(state,"backups");
        if(!backups.isDirectory()&&!backups.mkdirs())throw new IOException("无法创建历史备份目录");
        String[] names={c.getPackageName()+".v2.playerprefs.xml","com.PigeonGames.Phigros.v2.playerprefs.xml"};
        File games=new File(c.getApplicationInfo().dataDir,"shared_prefs");
        File backup=new File(backups,Long.toString(System.currentTimeMillis()));
        if(!backup.mkdirs())throw new IOException("无法创建本次历史备份");
        RksPlanner.Record record=new RksPlanner.Record(new RksPlanner.Chart(chartKey,0f),accuracy,accuracy>=99.999f);
        record.score=score;record.combo=combo;
        Map<String,String> update=new HashMap<String,String>();
        try{
            update.put(encrypt(chartKey),encrypt(record.json()));
            for(String name:names){
                File source=new File(games,name);
                if(!source.isFile())continue;
                copy(source,new File(backup,name));
                String edited=replace(read(source),update);
                try(InputStream in=new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8))){LaunchActivity.copyAtomic(in,source);}
            }
            if(!prefs.edit().putString("rks_backup",backup.getAbsolutePath())
                    .putString("rks_status","已按强制结算写入本地历史 "+chartKey).commit())
                throw new IOException("无法保存强制结算状态");
            Log.i("PhigrosOffline","forced.result.recorded chart="+chartKey+" score="+score+" acc="+accuracy);
        }catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("强制结算写入失败："+e.getClass().getSimpleName(),e);}
    }
    static String mergeMissingCatalogEntries(String current,String seed)throws IOException {
        return CatalogXmlMerge.mergeMissing(current,seed);
    }
    static void mergeAllAPAsset(Context context,File target)throws IOException {
        String seed;
        try(InputStream in=context.getAssets().open("offline/all-ap.xml")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            seed=new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
        if(!target.isFile()||target.length()==0) {
            try(InputStream in=new ByteArrayInputStream(seed.getBytes(StandardCharsets.UTF_8))){
                LaunchActivity.copyAtomic(in,target);
            }
            return;
        }
        String current=read(target),merged=mergeMissingCatalogEntries(current,seed);
        if(merged.equals(current))return;
        try(InputStream in=new ByteArrayInputStream(merged.getBytes(StandardCharsets.UTF_8))){
            LaunchActivity.copyAtomic(in,target);
        }
        Log.i("PhigrosOffline","all-ap.catalogue.merged count="+(entries(merged).size()-entries(current).size()));
    }
    private static String seedData(String raw)throws Exception {
        Map<String,String> saved=entries(raw),changes=new HashMap<String,String>();long amount=0,factor=1;
        // Official 4.0.1 and 4.0.1.2 both use NumOfMoney0..4. A fresh or
        // untouched local save may simply omit them; absence means zero,
        // while a present field that cannot be decoded remains an error.
        for(int i=0;i<5;i++){String key=encrypt("NumOfMoney"+i),value=saved.get(key);int part=0;if(value!=null){part=Integer.parseInt(decrypt(value));if(part<0)throw new IOException("本地 Data 数量异常");}amount=Math.addExact(amount,Math.multiplyExact((long)part,factor));factor=Math.multiplyExact(factor,1024);}
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
        if(!marker.isFile() && !prefs.getBoolean("rks_pending",false) && !prefs.getBoolean("rks_restore",false) && !prefs.getBoolean("all_ap_pending",false) && !prefs.getBoolean("unlock_all_pending",false) && !seedData)return;
        String[] names={c.getPackageName()+".v2.playerprefs.xml","com.PigeonGames.Phigros.v2.playerprefs.xml"};
        File games=new File(c.getApplicationInfo().dataDir,"shared_prefs");
        Properties meta=new Properties();
        try {
            if(marker.isFile()) {try(InputStream in=new FileInputStream(marker)){meta.load(in);}}
            else {
                if(!txn.isDirectory() && !txn.mkdirs())throw new IOException("无法创建 RKS 事务目录");
                boolean restore=prefs.getBoolean("rks_restore",false),allAP=prefs.getBoolean("all_ap_pending",false),unlockOnly=prefs.getBoolean("unlock_all_pending",false);
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
                    if(allAP || unlockOnly) {
                        try(InputStream in=c.getAssets().open("offline/all-ap.xml")){
                            ByteArrayOutputStream out=new ByteArrayOutputStream();
                            byte[] buffer=new byte[65536];int count;
                            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
                            Map<String,String> unlocks=unlockEntries(new String(out.toByteArray(),StandardCharsets.UTF_8));
                            updates.putAll(unlocks);
                            // The shipped dump is a 157-era snapshot (1037 records) while the
                            // catalog now has 1072 charts, so charts added later had no entry
                            // and stayed locked. Give every catalog chart the save lacks a
                            // zero-score record - enough to unlock, without inventing a result.
                            updates.putAll(missingUnlockEntries(updates,charts));
                            // Songs are also gated by 1key/2key flags, and the
                            // dump leaves 191 catalog songs without one.
                            updates.putAll(missingUnlockFlags(unlocks,charts));
                        }
                    }
                    File backup=new File(backups,Long.toString(System.currentTimeMillis()));
                    for(String name:names) {
                        File source=new File(games,name);String edited=replaceOrAppend(read(source),updates);if(seedData)edited=seedData(edited);
                        copy(source,new File(backup,name));
                        try(InputStream in=new ByteArrayInputStream(edited.getBytes(StandardCharsets.UTF_8))){LaunchActivity.copyAtomic(in,new File(txn,name));}
                    }
                    meta.setProperty("backup",backup.getAbsolutePath());
                    // The catalogue size is per-build, so report it from the loaded
                    // table rather than printing a fixed number that goes stale.
                    int chartCount=catalog(c).size();
                    if(unlockOnly)meta.setProperty("status","全曲目与全收藏品已解锁");
                    else if(allAP)meta.setProperty("status",String.format(Locale.ROOT,"全解锁 ＋ 历史 RKS %.4f，AP %d / %d",RksPlanner.calculate(records),ap,chartCount));
                    else meta.setProperty("status",records==null?"初始 Data 已准备：至少 10000 MB":String.format(Locale.ROOT,"历史 RKS %.4f，AP %d / %d",RksPlanner.calculate(records),ap,chartCount));
                }
                if(seedData)meta.setProperty("dataSeeded","true");
                ByteArrayOutputStream out=new ByteArrayOutputStream();meta.store(out,"Prepared independent local history transaction");
                try(InputStream in=new ByteArrayInputStream(out.toByteArray())){LaunchActivity.copyAtomic(in,marker);}
            }
            // Commit both prepared files. A killed process resumes from the durable journal.
            for(String name:names){copy(new File(txn,name),new File(games,name));new File(games,name+".bak").delete();}
            SharedPreferences.Editor update=prefs.edit().putBoolean("rks_pending",false).putBoolean("rks_restore",false).putBoolean("all_ap_pending",false).putBoolean("unlock_all_pending",false).putString("rks_status",meta.getProperty("status","历史成绩已更新"));
            if(meta.getProperty("dataSeeded","false").equals("true"))update.putBoolean("data_10000mb_v165",true);
            if(meta.containsKey("backup"))update.putString("rks_backup",meta.getProperty("backup"));
            if(!update.commit())throw new IOException("无法保存 RKS 完成状态");
            if(!marker.delete())throw new IOException("无法清除 RKS 事务标记");
            Log.i("PhigrosOffline","rks.history.applied; "+meta.getProperty("status"));
        } catch(IOException e){throw e;}
        catch(Exception e){throw new IOException("历史成绩生成失败："+e.getClass().getSimpleName(),e);}
    }
}
