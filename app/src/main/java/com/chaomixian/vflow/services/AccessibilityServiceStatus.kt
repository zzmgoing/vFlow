package com.chaomixian.vflow.services

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.chaomixian.vflow.ui.viewmodel.SettingsViewModel
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

object AccessibilityServiceStatus {

    fun getOriginalServiceId(context: Context): String {
        return ComponentName(context, AccessibilityService::class.java).flattenToString()
    }

    fun getDisguisedServiceId(context: Context): String {
        return ComponentName(context, SelectToSpeakService::class.java).flattenToString()
    }

    fun getServiceId(context: Context): String {
        val prefs = context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val disguised = prefs.getBoolean(SettingsViewModel.KEY_ACCESSIBILITY_DISGUISE, false)
        return if (disguised) getDisguisedServiceId(context) else getOriginalServiceId(context)
    }

    fun isEnabledInSettings(context: Context): Boolean {
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return containsAnyServiceId(
            enabledServicesSetting,
            getOriginalServiceId(context),
            getDisguisedServiceId(context)
        )
    }

    fun isRunning(context: Context): Boolean {
        return ServiceStateBus.isAccessibilityServiceRunning()
    }

    /**
     * 无障碍服务可能正在正式组件与伪装组件之间重连。权限检查应识别两个属于
     * vFlow 的组件，不能仅依赖偏好中记录的目标组件，否则切换期间会误报未授权。
     */
    fun isGranted(context: Context): Boolean {
        return isRunning(context) || isEnabledInSettings(context)
    }

    internal fun containsServiceId(enabledServicesSetting: String?, expectedServiceId: String): Boolean {
        if (enabledServicesSetting.isNullOrBlank()) {
            return false
        }

        return enabledServicesSetting
            .split(':')
            .any { it.equals(expectedServiceId, ignoreCase = true) }
    }

    internal fun containsAnyServiceId(
        enabledServicesSetting: String?,
        vararg expectedServiceIds: String
    ): Boolean {
        return expectedServiceIds.any { containsServiceId(enabledServicesSetting, it) }
    }

    internal fun replaceServiceId(
        enabledServicesSetting: String?,
        fromServiceId: String,
        toServiceId: String
    ): String {
        val entries = enabledServicesSetting
            .orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot {
                it.equals(fromServiceId, ignoreCase = true) ||
                    it.equals(toServiceId, ignoreCase = true)
            }
            .toMutableList()

        entries += toServiceId
        return entries.joinToString(":")
    }
}
