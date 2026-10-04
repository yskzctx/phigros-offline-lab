package com.phigros.offline;

import android.content.Context;
import java.io.*;

public final class OfflineAssets {
    private static File root;
    public static void initialize(Context context) {
        root = new File(context.getFilesDir(), "offline-packs");
    }
    public static String packPath(String name) {
        if (root == null) return "";
        if (!"UnityDataAssetPack".equals(name) && !"UnityStreamingAssetsPack".equals(name)) return "";
        return new File(new File(root,name), "assets").getAbsolutePath();
    }
    public static void prepare(Context context) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建资源包目录");
        File ready = new File(root, "ready-v159-base-data");
        if (ready.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("offline/packs-index.tsv"), "UTF-8"))) {
            String line;
            while ((line=reader.readLine()) != null) {
                String[] parts=line.split("\t");
                if (parts.length != 3) throw new IOException("资源索引损坏");
                long expected=Long.parseLong(parts[2]);
                File target=new File(packPath(parts[0]),parts[1]);
                if (target.length()==expected && target.isFile()) continue;
                try (InputStream in=context.getAssets().open(parts[1])) {
                    LaunchActivity.copyAtomic(in,target);
                }
                if (target.length()!=expected) throw new IOException("资源长度不符: "+parts[1]);
            }
        }
        try (FileOutputStream out=new FileOutputStream(ready)) { out.write(1);out.getFD().sync(); }
    }
}
