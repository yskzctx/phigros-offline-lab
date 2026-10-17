package com.phigros.offline;
import android.app.*;import android.os.*;import android.content.*;import android.graphics.Color;import android.graphics.Typeface;import android.widget.*;import android.view.*;import java.io.*;
public final class SkinActivity extends Activity {
    private LinearLayout list;private TextView status;private boolean importing;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xff17283c);t.setPadding(dp(8),dp(6),dp(8),dp(6));return t;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextColor(0xff2158a6);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffeaf2ff));b.setAllCaps(false);b.setMinHeight(dp(48));return b;}
    public void onCreate(Bundle saved){super.onCreate(saved);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(16),dp(12),dp(16),dp(12));root.setBackgroundColor(0xfff1f5f9);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("音符皮肤",22);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button back=button("返回游戏");back.setOnClickListener(v->finish());header.addView(back);root.addView(header);
        status=text(SkinController.summary(this),16);root.addView(status);root.addView(text("导入 Phira 标准音符图包。包括蓝键、黄键、红键、长条及复押图片；选好后完全退出并重启游戏应用。",14));
        LinearLayout actions=new LinearLayout(this);Button add=button("导入皮肤 ZIP"),reset=button("恢复默认");actions.addView(add,new LinearLayout.LayoutParams(0,-2,1));actions.addView(reset,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
        add.setOnClickListener(v->{if(importing)return;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,170);});
        reset.setOnClickListener(v->{try{SkinController.select(this,"");refresh();Toast.makeText(this,"已选择默认皮肤，重启后应用",Toast.LENGTH_LONG).show();}catch(IOException e){showError(e.getMessage());}});
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));refresh();
    }
    private void refresh(){status.setText(SkinController.summary(this));list.removeAllViews();try{
        for(SkinPack p:SkinController.list(this)){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(10),dp(6),dp(10),dp(10));card.setBackgroundColor(Color.WHITE);card.addView(text(p.name,18));card.addView(text("作者："+p.author,13));
            Button use=button(p.id.equals(SkinController.selectedId(this))?"已选择":"使用此皮肤");use.setOnClickListener(v->{try{SkinController.select(this,p.id);refresh();Toast.makeText(this,"已保存，重启游戏后应用",Toast.LENGTH_LONG).show();}catch(IOException e){showError(e.getMessage());}});card.addView(use);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.bottomMargin=dp(12);list.addView(card,layout);
        }
        if(list.getChildCount()==0)list.addView(text("尚未导入皮肤，当前使用默认图片。",15));
    }catch(IOException e){showError(e.getMessage());}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=170||result!=RESULT_OK||data==null||data.getData()==null)return;importing=true;status.setText("正在验证并导入皮肤…");
        final android.net.Uri uri=data.getData();new Thread(()->{String error=null;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取所选文件");SkinController.importPack(this,in);}catch(Throwable e){error=String.valueOf(e.getMessage());}final String message=error;
            runOnUiThread(()->{importing=false;if(isFinishing()||isDestroyed())return;refresh();if(message==null)Toast.makeText(this,"皮肤已导入，点击使用后重启游戏",Toast.LENGTH_LONG).show();else showError(message);});
        },"PhigrosSkinImport").start();
    }
    private void showError(String error){new AlertDialog.Builder(this).setTitle("皮肤未应用").setMessage(error).setPositiveButton("知道了",null).show();}
}
