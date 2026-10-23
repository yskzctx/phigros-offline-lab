package com.phigros.offline;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;import java.lang.reflect.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;
public class SkinPackTest {
 static int checks;static final String[] PNGS={"click.png","drag.png","flick.png","hold.png","click_mh.png","drag_mh.png","flick_mh.png","hold_mh.png"};
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static byte[] png(int w,int h)throws Exception{BufferedImage i=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);ByteArrayOutputStream b=new ByteArrayOutputStream();ImageIO.write(i,"png",b);return b.toByteArray();}
 static byte[] pack(String info,String prefix,Map<String,byte[]> extra,boolean complete)throws Exception{
  ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){
   z.putNextEntry(new ZipEntry(prefix+"info.yml"));z.write(info.getBytes("UTF-8"));z.closeEntry();
   for(String name:PNGS){if(!complete&&name.equals("flick_mh.png"))continue;z.putNextEntry(new ZipEntry(prefix+name));z.write(png(64,name.startsWith("hold")?128:24));z.closeEntry();}
   for(Map.Entry<String,byte[]> e:extra.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}
  }return b.toByteArray();
 }
 static final String INFO="name: 测试皮肤\nauthor: local\nholdAtlas: [16, 24]\nholdAtlasMH: [20, 28]\n";
 static Class<?> type;static Method importMethod;
 static Object load(byte[] bytes,File root)throws Exception{return importMethod.invoke(null,new ByteArrayInputStream(bytes),root);}
 static void reject(byte[] bytes,File root)throws Exception{boolean bad=false;try{load(bytes,root);}catch(InvocationTargetException e){if(e.getCause() instanceof IOException)bad=true;else throw e;}check(bad,"bad pack must be rejected");}
 public static void main(String[] args)throws Exception{
  try{type=Class.forName("com.phigros.offline.SkinPack");}catch(ClassNotFoundException e){throw new AssertionError("Phira skin pack importer is not implemented",e);}
  importMethod=type.getMethod("importPack",InputStream.class,File.class);
  File root=Files.createTempDirectory("skins-test").toFile();File original=new File(root,"existing-private-save.xml");Files.write(original.toPath(),new byte[]{3,4,5});
  byte[] zip=pack(INFO,"",Collections.emptyMap(),true);Object result=load(zip,root);
  String id=(String)type.getField("id").get(result);File dir=(File)type.getField("directory").get(result);
  check(id.matches("[0-9a-f-]{36}"),"directory must use opaque id, not imported title");check(dir.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator),"result escaped root");
  check(type.getField("name").get(result).equals("测试皮肤"),"UTF-8 name lost");
  for(String name:PNGS)check(ImageIO.read(new File(dir,name))!=null,"imported PNG corrupt");
  check(Arrays.equals(Files.readAllBytes(original.toPath()),new byte[]{3,4,5}),"existing data was changed");
  Object wrapped=load(pack(INFO,"folder/",Collections.emptyMap(),true),root);File wd=(File)type.getField("directory").get(wrapped);check(new File(wd,"click.png").isFile(),"single wrapper folder not normalized");
  int before=root.list().length;
  reject(pack(INFO,"",Collections.singletonMap("../escape.txt",new byte[]{1}),true),root);
  reject(pack(INFO,"",Collections.singletonMap("C:/escape.txt",new byte[]{1}),true),root);
  reject(pack(INFO,"",Collections.singletonMap("folder\\escape.txt",new byte[]{1}),true),root);
  reject(pack(INFO,"",Collections.emptyMap(),false),root);
  reject(pack(INFO.replace("[16, 24]","[100, 100]"),"",Collections.emptyMap(),true),root);
  reject(pack(INFO+"holdRepeat: true\n","",Collections.emptyMap(),true),root);
  reject(pack(INFO,"",Collections.singletonMap("dir/click.png",png(64,24)),true),root);
  check(root.list().length==before,"failed imports must not leave partial packs");
  Object twice=load(zip,root);check(!type.getField("id").get(twice).equals(id),"imports collided");
  check(Arrays.equals(Files.readAllBytes(original.toPath()),new byte[]{3,4,5}),"private data overwritten by failed import");
  System.out.println("PASS: "+checks+" assertions; standard/MH images, atlas bounds, UTF-8, wrapper folder, safe paths, rollback and existing data preservation");
 }
}
