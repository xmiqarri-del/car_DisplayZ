package com.xmiqarri.cardisplayz.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xmiqarri.cardisplayz.AppSettings
import com.xmiqarri.cardisplayz.BuildConfig
import com.xmiqarri.cardisplayz.MainActivity
import com.xmiqarri.cardisplayz.R
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub for a newer build, downloads and verifies it (SHA-256), then posts a notification.
 * Runs every 6 hours when automatic checks are on, or once when the user presses "Check now".
 * Nothing is installed without the user tapping install.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            when (val check = UpdateRepository().checkForUpdate(
                BuildConfig.UPDATE_MANIFEST_URL,
                BuildConfig.VERSION_CODE
            )) {
                is UpdateCheck.UpToDate -> AppSettings.recordCheck(app, "none", null)
                is UpdateCheck.Available -> {
                    AppSettings.recordCheck(app, "available", check.info.versionName)
                    prepareUpdate(app, check.info)
                }
            }
            Result.success()
        } catch (e: UpdateException) {
            // Bad manifest, hash mismatch or HTTP error: stay silent and try again next run.
            AppSettings.recordCheck(app, "error", null)
            Result.success()
        } catch (e: IOException) {
            // No connection or interrupted transfer: WorkManager retries with backoff.
            AppSettings.recordCheck(app, "error", null)
            Result.retry()
        }
    }

    /** Downloads (or reuses) the verified APK and posts the "ready" notification. */
    private suspend fun prepareUpdate(app: Context, info: UpdateInfo) {
        val cached = ApkDownloader.cachedApk(app.cacheDir)
        val apk = if (cached.exists() && ApkInstaller.verifyPackage(app, cached, info.versionCode)) {
            cached
        } else {
            ApkDownloader(cacheDir = app.cacheDir).download(info)
        }
        if (ApkInstaller.verifyPackage(app, apk, info.versionCode)) {
            notifyReady(app, info)
        } else {
            apk.delete()
        }
    }

    private fun notifyReady(context: Context, info: UpdateInfo) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_INSTALL_UPDATE, true)
            .putExtra(EXTRA_VERSION_CODE, info.versionCode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_grid)
            .setContentTitle(context.getString(R.string.update_ready_title))
            .setContentText(context.getString(R.string.update_ready_text, info.versionName))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted; the Install button in the app still works.
        }
    }

    companion object {
        const val EXTRA_INSTALL_UPDATE = "install_update"
        const val EXTRA_VERSION_CODE = "version_code"

        private const val WORK_NAME = "car_displayz_update_check"
        private const val WORK_NAME_NOW = "car_displayz_update_check_now"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 2001

        /** Runs one check now (the "Check now" row). Works even when automatic checks are off. */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Schedules the 6-hourly check if the user keeps automatic checks on. */
        fun schedule(context: Context) {
            if (!AppSettings.autoUpdate(context)) return
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Turns off the 6-hourly check (the user switched automatic checks off). */
        fun cancelSchedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
