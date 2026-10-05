from pathlib import Path
import subprocess, sys

root=Path(__file__).resolve().parent
fixture=root/'storage-test-167'
fixture.mkdir(exist_ok=True)
files={
'android/util/Log.java':'''package android.util;public class Log {public static int i(String tag,String message){System.out.println(message);return 0;}}''',
'android/content/Context.java':'''package android.content;
import java.io.*;import android.content.pm.ApplicationInfo;import android.content.res.AssetManager;
public class Context {
 public final File dir; public final File apk;
 public Context(File d,File a){dir=d;apk=a;}
 public File getFilesDir(){return new File(dir,"files");}
 public ApplicationInfo getApplicationInfo(){ApplicationInfo a=new ApplicationInfo();a.sourceDir=apk.getAbsolutePath();return a;}
 public AssetManager getAssets(){return new AssetManager(apk);}
}''',
'android/content/pm/ApplicationInfo.java':'''package android.content.pm;public class ApplicationInfo {public String sourceDir;}''',
'android/content/res/AssetManager.java':'''package android.content.res;
import java.io.*;import java.util.zip.*;
public class AssetManager {private final File apk;public AssetManager(File a){apk=a;}
 public InputStream open(String name)throws IOException {try(ZipFile z=new ZipFile(apk)){ZipEntry e=z.getEntry("assets/"+name);if(e==null)throw new FileNotFoundException(name);try(InputStream in=z.getInputStream(e)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] s=new byte[2048];int n;while((n=in.read(s))!=-1)b.write(s,0,n);return new ByteArrayInputStream(b.toByteArray());}}}
}''',
'com/phigros/offline/LaunchActivity.java':'''package com.phigros.offline;
import java.io.*; public class LaunchActivity {public static void copyAtomic(InputStream in,File target)throws IOException{target.getParentFile().mkdirs();try(OutputStream o=new FileOutputStream(target)){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)o.write(b,0,n);}}}''',
'com/phigros/offline/OfflineAssets167Test.java':'''package com.phigros.offline;
import android.content.Context;import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public class OfflineAssets167Test {
 static final String DATA="bin/Data/sharedassets0.resource",STREAM="aa/Android/song.bundle";
 static final String INDEX="UnityDataAssetPack\\t"+DATA+"\\t3\\nUnityStreamingAssetsPack\\t"+STREAM+"\\t4\\n";
 static int checks;
 static void check(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
 static void write(File p,String s)throws IOException {p.getParentFile().mkdirs();Files.write(p.toPath(),s.getBytes("UTF-8"));}
 static File apk(File root,String index,boolean stream)throws IOException {
  File p=new File(root,"base.apk");root.mkdirs();try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(p))){
   String[] names={"assets/offline/packs-index.tsv","assets/"+DATA,"assets/"+STREAM};String[] values={index,"abc","defg"};
   for(int i=0;i<names.length;i++){if(i==2&&!stream)continue;z.putNextEntry(new ZipEntry(names[i]));z.write(values[i].getBytes("UTF-8"));z.closeEntry();}
  }return p;
 }
 static Context setup(String name,String index,boolean stream)throws IOException {File d=Files.createTempDirectory("phigros-storage-"+name).toFile();return new Context(d,apk(d,index,stream));}
 static File legacy(Context c,String pack,String path){return new File(c.getFilesDir(),"offline-packs/"+pack+"/assets/"+path);}
 static void failure(Context c)throws Exception {boolean failed=false;try{OfflineAssets.prepare(c);}catch(IOException e){failed=true;}check(failed,"bad APK/index must reject before cleanup");}
 public static void main(String[] args)throws Exception {
  Context fresh=setup("fresh",INDEX,true);OfflineAssets.initialize(fresh);
  check("".equals(OfflineAssets.packPath("UnityDataAssetPack")),"install-time resources must use APK AssetManager, not an extracted path");
  check("".equals(OfflineAssets.packPath("UnityStreamingAssetsPack")),"streaming assets must use built-in APK path");
  check("".equals(OfflineAssets.packPath("unknown")),"unknown pack must not expose a path");
  OfflineAssets.prepare(fresh);check(!new File(fresh.getFilesDir(),"offline-packs").exists(),"fresh install must not copy resource packs");
  Context upgrade=setup("upgrade",INDEX,true);
  File audio=legacy(upgrade,"UnityDataAssetPack",DATA),song=legacy(upgrade,"UnityStreamingAssetsPack",STREAM);
  write(audio,"abc");write(song,"defg");File marker=new File(upgrade.getFilesDir(),"offline-packs/ready-v159-base-data");write(marker,"1");
  File prefs=new File(upgrade.dir,"shared_prefs/game.xml"),backup=new File(upgrade.getFilesDir(),"offline-rks/backups/backup.xml"),unknown=new File(upgrade.getFilesDir(),"offline-packs/user-note.txt");
  write(prefs,"private prefs");write(backup,"private backup");write(unknown,"keep");
  OfflineAssets.initialize(upgrade);OfflineAssets.prepare(upgrade);
  check(!audio.exists()&&!song.exists()&&!marker.exists(),"upgrade must reclaim old indexed resource copies");
  check(prefs.exists()&&backup.exists()&&unknown.exists(),"save/backup/unrecognized files must survive");
  OfflineAssets.prepare(upgrade);check(unknown.exists(),"cleanup must be idempotent");
  for(String bad:new String[]{INDEX.replace("\\t4\\n","\\t5\\n"),INDEX+INDEX,INDEX.replace(STREAM,"../outside"),INDEX.replace("UnityStreamingAssetsPack","unknown"),""}){
   Context broken=setup("invalid",bad,true);File f=legacy(broken,"UnityDataAssetPack",DATA);write(f,"abc");OfflineAssets.initialize(broken);failure(broken);check(f.exists(),"failed validation must preserve old copies");
  }
  Context missing=setup("missing",INDEX,false);File f=legacy(missing,"UnityDataAssetPack",DATA);write(f,"abc");OfflineAssets.initialize(missing);failure(missing);check(f.exists(),"missing APK resource must preserve legacy copies");
  Context mismatch=setup("mismatch",INDEX,true);File odd=legacy(mismatch,"UnityDataAssetPack",DATA);write(odd,"unknown file");OfflineAssets.initialize(mismatch);OfflineAssets.prepare(mismatch);check(odd.exists(),"unexpected old file length must not be deleted");
  try {
   Context linked=setup("symlink",INDEX,true);File external=Files.createTempDirectory("phigros-external").toFile();write(new File(external,"sentinel"),"keep");linked.getFilesDir().mkdirs();Files.createSymbolicLink(new File(linked.getFilesDir(),"offline-packs").toPath(),external.toPath());OfflineAssets.initialize(linked);failure(linked);check(new File(external,"sentinel").exists(),"symlink cleanup must not escape app files");
  }catch(UnsupportedOperationException|java.nio.file.FileSystemException e){System.out.println("Symlink case unavailable on this host: "+e.getClass().getSimpleName());}
  if(args.length>0){Context real=new Context(Files.createTempDirectory("phigros-real-apk").toFile(),new File(args[0]));OfflineAssets.initialize(real);OfflineAssets.prepare(real);check(!new File(real.getFilesDir(),"offline-packs").exists(),"actual APK must validate without extraction");}
  System.out.println("PASS: "+checks+" storage assertions");
 }
}'''
}
for name,body in files.items():
    p=fixture/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8')
source=Path(sys.argv[1]) if len(sys.argv)>1 else root.parent/'src/com/phigros/offline/OfflineAssets.java'
classes=fixture/('red' if 'v166' in str(source) else 'green');classes.mkdir(exist_ok=True)
subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),str(source),*[str(fixture/name) for name in files]],check=True)
subprocess.run(['java','-cp',str(classes),'com.phigros.offline.OfflineAssets167Test',*sys.argv[2:]],check=True)
