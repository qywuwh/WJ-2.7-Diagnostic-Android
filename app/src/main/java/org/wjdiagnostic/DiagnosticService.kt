package org.wjdiagnostic

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class DiagnosticService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("diagnostic", "Диагностика автомобиля", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "diagnostic")
            .setContentTitle("WJ Diagnostic")
            .setContentText("Диагностика автомобиля активна")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(42, notification)
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
