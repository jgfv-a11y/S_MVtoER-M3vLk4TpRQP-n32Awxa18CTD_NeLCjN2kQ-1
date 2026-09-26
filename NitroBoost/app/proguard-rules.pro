# NitroBoost release rules

# Shizuku (Java package is rikka.shizuku even though the Maven groupId is dev.rikka)
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# The Shizuku user service is instantiated reflectively by the Shizuku server:
# keep the class and both constructors (no-arg + Context).
-keep class com.nitroboost.app.service.NitroUserService {
    <init>(...);
}
-keepclassmembers class com.nitroboost.app.service.NitroUserService {
    *;
}

# AIDL stubs
-keep class com.nitroboost.app.shizuku.** { *; }

-keep class com.nitroboost.app.core.** { *; }
