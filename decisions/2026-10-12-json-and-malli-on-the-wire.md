# JSON and malli on the wire

**Decision:** data travels between server and browser as plain JSON. The endpoint's shapes give the types back: malli decodes what the browser receives by the answer shape, and what the server receives by the input shape. Keys like `:post/title` travel as `"post/title"`; ids, dates, decimals and integers too big for JavaScript travel as text and come back as their real types.

**Why:** shapes are required on every endpoint, so the types are already written down once; the data needs nothing else. The browser parses JSON natively, the format is readable by any tool, and malli is actively maintained.

**The price:** a value is only as typed as its shape: a loose shape (`:map`, `:any`) gives plain strings back, so shapes stay strict.

**Still to decide:** what a date and a decimal become in the browser (JavaScript has no date-only type and no decimal).

**Instead of:** Transit. It carries ids and keywords by itself, but its browser side has had no release since 2022, dates and decimals still need handlers on both sides, and its ids turn into internals when handed to plain JavaScript.
