# Accept-Encoding is read by its grammar; gzip;q=0 is no

> The user's decision, 2026-10-27 (keep groundwork minimal; Accept-Encoding read with `.contains "gzip"` ignores q=0: parse the header's grammar properly, as a small function with tests). Since v0.7.13. Changes in part [Every value that sets behaviour is a setting](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): which browser "takes gzip".

**Decision:** a built file's `.gz` copy goes out when the request's Accept-Encoding takes gzip, read by the header's grammar (RFC 9110, 12.5.3) in `groundwork-bridge.routes`:

- the header is HTTP's list: split by `,`, an empty element allowed, the spaces and tabs around each taken off;
- an element is a coding (a token, in any case) with an optional `;q=` quality, 0 to 1 with at most three decimals; one outside that (`q=1.5`, `q=abc`, `gzip;level=9`) is left out;
- gzip is taken when `gzip` (or `x-gzip`, the same coding, RFC 9110 8.4.1.3) is listed with a quality above 0; when it is unlisted, when `*` is listed with one; the first listing of each counts. `gzip;q=0` is no, and so is `*;q=0` with gzip unlisted; `*, gzip;q=0` is no, as gzip's own listing wins;
- `identity` says nothing about gzip: the file as it is still goes out to a request that takes neither, as before;
- a request without Accept-Encoding gets the file as it is, as before (RFC 9110 allows any coding then; old clients that send none may not read gzip).

**Why:** `.contains "gzip"` sent gzip to `gzip;q=0` (a client saying it can't read it), to `gzipper`, and to `nogzip`, and missed `*`. The grammar is short; no library on the path reads it (Ring has no Accept-Encoding reading), and the JDK has none.

**The price:** what goes out otherwise than v0.7.12, each in the tests: the file as it is to `gzip;q=0`, to `*;q=0`, and to a coding whose name merely holds gzip; the `.gz` copy to `*` and to `x-gzip`.

**Instead of:**
- **`.contains "gzip"`**: q=0 and other names read as yes
- **the highest quality of gzip's listings**: a header listing gzip twice is broken; the first listing is what a reader of it sees first
- **gzip to a request without the header**: RFC 9110 allows it, but a client that sends nothing may be one that can't decode
