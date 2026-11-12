package com.phigros.offline;
import android.app.Activity;import android.app.Dialog;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.view.Gravity;import android.view.View;import android.view.Window;import android.widget.Button;import android.widget.LinearLayout;import android.widget.ScrollView;import android.widget.TextView;
/** Per-process notice. Its buttons do not inherit colors from the host dialog theme. */
public final class UsageNotice {
 public static final String TEXT="本项目为非官方实验工具，与 Phigros、Phira 官方及资源权利人无隶属关系，不代表官方认可或背书。游戏、音乐、谱面、插画与皮肤等权利归各自权利人所有；本项目不额外授予资源的使用或再分发权利。\n\n自动游玩、判定干预、全 AP 与目标 RKS 所产生的数据为模拟数据，不是真实手动游玩成绩，不得据此冒充正常成绩或制作误导性证明。\n\n测试软件可能崩溃、漏判、误判、损坏或改变存档；请先备份，并核对适配版本。仅使用本人有权使用的游戏、谱面和皮肤，不提供购买、账号、服务器、签名完整性或反作弊校验绕过。\n\n本修改版仅供本地使用：独立包名与存档，不提供账号登录、排行榜、云存档或其他线上操作，不声明网络权限。不得将模拟记录上传在线服务。\n\n本说明用于告知权属、功能边界与已知风险，不替代合法授权，不构成免责承诺，也不免除依法应承担的责任。有问题可到对应 GitHub 仓库 Issues 反馈；请勿公开私人存档、账号凭据、签名密钥或设备日志。";
 private static boolean accepted;private static Dialog current;
 public static boolean accepted(){return accepted;}
 public static void show(Activity a,Runnable proceed,Runnable decline){
  if(accepted){proceed.run();return;}if(a.isFinishing()||a.isDestroyed()||current!=null)return;
  int pad=Math.round(20*a.getResources().getDisplayMetrics().density);
  LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);root.setBackgroundColor(0xffffffff);
  TextView title=new TextView(a);title.setText("免责声明与使用范围");title.setTextSize(20);title.setTextColor(0xff15213a);title.setTypeface(null,Typeface.BOLD);root.addView(title);
  TextView body=new TextView(a);body.setText(TEXT);body.setTextSize(15);body.setTextColor(0xff26364a);body.setLineSpacing(Math.round(5*a.getResources().getDisplayMetrics().density),1);body.setTextIsSelectable(true);body.setPadding(0,pad,0,pad);root.addView(body);
  LinearLayout buttons=new LinearLayout(a);buttons.setGravity(Gravity.END);buttons.setOrientation(LinearLayout.HORIZONTAL);
  Button no=button(a,"暂不使用",0xff1e3a5f,0xffeaf1ff,0xff9fb3ce);Button yes=button(a,"已阅读，继续",0xffffffff,0xff1d4ed8,0xff1d4ed8);
  LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,Math.round(48*a.getResources().getDisplayMetrics().density),1);bp.setMargins(Math.round(4*a.getResources().getDisplayMetrics().density),0,Math.round(4*a.getResources().getDisplayMetrics().density),0);buttons.addView(no,bp);buttons.addView(yes,bp);root.addView(buttons);
  ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
  Dialog d=new Dialog(a);d.setCancelable(false);d.setContentView(scroll);current=d;
  no.setOnClickListener(v->{if(current==d)current=null;d.dismiss();decline.run();});
  yes.setOnClickListener(v->{accepted=true;if(current==d)current=null;d.dismiss();proceed.run();});
  d.setOnDismissListener(v->{if(current==d)current=null;});d.show();
  Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Math.min(Math.round(620*a.getResources().getDisplayMetrics().density),a.getResources().getDisplayMetrics().widthPixels-24),Math.min(Math.round(640*a.getResources().getDisplayMetrics().density),a.getResources().getDisplayMetrics().heightPixels-24));}
 }
 private static Button button(Activity a,String text,int ink,int fill,int stroke){
  Button b=new Button(a);b.setText(text);b.setTextSize(15);b.setTextColor(ink);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);
  GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(Math.round(12*a.getResources().getDisplayMetrics().density));bg.setStroke(Math.round(a.getResources().getDisplayMetrics().density),stroke);b.setBackground(bg);b.setMinHeight(Math.round(48*a.getResources().getDisplayMetrics().density));return b;
 }
 private UsageNotice(){}
}
