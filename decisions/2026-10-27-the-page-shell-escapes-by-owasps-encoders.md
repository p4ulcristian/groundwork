# The page shell escapes by OWASP's encoders; its settings are a JSON string

> The user's decision, 2026-10-27 (keep groundwork minimal; take a library only where the problem is one people get subtly wrong, escaping among them, and its cost is small where it lands; the composer's HTML escaping by OWASP Java Encoder). Since v7.0.2. Changes in part [The server puts the browser's settings in the page](2026-10-22-the-server-puts-the-browsers-settings-in-the-page.md): what the settings' script element holds and how the browser reads it.

**Decision:** the page shell (`groundwork.server.main.page`) escapes by OWASP Java Encoder (`org.owasp.encoder/encoder` 1.6.0), the server's only:

- **text and quoted attributes** (the language, the viewport, the title, the description, the alternate links, the stylesheets' and scripts' addresses, the nonce) by `Encode/forHtml`: `&` `<` `>` `"` `'` each as its entity. A character HTML can't hold (a control character, a lone surrogate) becomes a space.
- **the browser's settings** by `Encode/forJson`: the settings' JSON text, as shaped-json writes it, becomes one JSON string, its `"` and `\` escaped and `<` `>` `&` written `<` `>` `&`, so nothing in it ends the `<script type="application/json" id="groundwork-settings">` element or opens a comment. The browser (`read-settings-from-page`) reads the element's text with `JSON.parse` twice: the string, then the settings in it.

```html
<script type="application/json" id="groundwork-settings">"{\"version\":\"2026-10-27.1\",\"pages\":{…}}"</script>
```

**Why:** the hand-written `escape-html` missed `'`: an attribute in single quotes, which the shell doesn't write today, would have been open to its text. The encoders are OWASP's, written for exactly these contexts and read by many; 43 KB, no dependencies of their own, on the server only. OWASP's encoder for JSON escapes text for a JSON string: put whole into the element, it would escape the settings' own quotes, so the settings are the JSON string's text instead of being re-escaped by hand.

**The price:** the page's settings are a byte bigger for each `"` in them (written `\"`) and parsed twice in the browser, once per page. A page shell of v7.0.1 and a browser of v7.0.2, or the other way round, can't read each other's settings: the shell and the browser start ship together, in one deploy. The title's and the attributes' entities read differently in the HTML source (`&#34;` for `&quot;`), the same text in the browser.

**Instead of:**
- **the hand-written escaping**: `'` missed, and the JSON's `<` the only character escaped, a rule read by nobody else
- **`Encode/forJavaScriptBlock` on the JSON**: JavaScript escapes (`\x22`, `\'`) that aren't JSON; `JSON.parse` refuses them
- **the settings in a hidden element, HTML-escaped by `Encode/forHtmlContent`** (OWASP's cheat sheet's way): read back by `textContent` with no second parse, but an app's CSS can show a hidden element (`div { display: block }` beats `hidden`), and a `<div>` can't sit in `<head>`
