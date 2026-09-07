package io.legado.app.fanqie

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.delay
import splitties.init.appCtx

object FanqieFeature {

    private const val SYNC_INTERVAL_MS = 12L * 60 * 60 * 1000

    fun init() {
        // 阅读器关闭/App 退后台时兜底上报进度，避免 30s 防抖内退出丢失
        (appCtx as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                FanqieProgressSyncer.flush()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
            }
        )
        Coroutine.async {
            FanqieGroup.ensureGroup()
            FanqieProgressSyncer.start()
            FanqieApi.refreshLoginState()
            // 启动时不做全量同步，由书架页加载 / 手动同步 / 下方 12h 定时兜底
            while (true) {
                delay(SYNC_INTERVAL_MS)
                if (!FanqieApi.hasCookie()) continue
                runCatching { FanqieShelfRepository.syncFromCloud(FanqieApi.fetchShelfBooks()) }
            }
        }
    }
}
