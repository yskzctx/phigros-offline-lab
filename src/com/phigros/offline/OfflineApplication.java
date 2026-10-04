package com.phigros.offline;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

public final class OfflineApplication extends Application {

    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(context);
        Log.i("PhigrosOffline", "app.attach; preparing independent preferences");
        OfflineAssets.initialize(this);
        // Restore before Unity or SDK content providers can cache the preferences.
        try { LaunchActivity.prepareInitialData(this); }
        catch (Throwable error) { Log.e("PhigrosOffline", "Initial data setup failed", error); }
    }

    @Override public void onCreate() {
        super.onCreate();
        Log.i("PhigrosOffline", "app.created; manual by default, controls on game entry");
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity activity) {
                if (!LaunchActivity.isSessionChosen()
                        && "com.unity3d.player.UnityPlayerActivity".equals(activity.getClass().getName())) {
                    try {LaunchActivity.beginManualSession(activity);}
                    catch(java.io.IOException e){Log.e("PhigrosOffline","Manual session initialization failed",e);}
                }
                OverlayMenu.resume(activity);
            }
            public void onActivityCreated(Activity activity, Bundle saved) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityPaused(Activity a) { OverlayMenu.pause(a); }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { OverlayMenu.destroy(a); }
        });
    }
}
