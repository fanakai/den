# service

The OpenAI-compatible HTTP server of the Den app (`OpenAiServer`): `GET /v1/models` and `POST /v1/chat/completions`, with streaming. Plain Kotlin (JVM) with no Android dependency, so CI tests it with the official OpenAI Java client, and a Linux daemon (#74) can reuse it.

The host gives it the model names, a way to run a model (`use`), and the token check; the Den app stores the tokens (`apps/den`).

Limits: 127.0.0.1 only, 8 connections at a time, 64 header lines of at most 8 KB, 4 MB per body. `tools`, `functions`, `response_format` other than text and `n` > 1 are refused with 400 until #81.
