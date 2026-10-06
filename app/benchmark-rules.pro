# The benchmark build is minified like release, but the instrumented benchmark in androidTest calls
# into these classes directly, so their public surface must survive R8.
-keep class com.yashikota.omaigenzo.LibRawBridge { public *; }
-keep class com.yashikota.omaigenzo.LibRawBridge$Companion { public *; }
-keep class com.yashikota.omaigenzo.DecodePriority { *; }
-keep class com.yashikota.omaigenzo.PhotoItem { *; }
-keep class com.yashikota.omaigenzo.data.PerfLogger { public *; }
-keep class com.yashikota.omaigenzo.data.PreviewMetrics { public *; }
-keep class com.yashikota.omaigenzo.data.PrefetchCoordinator { public *; }
-keep class com.yashikota.omaigenzo.data.PrefetchRequest { *; }
-keep class com.yashikota.omaigenzo.data.ScannedFileEntry { *; }
-keep class com.yashikota.omaigenzo.data.ZeroCopyFolderScanner { public *; }

# androidx.test references annotations that are not on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**

# AndroidJUnitRunner runs inside the app process and needs these from the app's own classpath. R8
# strips them from the minified app because nothing in the app references them, which crashed the
# process on start (NoClassDefFoundError: androidx.tracing.Trace) and left `am instrument` silent.
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.concurrent.futures.** { *; }
# The test APK links against the app's copies of the Kotlin runtime, so they must all survive
# (found by diffing the test APK's references against the minified app's classes).
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-dontwarn com.google.common.util.concurrent.**
