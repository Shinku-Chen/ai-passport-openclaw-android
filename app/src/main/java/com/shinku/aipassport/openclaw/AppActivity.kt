package com.shinku.aipassport.openclaw

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 当前前台 Activity 弱引用持有者。
 *
 * BLE 配对输入框必须在 Activity(带 window token)上弹,而配对广播由前台 Service
 * 收到;Service 拿不到 Activity,故由 MainActivity 在 onCreate/onDestroy 注册/清理,
 * BleCentral 经此取当前 Activity 上下文弹框。WeakReference 避免持有导致泄漏。
 */
object AppActivity {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    /** 当前 Activity;App 不在前台时为 null。 */
    fun current(): Activity? = ref?.get()
}
