# Params that don't fit are answered `:groundwork-steps/invalid` in the browser, not thrown

> The user's decision, 2026-11-01 (the libraries never hang a form again). Since v0.7.14. Goes with groundwork-screens's [A send that throws or fails is a failed answer](https://github.com/p4ulcristian/groundwork-screens/blob/main/decisions/2026-11-01-a-send-that-throws-or-fails-is-a-failed-answer.md).

**What was wrong:** `send-message!` checks each name's params against its `:params-shape` before it sends, and a misfit threw (`:groundwork-bridge/bad-params`), synchronously, before any promise existed. groundwork-screens's send effect waited only for an answer, so the write's event never ran and the form stayed "saving" for good. Found on Iron Rainbow's setup wizard.

**Decision:** `send-message!` never throws for params that don't fit. A name whose params don't fit (or carry a key the shape doesn't name):

- isn't sent, and is answered in the browser as the server would answer it, `{:groundwork-steps/refused :groundwork-steps/invalid :groundwork-steps/data {:errors …}}`, the shaper's errors by key, with no `:groundwork-steps/request-id` (nothing was sent, so nothing has one);
- is reported as the browser reports an uncaught error, with `reportError` (an `ex-info` of type `:groundwork-bridge/bad-params`, its message naming the name and the errors): the console shows it, and the page's `error` listeners hear it, so an app's error list (groundwork's `:groundwork/browser-error`) gets it as it got the throw before; where the browser has no `reportError` (node), the console's error;
- the names in the same message that fit are sent, as the server answers each name apart; when none fits, nothing is sent and the promise is of the refusals.

A name the shapes don't have still throws (`:groundwork-bridge/no-shapes`), as do bad shapes and bad options: they are wrong for every call, found the first time the page runs. `send-message-on-ws-line!` still throws for params that don't fit: it answers nothing itself (its answers come to the line's handlers), and its caller gets the throw at once.

**Why:** the reason exists already. Params that don't fit their shape are `:groundwork-steps/invalid {:errors …}` ([A refusal is a named reason](2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md), point 2), and the browser's check is the server's own, moved before the send: the same answer, so every page's handling of a refusal (and every app's catalog text for `:groundwork-steps/invalid`) covers it with nothing new. Reporting it with `reportError` keeps it loud, in the console and in the app's error list, without breaking the promise the page waits on.

**Instead of:**
- **a new reason, `:groundwork-bridge/bad-params`:** a second name for what `:groundwork-steps/invalid` already says, which every app's catalogs and every page's refusal handling would need to learn; the reasons that are no handler's own are groundwork-steps's, one namespace
- **a failed promise:** `send-message!` promises it never fails; a page's `.then` would still not run
- **a new required option, the app's hook for it:** the browser already has one, its `error` event, which groundwork's start listens to
- **refusing every name of the message when one doesn't fit:** the server answers each name apart, and the browser's check is the server's
