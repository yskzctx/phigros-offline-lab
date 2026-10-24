package com.phira.lab;
import android.app.*;import android.content.*;import android.content.pm.*;import android.os.*;import java.io.*;import java.security.*;import java.util.*;import java.util.zip.*;import de.robv.android.xposed.*;import de.robv.android.xposed.callbacks.XC_LoadPackage;
public final class Entry implements IXposedHookLoadPackage {
 public static final String TARGET="org.flos.phira";private static final String HASH="5c2c484265d05b36e792fba328244b195360e799da296292a569446b42dee782";
 static File root;static Class<?> quad;static boolean redirected;
 private static String digest(InputStream in)throws Exception{MessageDigest m=MessageDigest.getInstance("SHA-256");byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)m.update(b,0,n);StringBuilder s=new StringBuilder();for(byte v:m.digest())s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
 private static String hash(ApplicationInfo info)throws Exception{File lib=new File(info.nativeLibraryDir,"libphira.so");if(lib.isFile())try(InputStream in=new FileInputStream(lib)){return digest(in);}ArrayList<String> paths=new ArrayList<>();paths.add(info.sourceDir);if(info.splitSourceDirs!=null)Collections.addAll(paths,info.splitSourceDirs);for(String p:paths)try(ZipFile z=new ZipFile(p)){ZipEntry e=z.getEntry("lib/arm64-v8a/libphira.so");if(e!=null)try(InputStream in=z.getInputStream(e)){return digest(in);}}return "";}
 public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam p)throws Throwable{
  if(!TARGET.equals(p.packageName)||!TARGET.equals(p.processName))return;
  XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam call){try{prepare((Context)call.args[0],(Application)call.thisObject,p.classLoader);}catch(Throwable e){XposedBridge.log("PhiraLab inactive: "+e.getClass().getSimpleName());}}});
 }
 private static void prepare(Context host,Application app,ClassLoader loader)throws Exception{
  PackageInfo info=host.getPackageManager().getPackageInfo(TARGET,0);long code=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
  if(code!=39||!android.os.Process.is64Bit()||!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")||!HASH.equals(hash(host.getApplicationInfo())))throw new IOException("仅适配已核对的 Phira 0.8.2 arm64");
  quad=XposedHelpers.findClass("quad_native.QuadNative",loader);
  XposedHelpers.findAndHookMethod(quad,"setDataPath",String.class,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p)throws Throwable{
   if(redirected&&root!=null){p.args[0]=root.getCanonicalPath();return;}File original=new File((String)p.args[0]).getCanonicalFile();if(!original.isAbsolute()||!original.isDirectory())throw new IOException("原数据目录异常");
   File prepared=LabStore.prepare(original,host.getFilesDir());if(!LabNative.arm())return;
   root=prepared;p.args[0]=root.getCanonicalPath();p.setObjectExtra("lab.redirect",true);redirected=true;
  }protected void afterHookedMethod(MethodHookParam p){if(Boolean.TRUE.equals(p.getObjectExtra("lab.redirect"))&&p.hasThrowable()){LabNative.disarm();redirected=false;}else if(redirected)XposedHelpers.callStaticMethod(quad,"setTempDir","cache");}});
  XposedHelpers.findAndHookMethod(quad,"setTempDir",String.class,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){if(redirected&&root!=null)p.args[0]="cache";}protected void afterHookedMethod(MethodHookParam p){if(redirected)LabNative.setTemporaryRoot(new File(root,"cache").getAbsolutePath());}});
  System.loadLibrary("phira_lab");
  app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){public void onActivityResumed(Activity a){if(a.getClass().getName().equals("org.flos.phira.MainActivity"))Controls.attach(a);}public void onActivityPaused(Activity a){Controls.hide(a);}public void onActivityDestroyed(Activity a){Controls.detach(a);}public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}});
 }
}
