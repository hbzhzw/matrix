package com.tencent.matrix.hook;


import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.matrix.util.MatrixLog;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by Yves on 2020-03-17
 */
public class HookManager {
    private static final String TAG = "Matrix.HookManager";

    public static final HookManager INSTANCE = new HookManager();

    private volatile boolean hasHooked;
    private Set<AbsHook> mHooks = new HashSet<>();

    private NativeLibraryLoader mNativeLibLoader = null;

    public interface NativeLibraryLoader {
        void loadLibrary(@NonNull String libName);
    }

    private HookManager(){
    }

    private void exclusiveHook() {
        xhookEnableDebugNative(BuildConfig.DEBUG);
        xhookEnableSigSegvProtectionNative(!BuildConfig.DEBUG);

        xhookRefreshNative(false);

        hasHooked = true;
    }

    public void commitHooks() throws HookFailedException {
        if (hasHooked) {
            throw new HookFailedException("this process has already been hooked!");
        }

        if (mHooks.isEmpty()) {
            return;
        }

        try {
            if (mNativeLibLoader != null) {
                mNativeLibLoader.loadLibrary("matrix-hooks");
            } else {
                System.loadLibrary("matrix-hooks");
            }
        } catch (Throwable e) {
            MatrixLog.printErrStackTrace(TAG, e, "");
            return;
        }

        for (AbsHook hook : mHooks) {
            hook.onConfigure();
        }
        for (AbsHook hook : mHooks) {
            hook.onHook();
        }
        exclusiveHook();
    }

    public HookManager setNativeLibraryLoader(@Nullable NativeLibraryLoader loader) {
        mNativeLibLoader = loader;
        return this;
    }

    public HookManager addHook(@Nullable AbsHook hook) {
        if (hook != null) {
            mHooks.add(hook);
        }
        return this;
    }

    public HookManager clearHooks() {
        mHooks.clear();
        return this;
    }

    @Keep
    public static String getStack() {
        return stackTraceToString(Thread.currentThread().getStackTrace());
    }

    private static String stackTraceToString(final StackTraceElement[] arr) {
        if (arr == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (StackTraceElement stackTraceElement : arr) {
            String className = stackTraceElement.getClassName();
            // remove unused stacks
            if (className.contains("java.lang.Thread")) {
                continue;
            }

            sb.append(stackTraceElement).append(';');
        }
        return sb.toString();
    }

    public boolean hasHooked() {
        return hasHooked;
    }

    private native int xhookRefreshNative(boolean async);
    private native void xhookEnableDebugNative(boolean flag);
    private native void xhookEnableSigSegvProtectionNative(boolean flag);
    private native void xhookClearNative();

    public static class HookFailedException extends Exception {

        public HookFailedException(String message) {
            super(message);
        }
    }
}