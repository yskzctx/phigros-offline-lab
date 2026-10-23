package com.phigros.offline;
import android.app.*;import android.content.*;import android.os.*;import android.graphics.Typeface;import android.view.Gravity;import android.widget.*;import java.io.*;import java.util.*;

/** Native Android chapter library. Imported entries are opened by the real PRPR player. */
public final class ChapterLibraryActivity extends Activity {
    private LinearLayout entries;private TextView status;private boolean busy;private String exportId;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xff17283c);t.setPadding(dp(8),dp(6),dp(8),dp(6));return t;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(0xff2158a6);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffeaf2ff));b.setMinHeight(dp(48));return b;}
    static File home(Context c)throws IOException {File root=new File(c.getFilesDir().getCanonicalFile(),"offline-charts");if(!root.getCanonicalFile().equals(root.getAbsoluteFile()))throw new IOException("章节目录异常");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("章节目录无法创建");return root;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(16),dp(12),dp(16),dp(12));root.setBackgroundColor(0xfff1f5f9);setContentView(root);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("我的自制章节",22);title.setTypeface(null,Typeface.BOLD);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button back=button("返回 Phigros");back.setOnClickListener(v->finish());row.addView(back);root.addView(row);
        if(saved!=null)exportId=saved.getString("export_id");
        status=text("谱包保存在修改版内；导入、导出、删除均不需要 root",14);root.addView(status);Button add=button("导入自制谱 .pez / ZIP");add.setOnClickListener(v->{if(busy)return;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,171);});root.addView(add);
        ScrollView scroll=new ScrollView(this);entries=new LinearLayout(this);entries.setOrientation(1);scroll.addView(entries);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));refresh();
    }
    private void refresh(){entries.removeAllViews();try{File[] dirs=home(this).listFiles();List<ChapterPack> packs=new ArrayList<>();if(dirs!=null)for(File f:dirs)if(f.getName().matches("[0-9a-f-]{36}")){try{packs.add(ChapterPack.open(f));}catch(IOException ignored){}}
        packs.sort((a,b)->a.name.compareTo(b.name));for(ChapterPack p:packs){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(10),dp(8),dp(10),dp(10));card.setBackgroundColor(0xffffffff);card.addView(text(p.name,18));card.addView(text(p.level,14));Button play=button("开始游玩");play.setOnClickListener(v->{if(busy)return;try{Intent i=new Intent(this,ChapterPlayerActivity.class);i.putExtra("chart_id",p.id);i.putExtra("autoplay",LaunchActivity.isAutoplay());startActivity(i);}catch(RuntimeException e){error("播放器无法打开，请检查候选版本是否完整安装");}});card.addView(play);
        LinearLayout manage=new LinearLayout(this);Button export=button("导出谱包"),remove=button("删除");
        export.setOnClickListener(v->{if(busy)return;exportId=p.id;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,p.name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_")+".pez");startActivityForResult(i,172);});
        remove.setOnClickListener(v->{if(busy)return;new AlertDialog.Builder(this).setTitle("删除此谱包？").setMessage(p.name+"\n仅删除此导入谱包及它的本地游玩记录。可先导出谱包；导出不含记录。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->operation("正在删除谱包…",()->ChapterFiles.deletePack(home(this),p.id))).show();});
        manage.addView(export,new LinearLayout.LayoutParams(0,-2,1));manage.addView(remove,new LinearLayout.LayoutParams(0,-2,1));card.addView(manage);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);entries.addView(card,lp);}
        if(packs.isEmpty())entries.addView(text("章节里还没有谱面。导入包含谱面、音乐、插画和 info.yml / info.txt 的自制包。",15));
    }catch(IOException e){error(e.getMessage());}}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("export_id",exportId);}
    private interface Job{void run()throws Exception;}
    private void operation(String message,Job job){if(busy)return;busy=true;status.setText(message);new Thread(()->{String failure=null;try{job.run();}catch(Exception e){failure=String.valueOf(e.getMessage());}final String outcome=failure;runOnUiThread(()->{busy=false;if(isFinishing()||isDestroyed())return;status.setText("谱面解码及资源加载会在播放器打开时检查；管理谱包不需要 root");refresh();if(outcome!=null)error(outcome);else Toast.makeText(this,"操作完成",Toast.LENGTH_SHORT).show();});},"LocalChartFiles").start();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;final android.net.Uri uri=data.getData();
        if(request==172){final String id=exportId;exportId=null;operation("正在导出谱包…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException("无法写入所选位置");ChapterFiles.exportPack(home(this),id,out);}});}
        else if(request==171)operation("正在检查并导入自制谱…",()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取所选文件");ChapterPack.importPack(in,home(this));}});
    }
    private void error(String message){new AlertDialog.Builder(this).setTitle("自制章节").setMessage(message).setPositiveButton("知道了",null).show();}
}
