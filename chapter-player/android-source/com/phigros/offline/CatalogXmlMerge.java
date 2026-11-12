package com.phigros.offline;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CatalogXmlMerge {
    private static final Pattern ENTRY=Pattern.compile("<string\\b[^>]*\\bname=\"([^\"]+)\"[^>]*>(.*?)</string>",Pattern.DOTALL);
    private CatalogXmlMerge(){}

    static String mergeMissing(String current,String seed)throws IOException {
        Map<String,String> have=entries(current),missing=new HashMap<String,String>();
        StringBuilder tags=new StringBuilder();
        Matcher match=ENTRY.matcher(seed);
        while(match.find()) {
            String key=match.group(1);
            if(!have.containsKey(key)) {
                missing.put(key,match.group(2));
                tags.append('\n').append(match.group());
                have.put(key,match.group(2));
            }
        }
        if(missing.isEmpty())return current;
        int close=current.lastIndexOf("</map>");
        if(close<0)throw new IOException("存档目录格式无效");
        String merged=current.substring(0,close)+tags+'\n'+current.substring(close);
        Matcher replacement=ENTRY.matcher(merged);
        StringBuffer out=new StringBuffer();
        int found=0;
        while(replacement.find()) {
            String value=missing.get(replacement.group(1));
            String tag=replacement.group();
            if(value==null) {
                replacement.appendReplacement(out,Matcher.quoteReplacement(tag));
            } else {
                int start=tag.indexOf('>')+1,end=tag.lastIndexOf("</string>");
                replacement.appendReplacement(out,Matcher.quoteReplacement(tag.substring(0,start)+value+tag.substring(end)));
                found++;
            }
        }
        replacement.appendTail(out);
        if(found!=missing.size())throw new IOException("新增谱面记录未完整写入");
        return out.toString();
    }

    private static Map<String,String> entries(String raw) {
        Map<String,String> result=new HashMap<String,String>();
        Matcher match=ENTRY.matcher(raw);
        while(match.find())result.put(match.group(1),match.group(2));
        return result;
    }
}
