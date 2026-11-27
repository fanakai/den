# router cases

Each file is one routing case as data: a device profile, its backends, the catalog's files of one model, recipes, the paths that crashed before, the disk, the network, the routing policy and the request. `expect` holds the plan the router must make, in order, and what it leaves out.
`RouterCasesTest` (core, common code) checks every file on the JVM and on linuxX64; a router written in another language should pass the same files.

- `plan`: `"backend accel quant"`, in order; a server running a name no catalog knows has the quant `server`.
- `source`: where the order of the first candidate comes from (`RECIPE_SOC`, `RECIPE_FAMILY`, `RECIPE_LOCAL`, `DEFAULT`).
- `rejected`: `"quant REASON"` for a file, or `"quant REASON backend accel"` for a path of it, with the reasons of `RejectReason`.
- `disk`: free space in MB and the quants already on disk; `crashed`: `{ backend, accel, quant }` paths.
- `backends[].location`: `DEVICE` (the default), `LAN` or `CLOUD`; `backends[].serves`: model names a server has that no catalog knows.
- `network`: `online` (any network) and `localNetwork` (Wi-Fi, Ethernet or a VPN), both true by default.
- `policy`: `LOCAL_ONLY` (the default), `LOCAL_FIRST` or `REMOTE_FIRST`; `request.reach`: where this call may run the model, for an app the user limited.
- `request.hint`: `PRIVATE`, `CHEAP` or `FAST`, as an app asks; it narrows the reach (`RouteHint.limit`), as the SDK and the Den app do.
- `speeds`: `{ backend, accel, quant, decodeTps }` of earlier requests on this phone, which `FAST` orders by.

The numbers are test data, not measurements. Every reason a case can reach, every policy, every location and every hint must have a case.
