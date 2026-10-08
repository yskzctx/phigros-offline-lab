package com.phigros.offline;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.file.Files;

public final class OfflineAssets {
    public static void initialize(Context context) { }

    public static String packPath(String name) {
        // Both packs are merged into the installed APK. Unity's install-time
        // path is empty: core data uses AssetManager, StreamingAssets uses APK URI.
        return "";
    }

    private static final class Entry {
        final String pack,path; final long size;
        Entry(String p,String n,long s){pack=p;path=n;size=s;}
    }

    private static List<Entry> validate(Context context) throws IOException {
        List<Entry> entries=new ArrayList<>();Set<String> seen=new HashSet<>();
        try(ZipFile apk=new ZipFile(context.getApplicationInfo().sourceDir);
            BufferedReader reader=new BufferedReader(new InputStreamReader(
                context.getAssets().open("offline/packs-index.tsv"),"UTF-8"))) {
            String line;
            while((line=reader.readLine())!=null) {
                String[] parts=line.split("\t",-1);
                if(parts.length!=3)throw new IOException("资源索引损坏");
                String pack=parts[0],path=parts[1];
                String prefix="UnityDataAssetPack".equals(pack)?"bin/Data/":
                    "UnityStreamingAssetsPack".equals(pack)?"aa/":null;
                if(prefix==null||!path.startsWith(prefix)||path.indexOf('\\')>=0
                        ||path.indexOf(':')>=0||path.indexOf('\0')>=0)
                    throw new IOException("资源索引路径无效");
                for(String component:path.split("/",-1))
                    if(component.isEmpty()||".".equals(component)||"..".equals(component))
                        throw new IOException("资源索引路径无效");
                long size;
                try{size=Long.parseLong(parts[2]);}
                catch(NumberFormatException error){throw new IOException("资源索引长度无效",error);}
                if(size<0||!seen.add(path))throw new IOException("资源索引长度或重复项无效");
                ZipEntry entry=apk.getEntry("assets/"+path);
                if(entry==null||entry.isDirectory()||entry.getSize()!=size)
                    throw new IOException("安装包资源缺失或长度不符: "+path);
                entries.add(new Entry(pack,path,size));
            }
        }
        if(entries.isEmpty())throw new IOException("资源索引为空");
        return entries;
    }

    private static void checkPath(File root,File target) throws IOException {
        if(!target.getAbsoluteFile().equals(target.getCanonicalFile())
                ||!target.getPath().startsWith(root.getPath()+File.separator))
            throw new IOException("旧资源目录路径异常，未清理");
        for(File part=target;!part.equals(root);part=part.getParentFile())
            if(Files.isSymbolicLink(part.toPath()))
                throw new IOException("旧资源目录包含链接，未清理");
    }

    public static void prepare(Context context) throws IOException {
        // Validate the installed copy before reclaiming any old extracted file.
        List<Entry> entries=validate(context);
        File root=new File(context.getFilesDir().getCanonicalFile(),"offline-packs");
        if(Files.isSymbolicLink(root.toPath())
                ||!root.getAbsoluteFile().equals(root.getCanonicalFile()))
            throw new IOException("旧资源目录路径异常，未清理");
        List<File> targets=new ArrayList<>();
        for(Entry entry:entries){
            File target=new File(root,entry.pack+"/assets/"+entry.path);
            checkPath(root,target);targets.add(target);
        }
        File marker=new File(root,"ready-v159-base-data");checkPath(root,marker);
        long reclaimed=0;
        for(int i=0;i<entries.size();i++){
            File target=targets.get(i);Entry entry=entries.get(i);
            // Only known resource files of the expected length are disposable.
            // Saves, backups and unrecognized files are never traversed.
            if(target.isFile()&&target.length()==entry.size&&target.delete()){
                reclaimed+=entry.size;
                File parent=target.getParentFile();
                while(!parent.equals(root)&&parent.delete())parent=parent.getParentFile();
            }
        }
        if(marker.isFile()&&marker.length()==1)marker.delete();
        root.delete(); // succeeds only when empty; does not recursively traverse.
        Log.i("PhigrosOffline","assetpack.apk.direct; verified="+entries.size()
                +"; reclaimedBytes="+reclaimed);
    }
}
