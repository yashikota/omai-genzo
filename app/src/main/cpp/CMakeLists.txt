cmake_minimum_required(VERSION 3.22.1)

project("androidlibrawtest")

# Recompiling LibRaw is the bulk of a native build. Use ccache when the machine has it.
find_program(CCACHE_PROGRAM ccache)
if (CCACHE_PROGRAM)
    set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
endif()

# Collect LibRaw source files
file(GLOB_RECURSE LIBRAW_SOURCES
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw/src/*.cpp"
)
# Exclude placeholder _ph.cpp files that cause duplicate symbols
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*_ph\\.cpp$")

# Native shared library containing LibRaw, FastGpuEngine, and JNI wrappers
add_library(
    native-lib
    SHARED
    native-lib.cpp
    FastGpuEngine.cpp
    ${LIBRAW_SOURCES}
)

# Header include paths (Include CMAKE_CURRENT_SOURCE_DIR for cross-platform libraw/libraw.h resolving)
target_include_directories(
    native-lib PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw"
    "${CMAKE_CURRENT_SOURCE_DIR}/libraw/libraw"
)

# Dead-strip unused decoders and keep symbols private; release additionally links with LTO.
target_compile_options(
    native-lib PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)
# CMake's INTERPROCEDURAL_OPTIMIZATION asks for the gold linker, which NDK r27 no longer ships.
if (CMAKE_BUILD_TYPE MATCHES "^(Release|RelWithDebInfo)$")
    target_compile_options(native-lib PRIVATE -flto=thin)
    target_link_options(native-lib PRIVATE -flto=thin)
endif()

# Compiler definitions
target_compile_definitions(
    native-lib PRIVATE
    LIBRAW_NOTHREADS
)

# Android specific libraries to link
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(gles3-lib GLESv3)
find_library(egl-lib EGL)
find_library(android-lib android)
find_library(dl-lib dl)

target_link_libraries(
    native-lib
    ${log-lib}
    ${jnigraphics-lib}
    ${gles3-lib}
    ${egl-lib}
    ${android-lib}
    ${dl-lib}
)

# Enforce 16 KB page size alignment for Android 15+ (API 35+) compatibility
target_link_options(
    native-lib PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"
)
