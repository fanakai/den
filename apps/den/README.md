# apps/den

The Den app. It hosts the engine for every app on the phone, so all of them share one download and one loaded copy of each model.

## For Android apps (AIDL)

Apps that use the Kotlin SDK (`sdk/kotlin`) find the Den app by themselves (`DenMode.AUTO`). The first time an app opens a model, Den asks the user whether to allow it: on an approval screen that the app opens through the SDK, and by notification. The answer is kept with the app's signing certificate, and the Den screen lists the apps with Allow, Deny and Forget, including those that closed the screen without an answer.

## OpenAI-compatible HTTP endpoint

Off by default. When it is turned on in the Den app, Den serves on `http://127.0.0.1:8000/v1`, for apps on the phone only:

- `GET /v1/models`;
- `POST /v1/chat/completions`, with `stream: true` for server-sent events. `model`, `messages`, `max_tokens` (or `max_completion_tokens`), `temperature`, `top_p` and `stop` are used; other fields are ignored.

Each client needs a token, which the user creates on the Den screen (one per client, revocable) and which the client sends as its API key:

```python
from openai import OpenAI

client = OpenAI(base_url="http://127.0.0.1:8000/v1", api_key="den-...")
stream = client.chat.completions.create(
    model="qwen3-0.6b",
    messages=[{"role": "user", "content": "Why do foxes live in dens?"}],
    stream=True,
)
for chunk in stream:
    print(chunk.choices[0].delta.content or "", end="")
```

A leading `<think>...</think>` block (Qwen3) is returned in `reasoning_content`, as llama.cpp's server does, and an empty one is dropped. When a client disconnects, its generation stops.

## Memory

Den keeps a model loaded while it is in use, and unloads it:
- when its last session closes;
- after 5 minutes without a request (HTTP included);
- when Android reports low memory;
- to make room for another model, least recently used first, keeping 512 MB free on top of what the router asks for.

A session whose model was unloaded keeps working; its next request loads the model again.

## Disk

The Models section of the Den screen lists the downloaded files with their size, the space they use and the free space on the phone. Delete removes every file of a model, after unloading it; it is refused while the model generates or downloads. Remove unfinished downloads deletes the `.part` files of downloads that are not running. Approvals and HTTP tokens are kept apart from the models, so deleting models does not touch them.

## Current limits

- Requests on one model run one at a time, in order (see #11).
- Models load into RAM one at a time; downloads run beside loads and requests.
- The endpoint does not come back by itself after a reboot or an update of the Den app; opening the Den app brings it back.
- To test from a PC: `adb forward tcp:8000 tcp:8000`. The adb forward adds a delay of a few seconds before a disconnected generation stops.
