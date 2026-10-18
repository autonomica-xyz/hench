# Keep release builds functional until a dedicated shrinker config is added.
-keep class net.nostr.hench.** { *; }

# JNA resolves native dispatch fields, structures, and library methods by name.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
# JNA's desktop-only AWT helper is not used by Android bindings.
-dontwarn java.awt.**

# JNI resolves Zstd class, method, and field names.
-keep class com.github.luben.zstd.** { *; }
