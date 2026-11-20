# recipes

Each file records which backend, accelerator and quantization work best for one model on one SoC.
The router tries the choices in order and falls back to the next one when a choice cannot run or fails to load.
A recipe with `soc` set applies only to that chip and takes priority over a recipe for the whole `soc_family`.

Files are laid out as `recipes/<model>/<soc or soc_family>.yaml`:

```yaml
model: qwen3-1.7b
soc_family: snapdragon-8-elite
soc: SM8750                       # optional
choices:
  - { backend: llamacpp, accel: gpu, format: gguf, quant: q4_0 }
  - { backend: llamacpp, accel: cpu, format: gguf, quant: q4_0 }
measured:                         # for the first choice
  prefill_tps: 196.4
  decode_tps: 24.3
  device: Galaxy S25 (SM-S931B)
  date: 2026-11-19
  peak_ram_mb: 1450               # optional
  protocol: den-smoke, llama.cpp b9590, 300-token prompt, ...   # optional
```

Values: `accel` is `cpu`, `gpu` or `npu`; `format` is `gguf`, `litertlm`, `pte` or `qnn`.
Unknown fields are rejected, so a typo fails the build instead of being ignored.

## Current recipes

| Model | Phone | First choice | Prefill (tok/s) | Decode (tok/s) |
|---|---|---|---|---|
| qwen3-0.6b | Galaxy S25 (SM8750) | llamacpp, CPU, Q4_0 | 213.8 | 40.9 |
| qwen3-1.7b | Galaxy S25 (SM8750) | llamacpp, GPU, Q4_0 | 196.4 | 24.3 |
| qwen3-0.6b | Galaxy A52s (SM7325) | llamacpp, CPU, Q4_0 | 43.6 | 30.0 |
| qwen3-1.7b | Galaxy A52s (SM7325) | llamacpp, CPU, Q4_0 | 33.3 | 12.8 |

On the S25 the CPU decodes 0.6B faster than the GPU, while the GPU is 2.7x faster at prefill on 1.7B with the same decode.
On the A52s the OpenCL driver is too old for llama.cpp and the Vulkan path is slower than the CPU.
A52s prefill varies a lot between runs, likely due to core parking and heat.

## Contributing

Run the benchmark on your phone and open a pull request with the file. `den bench` (#14) will generate it.
