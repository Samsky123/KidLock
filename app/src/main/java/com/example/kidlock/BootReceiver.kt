package com.example.kidlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机后强制先进入答题锁屏。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && context != null) {
            val i = Intent(context, LaunchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }
}
