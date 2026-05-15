package com.whatsapparchiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.whatsapparchiver.worker.WorkerScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Reiniciando Workers após boot/update")
            val prefs = context.getSharedPreferences("archiver_prefs", Context.MODE_PRIVATE)
            WorkerScheduler.initialize(
                context,
                intervalMinutes = prefs.getLong("interval_minutes", 30),
                wifiOnly        = prefs.getBoolean("wifi_only", false),
                chargingOnly    = prefs.getBoolean("charging_only", false)
            )
        }
    }
}
