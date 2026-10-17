package com.phigros.offline;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.Files;import java.util.*;import java.util.zip.*;

/** Imports owned local chart bundles; gameplay decoding is performed by the native player. */
public final class ChapterPack {
    public final String id,name,chart,music,illustration,level;
    public final File directory;
    private ChapterPack(String id,File dir,Map<String,String> m)throws IOException{
        this.id=id;directory=dir;name=value(m,"name");chart=asset(m,"chart",dir);music=asset(m,"music",dir);illustration=asset(m,"illustration",dir);level=m.containsKey("level")?m.get("level"):"自制谱";
        String suffix=chart.substring(chart.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);if(!Arrays.asList("json","pec","pbc").contains(suffix))throw new IOException("目前支持 PGR/RPE JSON、PEC 与 PBC 谱面文件");
        for(String key:new String[]{"id","uploader","unlockVideo","unlock_video"})if(m.containsKey(key)&&!m.get(key).equals("null")&&!m.get(key).isEmpty())throw new IOException("此章节仅接受本地自制包，不能移除在线身份或解锁视频校验");
    }
    private static String value(Map<String,String> m,String key)throws IOException{String v=m.get(key);if(v==null||v.trim().isEmpty()||v.length()>1024)throw new IOException("谱包信息缺少 "+key);return v;}
    private static String path(String name)throws IOException{
        if(name==null||name.isEmpty()||name.length()>1024||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.indexOf('\0')>=0)throw new IOException("谱包路径无效");String n=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String p:n.split("/",-1))if(p.isEmpty()||p.equals(".")||p.equals("..")||p.endsWith(".")||p.endsWith(" ")||p.toUpperCase(Locale.ROOT).matches("(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))throw new IOException("谱包包含不安全路径");return n;
    }
    private static File child(File root,String name)throws IOException{File file=new File(root,path(name));if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("谱包路径越界");return file;}
    private static String asset(Map<String,String> m,String key,File dir)throws IOException{String name=path(value(m,key));File file=child(dir,name);if(!file.isFile()||Files.isSymbolicLink(file.toPath()))throw new IOException("谱包缺少本地资源: "+name);return name;}
    private static Map<String,String> meta(File info)throws IOException{
        if(info.length()>65536)throw new IOException("谱包信息过长");Map<String,String> m=new LinkedHashMap<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(info),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null){line=line.trim();if(line.isEmpty()||line.startsWith("#"))continue;int at=line.indexOf(':');if(at<1)continue;String key=line.substring(0,at).trim(),v=line.substring(at+1).trim();
            if(v.length()>1&&((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'"))))v=v.substring(1,v.length()-1);
            String lower=key.toLowerCase(Locale.ROOT);if(lower.equals("song"))key="music";else if(lower.equals("picture"))key="illustration";else if(Arrays.asList("name","chart","music","illustration","level","id","uploader").contains(lower))key=lower;
            if(m.put(key,v)!=null)throw new IOException("谱包信息字段重复: "+key);
        }}return m;
    }
    public static ChapterPack importPack(InputStream input,File requestedRoot)throws IOException{
        if(Files.isSymbolicLink(requestedRoot.toPath()))throw new IOException("章节目录不能是链接");File root=requestedRoot.getCanonicalFile();if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建独立章节目录");
        String id=UUID.randomUUID().toString();File scratch=new File(root,".import-"+id),ready=new File(root,id);if(!scratch.mkdir())throw new IOException("无法创建谱包临时目录");boolean done=false;
        try{Set<String> names=new HashSet<>();List<File> infos=new ArrayList<>();long total=0;int count=0;
            try(ZipInputStream zip=new ZipInputStream(input,StandardCharsets.UTF_8)){ZipEntry e;byte[] b=new byte[65536];while((e=zip.getNextEntry())!=null){if(++count>4096)throw new IOException("谱包文件过多");String n=path(e.getName());if(!names.add(n.toLowerCase(Locale.ROOT)))throw new IOException("谱包路径重复");File f=child(scratch,n);
                if(e.isDirectory()){if(!f.isDirectory()&&!f.mkdirs())throw new IOException("无法创建谱包子目录");zip.closeEntry();continue;}
                String lower=n.toLowerCase(Locale.ROOT);if(lower.endsWith(".so")||lower.endsWith(".dex")||lower.endsWith(".apk")||lower.endsWith(".exe")||lower.endsWith(".sh")||lower.endsWith(".js"))throw new IOException("自制谱包不能包含可执行文件");
                if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException("无法创建资源目录");long bytes=0;
                try(OutputStream out=new FileOutputStream(f)){int k;while((k=zip.read(b))!=-1){bytes+=k;total+=k;if(bytes>256L*1024*1024||total>512L*1024*1024)throw new IOException("谱包超过解压大小限制");out.write(b,0,k);}}
                if(f.getName().equals("info.yml")||f.getName().equals("info.txt"))infos.add(f);zip.closeEntry();
            }}
            if(infos.size()!=1)throw new IOException("谱包须且只能包含一份 info.yml 或 info.txt");File content=infos.get(0).getParentFile();Map<String,String> m=meta(infos.get(0));new ChapterPack(id,content,m);
            if(content.equals(scratch)){if(!scratch.renameTo(ready))throw new IOException("谱包无法原子提交");}
            else{if(!content.renameTo(ready))throw new IOException("谱包文件夹无法提交");}
            ChapterPack result=new ChapterPack(id,ready,m);done=true;return result;
        }finally{deleteOwned(root,scratch);if(!done)deleteOwned(root,ready);}
    }
    private static void deleteOwned(File root,File dir)throws IOException{if(!dir.exists()&&!Files.isSymbolicLink(dir.toPath()))return;if(!dir.getAbsolutePath().startsWith(root.getAbsolutePath()+File.separator))throw new IOException("谱包回滚路径异常");
        if(!Files.isSymbolicLink(dir.toPath())){File[] children=dir.listFiles();if(children!=null)for(File f:children)deleteOwned(root,f);}if(!dir.delete())throw new IOException("无法清理谱包临时文件");
    }
    public static ChapterPack open(File dir)throws IOException{String id=dir.getName();if(!id.matches("[0-9a-f-]{36}")||Files.isSymbolicLink(dir.toPath()))throw new IOException("谱包标识无效");File a=new File(dir,"info.yml"),b=new File(dir,"info.txt");if(a.isFile()==b.isFile())throw new IOException("谱包信息缺失或冲突");return new ChapterPack(id,dir,meta(a.isFile()?a:b));}
}
