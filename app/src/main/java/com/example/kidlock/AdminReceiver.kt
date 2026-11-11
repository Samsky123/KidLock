package com.example.kidlock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 设备管理器接收器。仅用于设备所有者模式下的 lockNow()。
 * 无需在此写逻辑，注册即可。
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "设备管理器已启用", Toast.LENGTH_SHORT).show()
    }
    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "设备管理器已禁用", Toast.LENGTH_SHORT).show()
    }
}
