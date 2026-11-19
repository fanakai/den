# recipes

Each file records which backend, accelerator and quantization work best for one model on one SoC family.
The router reads the choices in order and falls back to the next one when a choice fails to load.
A recipe with `soc` set applies only to that chip and takes priority over the family recipe.

```yaml
model: qwen3-1.7b
soc_family: snapdragon-8-elite
soc: SM8750                # optional
choices:
  - { backend: litert,   accel: npu, format: litertlm, quant: int4 }
  - { backend: llamacpp, accel: gpu, format: gguf,     quant: q4_k_m }
  - { backend: llamacpp, accel: cpu, format: gguf,     quant: q4_0 }
measured:                  # from `den bench`, for the first choice
  prefill_tps: 0
  decode_tps: 0
  peak_ram_mb: 0
  device: ""
  date: ""
```

To contribute, run the benchmark on your phone and open a pull request with the generated file.
