# Libraries pass values

> **Replaced** by [Libraries stand in layers](2026-10-22-libraries-stand-in-layers.md) (2026-10-22): a library may use one in a lower layer.

**Decision:** a `ground-*` library takes values and returns values. It never reads another library's files, state or functions. `groundwork` is the only place that knows two libraries, and all it does is hand values from one to the other. For example, `ground-build` and `ground-styles` each answer with a map of each module's files, and `groundwork` merges the two and gives the result to `ground-web`; no manifest file is shared.

**Why:** a library that only takes and gives values stands alone, is tested with plain data, and can't break because another library changed its insides.

**The price:** what used to be a direct call becomes a value `groundwork` passes on.

**Instead of:** libraries reading each other's files or calling each other's functions: fewer moving values, but each library tied to the insides of others.
