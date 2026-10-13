# A page loads the stylesheets its code uses

**Decision:** the build works out from the code which namespaces each browser module uses (classes are marked keywords, so this is exact) and gathers those stylesheets, whole and as written, into one fingerprinted bundle per module; a namespace several modules use goes into the bundle they share, and a module loaded on demand brings its own. Base and the libraries' files come first, in the settings' order. Nothing is generated, renamed or cut apart: what the browser gets is what was written.

Around it:

- in development, saving a stylesheet updates the open page;
- "explain this class": where it is defined, which code uses it, which bundle carries it;
- "what does this page load, and why?";
- one report listing every problem at once, each with its file and line.

An app with styles from before moves area by area, each compared before and after by screenshots of every page, pixel by pixel; the areas still on the old way are a list that only shrinks.

**Why:** a hand-written list of what each area loads can be wrong (a page missing a style it uses), and checking it needs the same work as deriving it. Derived from what the code says, and always able to explain itself, it can't be wrong and isn't hidden.

**The price:** a module gets a whole stylesheet even when it uses a few of its classes.

**Instead of:** everything in one file (every visitor downloads every area's styles); bundles declared by hand; giving each module only the single rules it uses (rules depend on each other and their order, so cutting a file apart can change how things look).
