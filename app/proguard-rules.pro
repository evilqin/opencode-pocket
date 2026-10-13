# 默认不开启 R8（release 也保持不混淆，方便在手机上直接看堆栈）。
#
# 想压包：./gradlew :app:assembleRelease -Ppocket.minify=true
# 打开后务必真机回归一遍 —— Shizuku 用户服务是「按类名从 APK 里加载」的，
# 下面几条 keep 规则不能删。
-keep class dev.pocket.opencode.service.DeviceShellUserService { *; }
-keep interface dev.pocket.opencode.IDeviceShellService { *; }
-keep class dev.pocket.opencode.IDeviceShellService$Stub { *; }
-keep class dev.pocket.opencode.IDeviceShellService$Stub$Proxy { *; }

# 数据模型只在 JVM 单元测试里用反射字段名，保留下来更稳
-keep class dev.pocket.opencode.data.** { *; }

# commons-compress / xz 会走 ServiceLoader，整包保住；它们加起来不到 1.5 MB
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
