# Dates and decimals keep their types in the browser

> **Changed in part** by [Browser values are typed, and small](2026-10-19-browser-values-are-typed-small.md).

**Decision:**

- **Dates** use the same types on the server and in the browser: java.time on the server, js-joda in the browser through a Clojure wrapper, so a day, a time and a moment stay what they are and date code is shared. They travel as ISO text and come back as their type by the shape.
- **Money** travels as whole cents, which are exact in JavaScript.
- **Other decimals** travel as text and become an exact decimal type in the browser (big.js), so 12.30 stays 12.30.

**Why:** JavaScript's own date is always a moment, so a day can shift to the day before in some time zones; and its numbers can't hold decimals exactly. With the same date types on both sides, a rule like "due 30 days from today in the shop's zone" gives the same answer in the server and in the browser.

**Instead of:**
- Temporal, JavaScript's new date system: the right types and built in, but Safari hasn't shipped it (Chrome since January 2026, Firefox since May 2025), so iPhones would need a polyfill. It can replace js-joda once Safari ships.
- JavaScript's own date for moments and plain text for days: nothing to add, but a day is just text, and the shift comes back when it's turned into a date.
