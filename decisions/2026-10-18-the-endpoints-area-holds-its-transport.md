# The endpoints area holds its transport

> Adds to [One folder per area, one job per file](2026-10-18-one-folder-per-area.md).

**Decision:** endpoints and the way they are asked are one area: `groundwork.endpoints` is its door, and its folder holds the transport (`endpoints.http`, `endpoints.websocket`, `endpoints.bells`, `endpoints.wire`, `endpoints.route`, and the browser's `endpoints.store`). The `parquery` folder goes. The address stays `/parquery`.

**Why:** the root holds one door per area. `parquery` was a folder with no door, while its door sat beside it under another name.

**The price:** apps' requires of the transport and the store change once. The word "parquery" leaves the code's names; the settings key follows in the naming pass.

**Instead of:** keeping `parquery/` as a folder without a door.
