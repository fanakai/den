# HTTP API

The Den app can serve its models over HTTP, for code that speaks the OpenAI API. The endpoint is off by default; the
Den screen turns it on and sets the port (8742 by default).

## Rules

1) The server listens on `127.0.0.1` only, so only apps on the same phone reach it.
2) `/v1` follows the OpenAI API and nothing else is added there. What Den adds lives under `/den/v1`.
3) Each client sends a token made on the Den screen as its API key: `Authorization: Bearer <token>`. A missing or
   revoked token gets 401.
4) Errors have the OpenAI shape: `{"error": {"message": "...", "type": "..."}}`.

## `/v1`

### `GET /v1/models`

The models the Den app knows, in the OpenAI list shape. `hf:owner/repo` names are not listed, because they are looked
up on demand; a request may still name one.

### `POST /v1/chat/completions`

| Field | Use |
|---|---|
| `model` | A model name, an alias (`den.default`), or `hf:owner/repo[@revision][:QUANT]`. |
| `messages` | Roles `system`, `user`, `assistant`, `tool`; text content, or the text parts of a content array. |
| `max_completion_tokens`, `max_tokens` | Reply length; 512 when neither is given. |
| `temperature`, `top_p` | 0.7 and 0.95 when not given; `temperature` 0 is greedy. |
| `stop` | A string or a list of strings. |
| `stream` | Server-sent events, `chat.completion.chunk` objects, then `data: [DONE]`. |
| `stream_options.include_usage` | A last chunk with `usage` (prompt and completion tokens) and no choices. |
| `response_format` | `text`; `json_object` (the reply is a JSON object); `json_schema` with `json_schema.schema` (the reply follows the schema). |
| `tools`, `functions` | Passed to the backend; a backend without tool calls answers 400 with its reason. |
| `n` | 1 only; more gets 400. |

A model the phone does not have yet is downloaded first, after the user approves it on the Den screen when it comes
from a hub. While a model loads, a stream already has its header and sends a comment every few seconds, so clients do
not time out.

A leading `<think>…</think>` block (Qwen3 and similar) is moved out of the reply into `reasoning_content`, as
llama.cpp's server does.

### `POST /v1/embeddings`

400 for now: no backend in Den has embeddings yet.

## `/den/v1`

### `GET /den/v1/system-info`

The Den app version, the device (SoC, family, Android API level, RAM, free RAM, thermal state) and the backends with
their descriptors (id, name, version, author, licence, URL, support tier, modes, formats, slot policy, location and
features).

## Limits

| Limit | Answer |
|---|---|
| More than 8 open connections | 503 |
| A body over 4 MB | 413 |
| More than 64 header lines, or a line over 8 KB | 431 |
| A chunked body | 411: send `Content-Length` |
