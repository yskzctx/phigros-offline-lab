package com.phigros.offline;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;

/** Exports selected Phira resources; missing resources use bundled defaults. */
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
  File out=new File(root,skin.id+"-v2");if(Files.isSymbolicLink(out.toPath())||!out.getCanonicalFile().getParentFile().equals(root))throw new IOException("章节皮肤越界");
  if(out.exists()){
   regular(new File(out,"ready-v2"));regular(new File(out,"info.yml"));
   for(String name:SkinPack.IMAGES){regular(new File(out,name));SkinPack.pngSize(new File(out,name));}
   regular(new File(out,"hit_fx.png"));for(String name:SkinPack.SOUND_NAMES){boolean found=false;for(String type:SkinPack.SOUND_TYPES){File f=new File(out,name+"."+type);if(f.exists()){regular(f);found=true;}}if(!found)throw new IOException("章节皮肤缺少音效: "+name);}return out;
  }
  File pending=new File(root,".prepare-"+skin.id);if(!pending.mkdir())throw new IOException("章节皮肤正在处理，请稍后重试");
  try{
   String info;try(InputStream in=assets.open("respack/info.yml")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(in,bytes,65536);info=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
   info=atlas(info,"holdAtlas",skin.tail,skin.head);info=atlas(info,"holdAtlasMH",skin.tailMH,skin.headMH);
   java.util.Map<String,String> selected=SkinPack.readMeta(new File(skin.directory,"info.yml"));
   info=field(info,"name",quote(skin.name));info=field(info,"author",quote(skin.author));
   if(new File(skin.directory,"hit_fx.png").isFile())info=field(info,"hitFx",selected.get("hitFx"));
   for(String key:new String[]{"hitFxDuration","hitFxScale","hitFxRotate","hitFxTinted","hideParticles","colorPerfect","colorGood"})if(selected.containsKey(key))info=field(info,key,selected.get(key));
   try(OutputStream f=new FileOutputStream(new File(pending,"info.yml"))){f.write(info.getBytes(StandardCharsets.UTF_8));}
   for(String name:SkinPack.IMAGES){File from=new File(skin.directory,name);regular(from);try(InputStream in=new FileInputStream(from);OutputStream f=new FileOutputStream(new File(pending,name))){copy(in,f,24L*1024*1024);}}
   File fx=new File(skin.directory,"hit_fx.png");
   if(fx.exists())regular(fx);
   try(InputStream in=fx.exists()?new FileInputStream(fx):assets.open("respack/hit_fx.png");OutputStream f=new FileOutputStream(new File(pending,"hit_fx.png"))){copy(in,f,24L*1024*1024);}
   for(String sound:SkinPack.SOUND_NAMES){File custom=null;for(String type:SkinPack.SOUND_TYPES){File f=new File(skin.directory,sound+"."+type);if(f.exists()){regular(f);custom=f;break;}}
    String name=custom==null?sound+".ogg":custom.getName();
    try(InputStream in=custom==null?assets.open(name):new FileInputStream(custom);OutputStream f=new FileOutputStream(new File(pending,name))){copy(in,f,24L*1024*1024);}
   }
   try(FileOutputStream marker=new FileOutputStream(new File(pending,"ready-v2"))){marker.write(1);marker.getFD().sync();}
   Files.move(pending.toPath(),out.toPath(),StandardCopyOption.ATOMIC_MOVE);return out;
  }finally{
   if(pending.exists()){
    File[] files=pending.listFiles();if(files!=null)for(File file:files){if(file.isDirectory()&&!Files.isSymbolicLink(file.toPath()))throw new IOException("临时皮肤目录内容异常");Files.deleteIfExists(file.toPath());}
    Files.deleteIfExists(pending.toPath());
   }
  }
 }
 private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
 private static String field(String info,String key,String value)throws IOException {
  String expression="(?m)^"+key+":[^\\r\\n]*$";java.util.regex.Matcher m=java.util.regex.Pattern.compile(expression).matcher(info);
  if(!m.find())return info+"\n"+key+": "+value+"\n";
  if(m.find())throw new IOException("重复图包字段: "+key);
  return info.replaceAll(expression,java.util.regex.Matcher.quoteReplacement(key+": "+value));
 }
 private static String atlas(String text,String key,int tail,int head)throws IOException {
  String expression="(?m)^"+key+":[^\\r\\n]*$";java.util.regex.Matcher m=java.util.regex.Pattern.compile(expression).matcher(text);
  if(!m.find()||m.find())throw new IOException("默认图包缺少或重复长条图集字段");return text.replaceAll(expression,key+": ["+tail+", "+head+"]");
 }
 private static void copy(InputStream in,OutputStream out,long limit)throws IOException {byte[] buffer=new byte[65536];long size=0;int n;while((n=in.read(buffer))!=-1){size+=n;if(size>limit)throw new IOException("章节皮肤文件过大");out.write(buffer,0,n);}}
 private ChapterSkinExport(){}
}
