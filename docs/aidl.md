# AIDL interface

Apps that use the SDK in connected mode talk to the Den app through `IDenService`, a bound service
(`ai.fanak.den.BIND`). The SDK checks the Den app's signing certificate before it binds.

## Rule

The Den app and the apps that use it update at different times. So:

1) a new method goes at the end of `IDenService` or `IOpenCallback`, never between existing ones, and no method
   changes its parameters;
2) each change raises `IDenService.version()`, and the SDK calls a newer method only when the Den app's version has it;
3) callbacks are `oneway`: a Den app that calls a method an older client does not have is ignored, so the Den app may
   call newer callbacks on any client.

`IEngine` and `IGenCallback` are internal: the SDK and its engine process always ship in the same APK, so they may
change in place.

## Versions

| Version | Added |
|---|---|
| 1 | `version`, `models`, `open`, `generate`, `cancel`, `close` |
| 2 | `cancelOpen`: stops an open in progress and the download it started |
| 3 | `IOpenCallback.onApproval`: the Den app's approval screen, opened by the client |
| 4 | `httpPort`: the port of the HTTP endpoint, or -1 |
| 5 | `openWith` (context length and the shortest accepted), `plan`; `IOpenCallback.onContext`, `onFailed` |
| 6 | aliases resolved in `openWith` and `plan`; `IOpenCallback.onModel` |
| 7 | `"hint"` in the options of `openWith` and `plan`: `private` (the phone only) or `cheap` (no cloud); it narrows what the user allows the app |
| 8 | `"hint": "fast"`: the path that decoded the model fastest on earlier requests first, within what the user allows; the SDK sends it only to version 8 or later |
