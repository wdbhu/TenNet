
# ============ TunNet / LSPosed 模块 ============
# java_init.list 里写的是 com.fuckad.xx.Main，被混淆就找不到入口
-keep class com.fuckad.xx.Main { *; }
-keep class com.fuckad.xx.Config { *; }
-keep class com.fuckad.xx.Logs { *; }
-keep class com.fuckad.xx.S { *; }
-keep class com.fuckad.xx.core.** { *; }
-keep class com.fuckad.xx.ui.Toaster { *; }

# libxposed API
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# hook 目标里用到的反射字段/方法名不能混淆
-keepclassmembers class * {
    @io.github.libxposed.api.annotations.* <methods>;
}
