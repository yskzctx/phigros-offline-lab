package com.phigros.offline;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

/** Standard Phira note textures only. No scripts, shaders, or native code are loaded. */
public final class SkinPack {
    public static final String[] IMAGES={"click.png","drag.png","flick.png","hold.png","click_mh.png","drag_mh.png","flick_mh.png","hold_mh.png"};
    public final String id,name,author;
    public final File directory;
    public final int tail,head,tailMH,headMH;
    private static final long MAX_TOTAL=128L*1024*1024;
    private static final int MAX_IMAGE=24*1024*1024;
    private SkinPack(String id,File dir,Map<String,String> meta)throws IOException {
        this.id=id;directory=dir;name=required(meta,"name");author=required(meta,"author");
        int[] a=atlas(meta.get("holdAtlas")),b=atlas(meta.get("holdAtlasMH"));tail=a[0];head=a[1];tailMH=b[0];headMH=b[1];
    }
    private static String required(Map<String,String> meta,String key)throws IOException {
        String v=meta.get(key);if(v==null||v.trim().isEmpty()||v.length()>100)throw new IOException("皮肤 "+key+" 缺失或超过100字");return v;
    }
    private static int[] atlas(String value)throws IOException {
        if(value==null||!value.matches("\\[\\s*[0-9]{1,4}\\s*,\\s*[0-9]{1,4}\\s*\\]"))throw new IOException("长条图集须为 [尾部像素, 头部像素]");
        String[] p=value.substring(1,value.length()-1).split(",");int a=Integer.parseInt(p[0].trim()),b=Integer.parseInt(p[1].trim());
        if(a<1||b<1)throw new IOException("长条头尾图集高度须大于0");return new int[]{a,b};
    }
    public static Map<String,String> readMeta(File file)throws IOException {
        if(file.length()>65536)throw new IOException("皮肤信息过长");Map<String,String> m=new LinkedHashMap<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            String line;while((line=r.readLine())!=null){line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;int sep=line.indexOf(':');if(sep<1)continue;
                String key=line.substring(0,sep).trim(),value=line.substring(sep+1).trim();
                if(value.length()>1&&((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))value=value.substring(1,value.length()-1);
                if(m.put(key,value)!=null)throw new IOException("皮肤信息包含重复字段: "+key);
            }
        }
        String[] keys={"holdRepeat","holdCompact","holdKeepHead"},labels={"长条重复平铺","紧凑长条","保留长条头"};
        for(int i=0;i<keys.length;i++)if(m.containsKey(keys[i])&&!"false".equals(m.get(keys[i])))
            throw new IOException("当前标准图集暂不支持“"+labels[i]+"”样式，请选择普通音符图包");
        return m;
    }
    private static String safeName(String name)throws IOException {
        if(name==null||name.isEmpty()||name.length()>1024||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.indexOf('\0')>=0)throw new IOException("皮肤包路径无效");
        String path=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String part:path.split("/",-1)){
            if(part.isEmpty()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" ")||part.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))throw new IOException("皮肤包包含不安全路径");
        }return path;
    }
    private static File child(File root,String name)throws IOException {
        File target=new File(root,name);if(!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("皮肤包路径越界");return target;
    }
    public static SkinPack importPack(InputStream stream,File requestedRoot)throws IOException {
        if(Files.isSymbolicLink(requestedRoot.toPath()))throw new IOException("皮肤目录不能是链接");
        File root=requestedRoot.getCanonicalFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建独立皮肤目录");
        String id=UUID.randomUUID().toString();File scratch=new File(root,".import-"+id),ready=new File(root,".ready-"+id),destination=new File(root,id);
        if(!scratch.mkdir())throw new IOException("无法创建导入临时目录");boolean committed=false;
        try{
            Set<String> names=new HashSet<>();List<File> infos=new ArrayList<>();int count=0;long total=0;
            try(ZipInputStream zip=new ZipInputStream(stream,StandardCharsets.UTF_8)){
                ZipEntry entry;byte[] buffer=new byte[65536];
                while((entry=zip.getNextEntry())!=null){if(++count>1024)throw new IOException("皮肤包文件过多");String name=safeName(entry.getName());
                    if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("皮肤包包含重复路径");File target=child(scratch,name);
                    if(entry.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("无法创建图包子目录");zip.closeEntry();continue;}
                    String lower=name.toLowerCase(Locale.ROOT);if(lower.endsWith(".so")||lower.endsWith(".dex")||lower.endsWith(".apk")||lower.endsWith(".exe")||lower.endsWith(".js")||lower.endsWith(".sh"))throw new IOException("音符皮肤不接受可执行文件");
                    if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("无法创建图包目录");
                    long bytes=0;try(OutputStream out=new FileOutputStream(target)){int n;while((n=zip.read(buffer))!=-1){bytes+=n;total+=n;if(bytes>MAX_IMAGE||total>MAX_TOTAL)throw new IOException("皮肤包超过图片/解压大小限制");out.write(buffer,0,n);}}
                    if(target.getName().equals("info.yml"))infos.add(target);zip.closeEntry();
                }
            }
            if(infos.size()!=1)throw new IOException("皮肤包须且只能包含一份 info.yml");File bundle=infos.get(0).getParentFile();Map<String,String> meta=readMeta(infos.get(0));
            SkinPack result=new SkinPack(id,destination,meta);
            for(String name:IMAGES){File p=new File(bundle,name);if(!p.isFile())throw new IOException("缺少皮肤图片: "+name);int[] wh=pngSize(p);
                if(name.equals("hold.png")&&(long)result.tail+result.head>=wh[1])throw new IOException("普通长条图集超出图片范围");
                if(name.equals("hold_mh.png")&&(long)result.tailMH+result.headMH>=wh[1])throw new IOException("复押长条图集超出图片范围");
                for(String path:names)if(path.endsWith("/"+name)&&!path.equals(relative(scratch,p)))throw new IOException("皮肤包包含歧义图片: "+name);
            }
            if(!ready.mkdir())throw new IOException("无法创建待提交图包");
            for(String name:IMAGES)copy(new File(bundle,name),new File(ready,name));copy(infos.get(0),new File(ready,"info.yml"));
            if(destination.exists()||!ready.renameTo(destination))throw new IOException("无法原子提交皮肤");committed=true;return result;
        }finally{removeTemporary(root,scratch);removeTemporary(root,ready);if(!committed)removeTemporary(root,destination);}
    }
    private static String relative(File root,File child)throws IOException {return root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar,'/');}
    private static void copy(File a,File b)throws IOException {try(InputStream in=new FileInputStream(a);OutputStream out=new FileOutputStream(b)){byte[] s=new byte[65536];int n;while((n=in.read(s))!=-1)out.write(s,0,n);}}
    private static void removeTemporary(File root,File dir)throws IOException {
        if(!dir.exists()&&!Files.isSymbolicLink(dir.toPath()))return;
        if(!dir.getAbsolutePath().startsWith(root.getAbsolutePath()+File.separator))throw new IOException("临时目录越界，未清理");
        if(!Files.isSymbolicLink(dir.toPath())){File[] items=dir.listFiles();if(items!=null)for(File item:items)removeTemporary(root,item);}
        if(!dir.delete())throw new IOException("无法清理导入临时文件");
    }
    public static SkinPack open(File directory)throws IOException {
        String id=directory.getName();if(!id.matches("[0-9a-f-]{36}")||Files.isSymbolicLink(directory.toPath()))throw new IOException("皮肤标识无效");
        SkinPack result=new SkinPack(id,directory,readMeta(new File(directory,"info.yml")));
        for(String name:IMAGES){int[] size=pngSize(new File(directory,name));
            if(name.equals("hold.png")&&(long)result.tail+result.head>=size[1])throw new IOException("普通长条图集超出图片范围");
            if(name.equals("hold_mh.png")&&(long)result.tailMH+result.headMH>=size[1])throw new IOException("复押长条图集超出图片范围");
        }return result;
    }
    static void discardFailedImport(File root,SkinPack pack)throws IOException {
        if(!pack.directory.getParentFile().getCanonicalFile().equals(root.getCanonicalFile()))throw new IOException("导入回滚路径异常");
        removeTemporary(root.getCanonicalFile(),pack.directory);
    }
    public static int[] pngSize(File file)throws IOException {
        if(file.length()<33||file.length()>MAX_IMAGE||Files.isSymbolicLink(file.toPath()))throw new IOException("PNG 文件无效或过大");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))){
            if(in.readLong()!=0x89504e470d0a1a0aL)throw new IOException("皮肤图片必须为 PNG");int w=0,h=0,chunkCount=0;boolean data=false,end=false;long consumed=8;byte[] buf=new byte[65536];
            while(!end){int length=in.readInt(),kind=in.readInt();consumed+=12L+length;if(length<0||length>MAX_IMAGE||consumed>file.length()||++chunkCount>4096)throw new IOException("PNG 块长度无效");
                CRC32 crc=new CRC32();for(int shift=24;shift>=0;shift-=8)crc.update(kind>>shift&255);
                if(chunkCount==1){if(kind!=0x49484452||length!=13)throw new IOException("PNG 缺少 IHDR");byte[] header=new byte[13];in.readFully(header);crc.update(header);
                    w=((header[0]&255)<<24)|((header[1]&255)<<16)|((header[2]&255)<<8)|(header[3]&255);h=((header[4]&255)<<24)|((header[5]&255)<<16)|((header[6]&255)<<8)|(header[7]&255);
                    if(w<1||h<1||w>4096||h>4096||(long)w*h>4L*1024*1024)throw new IOException("图片限4096边长及400万像素");
                }else{if(kind==0x49484452)throw new IOException("重复 PNG IHDR");int left=length;while(left>0){int n=Math.min(left,buf.length);in.readFully(buf,0,n);crc.update(buf,0,n);left-=n;}}
                if((int)crc.getValue()!=in.readInt())throw new IOException("PNG CRC 不符");if(kind==0x49444154&&length>0)data=true;if(kind==0x49454e44){if(length!=0)throw new IOException("PNG IEND 无效");end=true;}
            }
            if(!data||in.read()!=-1)throw new IOException("PNG 数据缺失或存在尾随数据");return new int[]{w,h};
        }
    }
}
