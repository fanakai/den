# Releasing

A tag `v<major>.<minor>.<patch>` on `main` runs `.github/workflows/release.yml`. It:

1. runs `./gradlew build` at that version;
2. builds the Den app signed with the release key, and checks that the certificate is the one the SDK trusts (`DEN_CERTIFICATES`);
3. creates a GitHub release with `den-<version>.apk` and its SHA-256;
4. publishes the libraries to Maven Central, signed.

The libraries:

| Artifact | Module | What it is |
|---|---|---|
| `ai.fanak:den` | `sdk/kotlin` | The Android SDK: device probe, catalog, downloads, connected mode. No native code. |
| `ai.fanak:den-llamacpp` | `backends/llamacpp` | llama.cpp for embedded mode, arm64-v8a. |
| `ai.fanak:den-litertlm` | `backends/litertlm` | LiteRT-LM for embedded mode: `.litertlm` files on the CPU and the GPU. |
| `ai.fanak:den-openai` | `backends/openai` | OpenAI-compatible servers on the LAN or in the cloud. |
| `ai.fanak:den-core` | `core` | Router, backend interface, recipes (Kotlin Multiplatform: JVM and linuxX64). |

`den.version` in `gradle.properties` stays a `-SNAPSHOT` on `main`; the tag gives the release its version. The Den app's version code comes from it: 1.2.3 is 10203.

## Making a release

```sh
git checkout main && git pull
git tag v0.1.0
git push origin v0.1.0
```

A failed run publishes nothing to Maven Central if it fails before the last step. A failure in the last step leaves a GitHub release without libraries: fix the cause, delete the release and the tag, and tag again.

## One-time setup

Repository secrets (Settings, Secrets and variables, Actions):

| Secret | What it holds |
|---|---|
| `DEN_RELEASE_KEYSTORE` | The release keystore, in base64 (`base64 -w0 den-release.jks`). |
| `DEN_RELEASE_PASSWORD` | Its password, also the key's. |
| `DEN_RELEASE_KEY_ALIAS` | The key's alias; `den` when not set. |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A user token of the Central Portal (central.sonatype.com, Account, Generate User Token). |
| `SIGNING_KEY` | An OpenPGP private key, ASCII-armored (`gpg --export-secret-keys --armor <id>`). |
| `SIGNING_KEY_PASSWORD` | Its passphrase. |

The namespace `ai.fanak` must be verified on the Central Portal, with the DNS TXT record it gives for `fanak.ai`. The public half of the signing key must be on a key server Central reads, such as `keys.openpgp.org`.

## Checking a build before a release

```sh
./gradlew publishToMavenLocal -Pden.version=0.1.0
```

puts the five libraries in `~/.m2/repository/ai/fanak/`, unsigned. A separate project with `mavenLocal()` in its repositories can then depend on `ai.fanak:den:0.1.0`.
