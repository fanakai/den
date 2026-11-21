# Privacy

Den runs models on the phone. Prompts and replies stay on it.

## Message content

- Den does not log or store prompts or replies. They exist in memory while a request runs, and in the model's KV
  cache until the next request or until the model unloads.
- `Chat` keeps a conversation's messages in the memory of the app that uses it, not on disk.
- The logs of the Den app and the SDK hold models, speeds, sessions and errors, never text. The diagnostics file
  follows the same rule and leaves the phone only if the user shares it.

## Telemetry

None. Den sends no usage data, crash reports or identifiers anywhere.

## Network

Den connects only to the model sources, and only when a model is looked up or downloaded:

- Hugging Face: the model API, the file list, the first 256 KB of one GGUF file (its header), and the files the router
  picks;
- ModelScope, when an app adds that catalog.

Each request sends the user agent `fanak-den/0.1` and nothing that identifies the user or the phone. The HTTP
endpoint of the Den app listens on `127.0.0.1` and is not reachable from other devices.

## Stored on the phone

| What | Where |
|---|---|
| Model files and their manifest | the app's files, `den/models` |
| Catalog lookups (file lists, licences) | `den/catalog` |
| Paths that crashed the engine | `den/failures.json` |
| Recipes measured by the first-run benchmark | `den/local-recipes` |
| Den app: allowed and denied apps, approved repositories, quota, HTTP port | the Den app's preferences |
| Den app: HTTP tokens | SHA-256 only |
| `den bench` results, when the user runs it | the app's external files, `bench/` |

The Den app excludes all of it from backups and device transfer. In an app that embeds the SDK, that app's own backup
rules apply. Uninstalling an app removes what Den stored in it.
