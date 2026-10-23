package com.phigros.offline;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public final class SkinResources174Test {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static byte[] wav(){ByteBuffer b=ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(38).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(2).putShort((short)0);return b.array();}
 public static void main(String[] args)throws Exception{
  File root=Files.createTempDirectory("skin-resources174-").toFile();
  String meta=SkinPackTest.INFO+"hitFx: [2, 2]\nhitFxDuration: 0.5\nhitFxScale: 0.8\nhitFxTinted: false\ncolorPerfect: 0xff66ccff\ncolorGood: 16711935\n";
  Map<String,byte[]> extra=new LinkedHashMap<>();extra.put("bundle/hit_fx.png",SkinPackTest.png(32,32));extra.put("bundle/click.wav",wav());
  SkinPack skin=SkinPack.importPack(new ByteArrayInputStream(SkinPackTest.pack(meta,"bundle/",extra,true)),new File(root,"skins"));
  check(new File(skin.directory,"hit_fx.png").isFile(),"import discarded hit effect atlas");
  check(Arrays.equals(Files.readAllBytes(new File(skin.directory,"click.wav").toPath()),wav()),"import discarded/changed custom hit sound");
  Files.write(new File(skin.directory,"sanitized-v1").toPath(),new byte[]{1});
  ChapterSkinExport.Assets defaults=name->{if(name.equals("respack/info.yml"))return new ByteArrayInputStream("name: Default\nauthor: Default\nhitFx: [4, 4]\nholdAtlas: [10, 10]\nholdAtlasMH: [10, 10]\n".getBytes("UTF-8"));return new ByteArrayInputStream(name.equals("respack/hit_fx.png")?SkinPackTestPng.defaultPng:new byte[]{1,2,3});};
  File out=ChapterSkinExport.prepare(skin,new File(root,"export"),defaults);
  String info=new String(Files.readAllBytes(new File(out,"info.yml").toPath()),"UTF-8");
  check(info.contains("hitFx: [2, 2]")&&info.contains("colorPerfect: 0xff66ccff")&&info.contains("colorGood: 16711935")&&info.contains("hitFxDuration: 0.5"),"export lost effects/line colors metadata");
  check(Arrays.equals(Files.readAllBytes(new File(out,"hit_fx.png").toPath()),extra.get("bundle/hit_fx.png")),"default replaced custom effect");
  check(new File(out,"click.wav").isFile()&&!new File(out,"click.ogg").exists(),"default OGG shadows custom WAV");
  check(new File(out,"drag.ogg").isFile(),"missing sound fallback");
  try{SkinPack.importPack(new ByteArrayInputStream(SkinPackTest.pack(meta.replace("[2, 2]","[3, 3]"),"bundle/",extra,true)),new File(root,"skins"));throw new AssertionError("invalid effect grid accepted");}catch(IOException expected){}
  System.out.println("PASS: optional FX/sound retained, colors and effect metadata exported, custom WAV precedence, fallbacks, atlas bounds");
 }
 static final class SkinPackTestPng {static final byte[] defaultPng;static{try{defaultPng=SkinPackTest.png(32,32);}catch(Exception e){throw new RuntimeException(e);}}}
}
