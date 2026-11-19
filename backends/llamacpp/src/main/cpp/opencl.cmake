# OpenCL headers and a link-time libOpenCL.so (Khronos ICD loader).
# On the phone the vendor /vendor/lib64/libOpenCL.so is loaded instead, so the loader is never packaged.
include(FetchContent)

set(DEN_KHRONOS_TAG v2025.07.22)
FetchContent_Declare(opencl_headers GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
                     GIT_TAG ${DEN_KHRONOS_TAG} GIT_SHALLOW TRUE)
FetchContent_Declare(opencl_loader GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
                     GIT_TAG ${DEN_KHRONOS_TAG} GIT_SHALLOW TRUE)
FetchContent_GetProperties(opencl_headers)
if(NOT opencl_headers_POPULATED)
    FetchContent_Populate(opencl_headers)
    FetchContent_Populate(opencl_loader)
endif()

set(_loader_build ${CMAKE_BINARY_DIR}/opencl-loader)
if(NOT EXISTS ${_loader_build}/libOpenCL.so)
    execute_process(
        COMMAND ${CMAKE_COMMAND} -S ${opencl_loader_SOURCE_DIR} -B ${_loader_build} -G ${CMAKE_GENERATOR}
                -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
                -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI}
                -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DCMAKE_BUILD_TYPE=Release
                -DOPENCL_ICD_LOADER_HEADERS_DIR=${opencl_headers_SOURCE_DIR} -DBUILD_TESTING=OFF
        RESULT_VARIABLE _cfg)
    execute_process(COMMAND ${CMAKE_COMMAND} --build ${_loader_build} --target OpenCL RESULT_VARIABLE _bld)
    if(_cfg OR _bld)
        message(FATAL_ERROR "OpenCL ICD loader build failed")
    endif()
endif()

set(OpenCL_INCLUDE_DIR ${opencl_headers_SOURCE_DIR} CACHE PATH "" FORCE)
set(OpenCL_LIBRARY ${_loader_build}/libOpenCL.so CACHE FILEPATH "" FORCE)
