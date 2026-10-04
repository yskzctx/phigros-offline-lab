package com.phigros.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.io.*;

public final class LaunchActivity extends Activity {
    private static volatile boolean launched;
    private static volatile boolean nativeLoaded;
    private static volatile boolean autoplay;
    private static native void setAutoplay(boolean enabled);
    private ProgressDialog progress;

    public static boolean isSessionChosen() { return launched; }
    public static boolean isNativeLoaded() { return nativeLoaded; }
    public static boolean isAutoplay() { return autoplay; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (launched) { startGame(); return; }
        OfflineAssets.initialize(this);
        progress = new ProgressDialog(this);
        progress.setTitle("Phigros 离线修改版");
        progress.setMessage("正在准备游戏资源，首次启动需要一些时间……");
        progress.setCancelable(false);
        progress.show();
        new Thread(new Runnable() { public void run() {
            try {
                Log.i("PhigrosOffline", "resource.setup.begin");
                prepareInitialData(LaunchActivity.this);
                OfflineAssets.prepare(LaunchActivity.this);
                Log.i("PhigrosOffline", "resource.setup.complete; awaiting mode choice");
                runOnUiThread(new Runnable() { public void run() {
                    progress.dismiss();
                    new AlertDialog.Builder(LaunchActivity.this)
                        .setTitle("是否开启自动游玩？")
                        .setPositiveButton("开启", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) { choose(true); }
                        })
                        .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) { choose(false); }
                        })
                        .setCancelable(false).show();
                }});
            } catch (final Throwable error) {
                runOnUiThread(new Runnable() { public void run() {
                    progress.dismiss();
                    new AlertDialog.Builder(LaunchActivity.this)
                        .setTitle("资源准备失败")
                        .setMessage("请检查手机剩余空间。\n" + error.getClass().getSimpleName()
                            + ": " + String.valueOf(error.getMessage()))
                        .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) { finish(); }
                        }).show();
                }});
            }
        }}, "PhigrosResourceSetup").start();
    }

    private static String[] prefNames(Context context) {
        return new String[] {context.getPackageName()+".v2.playerprefs.xml",
                            "com.PigeonGames.Phigros.v2.playerprefs.xml"};
    }

    public static void prepareInitialData(Context context) throws IOException {
        File prefs = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        File state = new File(context.getFilesDir(), "offline-session");
        if (!prefs.isDirectory() && !prefs.mkdirs()) throw new IOException("无法创建存档目录");
        if (!state.isDirectory() && !state.mkdirs()) throw new IOException("无法创建会话目录");
        File pending = new File(state, "autoplay.pending");
        if (pending.exists()) {
            for (String name : prefNames(context)) {
                File backup = new File(state, name);
                if (!backup.isFile()) throw new IOException("自动游玩会话备份缺失");
                try (InputStream in = new FileInputStream(backup)) { copyAtomic(in, new File(prefs,name)); }
                new File(prefs, name+".bak").delete();
            }
            if (!pending.delete()) throw new IOException("无法恢复自动游玩会话");
        }
        File seeded = new File(state, "all-ap-v157.seeded");
        if (!seeded.exists()) {
            for (String name : prefNames(context)) {
                try (InputStream in = context.getAssets().open("offline/all-ap.xml")) {
                    copyAtomic(in, new File(prefs, name));
                }
            }
            try (FileOutputStream out = new FileOutputStream(seeded)) {
                out.write(1); out.getFD().sync();
            }
        }
        RksEditor.applyPending(context);
    }

    private void choose(final boolean enabled) {
        new Thread(new Runnable() { public void run() {
            try {
                beginSession(LaunchActivity.this, enabled);
                runOnUiThread(new Runnable() { public void run() { startGame(); }});
            } catch (final Throwable error) {
                runOnUiThread(new Runnable() { public void run() {
                    new AlertDialog.Builder(LaunchActivity.this).setTitle("存档备份失败")
                        .setMessage(String.valueOf(error.getMessage()))
                        .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) { finish(); }
                        }).show();
                }});
            }
        }}, "PhigrosSessionSetup").start();
    }

    private static void beginSession(Context context, boolean enabled) throws IOException {
        changeAutoplay(context,enabled);
        launched=true;
    }

    public static synchronized void changeAutoplay(Context context, boolean enabled) throws IOException {
        File prefs=new File(context.getApplicationInfo().dataDir,"shared_prefs");
        File state=new File(context.getFilesDir(),"offline-session");
        if (enabled && !new File(state,"autoplay.pending").exists()) {
            for (String name : prefNames(context)) {
                try (InputStream in=new FileInputStream(new File(prefs,name))) {
                    copyAtomic(in,new File(state,name));
                }
            }
            try (FileOutputStream out=new FileOutputStream(new File(state,"autoplay.pending"))) {
                out.write(1);out.getFD().sync();
            }
        }
        if (enabled) {
            Log.i("PhigrosOffline", "native.load.begin; mode=enabled");
            if(!nativeLoaded) {System.loadLibrary("phigros_offline");nativeLoaded=true;}
            NativeControls.apply(NativeControls.load(context));
            setAutoplay(true);
            Log.i("PhigrosOffline", "native.mode.enabled");
        } else {
            if(nativeLoaded)setAutoplay(false);
            Log.i("PhigrosOffline", "native.skipped; mode=disabled");
        }
        autoplay=enabled;
    }

    public static void showRestoredPrompt(final Activity activity) {
        DialogInterface.OnClickListener onChoice=new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, final int which) {
                new Thread(new Runnable() { public void run() {
                    try { beginSession(activity,which==DialogInterface.BUTTON_POSITIVE); }
                    catch (final Throwable error) {
                        activity.runOnUiThread(new Runnable() { public void run() {
                            new AlertDialog.Builder(activity).setTitle("存档备份失败")
                                .setMessage(String.valueOf(error.getMessage()))
                                .setPositiveButton("关闭",new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d,int w) { activity.finish(); }
                                }).show();
                        }});
                    }
                }},"PhigrosRestoredSession").start();
            }
        };
        new AlertDialog.Builder(activity).setTitle("是否开启自动游玩？")
            .setPositiveButton("开启",onChoice).setNegativeButton("关闭",onChoice)
            .setCancelable(false).show();
    }

    private void startGame() {
        Intent intent = new Intent();
        intent.setClassName(getPackageName(), "com.unity3d.player.UnityPlayerActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Log.i("PhigrosOffline", "unity.launch.request");
        startActivity(intent);
        Log.i("PhigrosOffline", "unity.launch.request.sent");
        finish();
    }

    public static void copyAtomic(InputStream in, File target) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建资源目录");
        File temporary = new File(parent, target.getName()+".offline-tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[262144];
            int count;
            while ((count=in.read(buffer)) != -1) out.write(buffer,0,count);
            out.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new IOException("无法写入资源: "+target.getName());
    }
}
