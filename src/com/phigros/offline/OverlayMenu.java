package com.phigros.offline;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class OverlayMenu {
    private static final WeakHashMap<Activity,OverlayMenu> menus=new WeakHashMap<Activity,OverlayMenu>();
    private final Activity activity;
    private final WindowManager manager;
    private final SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout panel;
    private WebView web;
    private WindowManager.LayoutParams layout;
    private boolean visible,guided,busy;
    private int consumedKey=-1;
    private final Runnable ticker=new Runnable(){public void run(){if(visible){publish(false,null,false);handler.postDelayed(this,1200);}}};
    private OverlayMenu(Activity a){activity=a;manager=(WindowManager)a.getSystemService(Context.WINDOW_SERVICE);prefs=NativeControls.preferences(a);wrapCallback();}
    public static void resume(Activity a){if(!"com.unity3d.player.UnityPlayerActivity".equals(a.getClass().getName()))return;OverlayMenu m=menus.get(a);if(m==null){m=new OverlayMenu(a);menus.put(a,m);}m.show();}
    public static void pause(Activity a){OverlayMenu m=menus.get(a);if(m!=null)m.hide();}
    public static void destroy(Activity a){OverlayMenu m=menus.remove(a);if(m!=null){m.hide();if(m.web!=null){m.web.removeJavascriptInterface("OfflineBridge");m.web.destroy();}}}
    private int dp(float x){return Math.round(activity.getResources().getDisplayMetrics().density*x);}
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    private void wrapCallback(){final Window.Callback original=activity.getWindow().getCallback();activity.getWindow().setCallback((Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
        if(method.getName().equals("dispatchKeyEvent")&&args!=null&&args.length==1&&key((KeyEvent)args[0]))return true;
        try{return method.invoke(original,args);}catch(InvocationTargetException e){throw e.getCause();}
    }}));}
    private boolean key(KeyEvent e){int c=e.getKeyCode();if(c!=KeyEvent.KEYCODE_VOLUME_UP&&c!=KeyEvent.KEYCODE_VOLUME_DOWN)return false;
        if(e.getAction()==KeyEvent.ACTION_UP&&consumedKey==c){consumedKey=-1;return true;}
        if(e.getAction()!=KeyEvent.ACTION_DOWN)return visible;
        if(consumedKey==c)return true;
        if(c==KeyEvent.KEYCODE_VOLUME_UP){consumedKey=c;show();return true;}
        if(visible){consumedKey=c;hide();return true;}return false;
    }
    private void guide(){if(guided)return;guided=true;new AlertDialog.Builder(activity).setTitle("允许显示控制面板")
        .setMessage("请允许离线版显示悬浮窗。授权后回到游戏，控制面板会自动打开；也可用音量上键打开。")
        .setPositiveButton("前往授权",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+activity.getPackageName())));}catch(ActivityNotFoundException e){toast("请在系统设置中允许此应用显示悬浮窗");}}}).setNegativeButton("稍后",null).show();}
    private void create()throws IOException {
        panel=new LinearLayout(activity){@Override public boolean dispatchKeyEvent(KeyEvent e){return key(e)||super.dispatchKeyEvent(e);}};
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xf7ffffff);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),0xffb6c6d7);panel.setBackground(bg);panel.setElevation(dp(12));
        LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(14),0,dp(6),0);
        TextView title=new TextView(activity);title.setText("离线控制  ·  拖动");title.setTextColor(0xff18324c);title.setTextSize(17);title.setTypeface(null,1);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));title.setGravity(Gravity.CENTER_VERTICAL);
        Button close=new Button(activity);close.setText("× 收起");close.setTextSize(14);close.setTextColor(0xff2159ba);close.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffeaf2ff));close.setAllCaps(false);close.setOnClickListener(new View.OnClickListener(){public void onClick(View v){hide();}});header.addView(close,new LinearLayout.LayoutParams(dp(88),dp(44)));panel.addView(header);
        web=new WebView(activity);web.setBackgroundColor(0xfff1f5f9);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setDomStorageEnabled(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if(Build.VERSION.SDK_INT>=29)settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        web.addJavascriptInterface(new Bridge(),"OfflineBridge");
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}@Override public boolean shouldOverrideUrlLoading(WebView v,String url){return true;}@Override public void onPageFinished(WebView v,String url){publish(true,null,false);}});
        panel.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=activity.getAssets().open("offline/controls-ui.html")){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)data.write(b,0,n);}
        // Only bundled HTML is loaded. No navigation, external resources or file access.
        web.loadDataWithBaseURL("https://offline.invalid/",new String(data.toByteArray(),StandardCharsets.UTF_8),"text/html","UTF-8",null);
        int w=activity.getResources().getDisplayMetrics().widthPixels,h=activity.getResources().getDisplayMetrics().heightPixels;
        layout=new WindowManager.LayoutParams(Math.min(dp(620),w-dp(24)),Math.min(dp(520),h-dp(24)),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        layout.gravity=Gravity.TOP|Gravity.LEFT;layout.x=Math.max(0,Math.min(prefs.getInt("x",dp(12)),w-layout.width));layout.y=Math.max(0,Math.min(prefs.getInt("y",dp(12)),h-layout.height));layout.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        title.setOnTouchListener(new View.OnTouchListener(){float x,y;int px,py;public boolean onTouch(View v,MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();px=layout.x;py=layout.y;return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){layout.x=Math.max(0,Math.min(px+(int)(e.getRawX()-x),w-layout.width));layout.y=Math.max(0,Math.min(py+(int)(e.getRawY()-y),h-layout.height));if(visible)manager.updateViewLayout(panel,layout);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){prefs.edit().putInt("x",layout.x).putInt("y",layout.y).apply();return true;}return false;
        }});
    }
    private void show(){if(!Settings.canDrawOverlays(activity)){guide();return;}if(visible)return;
        try{if(panel==null)create();manager.addView(panel,layout);visible=true;publish(false,null,false);handler.post(ticker);}catch(Exception e){toast("控制面板无法打开："+e.getClass().getSimpleName());}}
    private void hide(){handler.removeCallbacks(ticker);if(visible&&panel!=null){try{manager.removeViewImmediate(panel);}catch(RuntimeException ignored){}}visible=false;}
    private void publish(boolean reload,String message,boolean saved){if(web==null)return;try{
        ControlsConfig c=NativeControls.load(activity);JSONObject state=new JSONObject();state.put("auto",LaunchActivity.isAutoplay());state.put("mode",c.mode);state.put("score",Integer.toString(c.target));state.put("accuracy",new java.math.BigDecimal(c.accuracy).divide(new java.math.BigDecimal(100)).toPlainString());state.put("reload",reload);state.put("saved",saved);
        JSONArray rules=new JSONArray();for(ControlsConfig.Rule r:c.rules){JSONObject o=new JSONObject();o.put("judgment",r.judgment);o.put("position",r.position);o.put("start",Integer.toString(r.start));o.put("end",Integer.toString(r.end));o.put("count",Integer.toString(r.count));StringBuilder ids=new StringBuilder();for(int id:r.indices){if(ids.length()>0)ids.append(',');ids.append(id);}o.put("indices",ids.toString());rules.put(o);}state.put("rules",rules);
        int[] s=NativeControls.getStatus();String runtime=LaunchActivity.isNativeLoaded()?(s[0]==1?"控制就绪 · 设置在下一局使用":"控制准备中，请在选曲页开启后再进入谱面"):"在选曲页开启自动游玩；保存设置后下一局生效。";
        if(s[2]>0)runtime="本局计划 "+s[3]+" 个，已执行 "+s[4]+" 个；预测 "+s[6]+" 分 / "+String.format(Locale.ROOT,"%.2f",s[7]/100.0)+"% · Miss "+s[8]+" / Good "+s[9]+" / Bad "+s[10];
        state.put("runtime",runtime);state.put("rksTarget",prefs.getString("rks_target",""));String history=prefs.getString("rks_status","");
        if(prefs.getBoolean("all_ap_pending",false))history="等待冷启动：恢复全部 AP";else if(prefs.getBoolean("rks_restore",false))history="等待冷启动：恢复上次历史备份";else if(prefs.getBoolean("rks_pending",false))history="等待冷启动：目标 RKS "+prefs.getString("rks_target","");state.put("history",history);if(message!=null)state.put("message",message);
        web.evaluateJavascript("window.receive("+state.toString()+")",null);
    }catch(Exception e){toast("读取控制状态失败");}}
    private final class Bridge {
        @JavascriptInterface public void submit(final String action,final String payload){if(action==null||payload==null||payload.length()>100000)return;handler.post(new Runnable(){public void run(){handle(action,payload);}});}
    }
    private void handle(String action,String payload){try{
        JSONObject p=new JSONObject(payload);
        if(action.equals("ready")){publish(true,null,false);return;}
        if(action.equals("save")){ControlsConfig c=ControlsConfig.parse(p.getInt("mode"),p.getString("score"),p.getString("accuracy"),p.getString("rules"));NativeControls.save(activity,c);publish(false,"已保存，下局使用新判定计划",true);return;}
        if(action.equals("reset")){NativeControls.save(activity,ControlsConfig.parse(0,"1000000","100",""));publish(true,"游玩设置已重置；历史成绩未改变",true);return;}
        if(action.equals("auto")){if(busy){publish(false,"请等待上一次切换完成",false);return;}busy=true;final boolean enabled=p.getBoolean("enabled");new Thread(new Runnable(){public void run(){String error=null;try{LaunchActivity.changeAutoplay(activity,enabled);}catch(Throwable e){error=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}final String outcome=error;handler.post(new Runnable(){public void run(){busy=false;publish(false,outcome==null?(enabled?"自动游玩已开启":"自动游玩已关闭"):outcome,false);}});}},"PhigrosControlsToggle").start();return;}
        SharedPreferences.Editor edit=prefs.edit();
        if(action.equals("rks")){String value=p.getString("target").trim();RksEditor.validate(activity,value);edit.putString("rks_target",value).putBoolean("rks_pending",true).putBoolean("all_ap_pending",false).putBoolean("rks_restore",false);}
        else if(action.equals("all-ap")){edit.putString("rks_target","").putBoolean("all_ap_pending",true).putBoolean("rks_pending",false).putBoolean("rks_restore",false);}
        else if(action.equals("restore")){if(prefs.getString("rks_backup","").isEmpty())throw new IllegalArgumentException("尚无历史备份");edit.putString("rks_target","").putBoolean("rks_restore",true).putBoolean("rks_pending",false).putBoolean("all_ap_pending",false);}
        else throw new IllegalArgumentException("未知控制操作");
        if(!edit.commit())throw new IllegalStateException("操作保存失败");publish(false,"已排队，完全退出并冷启动游戏后生效",false);
    }catch(Exception e){publish(false,String.valueOf(e.getMessage()),false);}}
}
