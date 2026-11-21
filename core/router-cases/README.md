# router cases

Each file is one routing case as data: a device profile, its backends, the catalog's files of one model, recipes, the paths that crashed before, the disk, and the request. `expect` holds the plan the router must make, in order, and what it leaves out.
`RouterCasesTest` (core, JVM) checks every file; a router written in another language should pass the same files.

- `plan`: `"backend accel quant"`, in order.
- `source`: where the order of the first candidate comes from (`RECIPE_SOC`, `RECIPE_FAMILY`, `RECIPE_LOCAL`, `DEFAULT`).
- `rejected`: `"quant REASON"` for a file, or `"quant REASON backend accel"` for a path of it, with the reasons of `RejectReason`.
- `disk`: free space in MB and the quants already on disk; `crashed`: `{ backend, accel, quant }` paths.

The numbers are test data, not measurements. Every reason a case can reach must have a case.
