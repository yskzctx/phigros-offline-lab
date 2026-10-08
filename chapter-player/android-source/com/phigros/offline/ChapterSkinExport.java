package com.phigros.offline;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;

/** Adapts validated note-only skins to PRPR's full resource-pack contract. */
public final class ChapterSkinExport {
 public interface Assets {InputStream open(String name)throws IOException;}
 private static final String[] DEFAULTS={"hit_fx.png","click.ogg","drag.ogg","flick.ogg","ending.ogg"};
 private static void regular(File file)throws IOException {
  if(Files.isSymbolicLink(file.toPath())||!file.isFile()||!file.getCanonicalFile().equals(file.getAbsoluteFile()))throw new IOException("皮肤文件路径异常");
 }
 public static File prepare(SkinPack skin,File requestedRoot,Assets assets)throws IOException {
  regular(new File(skin.directory,"sanitized-v1"));SkinPack.open(skin.directory);
  if(Files.isSymbolicLink(requestedRoot.toPath())||!requestedRoot.getAbsoluteFile().equals(requestedRoot.getCanonicalFile()))throw new IOException("章节皮肤路径异常");
  File root=requestedRoot.getCanonicalFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法建立章节皮肤目录");
  File out=new File(root,skin.id);if(Files.isSymbolicLink(out.toPath())||!out.getCanonicalFile().getParentFile().equals(root))throw new IOException("章节皮肤越界");
  if(out.exists()){
   regular(new File(out,"ready-v1"));regular(new File(out,"info.yml"));
   for(String name:SkinPack.IMAGES){regular(new File(out,name));SkinPack.pngSize(new File(out,name));}
   for(String name:DEFAULTS)regular(new File(out,name));return out;
  }
  File pending=new File(root,".prepare-"+skin.id);if(!pending.mkdir())throw new IOException("章节皮肤正在处理，请稍后重试");
  try{
   String info;try(InputStream in=assets.open("respack/info.yml")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(in,bytes,65536);info=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
   info=atlas(info,"holdAtlas",skin.tail,skin.head);info=atlas(info,"holdAtlasMH",skin.tailMH,skin.headMH);
   try(OutputStream f=new FileOutputStream(new File(pending,"info.yml"))){f.write(info.getBytes(StandardCharsets.UTF_8));}
   for(String name:SkinPack.IMAGES){File from=new File(skin.directory,name);regular(from);try(InputStream in=new FileInputStream(from);OutputStream f=new FileOutputStream(new File(pending,name))){copy(in,f,24L*1024*1024);}}
   for(String name:DEFAULTS)try(InputStream in=assets.open(name.equals("hit_fx.png")?"respack/"+name:name);OutputStream f=new FileOutputStream(new File(pending,name))){copy(in,f,24L*1024*1024);}
   try(FileOutputStream marker=new FileOutputStream(new File(pending,"ready-v1"))){marker.write(1);marker.getFD().sync();}
   Files.move(pending.toPath(),out.toPath(),StandardCopyOption.ATOMIC_MOVE);return out;
  }finally{
   if(pending.exists()){
    File[] files=pending.listFiles();if(files!=null)for(File file:files){if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath()))throw new IOException("临时皮肤目录内容异常");Files.deleteIfExists(file.toPath());}
    Files.deleteIfExists(pending.toPath());
   }
  }
 }
 private static String atlas(String text,String key,int tail,int head)throws IOException {
  String expression="(?m)^"+key+":[^\\r\\n]*$";java.util.regex.Matcher m=java.util.regex.Pattern.compile(expression).matcher(text);
  if(!m.find()||m.find())throw new IOException("默认图包缺少或重复长条图集字段");return text.replaceAll(expression,key+": ["+tail+", "+head+"]");
 }
 private static void copy(InputStream in,OutputStream out,long limit)throws IOException {byte[] buffer=new byte[65536];long size=0;int n;while((n=in.read(buffer))!=-1){size+=n;if(size>limit)throw new IOException("章节皮肤文件过大");out.write(buffer,0,n);}}
 private ChapterSkinExport(){}
}
