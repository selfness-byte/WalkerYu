package com.mj.transitmap.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.mj.transitmap.data.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class BaseTransitWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.renderAll(context)
        WidgetRefreshJob.schedulePeriodic(context)
        WidgetRefreshJob.syncMinuteSchedule(context)
        WidgetRefreshJob.runNow(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshJob.schedulePeriodic(context)
        WidgetRefreshJob.syncMinuteSchedule(context)
    }

    override fun onDisabled(context: Context) {
        if (!WidgetUpdater.hasWidgets(context)) WidgetRefreshJob.cancelAll(context)
    }
}

class SmallWidgetProvider : BaseTransitWidget()
class MediumWidgetProvider : BaseTransitWidget()
class LargeWidgetProvider : BaseTransitWidget()

/** 위젯 버튼: 📍위치 갱신 ON/OFF, ⟳ 새로고침, 1분 자동 갱신 틱 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        when (intent.action) {
            ACTION_TOGGLE_LOCATION -> {
                val prefs = AppPrefs(appCtx)
                prefs.widgetUseLocation = !prefs.widgetUseLocation
                WidgetRefreshJob.syncMinuteSchedule(appCtx)
                WidgetUpdater.renderAll(appCtx, refreshing = true)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        WidgetUpdater.refreshAll(appCtx)
                    } finally {
                        WidgetRefreshJob.syncMinuteSchedule(appCtx)
                        pending.finish()
                    }
                }
            }
            ACTION_REFRESH -> {
                WidgetRefreshJob.syncMinuteSchedule(appCtx)
                WidgetUpdater.renderAll(appCtx, refreshing = true)
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        WidgetUpdater.refreshAll(appCtx)
                    } finally {
                        WidgetRefreshJob.syncMinuteSchedule(appCtx)
                        pending.finish()
                    }
                }
            }
            ACTION_MINUTE_TICK -> {
                if (AppPrefs(appCtx).widgetUseLocation && WidgetUpdater.hasWidgets(appCtx)) {
                    WidgetRefreshJob.syncMinuteSchedule(appCtx)
                    val pending = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                        try {
                            WidgetUpdater.refreshAll(appCtx)
                        } finally {
                            WidgetRefreshJob.syncMinuteSchedule(appCtx)
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_LOCATION = "com.mj.transitmap.widget.TOGGLE_LOCATION"
        const val ACTION_REFRESH = "com.mj.transitmap.widget.REFRESH"
        const val ACTION_MINUTE_TICK = "com.mj.transitmap.widget.MINUTE_TICK"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && WidgetUpdater.hasWidgets(context)) {
            WidgetRefreshJob.schedulePeriodic(context)
            WidgetRefreshJob.syncMinuteSchedule(context)
        }
    }
}

/**
 * 위젯 갱신 작업:
 * - 기본(위치 OFF): 15분 주기 JobScheduler
 * - 위치 ON(📍 위치 ON): 1분(60초) 간격 연쇄 Job + AlarmManager 로 1분마다 위치 및 도착정보 자동 갱신
 */
class WidgetRefreshJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                WidgetUpdater.refreshAll(applicationContext)
            } finally {
                syncMinuteSchedule(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_PERIODIC = 4101
        private const val JOB_NOW = 4102
        private const val JOB_MINUTE_TICK = 4103
        private const val REQ_ALARM_MINUTE = 4104
        const val MINUTE_INTERVAL_MS = 60_000L

        private fun scheduler(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

        fun schedulePeriodic(ctx: Context) {
            val js = scheduler(ctx) ?: return
            if (js.getPendingJob(JOB_PERIODIC) != null) return
            val info = JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, WidgetRefreshJob::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }

        /**
         * 위젯 '📍 위치 ON' 상태일 때는 1분(60초) 뒤 다음 갱신이 실행되도록 예약하고,
         * '○ 위치 OFF' 상태일 때는 1분 타이머를 해제한다.
         */
        fun syncMinuteSchedule(ctx: Context) {
            val appCtx = ctx.applicationContext
            val prefs = AppPrefs(appCtx)
            if (!prefs.widgetUseLocation || !WidgetUpdater.hasWidgets(appCtx)) {
                cancelMinuteTick(appCtx)
                return
            }

            // 1) JobScheduler 1분 지연 단발 작업 예약 (연쇄 실행)
            scheduler(appCtx)?.let { js ->
                val info = JobInfo.Builder(JOB_MINUTE_TICK, ComponentName(appCtx, WidgetRefreshJob::class.java))
                    .setMinimumLatency(MINUTE_INTERVAL_MS)
                    .setOverrideDeadline(MINUTE_INTERVAL_MS + 10_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                js.schedule(info)
            }

            // 2) AlarmManager 보조 1분 알람
            val am = appCtx.getSystemService(AlarmManager::class.java)
            if (am != null) {
                val pi = minuteAlarmIntent(appCtx)
                runCatching {
                    am.setWindow(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + MINUTE_INTERVAL_MS,
                        10_000L,
                        pi,
                    )
                }
            }
        }

        private fun cancelMinuteTick(ctx: Context) {
            scheduler(ctx)?.cancel(JOB_MINUTE_TICK)
            val am = ctx.getSystemService(AlarmManager::class.java)
            am?.cancel(minuteAlarmIntent(ctx))
        }

        private fun minuteAlarmIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_MINUTE_TICK)
            return PendingIntent.getBroadcast(ctx, REQ_ALARM_MINUTE, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun runNow(ctx: Context) {
            val js = scheduler(ctx) ?: return
            val info = JobInfo.Builder(JOB_NOW, ComponentName(ctx, WidgetRefreshJob::class.java))
                .setOverrideDeadline(500)
                .build()
            js.schedule(info)
        }

        fun cancelAll(ctx: Context) {
            cancelMinuteTick(ctx)
            scheduler(ctx)?.cancel(JOB_PERIODIC)
            scheduler(ctx)?.cancel(JOB_NOW)
        }
    }
}
