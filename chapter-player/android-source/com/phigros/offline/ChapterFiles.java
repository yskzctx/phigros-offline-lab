package com.phigros.offline;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;

/** User-invoked management of one imported bundle; never accesses game saves. */
public final class ChapterFiles {
 private ChapterFiles(){}
 private static File owned(File root,String id)throws IOException{
  if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IOException("谱包标识无效");
  File dir=new File(root.getCanonicalFile(),id);
  if(Files.isSymbolicLink(dir.toPath())||!dir.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new IOException("谱包目录越界");
  ChapterPack.open(dir);return dir.getCanonicalFile();
 }
 private static void collect(File base,File dir,List<File> files)throws IOException{
  File[] entries=dir.listFiles();if(entries==null)throw new IOException("无法读取谱包目录");
  for(File f:entries){
   if(Files.isSymbolicLink(f.toPath())||!f.getCanonicalPath().startsWith(base.getCanonicalPath()+File.separator))throw new IOException("谱包包含链接或越界文件");
   if(files.size()>=4096)throw new IOException("谱包文件过多");files.add(f);
   if(f.isDirectory())collect(base,f,files);else if(!f.isFile())throw new IOException("谱包包含非普通文件");
  }
 }
 public static void exportPack(File root,String id,OutputStream stream)throws IOException{
  File dir=owned(root,id);List<File> files=new ArrayList<>();collect(dir,dir,files);long total=0;
  try(ZipOutputStream zip=new ZipOutputStream(stream)){
   byte[] buffer=new byte[65536];for(File f:files){if(f.isDirectory()||f.getName().equals("local-record.json")||f.getName().equals("local-record.json.tmp"))continue;
    String name=dir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar,'/');zip.putNextEntry(new ZipEntry(name));
    try(InputStream in=new FileInputStream(f)){int n;long size=0;while((n=in.read(buffer))!=-1){size+=n;total+=n;if(size>256L*1024*1024||total>512L*1024*1024)throw new IOException("谱包超出导出大小限制");zip.write(buffer,0,n);}}
    zip.closeEntry();
   }
  }
 }
 public static void deletePack(File root,String id)throws IOException{
  File dir=owned(root,id);List<File> files=new ArrayList<>();collect(dir,dir,files);
  Collections.reverse(files);for(File f:files)Files.delete(f.toPath());Files.delete(dir.toPath());
 }
}
