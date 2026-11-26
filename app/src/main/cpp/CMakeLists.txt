cmake_minimum_required(VERSION 3.22.1)
project("helix_moonlight" LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# MbedTLS static configuration (must be set before FetchContent_MakeAvailable)
set(ENABLE_TESTING OFF CACHE BOOL "Disable MbedTLS tests" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "Disable MbedTLS sample programs" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "Do not emit shared MbedTLS .so libraries" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "Build static MbedTLS libraries" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "Do not treat MbedTLS warnings as errors" FORCE)
set(GEN_FILES OFF CACHE BOOL "Use pre-generated MbedTLS source files" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "Disable MbedTLS install targets" FORCE)

FetchContent_Declare(
    mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG v3.6.2
    GIT_SHALLOW TRUE
    GIT_SUBMODULES ""
    PATCH_COMMAND ${CMAKE_COMMAND} -E touch framework/CMakeLists.txt
)

# moonlight-common-c static configuration (must be set before FetchContent_MakeAvailable)
set(USE_MBEDTLS ON CACHE BOOL "Use MbedTLS PSA Crypto in moonlight-common-c" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries only" FORCE)
set(CODE_ANALYSIS OFF CACHE BOOL "Disable static analyzer flags" FORCE)

FetchContent_Declare(
    moonlight-common-c
    GIT_REPOSITORY https://github.com/moonlight-stream/moonlight-common-c.git
    GIT_TAG 94c65fd364d17b223f00a8763c9a5d94a66cb8dd
    GIT_SUBMODULES_RECURSE TRUE
)

FetchContent_MakeAvailable(mbedtls moonlight-common-c)

if(TARGET moonlight-common-c)
    target_compile_options(moonlight-common-c PRIVATE -Wno-error)
    if(DEFINED mbedtls_SOURCE_DIR)
        target_include_directories(moonlight-common-c SYSTEM PRIVATE "${mbedtls_SOURCE_DIR}/include")
    endif()
endif()

if(TARGET enet)
    target_compile_options(enet PRIVATE -Wno-error)
endif()

add_library(
    helix_moonlight
    SHARED
    helix_moonlight_jni.cpp
    helix_video.cpp
    helix_audio.cpp
)

target_compile_features(helix_moonlight PRIVATE cxx_std_17)

if(DEFINED moonlight-common-c_SOURCE_DIR)
    target_include_directories(
        helix_moonlight
        PRIVATE
        "${moonlight-common-c_SOURCE_DIR}/src"
    )
endif()

if(DEFINED mbedtls_SOURCE_DIR)
    target_include_directories(
        helix_moonlight
        PRIVATE
        "${mbedtls_SOURCE_DIR}/include"
    )
endif()

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

find_library(
    mediandk-lib
    mediandk
)

find_library(
    aaudio-lib
    aaudio
)

target_link_libraries(
    helix_moonlight
    PRIVATE
    moonlight-common-c
    mbedcrypto
    ${log-lib}
    ${android-lib}
    ${mediandk-lib}
    ${aaudio-lib}
)
