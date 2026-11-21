cmake_minimum_required(VERSION 3.22)
project(den_llamacpp C CXX)

set(CMAKE_CXX_STANDARD 17)

# Backends are separate libraries loaded at run time, so a phone whose OpenCL driver is old or missing still gets the CPU.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
foreach(opt LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER
            LLAMA_BUILD_APP LLAMA_BUILD_UI LLAMA_OPENSSL GGML_NATIVE GGML_OPENMP)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
if(ANDROID)
    # One CPU library per instruction set (armv8.0 up to armv9.2 with SME); the engine loads the best one this CPU runs.
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
    set(DEN_LOG log)
else()
    # Desktop build for the engine tests in CI: one library with the CPU backend inside, loaded by the JVM.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(DEN_OPENCL OFF CACHE BOOL "" FORCE)
    find_package(JNI REQUIRED)
    set(DEN_LOG "")
endif()

# Adreno GPU through OpenCL. ggml skips GPUs it does not support at run time.
option(DEN_OPENCL "Build the OpenCL backend" ON)
if(DEN_OPENCL)
    include(${CMAKE_CURRENT_SOURCE_DIR}/opencl.cmake)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

if(DEN_OPENCL)
    # OpenCL 3.0 functions as weak references, so OpenCL 2.x drivers still load the backend (see opencl_weak.h).
    target_compile_options(ggml-opencl PRIVATE -include ${CMAKE_CURRENT_SOURCE_DIR}/opencl_weak.h)
endif()

# JSON schema to grammar, from llama.cpp's common library, with a shim for the three helpers it needs from common.cpp.
set(DEN_LLAMA ${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp)
set(DEN_SOURCES engine.cpp threads.cpp common_shim.cpp ${DEN_LLAMA}/common/json-schema-to-grammar.cpp)
if(DEN_SMOKE)
    add_executable(den-smoke ${DEN_SOURCES} smoke.cpp)
    target_link_libraries(den-smoke PRIVATE llama ${DEN_LOG})
    target_include_directories(den-smoke PRIVATE ${DEN_LLAMA}/common ${DEN_LLAMA}/vendor)
else()
    add_library(den_llamacpp SHARED ${DEN_SOURCES} jni.cpp)
    target_link_libraries(den_llamacpp PRIVATE llama ${DEN_LOG})
    target_include_directories(den_llamacpp PRIVATE ${DEN_LLAMA}/common ${DEN_LLAMA}/vendor)
    if(NOT ANDROID)
        target_include_directories(den_llamacpp PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
endif()
