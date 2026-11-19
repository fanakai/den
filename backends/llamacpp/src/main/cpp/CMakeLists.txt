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
# dotprod and fp16 are available on every arm64 SoC we target (Snapdragon 7xx and newer).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# Adreno GPU through OpenCL. ggml skips GPUs it does not support at run time.
option(DEN_OPENCL "Build the OpenCL backend" ON)
if(DEN_OPENCL)
    include(${CMAKE_CURRENT_SOURCE_DIR}/opencl.cmake)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

set(DEN_SOURCES engine.cpp threads.cpp)
if(DEN_SMOKE)
    add_executable(den-smoke ${DEN_SOURCES} smoke.cpp)
    target_link_libraries(den-smoke PRIVATE llama)
else()
    add_library(den_llamacpp SHARED ${DEN_SOURCES} jni.cpp)
    target_link_libraries(den_llamacpp PRIVATE llama log)
endif()
