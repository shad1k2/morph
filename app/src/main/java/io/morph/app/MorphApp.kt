package io.morph.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Tracks whether any MORPH screen is in front, to pick in-app vs overlay card. */
class MorphApp : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        @Volatile
        var isForeground: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        isForeground = true
    }

    override fun onActivityPaused(activity: Activity) {
        isForeground = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
