package com.phira.lab;
import android.app.*;import android.view.*;import android.widget.*;import android.content.res.ColorStateList;import java.lang.reflect.*;import java.util.*;import de.robv.android.xposed.XposedHelpers;
/** A normal Activity dialog; no system-wide overlay and no WebView network surface. */
public final class Controls {
 private static final WeakHashMap<Activity,Controls> hosts=new WeakHashMap<>();
 private final Activity activity;private final Window.Callback previous,proxy;private Dialog dialog;
 private Controls(Activity a){activity=a;Window w=a.getWindow();previous=w.getCallback();proxy=(Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},(p,m,args)->{
  if(m.getName().equals("dispatchKeyEvent")&&args!=null&&args.length==1){KeyEvent e=(KeyEvent)args[0];if(e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_UP){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)show();return true;}if(e.getKeyCode()==KeyEvent.KEYCODE_VOLUME_DOWN&&dialog!=null){if(e.getAction()==KeyEvent.ACTION_DOWN)close();return true;}}
  try{return m.invoke(previous,args);}catch(InvocationTargetException e){throw e.getCause();}
 });w.setCallback(proxy);}
 public static void attach(Activity a){if(!hosts.containsKey(a))hosts.put(a,new Controls(a));}
 public static void hide(Activity a){Controls c=hosts.get(a);if(c!=null)c.close();}
 public static void detach(Activity a){Controls c=hosts.remove(a);if(c!=null){c.close();if(a.getWindow().getCallback()==c.proxy)a.getWindow().setCallback(c.previous);}}
 private int dp(int x){return Math.round(x*activity.getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(0xff17283c);t.setPadding(0,dp(8),0,dp(8));return t;}
 private void button(LinearLayout box,String label,Runnable action){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextColor(0xff2158a6);b.setBackgroundTintList(ColorStateList.valueOf(0xffeaf2ff));b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());box.addView(b,new LinearLayout.LayoutParams(-1,-2));}
 private void message(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
 private void show(){if(dialog!=null||activity.isFinishing())return;
  int[] status=LabNative.safeStatus();boolean active=Entry.redirected&&status[0]==1&&status[1]==1;
  LinearLayout box=new LinearLayout(activity);box.setOrientation(1);box.setPadding(dp(20),dp(10),dp(20),dp(10));box.setBackgroundColor(0xfff1f5f9);
  box.addView(text("Phira 本地实验",23));box.addView(text(active?"独立测试存档 · 原版离线结算":"模块尚未接入：版本、加载或路径不匹配",14));
  Switch autoplay=new Switch(activity);autoplay.setText("原版自动游玩 · 标准 AP");autoplay.setTextSize(18);autoplay.setTextColor(0xff17283c);autoplay.setChecked(status[2]==1);autoplay.setEnabled(active);autoplay.setMinHeight(dp(56));box.addView(autoplay);
  autoplay.setOnCheckedChangeListener((view,value)->{autoplay.setEnabled(false);new Thread(()->{String error=null;try{if(value)LabStore.beginAutoplay(Entry.root);if(!LabNative.setEnabled(value))throw new IllegalStateException("原生接口未就绪");}catch(Exception e){error=e.getMessage();}final String result=error;activity.runOnUiThread(()->{close();if(result!=null)message(result);show();});},"PhiraLabToggle").start();});
  box.addView(text("自动游玩冷启动默认关闭。开启后本次会话按不计成绩处理；重启时只恢复历史记录，保留新导入的谱面与皮肤。",14));
  button(box,"导入自制谱 .pez / ZIP",()->pick(false));button(box,"导入完整皮肤资源包",()->pick(true));
  box.addView(text("资源包的音符、特效、音效与判定线颜色由原版 Phira 资源系统加载。使用游戏原有资源包和收藏夹页面切换、整理。",14));
  button(box,"历史记录：全部 AP（重启生效）",()->history("all-ap"));button(box,"恢复修改前的历史记录（重启生效）",()->history("restore"));
  box.addView(text("开发候选：目标分数、目标准确率、逐音符规则及 RKS 尚未接入，本版不提供这些开关。",14));
  button(box,"关闭面板",this::close);ScrollView scroll=new ScrollView(activity);scroll.addView(box);dialog=new Dialog(activity,android.R.style.Theme_Material_Light_Dialog_NoActionBar);dialog.setContentView(scroll);dialog.setOnDismissListener(d->dialog=null);dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_VOLUME_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN)close();return true;}if(key==KeyEvent.KEYCODE_VOLUME_UP)return true;return false;});dialog.show();Window w=dialog.getWindow();if(w!=null)w.setLayout(Math.min(dp(520),activity.getResources().getDisplayMetrics().widthPixels-dp(28)),Math.min(dp(590),activity.getResources().getDisplayMetrics().heightPixels-dp(28)));
 }
 private void pick(boolean skin){try{if(!Entry.redirected)throw new IllegalStateException("实验路径尚未启用");close();XposedHelpers.callStaticMethod(Entry.quad,skin?"markImportRespack":"markImport");XposedHelpers.callMethod(activity,"chooseFile");}catch(Throwable e){message("无法打开原版导入器："+e.getClass().getSimpleName());}}
 private void history(String operation){try{if(!Entry.redirected||Entry.root==null)throw new IllegalStateException("实验路径尚未启用");LabStore.queueHistory(Entry.root,operation);message("已排队，完全退出并冷启动 Phira 后生效；仅修改实验记录");}catch(Exception e){message(e.getMessage());}}
 private void close(){Dialog d=dialog;dialog=null;if(d!=null)d.dismiss();}
 private Controls(){throw new AssertionError();}
}
