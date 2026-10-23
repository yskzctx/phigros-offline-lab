package com.phigros.offline;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class ChapterSkinExportTest {
 public static void main(String[] args)throws Exception {
  File root=Files.createTempDirectory("chapter-skin-export-").toFile();
  SkinPack skin;try(InputStream in=new FileInputStream(args[0])){skin=SkinPack.importPack(in,new File(root,"skins"));}
  try(FileOutputStream marker=new FileOutputStream(new File(skin.directory,"sanitized-v1"))){marker.write(1);}
  Map<String,byte[]> assets=new HashMap<>();assets.put("respack/info.yml","name: Default\nauthor: Default\nhitFx: [4, 4]\nholdAtlas: [10, 10]\nholdAtlasMH: [10, 10]\n".getBytes("UTF-8"));
  assets.put("respack/hit_fx.png",new byte[]{1,2,3});for(String n:new String[]{"click","drag","flick","ending"})assets.put(n+".ogg",new byte[]{4,5});
  File out=ChapterSkinExport.prepare(skin,new File(root,"chapter-skins"),name->{byte[] b=assets.get(name);if(b==null)throw new FileNotFoundException(name);return new ByteArrayInputStream(b);});
  String info=new String(Files.readAllBytes(new File(out,"info.yml").toPath()),"UTF-8");
  if(!info.contains("hitFx: [4, 4]")||!info.contains("holdAtlas: ["+skin.tail+", "+skin.head+"]"))throw new AssertionError("metadata/default effects not preserved");
  for(String name:SkinPack.IMAGES)if(!Arrays.equals(Files.readAllBytes(new File(out,name).toPath()),Files.readAllBytes(new File(skin.directory,name).toPath())))throw new AssertionError("note texture changed");
  for(String name:new String[]{"click.ogg","drag.ogg","flick.ogg","ending.ogg","hit_fx.png"})if(!new File(out,name).isFile())throw new AssertionError("missing fallback "+name);
  if(!out.equals(ChapterSkinExport.prepare(skin,new File(root,"chapter-skins"),name->{throw new IOException("cache should be reused");})))throw new AssertionError("cache not reused");
  new File(skin.directory,"sanitized-v1").delete();try{ChapterSkinExport.prepare(skin,new File(root,"chapter-skins"),name->new ByteArrayInputStream(new byte[0]));throw new AssertionError("unsanitized skin accepted");}catch(IOException expected){}
  System.out.println("Chapter skin export: metadata, eight textures, five default assets, cache, unsafe state rejection passed");
 }
}
