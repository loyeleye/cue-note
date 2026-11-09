# Builds libcuenote_ml: whisper.cpp (speech to text) and its Silero voice
# detector, wrapped for the JVM. The same file builds for the phone (through
# :core:ml-android and the NDK) and for a laptop (through host-build.sh), so
# JVM tests run exactly the native code the phone runs.
cmake_minimum_required(VERSION 3.22)
project(cuenote_ml CXX C)

set(CMAKE_CXX_STANDARD 17)
# whisper.cpp is linked statically into a shared library, so it must be
# position-independent.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID)
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    # The Pixel 8 Pro's cores have dot-product and half-float instructions,
    # which make whisper several times faster than plain arm64.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
  endif()
endif()

# Pinned whisper.cpp release. Pass -DWHISPER_SOURCE_DIR=<checkout> to build
# from a local copy instead of fetching it.
set(WHISPER_TAG v1.8.7)
if(WHISPER_SOURCE_DIR)
  add_subdirectory(${WHISPER_SOURCE_DIR} whisper EXCLUDE_FROM_ALL)
else()
  include(FetchContent)
  FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG ${WHISPER_TAG}
    GIT_SHALLOW TRUE)
  FetchContent_GetProperties(whisper)
  if(NOT whisper_POPULATED)
    FetchContent_Populate(whisper)
    add_subdirectory(${whisper_SOURCE_DIR} ${whisper_BINARY_DIR} EXCLUDE_FROM_ALL)
  endif()
endif()

add_library(cuenote_ml SHARED cuenote_ml_jni.cpp)
if(NOT ANDROID)
  find_package(JNI REQUIRED)
  target_include_directories(cuenote_ml PRIVATE ${JNI_INCLUDE_DIRS})
else()
  target_link_libraries(cuenote_ml PRIVATE log)
  # 16 KB page alignment, required for new apps on Android 15+.
  target_link_options(cuenote_ml PRIVATE "-Wl,-z,max-page-size=16384")
endif()
target_link_libraries(cuenote_ml PRIVATE whisper)
