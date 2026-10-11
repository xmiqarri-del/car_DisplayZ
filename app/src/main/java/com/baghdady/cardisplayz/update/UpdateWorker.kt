package com.baghdady.cardisplayz.update

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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.baghdady.cardisplayz.BuildConfig
import com.baghdady.cardisplayz.MainActivity
import com.baghdady.cardisplayz.R
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Runs in the background every 6 hours on a network connection. It checks for a newer build,
 * downloads and verifies it (SHA-256), then posts a notification. It shows nothing when there
 * is no update or when the check fails; the user only sees the "ready to install" notification.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            val check = UpdateRepository().checkForUpdate(
                BuildConfig.UPDATE_MANIFEST_URL,
                BuildConfig.VERSION_CODE
            )
            if (check is UpdateCheck.Available) {
                val info = check.info
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
            Result.success()
        } catch (e: UpdateException) {
            // Bad manifest, hash mismatch or HTTP error: stay silent and try again next run.
            Result.success()
        } catch (e: IOException) {
            // No connection or interrupted transfer: WorkManager retries with backoff.
            Result.retry()
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
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 2001

        /** Schedules the periodic background check. Calling it again keeps the existing schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
