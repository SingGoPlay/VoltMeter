# 小部件/服务由系统按组件名唤起，相关类不能删
-keep class io.moyi.voltmeter.VoltWidgetProvider { *; }
-keep class io.moyi.voltmeter.VoltService { *; }
-keep class io.moyi.voltmeter.MainActivity { *; }

# 本项目代码量很小，整体保留，省得出奇怪的反射问题
-keep class io.moyi.voltmeter.** { *; }

# MIUIX（Compose Multiplatform UI 库）
-keep class top.yukonga.miuix.** { *; }
-dontwarn top.yukonga.miuix.**
