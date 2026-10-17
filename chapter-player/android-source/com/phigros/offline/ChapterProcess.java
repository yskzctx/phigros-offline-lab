package com.phigros.offline;
import java.io.*;import java.nio.charset.StandardCharsets;
final class ChapterProcess {
    private ChapterProcess(){}
    static boolean isPlayer(String packageName){
        try(InputStream in=new FileInputStream("/proc/self/cmdline")){byte[] bytes=new byte[512];int n=in.read(bytes);if(n<=0)return false;int end=0;while(end<n&&bytes[end]!=0)end++;return new String(bytes,0,end,StandardCharsets.UTF_8).equals(packageName+":chapter_player");}
        catch(IOException error){return false;}
    }
}
