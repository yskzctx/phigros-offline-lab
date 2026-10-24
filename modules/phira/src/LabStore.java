package com.phira.lab;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import org.json.*;
/** Original data is read only for a private backup; the engine gets a fresh lab root. */
public final class LabStore {
 private static final long LIMIT=64L*1024*1024;
 private static File child(File base,String name)throws IOException{File f=new File(base,name);if(Files.isSymbolicLink(f.toPath())||!f.getAbsoluteFile().equals(f.getCanonicalFile()))throw new IOException("实验路径不能是链接");return f;}
 private static void directory(File f)throws IOException{if(!f.isDirectory()&&!f.mkdir())throw new IOException("无法创建实验目录");}
 private static byte[] read(File file)throws IOException{if(!file.isFile()||file.length()>LIMIT)throw new IOException("实验数据缺失或过大");try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];int n;long size=0;while((n=in.read(b))!=-1){size+=n;if(size>LIMIT)throw new IOException("实验数据过大");out.write(b,0,n);}return out.toByteArray();}}
 private static void atomic(File file,byte[] bytes)throws IOException{if(bytes.length>LIMIT)throw new IOException("实验数据过大");File temp=child(file.getParentFile(),file.getName()+".lab-tmp");try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp.toPath());}}
 private static JSONObject json(File f)throws IOException{try{return new JSONObject(new String(read(f),StandardCharsets.UTF_8));}catch(JSONException e){throw new IOException("实验 JSON 无法解析，未切换存档",e);}}
 private static byte[] encode(JSONObject object){return object.toString().getBytes(StandardCharsets.UTF_8);}
 private static JSONObject snapshot(JSONObject state)throws IOException{
  try{JSONObject records=state.optJSONObject("local_records"),chartRecords=new JSONObject();JSONArray charts=state.optJSONArray("charts");
   if(charts!=null)for(int i=0;i<charts.length();i++){JSONObject chart=charts.optJSONObject(i);if(chart==null)continue;String path=chart.optString("local_path","");if(path.isEmpty()||chartRecords.has(path))throw new IOException("谱面记录路径缺失或重复，未修改记录");Object record=chart.opt("record");if(record!=null&&record!=JSONObject.NULL&&!(record instanceof JSONObject))throw new IOException("谱面记录类型异常");chartRecords.put(path,record==null?JSONObject.NULL:record);}
   return new JSONObject().put("version",2).put("local_records",records==null?new JSONObject():records).put("chart_records",chartRecords);
  }catch(JSONException e){throw new IOException("谱面记录备份失败",e);}
 }
 private static void restoreRecords(JSONObject state,JSONObject saved)throws JSONException{
  if(saved.optInt("version",0)!=2){state.put("local_records",saved);return;}
  state.put("local_records",saved.getJSONObject("local_records"));JSONObject chartRecords=saved.getJSONObject("chart_records");JSONArray charts=state.optJSONArray("charts");
  if(charts!=null)for(int i=0;i<charts.length();i++){JSONObject chart=charts.optJSONObject(i);if(chart==null)continue;String path=chart.optString("local_path","");chart.put("record",chartRecords.has(path)?chartRecords.get(path):JSONObject.NULL);}
 }
 private static JSONObject ap()throws JSONException{return new JSONObject().put("score",1000000).put("accuracy",1.0).put("fullCombo",true);}
 public static synchronized File prepare(File originalRoot,File privateFiles)throws IOException{
  File files=privateFiles.getCanonicalFile();if(!files.isDirectory())throw new IOException("应用文件目录缺失");File lab=child(files,"phira-lab-082");directory(lab);directory(child(lab,"cache"));
  File data=child(lab,"data.json"),marker=child(lab,"initialized-v1");
  if(!marker.exists()){
   File original=child(originalRoot.getCanonicalFile(),"data.json"),backup=child(lab,"original-data-backup.json");
   if(original.exists()&&!backup.exists())atomic(backup,read(original));
   try{JSONObject seed=new JSONObject();seed.put("config",new JSONObject().put("offlineMode",true).put("mpEnabled",false).put("mods","").put("autoplay",false));seed.put("tokens",JSONObject.NULL).put("me",JSONObject.NULL).put("charts",new JSONArray()).put("local_records",new JSONObject()).put("respacks",new JSONArray()).put("respack_id",0);atomic(data,encode(seed));}
   catch(JSONException e){throw new IOException("实验种子无法创建",e);}
   atomic(marker,new byte[]{1});
  }
  if(!marker.isFile())throw new IOException("实验目录标记异常");
  JSONObject state=json(data);File pending=child(lab,"session-records.json"),history=child(lab,"history-pending.json");
  try{
   if(pending.exists())restoreRecords(state,json(pending));
   if(history.exists()){
    String operation=json(history).getString("operation");File backup=child(lab,"history-before-edit.json");
    if(operation.equals("all-ap")){
     if(!backup.exists())atomic(backup,encode(snapshot(state)));JSONObject records=state.optJSONObject("local_records");if(records==null)records=new JSONObject();Iterator<String> keys=records.keys();ArrayList<String> paths=new ArrayList<>();while(keys.hasNext())paths.add(keys.next());for(String path:paths)records.put(path,ap());state.put("local_records",records);
     JSONArray charts=state.optJSONArray("charts");if(charts!=null)for(int i=0;i<charts.length();i++){JSONObject chart=charts.optJSONObject(i);if(chart!=null)chart.put("record",ap());}
    }else if(operation.equals("restore"))restoreRecords(state,json(backup));else throw new IOException("历史记录操作无效");
   }
   JSONObject config=state.optJSONObject("config");if(config==null)config=new JSONObject();
   String mods=config.optString("mods","");ArrayList<String> kept=new ArrayList<>();for(String mod:mods.split("\\|")){mod=mod.trim();if(!mod.isEmpty()&&!mod.equals("AUTOPLAY"))kept.add(mod);}
   config.put("mods",String.join(" | ",kept)).put("autoplay",false).put("offlineMode",true).put("mpEnabled",false);state.put("config",config);atomic(data,encode(state));
  }catch(JSONException e){throw new IOException("实验会话恢复失败",e);}
  if(pending.exists()&&!pending.delete())throw new IOException("实验会话标记无法清理");if(history.exists()&&!history.delete())throw new IOException("历史操作标记无法清理");return lab;
 }
 public static synchronized void beginAutoplay(File lab)throws IOException{
  File pending=child(lab,"session-records.json");if(pending.exists())return;atomic(pending,encode(snapshot(json(child(lab,"data.json")))));
 }
 public static synchronized void queueHistory(File lab,String operation)throws IOException{
  if(!operation.equals("all-ap")&&!operation.equals("restore"))throw new IOException("历史记录操作无效");
  if(operation.equals("restore")&&!child(lab,"history-before-edit.json").isFile())throw new IOException("尚无历史记录备份");
  try{atomic(child(lab,"history-pending.json"),encode(new JSONObject().put("operation",operation)));}catch(JSONException e){throw new IOException("历史操作无法保存",e);}
 }
 private LabStore(){}
}
