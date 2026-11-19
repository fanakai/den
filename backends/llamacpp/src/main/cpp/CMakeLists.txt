cmake_minimum_required(VERSION 3.22)
project(den_llamacpp C CXX)

set(CMAKE_CXX_STANDARD 17)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(opt LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER
            LLAMA_BUILD_APP LLAMA_BUILD_UI LLAMA_OPENSSL GGML_NATIVE GGML_OPENMP)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
# dotprod and fp16 are available on every arm64 SoC we target (Snapdragon 7xx and newer).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

if(DEN_SMOKE)
    add_executable(den-smoke engine.cpp threads.cpp smoke.cpp)
    target_link_libraries(den-smoke PRIVATE llama)
else()
    add_library(den_llamacpp SHARED engine.cpp threads.cpp jni.cpp)
    target_link_libraries(den_llamacpp PRIVATE llama log)
endif()
