package com.vflow.fork.workflow.module

import android.content.Context
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.vflow.fork.hiddenobject.HiddenObjectAutoClickModule

/**
 * fork 专属模块注册入口。
 *
 * 新增 fork 模块统一从这里注册，上游注册表只保留一处桥接调用，
 * 方便后续同步上游代码时处理冲突。
 */
object ForkModuleRegistry {
    fun registerAll(context: Context) {
        ModuleRegistry.register(GoldenFingerClickerModule(), context)
        ModuleRegistry.register(HiddenObjectAutoClickModule(), context)
    }
}
