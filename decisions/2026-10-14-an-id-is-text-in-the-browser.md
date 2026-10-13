# An id is text in the browser

> Changes [Dates and decimals keep their types in the browser](2026-10-12-dates-and-decimals-keep-their-types-in-the-browser.md) and [JSON and malli on the wire](2026-10-12-json-and-malli-on-the-wire.md) for ids only.

**Decision:** in the browser an id is text. On the server it is a real id, and every id that arrives is checked strictly against its shape. Dates and decimals keep their real types on both sides, as decided.

**Why:** an id is a name tag: compared and passed along, never calculated with. JavaScript has no id type; a browser "id value" is a wrapper around the same text that doesn't check the text, and it is a new object on every answer, so anything that compares by identity (React's change detection) sees a change that isn't one. Ids also arrive in the browser as text from places no shape decodes: the address bar, a form control's value, the browser's own storage. An id that is a value on one side and text on the other is silently unequal: a button that never shows, an empty dropdown, an answer stored twice. Looking through a large app's browser code found no bug a real id type would have prevented there; the server's strict check is what catches a wrong id.

**The price:** code shared by both sides sees an id as a value on the server and as text in the browser.

**Instead of:** real id values in the browser, converted at the router and the form controls. Those two places don't cover ids read back from storage or kept from a click, and the identity problem can't be converted away.
