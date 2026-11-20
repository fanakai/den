# models

The curated index: one file per short name, which an app passes to `den.model(...)`. Each name points to a trusted source, resolved when the app asks for it; the files, their sizes and their SHA-256 come from the source, not from this folder.

```yaml
name: qwen3-4b                   # lower case; what apps ask for
source: hf:unsloth/Qwen3-4B-GGUF@22c9fc8a8c7700b76a1789366280a6a5a1ad1120  # hf:owner/repo@commit
licence: apache-2.0
description: Qwen3 4B, for phones with about 4 GB of free RAM.
aliases: [qwen3-4b-instruct]      # optional
```

Rules:
1) only sources that publish the SHA-256 of each file (Hugging Face does, through Git LFS);
2) no gated repositories: Den refuses them, since the user has to accept their terms first;
3) the licence as the source states it;
4) pinned to a commit, so every phone gets the same files under one name and a recipe measured on one phone describes the others.

Apps can also name a source directly, without an entry here: `den.model("hf:owner/repo")`.
