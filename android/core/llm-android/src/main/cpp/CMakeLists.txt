# Builds libcuenote_llm: llama.cpp, for the card writer that runs on the
# phone (see com.cuenote.pipeline.cards.LlamaNative). Its own library, apart from
# libcuenote_ml: whisper.cpp and llama.cpp each bring their own copy of ggml,
# so each is linked statically into its library and none of it is exported.
cmake_minimum_required(VERSION 3.22)
project(cuenote_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID)
  set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    # The Pixel 8 Pro's cores (dot product, half floats, int8 matrix multiply).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
  endif()
endif()

# Pinned llama.cpp release. Pass -DLLAMA_SOURCE_DIR=<checkout> to build from a local copy.
set(LLAMA_TAG b6970)
if(LLAMA_SOURCE_DIR)
  add_subdirectory(${LLAMA_SOURCE_DIR} llama EXCLUDE_FROM_ALL)
else()
  include(FetchContent)
  FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW TRUE)
  FetchContent_GetProperties(llama)
  if(NOT llama_POPULATED)
    FetchContent_Populate(llama)
    add_subdirectory(${llama_SOURCE_DIR} ${llama_BINARY_DIR} EXCLUDE_FROM_ALL)
  endif()
endif()

add_library(cuenote_llm SHARED cuenote_llm_jni.cpp)
if(NOT ANDROID)
  find_package(JNI REQUIRED)
  target_include_directories(cuenote_llm PRIVATE ${JNI_INCLUDE_DIRS})
else()
  target_link_libraries(cuenote_llm PRIVATE log)
  target_link_options(cuenote_llm PRIVATE "-Wl,-z,max-page-size=16384")
endif()
# Only the JNI functions are visible; llama.cpp's ggml stays private to this library.
if(NOT APPLE)
  target_link_options(cuenote_llm PRIVATE "-Wl,--exclude-libs,ALL")
endif()
target_link_libraries(cuenote_llm PRIVATE llama common)
