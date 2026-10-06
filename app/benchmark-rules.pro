# The benchmark build is minified like release, but the instrumented benchmark in androidTest calls
# into these classes directly, so their public surface must survive R8.
-keep class com.yashikota.omaigenzo.LibRawBridge { public *; }
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
