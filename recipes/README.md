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
    speeds:                         # optional: choices measured at two prompt lengths, in the run of `measured`
      - choice: { backend: llamacpp, accel: cpu, format: gguf, quant: q4_0 }
        prompt_tokens: ...          # the short prompt, then prefill and decode on it
        prefill_tps: ...
        decode_tps: ...
        long_prompt_tokens: ...     # the long prompt, then decode with it in the context
        prefill_long_tps: ...
        decode_long_tps: ...
avoid:                              # optional: a choice that fails for one model although it suits its size
  - model: { arch: qwen3, params_b: 1.7 }
    choice: { backend: llamacpp, accel: gpu, format: gguf, quant: q4_k_m }
    reason: wrong output on this driver
```

How a model finds its ranking: the catalog gives each file its GGUF architecture and parameter count (from the hub's metadata, or pinned for built-in models), so `qwen3-1.7b` and `hf:unsloth/Qwen3-1.7B-GGUF` match the same ranking. The router takes the ranking of the nearest measured size, on a log scale, among the rankings of the same architecture when there are any. A model whose size is unknown gets the default order.

Values: `accel` is `cpu`, `gpu` or `npu`; `format` is `gguf`, `litertlm`, `pte` or `qnn`.
An app that says how long its prompts and answers are (`expected` in `model()`) gets the choices with `speeds` in the order of the time they take for such a request, in the places they had; the others keep theirs.
With a long prompt the NPU can answer sooner than the CPU although the CPU decodes faster on a short one (#194).

A choice may add `kv_cache: q8_0` for a q8_0 KV cache with flash attention, half the RAM of the default `f16`; only where it was measured on that phone, speed and quality (#170). Backends with one cache type ignore it.
In CI, unknown fields are rejected, so a typo fails the build; `RecipeLintTest` also checks that every ranking has a `measured` block with speeds, the device, a past date and the protocol, that every backend exists, and that the file is named after its SoC or family. On the phone, recipes are read leniently: unknown fields are ignored, and a recipe of a newer `schema` or one that cannot be read is skipped.

## Current recipes

<!-- Made from the recipe files by RecipeTableTest; do not edit. -->
| Phone | Model size | First choice | Then | Prefill (tok/s) | Decode (tok/s) | Measured |
|---|---|---|---|---|---|---|
| Galaxy A15 (MT6789V) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 65.9 | 14.6 | 2026-11-21 |
| Galaxy Z Flip7 (S5E9955) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 262.0 | 18.3 | 2026-11-21 |
| Galaxy Z Flip7 (S5E9955) | Qwen3 1.7B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 107.0 | 13.7 | 2026-11-21 |
| Galaxy A52s 5G (SM7325) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 43.6 | 30.0 | 2026-11-19 |
| Galaxy A52s 5G (SM7325) | Qwen3 1.7B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 33.3 | 12.8 | 2026-11-19 |
| Galaxy S25 (SM8750) | Qwen3 0.6B | litertlm, GPU, INT4 | llamacpp, CPU, Q4_0; llamacpp, NPU, Q4_0; llamacpp, GPU, Q4_0; litertlm, CPU, INT4; llamacpp, CPU, Q4_K_M | 541.5 | 80.3 | 2026-11-27 |
| Galaxy S25 (SM8750) | Qwen3 1.7B | litertlm, GPU, INT4 | llamacpp, NPU, Q4_0; llamacpp, GPU, Q4_0; llamacpp, CPU, Q4_0; litertlm, CPU, INT4; llamacpp, CPU, Q4_K_M | 297.5 | 32.8 | 2026-11-28 |
| ZTE Blade A75 5G (T760) | Qwen3 0.6B | llamacpp, CPU, Q4_0 | llamacpp, CPU, Q4_K_M | 166.4 | 13.6 | 2026-11-20 |
<!-- End of the generated table. -->

On the S25 the CPU decodes 0.6B faster than the GPU, while the GPU is 2.7x faster at prefill on 1.7B with the same decode.
On the A52s the OpenCL driver is too old for llama.cpp and the Vulkan path is slower than the CPU.
A52s prefill varies a lot between runs, likely due to core parking and heat.
On the T760 the Mali GPU is not used (the OpenCL path is for Adreno); Q4_0 and Q4_K_M decode at the same speed, and Q4_0 prefills about 10% faster.
With 3.4 GB of RAM it has about 1.2 GB free, so only the 0.6B size is measured.
The Exynos 2500 was measured on a Samsung Remote Test Lab phone, on CPU only: its Xclipse GPU is not used.
Its 0.6B decode is lower than on the A52s; the automatic thread choice on its 10-core CPU is not yet checked.
The Helio G99 (Galaxy A15, also from the lab) has 3.6 GB of RAM, so only 0.6B is measured; Q4_0 is clearly ahead of Q4_K_M there (prefill 65.9 against 41.3, decode 14.6 against 11.8).

## Contributing

Run the benchmark on your phone and open a pull request with the file. `den bench` (#14) will generate it.
Then run `./gradlew :core:jvmTest -Pden.writeTable` to update the table above, which CI checks against the files.
