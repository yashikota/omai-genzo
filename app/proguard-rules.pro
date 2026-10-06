# JNI entry points are resolved by name from native-lib.
-keep class com.yashikota.omaigenzo.LibRawBridge { *; }
-keep class com.yashikota.omaigenzo.FastGpuBridge { *; }

# Kept readable so OmaiPerf stack traces from release builds stay useful.
-keepattributes SourceFile,LineNumberTable
