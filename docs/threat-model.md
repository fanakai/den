# Threat model

Den runs models on the phone and shares them between apps through the Den app. This page lists what it trusts, what
it guards against, and what it does not cover yet.

## Assets

1) The user's prompts and replies.
2) The model files on the phone, and the phone's storage, memory and data plan.
3) The Den app's approvals (which apps may use it) and its HTTP tokens.

## Other apps on the phone

- **Binding to the Den app.** Any app can bind to its service. The first `open()` of an app asks the user, once per
  app (by package and signing certificate); until the user allows it, the app gets an error. The user can deny or
  forget an app on the Den screen.
- **An app posing as the Den app.** Any app can be installed under the name `ai.fanak.den`. The SDK checks the Den
  app's signing certificate (`DEN_CERTIFICATES`) before it connects, and runs the model in the app otherwise.
- **The HTTP endpoint.** It is off by default and listens on `127.0.0.1` only. Each client needs a token made on the
  Den screen; the Den app stores only its SHA-256, and the user can revoke it. Any app with network access can reach
  the port, so the token is the guard.
- **Sessions.** A session belongs to the app that opened it (by uid): another app cannot generate on it, cancel it or
  close it.
- **The diagnostics file.** It is served to the app the user shares it with, by a per-file grant; the provider is not
  exported. It holds no prompt or reply text.
- **Work profiles.** An app in a work profile cannot bind to the Den app of the personal profile (another Android
  user); it runs the model in the app instead.

## Files from model hubs

- **Integrity.** A download is pinned to a repository commit and checked against the SHA-256 the hub lists for it.
  A file that does not match is not used.
- **Unreviewed repositories.** Files from an `hf:` or `ms:` repository download only after the user approves that
  repository on the Den app's screen. Gated repositories are refused. Large files wait for Wi-Fi, and a quota (20 GB
  by default) bounds the space.
- **Malformed model files.** llama.cpp parses GGUF files, and a crafted file could exploit a bug in its parser. The
  engine runs in its own process, so a crash does not take the app or the Den service down. This process has the
  same user id as the app, so it is **not** a sandbox: code run there could read the app's files. A model from an
  unreviewed source is the main risk here; see the gaps below.

## The phone's resources

- The router checks free RAM before it loads a model, and free disk before it downloads one.
- A client that dies releases its sessions; a failing client does not stop the service for the others.

## Updates and data at rest

- The Den app excludes its data from backups and device transfer, so approvals and tokens do not move to another
  phone.
- Recipes ship inside the SDK and the Den app. Delivering them as signed data is planned (#86).

## Not covered yet

1) The engine is not an Android isolated process, so a parser exploit would run with the app's permissions.
2) The Den app trusts the hub's checksum. A compromised hub account could publish a bad file under a new commit; the
   approval of the repository is the user's check.
3) Prompts sent over the HTTP endpoint are in clear text on the loopback interface. Other apps cannot read loopback
   traffic without root.

Report problems as described in [SECURITY.md](../SECURITY.md).
