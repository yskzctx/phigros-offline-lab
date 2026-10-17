package com.phigros.offline;
import android.content.*;
import android.graphics.*;
import android.util.Log;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class SkinController {
    private static boolean loaded;
    private static String activeId="",failure="";
    private static native boolean configure(String directory);
    private static native int[] status();
    private SkinController(){}
    static File home(Context c)throws IOException {
        File dir=new File(c.getFilesDir().getCanonicalFile(),"offline-skins");
        if(!dir.getAbsoluteFile().equals(dir.getCanonicalFile()))throw new IOException("皮肤目录路径异常");return dir;
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("offline_content",Context.MODE_PRIVATE);}
    public static void initialize(Context c){
        String id=prefs(c).getString("skin_id","");if(id.isEmpty())return;
        try{SkinPack p=selected(c,id);if(!new File(p.directory,"sanitized-v1").isFile())throw new IOException("皮肤图片尚未安全转换");
            System.loadLibrary("phigros_skins");loaded=true;
            if(!configure(p.directory.getCanonicalPath()))throw new IOException("皮肤路径无法应用");activeId=id;
        }catch(Throwable e){failure="皮肤文件异常，当前使用默认图片";Log.e("PhigrosOffline","Skin setup failed; original note images retained",e);}
    }
    static SkinPack selected(Context c,String id)throws IOException {
        if(!id.matches("[0-9a-f-]{36}"))throw new IOException("皮肤标识无效");File root=home(c),dir=new File(root,id);
        if(!dir.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new IOException("皮肤路径越界");return SkinPack.open(dir);
    }
    static List<SkinPack> list(Context c)throws IOException {
        List<SkinPack> packs=new ArrayList<>();File[] files=home(c).listFiles();if(files!=null)for(File file:files){if(!file.getName().matches("[0-9a-f-]{36}"))continue;
            try{SkinPack p=selected(c,file.getName());if(new File(p.directory,"sanitized-v1").isFile())packs.add(p);}catch(IOException ignored){}
        }packs.sort(new Comparator<SkinPack>(){public int compare(SkinPack a,SkinPack b){return a.name.compareTo(b.name);}});return packs;
    }
    static SkinPack importPack(Context c,InputStream stream)throws IOException {
        File root=home(c);SkinPack p=SkinPack.importPack(stream,root);
        try{
            for(String name:SkinPack.IMAGES){File file=new File(p.directory,name);int[] expected=SkinPack.pngSize(file);BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                Bitmap image=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(image==null)throw new IOException("无法解码皮肤图片: "+name);
                try{if(image.getWidth()!=expected[0]||image.getHeight()!=expected[1])throw new IOException("图片解码尺寸不符");writePng(image,file);
                    if(name.startsWith("hold")){boolean mh=name.equals("hold_mh.png");int tail=mh?p.tailMH:p.tail,head=mh?p.headMH:p.head;String suffix=mh?"_mh":"";
                        // Phira UV0 addresses the first PNG row: tail at top, head at bottom.
                        // Crop using Bitmap's top-left coordinates before Unity LoadImage.
                        crop(image,0,tail,"hold-tail"+suffix+".png",p.directory);
                        crop(image,tail,image.getHeight()-tail-head,"hold-body"+suffix+".png",p.directory);
                        crop(image,image.getHeight()-head,head,"hold-head"+suffix+".png",p.directory);
                    }
                }finally{image.recycle();}
            }
            try(FileOutputStream out=new FileOutputStream(new File(p.directory,"sanitized-v1"))){out.write(1);out.getFD().sync();}
            return p;
        }catch(Throwable error){SkinPack.discardFailedImport(root,p);if(error instanceof IOException)throw(IOException)error;throw new IOException("皮肤转换失败",error);}
    }
    private static void crop(Bitmap source,int y,int height,String name,File dir)throws IOException {Bitmap part=Bitmap.createBitmap(source,0,y,source.getWidth(),height);try{writePng(part,new File(dir,name));}finally{if(part!=source)part.recycle();}}
    private static void writePng(Bitmap image,File destination)throws IOException {
        File temp=new File(destination.getParentFile(),destination.getName()+".png-tmp");
        try{try(FileOutputStream out=new FileOutputStream(temp)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG 转换失败");out.getFD().sync();}
            if(Files.isSymbolicLink(destination.toPath())||Files.isSymbolicLink(temp.toPath())
                    ||!temp.getCanonicalFile().getParentFile().equals(destination.getCanonicalFile().getParentFile()))throw new IOException("PNG 提交路径异常");
            try{Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{if(temp.exists())temp.delete();}
    }
    static void select(Context c,String id)throws IOException {
        if(!id.isEmpty()){SkinPack pack=selected(c,id);if(!new File(pack.directory,"sanitized-v1").isFile())throw new IOException("图片转换尚未完成");}
        if(!prefs(c).edit().putString("skin_id",id).commit())throw new IOException("皮肤配置保存失败");
    }
    static String selectedId(Context c){return prefs(c).getString("skin_id","");}
    static String summary(Context c){String id=selectedId(c);String label=id.isEmpty()?"默认皮肤":"已选择皮肤";try{if(!id.isEmpty())label=selected(c,id).name;}catch(IOException e){return "皮肤文件有误，请重新选择";}
        if(!failure.isEmpty())return failure;
        if(!id.equals(activeId))return label+" · 重启游戏后应用";
        if(loaded){int[] s=status();if(s.length>=4){if(s[0]<0||s[3]>0)return "皮肤加载异常，失败部分使用默认图片";}}
        return label;
    }
}
