# recipes

Each file records which backend, accelerator and quantization work best on one SoC, by model size.
Speed depends mostly on the chip, the engine, the accelerator, the quantization and the size of the model, and much less on which model of that size it is, so a few measured sizes cover every model.
The router tries the choices in order and falls back to the next one when a choice cannot run or fails to load.
A recipe with `soc` set applies only to that chip and takes priority over a recipe for the whole `soc_family`.

Files are laid out as `recipes/<soc or soc_family>.yaml`:

```yaml
schema: 1
soc_family: snapdragon-8-elite
soc: SM8750                         # optional
rankings:
  - model: { arch: qwen3, params_b: 1.7 }   # the measured model: GGUF architecture, billions of parameters
    choices:
      - { backend: llamacpp, accel: gpu, format: gguf, quant: q4_0 }
      - { backend: llamacpp, accel: cpu, format: gguf, quant: q4_0 }
    measured:                       # for the first choice
      prefill_tps: 196.4
      decode_tps: 24.3
      device: Galaxy S25 (SM-S931B)
      date: 2026-11-19
      peak_ram_mb: 1450             # optional
      sha256: ...                   # optional: the measured file
      protocol: den bench, ...      # how it was measured
avoid:                              # optional: a choice that fails for one model although it suits its size
  - model: { arch: qwen3, params_b: 1.7 }
    choice: { backend: llamacpp, accel: gpu, format: gguf, quant: q4_k_m }
    reason: wrong output on this driver
```

How a model finds its ranking: the catalog gives each file its GGUF architecture and parameter count (from the hub's metadata, or pinned for built-in models), so `qwen3-1.7b` and `hf:unsloth/Qwen3-1.7B-GGUF` match the same ranking. The router takes the ranking of the nearest measured size, on a log scale, among the rankings of the same architecture when there are any. A model whose size is unknown gets the default order.

Values: `accel` is `cpu`, `gpu` or `npu`; `format` is `gguf`, `litertlm`, `pte` or `qnn`.
In CI, unknown fields are rejected, so a typo fails the build; `RecipeLintTest` also checks that every ranking has a `measured` block with speeds, the device, a past date and the protocol, that every backend exists, and that the file is named after its SoC or family. On the phone, recipes are read leniently: unknown fields are ignored, and a recipe of a newer `schema` or one that cannot be read is skipped.

## Current recipes

| Phone | Model size | First choice | Prefill (tok/s) | Decode (tok/s) |
|---|---|---|---|---|
| Galaxy S25 (SM8750) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | 213.8 | 40.9 |
| Galaxy S25 (SM8750) | Qwen3 1.7B | llamacpp, GPU, Q4_0 | 196.4 | 24.3 |
| Galaxy A52s (SM7325) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | 43.6 | 30.0 |
| Galaxy A52s (SM7325) | Qwen3 1.7B | llamacpp, CPU, Q4_0 | 33.3 | 12.8 |
| ZTE Blade A75 5G (Unisoc T760) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | 166.4 | 13.6 |

On the S25 the CPU decodes 0.6B faster than the GPU, while the GPU is 2.7x faster at prefill on 1.7B with the same decode.
On the A52s the OpenCL driver is too old for llama.cpp and the Vulkan path is slower than the CPU.
A52s prefill varies a lot between runs, likely due to core parking and heat.
On the T760 the Mali GPU is not used (the OpenCL path is for Adreno); Q4_0 and Q4_K_M decode at the same speed, and Q4_0 prefills about 10% faster.
With 3.4 GB of RAM it has about 1.2 GB free, so only the 0.6B size is measured.

## Contributing

Run the benchmark on your phone and open a pull request with the file. `den bench` (#14) will generate it.
