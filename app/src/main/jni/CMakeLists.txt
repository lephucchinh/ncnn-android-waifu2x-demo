# Copyright (C) 2021  ArchieMeng <archiemeng@protonmail.com>
# Licensed under the Apache License, Version 2.0

cmake_minimum_required(VERSION 3.4.1)
project(Waifu2x)

# =========================
# Include dirs & defines
# =========================
include_directories(${CMAKE_CURRENT_BINARY_DIR})

# =========================
# NCNN setup
# =========================
if (USE_PREBUILT_NCNN)
    message(STATUS ">>> Using prebuilt NCNN for ABI=${ANDROID_ABI}")

    if (USE_SHARED_NCNN)
        add_library(ncnn SHARED IMPORTED)
        set_target_properties(ncnn PROPERTIES
                IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan-shared/${ANDROID_ABI}/lib/libncnn.so)
        include_directories(${CMAKE_SOURCE_DIR}/ncnn-android-vulkan-shared/${ANDROID_ABI}/include/ncnn)
    else ()
        # Static NCNN build
        set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
        include_directories(${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/include/ncnn)

        # Declare imported static libraries
        add_library(ncnn STATIC IMPORTED)
        add_library(glslang STATIC IMPORTED)
        add_library(OGLCompiler STATIC IMPORTED)
        add_library(OSDependent STATIC IMPORTED)
        add_library(SPIRV STATIC IMPORTED)
        add_library(GenericCodeGen STATIC IMPORTED)
        add_library(MachineIndependent STATIC IMPORTED)
        add_library(glslang-default-resource-limits STATIC IMPORTED)

        # Assign real .a paths
        set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libncnn.a)

        set_target_properties(glslang PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libglslang.a)

        set_target_properties(OGLCompiler PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libOGLCompiler.a)

        set_target_properties(OSDependent PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libOSDependent.a)

        set_target_properties(SPIRV PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libSPIRV.a)

        set_target_properties(GenericCodeGen PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libGenericCodeGen.a)

        set_target_properties(MachineIndependent PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libMachineIndependent.a)

        set_target_properties(glslang-default-resource-limits PROPERTIES IMPORTED_LOCATION
                ${CMAKE_SOURCE_DIR}/ncnn-android-vulkan/${ANDROID_ABI}/lib/libglslang-default-resource-limits.a)
    endif ()

else ()
    # =========================
    # Build ncnn from source
    # =========================
    if (NOT EXISTS "${CMAKE_SOURCE_DIR}/ncnn/CMakeLists.txt")
        message(FATAL_ERROR "NCNN submodule missing! Run: git submodule update --init --recursive")
    endif ()

    if (USE_SHARED_NCNN)
        option(NCNN_SHARED_LIB "" ON)
    endif ()

    add_subdirectory(ncnn)
endif ()

# =========================
# waifu2x shader setup
# =========================
macro(waifu2x_add_shader SHADER_SRC)
    get_filename_component(SHADER_SRC_NAME_WE ${SHADER_SRC} NAME_WE)
    set(SHADER_COMP_HEADER ${CMAKE_CURRENT_BINARY_DIR}/${SHADER_SRC_NAME_WE}.comp.hex.h)
    add_custom_command(
            OUTPUT ${SHADER_COMP_HEADER}
            COMMAND ${CMAKE_COMMAND}
            -DSHADER_SRC=${CMAKE_SOURCE_DIR}/waifu2x/${SHADER_SRC}
            -DSHADER_COMP_HEADER=${SHADER_COMP_HEADER}
            -P "${CMAKE_SOURCE_DIR}/waifu2x/generate_shader_comp_header.cmake"
            DEPENDS ${CMAKE_SOURCE_DIR}/waifu2x/${SHADER_SRC}
            COMMENT "Preprocessing shader source ${SHADER_SRC_NAME_WE}.comp"
            VERBATIM
    )
    set_source_files_properties(${SHADER_COMP_HEADER} PROPERTIES GENERATED TRUE)
    list(APPEND SHADER_SPV_HEX_FILES ${SHADER_COMP_HEADER})
endmacro()

# Compile all shaders
waifu2x_add_shader(waifu2x_preproc.comp)
waifu2x_add_shader(waifu2x_postproc.comp)
waifu2x_add_shader(waifu2x_preproc_tta.comp)
waifu2x_add_shader(waifu2x_postproc_tta.comp)

add_custom_target(generate-spirv DEPENDS ${SHADER_SPV_HEX_FILES})

# =========================
# Compiler optimizations
# =========================
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -fopenmp -fomit-frame-pointer -fstrict-aliasing -ffast-math -fvisibility=hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp -fomit-frame-pointer -fstrict-aliasing -ffast-math -fvisibility=hidden -fvisibility-inlines-hidden -fno-rtti -fno-exceptions")

if (DEFINED ANDROID_NDK_MAJOR AND ${ANDROID_NDK_MAJOR} GREATER 20)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -static-openmp")
endif ()

# =========================
# Waifu2x main library
# =========================
add_library(waifu2x SHARED
        waifu2x/waifu2x_jni.cpp
        waifu2x/waifu2x.cpp
)

add_dependencies(waifu2x generate-spirv)

# =========================
# Link all libs
# =========================
if (USE_PREBUILT_NCNN AND NOT USE_SHARED_NCNN)
    target_link_libraries(waifu2x
            ncnn
            glslang
            SPIRV
            OGLCompiler
            OSDependent
            MachineIndependent
            GenericCodeGen
            glslang-default-resource-limits
            android
            z
            log
            jnigraphics
            vulkan
    )
else ()
    target_link_libraries(waifu2x
            ncnn
            android
            z
            log
            jnigraphics
            vulkan
    )
endif ()
# Force 16 KB page alignment for Android 15 compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

message(STATUS ">>> NCNN build type: PREBUILT=${USE_PREBUILT_NCNN}, SHARED=${USE_SHARED_NCNN}")
