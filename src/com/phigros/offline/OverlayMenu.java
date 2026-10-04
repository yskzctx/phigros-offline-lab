package com.phigros.offline;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.WeakHashMap;

public final class OverlayMenu {
    private static final WeakHashMap<Activity,OverlayMenu> menus=new WeakHashMap<Activity,OverlayMenu>();
    private final Activity activity;
    private final WindowManager manager;
    private final SharedPreferences preferences;
    private final Handler handler=new Handler();
    private View panel;
    private WindowManager.LayoutParams layout;
    private TextView status;
    private Switch auto;
    private Spinner judgment,position;
    private EditText score,start,end,count,indices,rks;
    private boolean updating,visible,permissionPrompted;
    private int consumedKey=-1;
    private final Runnable ticker=new Runnable(){public void run(){if(visible){refresh();handler.postDelayed(this,1000);}}};
    private OverlayMenu(Activity a){activity=a;manager=(WindowManager)a.getSystemService(Context.WINDOW_SERVICE);preferences=NativeControls.preferences(a);wrapCallback();}
    public static void resume(Activity a) {
        if(!"com.unity3d.player.UnityPlayerActivity".equals(a.getClass().getName()))return;
        OverlayMenu menu=menus.get(a);if(menu==null){menu=new OverlayMenu(a);menus.put(a,menu);}
        if(!Settings.canDrawOverlays(a) && !menu.permissionPrompted && !preferences(a).getBoolean("overlay_guided",false))menu.guide();
    }
    private static SharedPreferences preferences(Activity a){return NativeControls.preferences(a);}
    public static void pause(Activity a){OverlayMenu m=menus.get(a);if(m!=null)m.hide();}
    public static void destroy(Activity a){OverlayMenu m=menus.remove(a);if(m!=null)m.hide();}
    private void wrapCallback() {
        final Window.Callback original=activity.getWindow().getCallback();
        Window.Callback callback=(Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},new InvocationHandler(){
            public Object invoke(Object proxy,Method method,Object[] args)throws Throwable {
                if(method.getName().equals("dispatchKeyEvent") && args!=null && args.length==1 && key((KeyEvent)args[0]))return true;
                try{return method.invoke(original,args);}catch(InvocationTargetException e){throw e.getCause();}
            }
        });
        activity.getWindow().setCallback(callback);
    }
    private boolean key(KeyEvent event) {
        int code=event.getKeyCode();
        if(code!=KeyEvent.KEYCODE_VOLUME_UP && code!=KeyEvent.KEYCODE_VOLUME_DOWN)return false;
        if(event.getAction()==KeyEvent.ACTION_UP && consumedKey==code){consumedKey=-1;return true;}
        if(event.getAction()!=KeyEvent.ACTION_DOWN)return visible;
        if(consumedKey==code)return true;
        if(code==KeyEvent.KEYCODE_VOLUME_UP){consumedKey=code;if(!visible)show();return true;}
        if(visible){consumedKey=code;hide();return true;}
        return false;
    }
    private void guide() {
        permissionPrompted=true;preferences.edit().putBoolean("overlay_guided",true).apply();
        new AlertDialog.Builder(activity).setTitle("启用游戏控制浮窗")
            .setMessage("请允许此修改版显示悬浮窗。授权后回到游戏，按音量上键打开菜单，音量下键关闭。")
            .setPositiveButton("前往授权",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                try {activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+activity.getPackageName())));}
                catch(ActivityNotFoundException e){toast("请在系统设置中允许此应用显示悬浮窗");}
            }}).setNegativeButton("稍后",null).show();
    }
    private int dp(float x){return (int)(activity.getResources().getDisplayMetrics().density*x+0.5f);}
    private void toast(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
    private TextView text(String s){TextView v=new TextView(activity);v.setText(s);v.setTextColor(Color.WHITE);v.setTextSize(14);v.setPadding(dp(6),dp(4),dp(6),dp(4));return v;}
    private EditText field(LinearLayout parent,String label,String value,boolean decimal) {
        parent.addView(text(label));EditText v=new EditText(activity);v.setSingleLine(true);v.setTextColor(Color.WHITE);v.setTextSize(15);v.setText(value);
        v.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));parent.addView(v);return v;
    }
    private Spinner spinner(LinearLayout parent,String label,String[] values,int selected) {
        parent.addView(text(label));Spinner s=new Spinner(activity);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,values);
        s.setAdapter(adapter);s.setSelection(selected);parent.addView(s);return s;
    }
    private Button button(LinearLayout parent,String label,final Runnable action) {
        Button b=new Button(activity);b.setText(label);b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});parent.addView(b);return b;
    }
    private ControlsConfig readConfig(){return ControlsConfig.parse(score.getText().toString(),judgment.getSelectedItemPosition(),position.getSelectedItemPosition(),start.getText().toString(),end.getText().toString(),count.getText().toString(),indices.getText().toString());}
    private void apply(boolean save) {
        try {
            ControlsConfig c=readConfig();String targetRks=rks.getText().toString().trim();
            if(save && !targetRks.isEmpty())RksEditor.validate(activity,targetRks);
            NativeControls.apply(c);
            // Retain edits in memory even if native code has not been loaded yet.
            SharedPreferences.Editor edit=preferences.edit().putString("score",score.getText().toString()).putInt("judgment",c.judgment).putInt("position",c.position)
                .putString("start",start.getText().toString()).putString("end",end.getText().toString()).putString("count",count.getText().toString()).putString("indices",indices.getText().toString());
            if(save && !targetRks.isEmpty())edit.putString("rks_target",targetRks).putBoolean("rks_pending",true).putBoolean("rks_restore",false);
            if(save && targetRks.isEmpty())edit.putString("rks_target","").putBoolean("rks_pending",false);
            if(!edit.commit())throw new IllegalStateException("配置写入失败");
            toast(save && !targetRks.isEmpty()?"配置已保存；下次冷启动生成目标 RKS 历史成绩":"配置已保存，判定设置从下一局生效");
            refresh();
        } catch(Exception e){toast(String.valueOf(e.getMessage()));}
    }
    private void refresh() {
        if(status==null)return;int[] s=NativeControls.getStatus();
        String message=LaunchActivity.isNativeLoaded()?(s[0]==1?"原生控制就绪":"原生控制准备中 / 未就绪"):"手动模式：未加载原生 Hook";
        if(s[2]>0)message+="\n本局音符 "+s[2]+"；计划干预 "+s[3]+"；已执行 "+s[4];
        String history=preferences.getString("rks_status","");if(!history.isEmpty())message+="\n"+history;
        if(preferences.getBoolean("rks_pending",false))message+="\n待重启应用 RKS："+preferences.getString("rks_target","");
        if(preferences.getBoolean("rks_restore",false))message+="\n待重启恢复历史备份";
        status.setText(message);updating=true;auto.setChecked(LaunchActivity.isAutoplay());updating=false;
    }
    private void show() {
        if(!Settings.canDrawOverlays(activity)){guide();return;}
        if(visible)return;
        LinearLayout outer=new LinearLayout(activity){@Override public boolean dispatchKeyEvent(KeyEvent e){return key(e)||super.dispatchKeyEvent(e);}};
        outer.setOrientation(LinearLayout.VERTICAL);outer.setBackgroundColor(0xe6252932);outer.setPadding(dp(8),dp(6),dp(8),dp(6));
        TextView header=text("Phigros 离线控制 · 拖动此处");header.setTextSize(16);outer.addView(header);
        ScrollView scroll=new ScrollView(activity);LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=text("");body.addView(status);
        auto=new Switch(activity);auto.setText("自动游玩");auto.setTextColor(Color.WHITE);body.addView(auto);auto.setChecked(LaunchActivity.isAutoplay());
        body.addView(text("每次启动重新选择。启用后整次会话的新成绩不保留；重启可恢复正常保存。判定配置请在选曲页设置。"));
        score=field(body,"目标结算数字（0～1000000；无指定干预时生效）",preferences.getString("score","1000000"),false);
        body.addView(text("目标分数只覆盖结算数字，判定统计保留实际结果；指定音符干预优先，按真实计分结算。"));
        judgment=spinner(body,"判定干预",new String[]{"关闭 / 默认 AP","Miss","Good","Bad"},preferences.getInt("judgment",0));
        position=spinner(body,"位置（整张谱面时间顺序，从 1 开始）",new String[]{"关闭","第 N 个","范围 N～M","编号列表","随机 K 个","从 N 开始连续 K 个","全部音符"},preferences.getInt("position",0));
        start=field(body,"N / 起始编号",preferences.getString("start","1"),false);end=field(body,"M / 结束编号",preferences.getString("end","1"),false);count=field(body,"K / 数量",preferences.getString("count","1"),false);
        indices=field(body,"编号列表，如 10,25,100",preferences.getString("indices",""),false);indices.setInputType(InputType.TYPE_CLASS_TEXT);
        body.addView(text("越界编号忽略；范围和数量截断到谱面长度，随机不重复。菜单状态显示实际计划数量。"));
        rks=field(body,"目标历史 RKS（最多 4 位小数；留空则不调整）",preferences.getString("rks_target",""),true);
        body.addView(text("保存后下次冷启动改写 1037 个本地历史记录，使大部分不再 AP。只改离线版；修改前历史会另存备份。最高约 17.3166，极低不可达值会拒绝。"));
        button(body,"应用判定设置",new Runnable(){public void run(){apply(false);}});
        button(body,"保存配置 / 排队应用 RKS",new Runnable(){public void run(){apply(true);}});
        button(body,"重置自动游玩配置",new Runnable(){public void run(){score.setText("1000000");judgment.setSelection(0);position.setSelection(0);start.setText("1");end.setText("1");count.setText("1");indices.setText("");apply(false);}});
        button(body,"下次启动恢复修改前历史成绩",new Runnable(){public void run(){
            if(preferences.getString("rks_backup","").isEmpty()){toast("尚无 RKS 修改前备份");return;}
            if(preferences.edit().putBoolean("rks_restore",true).putBoolean("rks_pending",false).putString("rks_target","").commit()){rks.setText("");toast("已排队，下次冷启动恢复历史备份");refresh();}
        }});
        button(outer,"收起（音量下键）",new Runnable(){public void run(){hide();}});
        int w=activity.getResources().getDisplayMetrics().widthPixels,h=activity.getResources().getDisplayMetrics().heightPixels;
        layout=new WindowManager.LayoutParams(Math.min(dp(350),w-dp(16)),Math.min(dp(550),h-dp(24)),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        layout.gravity=Gravity.TOP|Gravity.LEFT;layout.x=Math.max(0,Math.min(preferences.getInt("x",dp(16)),w-layout.width));layout.y=Math.max(0,Math.min(preferences.getInt("y",dp(16)),h-layout.height));
        layout.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        header.setOnTouchListener(new View.OnTouchListener(){float x,y;int px,py;public boolean onTouch(View v,MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();px=layout.x;py=layout.y;return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){layout.x=Math.max(0,Math.min(px+(int)(e.getRawX()-x),w-layout.width));layout.y=Math.max(0,Math.min(py+(int)(e.getRawY()-y),h-layout.height));manager.updateViewLayout(panel,layout);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){preferences.edit().putInt("x",layout.x).putInt("y",layout.y).apply();return true;}return false;
        }});
        panel=outer;
        try {manager.addView(panel,layout);visible=true;refresh();handler.post(ticker);}
        catch(RuntimeException e){panel=null;visible=false;toast("浮窗无法显示，请重新检查悬浮窗授权");}
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){public void onCheckedChanged(CompoundButton b,final boolean enabled){
            if(updating)return;b.setEnabled(false);
            new Thread(new Runnable(){public void run(){String failure=null;try{LaunchActivity.changeAutoplay(activity,enabled);}catch(Throwable e){failure=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());}
                final String error=failure;activity.runOnUiThread(new Runnable(){public void run(){if(auto!=null){auto.setEnabled(true);refresh();}if(error!=null)toast(error);}});
            }},"PhigrosControlToggle").start();
        }});
    }
    private void hide() {
        handler.removeCallbacks(ticker);if(panel!=null){try{manager.removeViewImmediate(panel);}catch(RuntimeException ignored){}panel=null;}visible=false;
    }
}
