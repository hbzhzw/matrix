package com.tencent.matrix.lifecycle.owners

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.NonNull
import androidx.lifecycle.*
import com.tencent.matrix.lifecycle.IStateObserver
import com.tencent.matrix.lifecycle.StatefulOwner
import com.tencent.matrix.lifecycle.owners.MatrixProcessLifecycleInitializer.Companion.init
import com.tencent.matrix.listeners.IAppForeground
import com.tencent.matrix.util.MatrixHandlerThread
import com.tencent.matrix.util.MatrixLog
import com.tencent.matrix.util.MatrixUtil
import com.tencent.matrix.util.safeLet
import java.util.*
import kotlin.collections.HashMap

/**
 * multi process version of [androidx.lifecycle.ProcessLifecycleOwner]
 *
 * for preloaded processes(never resumed or started), observer wouldn't be notified.
 * otherwise, adding observer would trigger callback immediately
 *
 * Activity's lifecycle callback is not always reliable and compatible. onStop might be called
 * without calling onStart or onResume, or onResume might be called more than once but stop once
 * only. For these wired cases we don't use simple counter here.
 *
 * Created by Yves on 2021/9/14
 */
object MatrixProcessLifecycleOwner {

    private const val TAG = "Matrix.ProcessLifecycle"

    private const val TIMEOUT_MS = 500L //mls
    private var processName: String? = null
    private var packageName: String? = null
    private var activityManager: ActivityManager? = null

    internal fun init(context: Context) {
        activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        processName = MatrixUtil.getProcessName(context)
        packageName = MatrixUtil.getPackageName(context)
        attach(context)
        MatrixLog.i(TAG, "init for [${processName}]")
    }

    // ========================== base ========================== //

    private val runningHandler = MatrixHandlerThread.getDefaultHandler()

    private val stub = Any()
    private val createdActivities = WeakHashMap<Activity, Any>() // maybe useless
    private val resumedActivities = WeakHashMap<Activity, Any>()
    private val startedActivities = WeakHashMap<Activity, Any>()

    private val destroyedActivities = WeakHashMap<Activity, Any>()

    private fun WeakHashMap<Activity, Any>.put(activity: Activity) {
        put(activity, stub)
    }

    private var pauseSent = true
    private var stopSent = true

    private open class AsyncOwner : StatefulOwner() {
        open fun turnOnAsync() { runningHandler.post { turnOn() } }
        open fun turnOffAsync() { runningHandler.post { turnOff() } }
    }

    private class CreatedStateOwner: AsyncOwner() {
        override fun active(): Boolean {
            return super.active() && createdActivities.all { false == it.key?.isFinishing }
        }
    }

    val createdStateOwner: StatefulOwner = CreatedStateOwner()
    val resumedStateOwner: StatefulOwner = AsyncOwner()
    val startedStateOwner: StatefulOwner = AsyncOwner()

    var recentActivity = "default"
        private set

    private val delayedPauseRunnable = Runnable {
        dispatchPauseIfNeeded()
        dispatchStopIfNeeded()
    }

    fun retainedActivities(): Map<String, Int> {
        val map = HashMap<String, Int>()
        Runtime.getRuntime().gc()

        val cpy = destroyedActivities.entries.toTypedArray()

        for (e in cpy) {
            val k = e.key ?: continue
            k.javaClass.simpleName.let {
                var count = map.getOrPut(it, { 0 })
                map[it] = ++count
            }
        }

        return map
    }

    private fun activityCreated(activity: Activity) {
        val isEmptyBefore = createdActivities.isEmpty()
        createdActivities.put(activity)

        if (isEmptyBefore) {
            (createdStateOwner as AsyncOwner).turnOnAsync()
        }
    }

    private fun activityStarted(activity: Activity) {
        val isEmptyBefore = startedActivities.isEmpty()
        startedActivities.put(activity)

        if (isEmptyBefore && stopSent) {
            (startedStateOwner as AsyncOwner).turnOnAsync()
        }
    }

    private fun activityResumed(activity: Activity) {
        val isEmptyBefore = resumedActivities.isEmpty()
        resumedActivities.put(activity)
        if (isEmptyBefore) {
            if (pauseSent) {
                (resumedStateOwner as AsyncOwner).turnOnAsync()
                pauseSent = false
            } else {
                runningHandler.removeCallbacks(delayedPauseRunnable)
            }
        }
    }

    private fun activityPaused(activity: Activity) {
        resumedActivities.remove(activity)

        if (resumedActivities.isEmpty()) {
            runningHandler.postDelayed(delayedPauseRunnable, TIMEOUT_MS)
        }
    }

    private fun activityStopped(activity: Activity) {
        startedActivities.remove(activity)
        dispatchStopIfNeeded()
    }

    private fun activityDestroyed(activity: Activity) {
        createdActivities.remove(activity)
        destroyedActivities.put(activity)
        if (createdActivities.isEmpty()) {
            (createdStateOwner as AsyncOwner).turnOffAsync()
        }
        // fallback remove
        startedActivities.remove(activity)?.let {
            MatrixLog.w(
                TAG,
                "removed [$activity] when destroy, maybe something wrong with onStart/onStop callback"
            )
        }
        resumedActivities.remove(activity)?.let {
            MatrixLog.w(
                TAG,
                "removed [$activity] when destroy, maybe something wrong with onResume/onPause callback"
            )
        }
    }

    private fun dispatchPauseIfNeeded() {
        if (resumedActivities.isEmpty()) {
            pauseSent = true
            (resumedStateOwner as AsyncOwner).turnOffAsync()
        }
    }

    private fun dispatchStopIfNeeded() {
        if (startedActivities.isEmpty() && pauseSent) {
            stopSent = true
            (startedStateOwner as AsyncOwner).turnOffAsync()
        }
    }

    private fun attach(context: Context) {
        startedStateOwner.observeForever(DefaultLifecycleObserver())

        val app = context.applicationContext as Application
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activityCreated(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                updateScene(activity)
                activityStarted(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                activityResumed(activity)
                recentActivity = activity.javaClass.simpleName
            }

            override fun onActivityPaused(activity: Activity) {
                activityPaused(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                activityStopped(activity)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
                activityDestroyed(activity)
            }
        })
    }

    // ========================== extension for compatibility ========================== //

    private val mListeners = HashSet<IAppForeground>()

    @get:JvmName("isProcessForeground")
    @Volatile
    var isProcessForeground = false
        private set

    // compat
    var visibleScene = "default"
        private set

    // compat
    var currentFragmentName: String? = null
        /**
         * must set after [Activity#onStart]
         */
        set(value) {
            MatrixLog.i(TAG, "[setCurrentFragmentName] fragmentName: $value")
            field = value

            if (value != null) {
                updateScene(value)
            } else {
                updateScene("?")
            }
        }

    fun addListener(listener: IAppForeground) {
        synchronized(mListeners) {
            mListeners.add(listener)
        }
    }

    fun removeListener(listener: IAppForeground) {
        synchronized(mListeners) {
            mListeners.remove(listener)
        }
    }

    private fun updateScene(activity: Activity) {
        visibleScene = activity.javaClass.name
    }

    private fun updateScene(scene: String) {
        visibleScene = scene
    }

    class DefaultLifecycleObserver : IStateObserver {
        private fun onDispatchForeground() {
            if (isProcessForeground) {
                return
            }
            MatrixLog.i(TAG, "onForeground... visibleScene[$visibleScene@$processName]")
            runningHandler.post {
                isProcessForeground = true
                synchronized(mListeners) {
                    for (listener in mListeners) {
                        listener.onForeground(true)
                    }
                }
            }
        }

        private fun onDispatchBackground() {
            if (!isProcessForeground) {
                return
            }
            MatrixLog.i(TAG, "onBackground... visibleScene[$visibleScene@$processName]")
            runningHandler.post {
                isProcessForeground = false
                synchronized(mListeners) {
                    for (listener in mListeners) {
                        listener.onForeground(false)
                    }
                }
            }
        }

        override fun on() = onDispatchForeground()

        override fun off() = onDispatchBackground()
    }
}

/**
 * You should init [com.tencent.matrix.Matrix] or call [init] manually before creating any Activity
 * Created by Yves on 2021/9/14
 */
class MatrixProcessLifecycleInitializer {

    companion object {
        private const val TAG = "Matrix.ProcessLifecycleOwnerInit"

        @Volatile
        private var inited = false

        @JvmStatic
        fun init(@NonNull context: Context, baseActivities: List<String>) {
            if (inited) {
                return
            }
            inited = true
            if (hasCreatedActivities()) {
                ("Matrix Warning: Matrix might be inited after launching first Activity, " +
                        "which would disable some features like ProcessLifecycleOwner, " +
                        "pls consider calling MultiProcessLifecycleInitializer#init manually " +
                        "or initializing matrix at Application#onCreate").let {
                    MatrixLog.e(TAG, it)
                }
                return
            }
            MatrixProcessLifecycleOwner.init(context)
//            ActivityRecorder.init(context.applicationContext as Application, baseActivities)
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        @JvmStatic
        fun hasCreatedActivities() = safeLet(tag = TAG, defVal = false) {
            val clazzActivityThread = Class.forName("android.app.ActivityThread")
            val objectActivityThread =
                clazzActivityThread.getMethod("currentActivityThread").invoke(null)
            val fieldMActivities = clazzActivityThread.getDeclaredField("mActivities")
            fieldMActivities.isAccessible = true
            val mActivities = fieldMActivities.get(objectActivityThread) as Map<*, *>?
            return mActivities != null && mActivities.isNotEmpty()
        }
    }
}