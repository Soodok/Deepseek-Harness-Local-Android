Thanks for an exceptionally detailed report — both defects are real, and we reproduced them against our shipped runtime. Your line references match our bundled `@deepseek-ai/dsh-attachment-local@0.2.0-rc.2` almost exactly (`syncDirectory` L357/363, `ensureDurableDirectory` L380/391, `ensureDurableHome` L402/405, `publishStagedObject` L536/554).

### Confirmed: this is not root-only

We traced the path expansion. `$DSH_HOME` is `/data/data/app.dsh.mobile/files/dsh-home`, so the ancestor walk visits `files → app.dsh.mobile → /data/data → /data → /` and hits the read-only erofs mount on `/` for **every** install, in **every** mode — root just made it easier to observe. Any build carrying the 0.2.0 runtime is affected.

### One correction

`dsh-session-persistence-jsonl`'s `syncDirPosix()` does **not** walk to the filesystem root — it only syncs single levels inside `$DSH_HOME` (L3127/3132/3137/3148). Session persistence was never affected. The fatal walk is unique to `ensureDurableHome`.

### A third site your report didn't cover

`dsh-session-persistence-jsonl/lib/worker.cjs` carries its own CJS copy of `defaultFileSystem` with `link: node_fs_promises.link` — a real hard link that the ESM-side patch cannot reach. It is currently unreachable (the worker entry only runs `verify()`; `prepare`/`publishCurrentExclusive` are not wired), but it is a landmine for the moment upstream wires migration preparation into the worker. We patched it too.

### How we fixed it

Fix 1 is yours as written: `syncDirectory` tolerates `EINVAL` only; every other errno stays fatal.

Fix 2 we implemented slightly differently, for two reasons:

- `rename` is what broke cleanup in the first place, so we removed it.
- A bare `copyFile` fallback silently **overwrites** an existing target, which destroys the `EEXIST` branch upstream relies on to digest-verify an already-published object.

Instead we import `link as fsLink` and install a module-level shim: try `fsLink`; on `EACCES` / `EPERM` / `ENOTSUP` / `EXDEV` / `EMLINK` / `ENOSYS` fall back to `copyFile(..., COPYFILE_EXCL)`. The source is preserved — so `unlink(staged.path)` cleans up correctly, and `publishImmutableAlias` no longer consumes the canonical content-addressed object — and an existing target still raises `EEXIST`.

One change covers both call sites, since `publishStagedObject` and `publishImmutableAlias` call the same `link`.

### Verification status

We extracted the patches from our build script and applied them to a pristine copy of the package, then syntax-checked them and smoke-tested each branch (EACCES → copy, EEXIST → rethrow, EIO → rethrow, EINVAL tolerated). What we have **not** done is an end-to-end run on device — we do not have your hardware.

Your on-device verification is exactly the confirmation we needed. Once the next release lands, could you re-run your `saveImageFile` → `readImageFile` digest check and confirm it is green **without** any local patching? If anything still surfaces, please reopen here.
