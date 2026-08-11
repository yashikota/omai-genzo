cmake_minimum_required(VERSION 3.22.1)

project("androidlibrawtest")

# Collect LibRaw source files
file(GLOB_RECURSE LIBRAW_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw/src/*.cpp"
)
# Exclude placeholder _ph.cpp files that cause duplicate symbols
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

# Native shared library containing LibRaw and JNI wrappers
add_library(
    native-lib
    SHARED
    native-lib.cpp
    ${LIBRAW_SOURCES}
)

# Header include paths (Include CMAKE_CURRENT_SOURCE_DIR for cross-platform libraw/libraw.h resolving)
target_include_directories(
    native-lib PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw"
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw/libraw"
)

# Compiler definitions
target_compile_definitions(
    native-lib PRIVATE
    LIBRAW_NOTHREADS
)

# Android specific libraries to link (log for logging, jnigraphics for direct Bitmap pixel access if needed)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    native-lib
    ${log-lib}
    ${jnigraphics-lib}
)

# Enforce 16 KB page size alignment for Android 15+ (API 35+) compatibility
target_link_options(
    native-lib PRIVATE
    "-Wl,-z,max-page-size=16384"
)
