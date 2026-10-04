package com.phigros.offline;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

public final class OfflineApplication extends Application {
    private boolean promptShown;

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
        Log.i("PhigrosOffline", "app.created; native library deferred until explicit enabled choice");
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity activity) {
                OverlayMenu.resume(activity);
                // Android can restore Unity directly after killing a background process.
                // Route that new process through the mode choice as well.
                if (!LaunchActivity.isSessionChosen() && !promptShown
                        && "com.unity3d.player.UnityPlayerActivity".equals(activity.getClass().getName())) {
                    promptShown = true;
                    LaunchActivity.showRestoredPrompt(activity);
                }
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
