cmake_minimum_required(VERSION 3.22.1)
project("vulkan_engine")

set(CMAKE_CXX_STANDARD 17)

if(ANDROID_ABI STREQUAL "arm64-v8a")

    find_package(Vulkan REQUIRED)

    # Find glslc from NDK
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(_GLSLC_EXT ".exe")
    else()
        set(_GLSLC_EXT "")
    endif()

    # glslc path: prefer Gradle-passed ANDROID_NDK, then fall back to toolchain
    if(DEFINED ANDROID_NDK AND NOT ANDROID_NDK STREQUAL "")
        set(GLSLC "${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG}/glslc${_GLSLC_EXT}")
    else()
        set(GLSLC "$ENV{ANDROID_NDK_ROOT}/shader-tools/${ANDROID_HOST_TAG}/glslc${_GLSLC_EXT}")
    endif()
    if(NOT EXISTS "${GLSLC}")
        message(FATAL_ERROR "glslc not found at ${GLSLC}. Shader compilation is required for Vulkan rendering; check NDK shader-tools.")
        unset(GLSLC)
    else()
        message(STATUS "Found glslc: ${GLSLC}")
    endif()

    set(SHADER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders)

    # SPIR-V 输出目录由 Gradle 通过 -DSHADER_ASSET_OUT 传入（位于 app/build/generated 下），
    # 不再写回源码树：避免生成物混入版本控制，也避免 gradlew clean 误删源码目录。
    if(NOT DEFINED SHADER_ASSET_OUT OR SHADER_ASSET_OUT STREQUAL "")
        message(FATAL_ERROR "SHADER_ASSET_OUT not set. Build via Gradle, which passes -DSHADER_ASSET_OUT=<dir>.")
    endif()
    set(SPIRV_DIR ${SHADER_ASSET_OUT}/shaders)
    file(MAKE_DIRECTORY ${SPIRV_DIR})

    set(SHADERS
        unified.comp
    )

    set(SPIRV_OUTPUTS "")

    if(GLSLC)
        foreach(SHADER ${SHADERS})
            set(SPIRV_OUTPUT ${SPIRV_DIR}/${SHADER}.spv)
            add_custom_command(
                OUTPUT ${SPIRV_OUTPUT}
                COMMAND ${GLSLC} --target-env=vulkan1.1 -O
                        ${SHADER_DIR}/${SHADER} -o ${SPIRV_OUTPUT}
                DEPENDS ${SHADER_DIR}/${SHADER}
                COMMENT "Compiling ${SHADER} -> SPIR-V"
            )
            list(APPEND SPIRV_OUTPUTS ${SPIRV_OUTPUT})
        endforeach()

        add_custom_target(shaders ALL DEPENDS ${SPIRV_OUTPUTS})
    endif()

    add_library(vulkan_engine SHARED
        vulkan_engine/VulkanEngine.cpp
        vulkan_engine/VulkanDevice.cpp
        vulkan_engine/VulkanPipeline.cpp
        vulkan_engine/VulkanImage.cpp
        vulkan_engine/VulkanBuffer.cpp
        vulkan_engine/VulkanCommandPool.cpp
        jni_bridge.cpp
    )

    if(GLSLC)
        add_dependencies(vulkan_engine shaders)
    endif()

    target_include_directories(vulkan_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/vulkan_engine
    )

    target_link_libraries(vulkan_engine
        vulkan
        android
        log
    )

else()
    # Stub library for non-arm64 ABIs
    add_library(vulkan_engine SHARED jni_bridge_stub.cpp)
    target_link_libraries(vulkan_engine android log)
endif()
