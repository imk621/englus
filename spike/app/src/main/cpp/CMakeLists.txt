cmake_minimum_required(VERSION 3.22.1)
project(englus_whisper CXX C)

set(CMAKE_CXX_STANDARD 17)

# spike/app/src/main/cpp -> spike/third_party/whisper.cpp
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

if (NOT EXISTS ${WHISPER_LIB_DIR}/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_LIB_DIR}.\n"
        "Run: git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git spike/third_party/whisper.cpp")
endif ()

# Stamp the upstream version into the binary so the benchmark report can name it.
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)"
       VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if (CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else ()
    set(WHISPER_VERSION "unknown")
endif ()
message(STATUS "englus: building against whisper.cpp ${WHISPER_VERSION}")

find_library(LOG_LIB log)
include(FetchContent)

# ggml is consumed as a source subproject; it brings its own CPU backend targets.
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

# Snapdragon 8 Elite is armv8.2+ and has native fp16 arithmetic. Compiling for it
# (rather than baseline armv8-a) is worth a large fraction of the inference speed,
# so the spike must measure with it on.
#
# x86_64 exists only so the pipeline can be exercised on an emulator; -march is
# arm-specific and would fail to compile there. Emulator timings are meaningless
# for this spike anyway -- they measure the host PC, not the phone.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(ENGLUS_ARCH_FLAGS -march=armv8.2-a+fp16)
else ()
    set(ENGLUS_ARCH_FLAGS "")
endif ()

if (ENGLUS_ARCH_FLAGS)
    foreach (ggml_target ggml ggml-cpu ggml-base)
        if (TARGET ${ggml_target})
            target_compile_options(${ggml_target} PRIVATE ${ENGLUS_ARCH_FLAGS})
        endif ()
    endforeach ()
endif ()

add_library(englus_whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp
)

target_compile_definitions(englus_whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(englus_whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
if (ENGLUS_ARCH_FLAGS)
    target_compile_options(englus_whisper PRIVATE ${ENGLUS_ARCH_FLAGS})
endif ()

target_include_directories(englus_whisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(englus_whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_options(englus_whisper PRIVATE -Wl,--gc-sections)
endif ()

target_link_libraries(englus_whisper ${LOG_LIB} android ggml)
