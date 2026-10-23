package com.phigros.offline;
import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public class ChapterPackTest {
 static byte[] zip(Map<String,String> files)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(b)){for(Map.Entry<String,String> e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue().getBytes("UTF-8"));z.closeEntry();}}return b.toByteArray();}
 static Map<String,String> files(String format){Map<String,String> f=new LinkedHashMap<>();f.put("info.txt","#\nName: 自制测试\nChart: chart."+format+"\nSong: song.ogg\nPicture: background.png\nLevel: IN Lv.10\n");f.put("chart."+format,format.equals("pec")?"0\nbp 0 120\nn1 0 1 0 0 1 0\n":"{\"formatVersion\":3,\"judgeLineList\":[]}");f.put("song.ogg","fixture-media");f.put("background.png","fixture-art");return f;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  Class<?> type;try{type=Class.forName("com.phigros.offline.ChapterPack");}catch(ClassNotFoundException e){throw new AssertionError("Custom chapter pack import is not implemented",e);}
  Method importer=type.getMethod("importPack",InputStream.class,File.class);File root=Files.createTempDirectory("chapters").toFile();File save=new File(root,"existing-save");Files.write(save.toPath(),new byte[]{1,2});
  Object result=importer.invoke(null,new ByteArrayInputStream(zip(files("json"))),root);File directory=(File)type.getField("directory").get(result);
  check(type.getField("name").get(result).equals("自制测试"),"name not read");check(new File(directory,"chart.json").isFile(),"chart missing");check(type.getField("chart").get(result).equals("chart.json"),"metadata chart path lost");
  for(String format:new String[]{"pec","pbc"})importer.invoke(null,new ByteArrayInputStream(zip(files(format))),root);
  List<Map<String,String>> invalid=new ArrayList<>();Map<String,String> f=files("json");f.put("../outside","no");invalid.add(f);
  f=files("json");f.put("info.txt",f.get("info.txt").replace("chart.json","../existing-save"));invalid.add(f);
  f=files("json");f.put("info.yml","name: Other\nchart: chart.json\nmusic: song.ogg\nillustration: background.png\n");invalid.add(f);
  f=files("json");f.put("info.yml","name: Other\nchart: chart.json\nmusic: song.ogg\nillustration: background.png\nid: 100\n");f.remove("info.txt");invalid.add(f);
  f=files("json");f.remove("song.ogg");invalid.add(f);f=files("json");f.put("payload.so","binary");invalid.add(f);
  int before=root.list().length;
  for(Map<String,String> bad:invalid){boolean rejected=false;try{importer.invoke(null,new ByteArrayInputStream(zip(bad)),root);}catch(InvocationTargetException e){check(e.getCause() instanceof IOException,"unexpected failure");rejected=true;}check(rejected,"unsafe or incomplete archive accepted");}
  check(root.list().length==before,"failed imports left partial directories");check(Arrays.equals(Files.readAllBytes(save.toPath()),new byte[]{1,2}),"existing save changed");
  System.out.println("PASS: PGR/RPE JSON, PEC/PBC bundle metadata, local dependencies, ambiguous metadata, unsafe paths, online ids, executable rejection and transactional preservation; gameplay parsing is validated by native player separately");
 }
}
