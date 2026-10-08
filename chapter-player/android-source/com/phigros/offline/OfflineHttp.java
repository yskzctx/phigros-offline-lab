package com.phigros.offline;

import java.io.*;
import java.net.*;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.*;
import javax.net.ssl.*;

public final class OfflineHttp {
    private OfflineHttp(){}
    private static final byte[] ERROR="{\"status\":403,\"error\":\"network_disabled\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static void install(){
        URL.setURLStreamHandlerFactory(new URLStreamHandlerFactory(){public URLStreamHandler createURLStreamHandler(String protocol){return
            "http".equals(protocol)||"https".equals(protocol)?new URLStreamHandler(){
                @Override protected URLConnection openConnection(URL url){return "https".equals(url.getProtocol())?new DeniedHttps(url):new DeniedHttp(url);}
                @Override protected URLConnection openConnection(URL url,Proxy proxy){return openConnection(url);}
            }:null;}});
    }
    private static InputStream error(){return new ByteArrayInputStream(ERROR);}
    private static OutputStream discard(){return new OutputStream(){public void write(int b){}public void write(byte[] b,int off,int len){if(off<0||len<0||off>b.length-len)throw new IndexOutOfBoundsException();}};}
    private static Map<String,List<String>> headers(){Map<String,List<String>> m=new LinkedHashMap<>();m.put(null,Collections.singletonList("HTTP/1.1 403 Forbidden"));m.put("Content-Type",Collections.singletonList("application/json; charset=utf-8"));m.put("Content-Length",Collections.singletonList(Integer.toString(ERROR.length)));return Collections.unmodifiableMap(m);}
    private static String header(String name){return name==null?"HTTP/1.1 403 Forbidden":"content-type".equalsIgnoreCase(name)?"application/json; charset=utf-8":"content-length".equalsIgnoreCase(name)?Integer.toString(ERROR.length):null;}
    private static final class DeniedHttp extends HttpURLConnection {
        DeniedHttp(URL url){super(url);responseCode=403;responseMessage="Forbidden";}
        public void connect(){connected=true;}public void disconnect(){connected=false;}public boolean usingProxy(){return false;}
        public int getResponseCode(){return 403;}public String getResponseMessage(){return "Forbidden";}
        public InputStream getErrorStream(){return error();}public InputStream getInputStream()throws IOException{throw new FileNotFoundException("HTTP 403: network disabled");}
        public OutputStream getOutputStream(){return discard();}public String getHeaderField(String name){return header(name);}public Map<String,List<String>> getHeaderFields(){return headers();}
    }
    private static final class DeniedHttps extends HttpsURLConnection {
        DeniedHttps(URL url){super(url);responseCode=403;responseMessage="Forbidden";}
        public void connect(){connected=true;}public void disconnect(){connected=false;}public boolean usingProxy(){return false;}
        public int getResponseCode(){return 403;}public String getResponseMessage(){return "Forbidden";}
        public InputStream getErrorStream(){return error();}public InputStream getInputStream()throws IOException{throw new FileNotFoundException("HTTP 403: network disabled");}
        public OutputStream getOutputStream(){return discard();}public String getHeaderField(String name){return header(name);}public Map<String,List<String>> getHeaderFields(){return headers();}
        public String getCipherSuite(){return "NONE";}public Certificate[] getLocalCertificates(){return null;}
        public Certificate[] getServerCertificates()throws SSLPeerUnverifiedException{throw new SSLPeerUnverifiedException("No connection: HTTP 403");}
        public Principal getPeerPrincipal()throws SSLPeerUnverifiedException{throw new SSLPeerUnverifiedException("No connection: HTTP 403");}
    }
}
