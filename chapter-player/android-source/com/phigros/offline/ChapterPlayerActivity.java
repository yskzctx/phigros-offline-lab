package com.phigros.offline;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import android.view.View;
import android.view.Surface;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;

import android.content.Context;
import android.content.Intent;


import quad_native.QuadNative;

// note: //% is a special miniquad's pre-processor for plugins
// when there are no plugins - //% whatever will be replaced to an empty string
// before compiling

//% IMPORTS

class QuadSurface
    extends
        SurfaceView
    implements
        View.OnTouchListener,
        View.OnKeyListener,
        SurfaceHolder.Callback {

    public QuadSurface(Context context){
        super(context);
        getHolder().addCallback(this);

        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();
        setOnTouchListener(this);
        setOnKeyListener(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i("SAPP", "surfaceCreated");
        Surface surface = holder.getSurface();
        QuadNative.surfaceOnSurfaceCreated(surface);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i("SAPP", "surfaceDestroyed");
        Surface surface = holder.getSurface();
        QuadNative.surfaceOnSurfaceDestroyed(surface);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder,
                               int format,
                               int width,
                               int height) {
        Log.i("SAPP", "surfaceChanged");
        Surface surface = holder.getSurface();
        QuadNative.surfaceOnSurfaceChanged(surface, width, height);

    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();

        switch(action) {
        case MotionEvent.ACTION_MOVE: {
            for (int i = 0; i < pointerCount; i++) {
                final int id = event.getPointerId(i);
                final float x = event.getX(i);
                final float y = event.getY(i);
                QuadNative.surfaceOnTouch(id, 0, x, y);
            }
            break;
        }
        case MotionEvent.ACTION_UP: {
            final int id = event.getPointerId(0);
            final float x = event.getX(0);
            final float y = event.getY(0);
            QuadNative.surfaceOnTouch(id, 1, x, y);
            break;
        }
        case MotionEvent.ACTION_DOWN: {
            final int id = event.getPointerId(0);
            final float x = event.getX(0);
            final float y = event.getY(0);
            QuadNative.surfaceOnTouch(id, 2, x, y);
            break;
        }
        case MotionEvent.ACTION_POINTER_UP: {
            final int pointerIndex = event.getActionIndex();
            final int id = event.getPointerId(pointerIndex);
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            QuadNative.surfaceOnTouch(id, 1, x, y);
            break;
        }
        case MotionEvent.ACTION_POINTER_DOWN: {
            final int pointerIndex = event.getActionIndex();
            final int id = event.getPointerId(pointerIndex);
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            QuadNative.surfaceOnTouch(id, 2, x, y);
            break;
        }
        case MotionEvent.ACTION_CANCEL: {
            for (int i = 0; i < pointerCount; i++) {
                final int id = event.getPointerId(i);
                final float x = event.getX(i);
                final float y = event.getY(i);
                QuadNative.surfaceOnTouch(id, 3, x, y);
            }
            break;
        }
        default:
            break;
        }

        return true;
    }

    // docs says getCharacters are deprecated
    // but somehow on non-latyn input all keyCode and all the relevant fields in the KeyEvent are zeros
    // and only getCharacters has some usefull data
    @SuppressWarnings("deprecation")
    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != 0) {
            QuadNative.surfaceOnKeyDown(keyCode);
        }

        if (event.getAction() == KeyEvent.ACTION_UP && keyCode != 0) {
            QuadNative.surfaceOnKeyUp(keyCode);
        }
        
        if (event.getAction() == KeyEvent.ACTION_UP || event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            int character = event.getUnicodeChar();
            if (character == 0) {
                String characters = event.getCharacters();
                if (characters != null && characters.length() >= 0) {
                    character = characters.charAt(0);
                }
            }

            if (character != 0) {
                QuadNative.surfaceOnCharacter(character);
            }
        }

        return true;
    }

    public Surface getNativeSurface() {
        return getHolder().getSurface();
    }
}

public class ChapterPlayerActivity extends Activity {
    
    private static native boolean configureChapter(String request);
    private boolean nativeReady;
    public void finishChapter(){runOnUiThread(new Runnable(){public void run(){finish();}});}
    public void chooseFile(){} // No file/account entry inside gameplay; picker lives in chapter library.


    private QuadSurface view;

    static {
        System.loadLibrary("phigros_chapter");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        
        try {
            String id=getIntent().getStringExtra("chart_id");
            if(id==null||!id.matches("[0-9a-f-]{36}"))throw new java.io.IOException("谱包标识无效");
            java.io.File root=ChapterLibraryActivity.home(this),dir=new java.io.File(root,id);
            if(!dir.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))throw new java.io.IOException("谱包路径越界");
            ChapterPack.open(dir);
            org.json.JSONObject request=new org.json.JSONObject();request.put("directory",dir.getCanonicalPath());request.put("data_root",root.getCanonicalPath());request.put("autoplay",getIntent().getBooleanExtra("autoplay",false));
            ControlsConfig controls=NativeControls.load(this);org.json.JSONObject settings=new org.json.JSONObject();settings.put("mode",controls.mode);settings.put("target",controls.target);settings.put("accuracy",controls.accuracy);settings.put("rules",controls.encodedRules);request.put("controls",settings);
            String skinId=SkinController.selectedId(this);
            if(!skinId.isEmpty()){
                try {
                    java.io.File skin=ChapterSkinExport.prepare(SkinController.selected(this,skinId),new java.io.File(getFilesDir().getCanonicalFile(),"offline-chapter-skins"),name->getAssets().open(name));
                    request.put("skin_directory",skin.getCanonicalPath());
                }catch(java.io.IOException e){android.widget.Toast.makeText(this,"章节皮肤无法加载，使用默认音符："+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}
            }
            if(!configureChapter(request.toString()))throw new java.io.IOException("播放器拒绝此谱包路径");
        } catch(Exception e) {android.widget.Toast.makeText(this,"自制谱无法打开："+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();finish();return;}

        view = new QuadSurface(this);
        setContentView(view);

        QuadNative.activityOnCreate(this);
        nativeReady=true;

        //% MAIN_ACTIVITY_ON_CREATE
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(nativeReady)QuadNative.activityOnResume();

        //% MAIN_ACTIVITY_ON_RESUME
    }

    @Override
    public void onBackPressed() {
        Log.w("SAPP", "onBackPressed");

        // TODO: here is the place to handle request_quit/order_quit/cancel_quit

        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(nativeReady){QuadNative.activityOnDestroy();QuadNative.prprActivityOnDestroy();}
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(nativeReady)QuadNative.activityOnPause();

        //% MAIN_ACTIVITY_ON_PAUSE
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        //% MAIN_ACTIVITY_ON_ACTIVITY_RESULT
    }

    public void setFullScreen(final boolean fullscreen) {
        runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    View decorView = getWindow().getDecorView();

                    if (fullscreen) {
                        getWindow().setFlags(LayoutParams.FLAG_LAYOUT_NO_LIMITS, LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                        getWindow().getAttributes().layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                        // Android deprecate dsetSystemUiVisibility, but it is not clear
                        // from the deprecation notes what exaclty we should do instead
                        // hope this works! but just in case, left the old code in a comment below
                        //int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
                        //decorView.setSystemUiVisibility(uiOptions);
                        getWindow().setDecorFitsSystemWindows(false);
                    }
                    else {
                       // there might be a bug hidden here: setSystemUiVisibility was dealing with the bars
                        // and setDecorFitsSystemWindows might not, needs some testing
                        //decorView.setSystemUiVisibility(0);
                        getWindow().setDecorFitsSystemWindows(true);

                    }
                }
            });
    }

    public void showKeyboard(final boolean show) {
        runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (show) {
                        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.showSoftInput(view, 0);
                    } else {
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(view.getWindowToken(),0); 
                    }
                }
            });
    }
}

