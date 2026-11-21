# Security

## Reporting a problem

Please report a security problem privately, through GitHub's private vulnerability reporting: the **Security** tab of
`fanakai/den`, then **Report a vulnerability**. Do not open a public issue for it.

Say what is affected (SDK, Den app, HTTP endpoint, a backend), how to reproduce it, and on which phone and Android
version. We answer within a week and agree on a date before anything is published.

## Supported versions

Den has no stable release yet. Fixes go to `main` and into the next release. Once 1.0 is out, the latest minor release
gets security fixes.

## Scope

In scope: the SDK, the Den app, its AIDL service and its HTTP endpoint, the llama.cpp backend as Den builds it, model
downloads and recipes. What Den protects against, and what it does not, is in [docs/threat-model.md](docs/threat-model.md).
What Den stores and sends is in [docs/privacy.md](docs/privacy.md).

Out of scope: rooted phones, adb access, and physical access to an unlocked phone. Bugs in llama.cpp itself go to
[llama.cpp](https://github.com/ggml-org/llama.cpp); tell us too when Den ships the affected version.
