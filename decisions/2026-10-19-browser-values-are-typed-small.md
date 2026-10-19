# Browser values are typed, and small

> Changes [Dates and decimals keep their types in the browser](2026-10-12-dates-and-decimals-keep-their-types-in-the-browser.md) in part: how, and that decimals are an app's choice.

**Decision:** the browser still gets real values, decoded once at the door from the answer shapes: a moment in time as an instant, a calendar day as a date without time or zone, an id as text. They are built on the date types that ship with ClojureScript (Closure's `goog.date`), decoded by Groundwork's own small decoder, not by a date library or malli's transformers. Exact decimals are an app's choice in its settings; an app that keeps money in whole cents leaves them off and ships no decimal library. Showing a time in a time zone uses the browser's own formatting.

**Why:** parsing wire text in every screen is where wrong dates come from (a due date shown a day early). Turning wire values into real values is plumbing, Groundwork's side. The first implementation shipped a complete date library and a decimal library to every visitor's first download, about 44 KB, for values the screens only showed.

**The price:** Closure's date types are plainer than a full date library: no calendar arithmetic beyond days and months, time zones only through the browser's formatting.

**Instead of:** text on the wire left as text (every screen parses); a full date library in the first download.
