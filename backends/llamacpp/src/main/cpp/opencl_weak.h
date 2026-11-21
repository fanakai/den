// Force-included into ggml-opencl. Android binds every symbol when a library loads, so one function missing from the
// driver blocks the whole backend: the A52s driver (Adreno 642L) has no clCreateBufferWithProperties (OpenCL 3.0).
// A weak reference loads without it. ggml calls it only for Adreno large buffers, which are off unless
// GGML_OPENCL_ADRENO_USE_LARGE_BUFFER is set.
#pragma once

#define CL_TARGET_OPENCL_VERSION GGML_OPENCL_TARGET_VERSION
#include <CL/cl.h>

#ifdef __cplusplus
extern "C" {
#endif

__attribute__((weak)) extern CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(
    cl_context context, const cl_mem_properties *properties, cl_mem_flags flags, size_t size, void *host_ptr,
    cl_int *errcode_ret);

#ifdef __cplusplus
}
#endif
