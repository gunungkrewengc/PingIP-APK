# Keep PingIP components & AIDL / Shizuku UserService
-keep class com.pingip.app.ShellUserService { *; }
-keep class com.pingip.app.IShellService** { *; }
-keep class com.pingip.app.RootShell { *; }
-keep class com.pingip.app.PingService { *; }
-keep class com.pingip.app.PingTileService { *; }
-keep class com.pingip.app.MainActivity { *; }

# Keep Shizuku API & Provider reflection/binder classes
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Keep AIDL & Binder stubs
-keep class * implements android.os.IInterface { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
