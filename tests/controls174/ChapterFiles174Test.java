package com.phigros.offline;
import java.io.*;import java.nio.file.*;import java.util.*;import java.lang.reflect.*;
public class ChapterFiles174Test {
 public static void main(String[] args)throws Exception{
  Class<?> type=Class.forName("com.phigros.offline.ChapterFiles");
  Method export=type.getMethod("exportPack",File.class,String.class,OutputStream.class),delete=type.getMethod("deletePack",File.class,String.class);
  File root=Files.createTempDirectory("chapter-files174-").toFile();
  File save=new File(root,"existing-save.xml");Files.write(save.toPath(),new byte[]{1,2,3});
  ChapterPack p=ChapterPack.importPack(new ByteArrayInputStream(ChapterPackTest.zip(ChapterPackTest.files("json"))),root);
  Files.write(new File(p.directory,"local-record.json").toPath(),"private-play-record".getBytes("UTF-8"));
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();export.invoke(null,root,p.id,bytes);
  ChapterPack copy=ChapterPack.importPack(new ByteArrayInputStream(bytes.toByteArray()),root);
  if(!copy.name.equals(p.name)||new File(copy.directory,"local-record.json").exists())throw new AssertionError("export lost bundle or leaked records");
  try{delete.invoke(null,root,"../existing-save.xml");throw new AssertionError("unsafe deletion accepted");}catch(InvocationTargetException expected){if(!(expected.getCause() instanceof IOException))throw expected;}
  delete.invoke(null,root,p.id);
  if(p.directory.exists()||!copy.directory.isDirectory()||!Arrays.equals(Files.readAllBytes(save.toPath()),new byte[]{1,2,3}))throw new AssertionError("delete affected other data");
  System.out.println("PASS: root-free ZIP roundtrip, local record exclusion, scoped delete and preservation of other data");
 }
}
