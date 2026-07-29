# kotobase-storage-s3

S3-compatible/R2 adapter for `kotobase-storage`. Immutable blocks are stored
under `blocks/<cid>` and refs under `refs/<name>`. Ref publication uses ETag
`If-Match`/`If-None-Match`.

Block creation is conditional and rejects a CID/bytes collision.
`signed-client` provides AWS Signature Version 4 HTTP requests; `r2-client`
accepts a Worker R2 binding.

## "S3-compatible" does not imply a conditional PUT

The ref CAS here is `-read-ref`, compare, conditional PUT. Whether that is a
compare-and-swap or a lost-update generator is a property of the **endpoint**,
not of this code:

| endpoint | conditional PUT | profile |
|---|---|---|
| Cloudflare R2 | `onlyIf.etagMatches`, evaluated by R2 | `:linearizable-ref` |
| AWS S3 | `If-None-Match: *` and `If-Match` on PutObject | `:linearizable-ref` once confirmed for the account in use |
| Backblaze B2 | **none**, on either the native or the S3-compatible API | `:single-writer-ref` |

B2 accepts the PUT and ignores the header. Nothing errors; the write
succeeds; the other writer's commit is simply gone.

So the profile is not a constant. Each client declares what its endpoint
enforces, and an unmarked client is `:unverified` — the only safe default,
because the failure of guessing wrong is silent.

```clojure
(s3/open {:client (s3/r2-client bucket)})           ; :linearizable-ref
(s3/open {:client (s3/signed-client cfg)})          ; :single-writer-ref
(s3/open {:client (assoc (s3/signed-client cfg)     ; :linearizable-ref, on your word
                         :conditional-put :verified)})

(s3/open {:client (s3/signed-client cfg)            ; throws
          :require-linearizable? true})
```

Set `:require-linearizable? true` wherever more than one writer touches a
ref. The alternative is learning the endpoint's behaviour from a missing
commit.

An earlier version of this adapter declared `:linearizable-ref`
unconditionally, for any endpoint. The older production code in
`kotobase-peer` already had this right — it fails closed behind
`MERKLE_S3_CONDITIONAL_HEAD`, noted "only for a backend with conditional
PutObject" — and this is that discipline, made executable rather than left
to a comment.

## Probe instead of asserting

```clojure
(s3/probe-conditional-put! {:client c :prefix "kotobase"})
;; => {:enforced? false
;;     :checks {:if-none-match-rejected? false   ; accepted a PUT on an object that exists
;;              :if-match-rejected? false        ; accepted a PUT with a wrong ETag
;;              :etag-returned? true}}
```

It writes one small object at `<prefix>probe/conditional-put`, then attempts
two preconditions that **must** fail against it. An endpoint accepting either
is ignoring the header. Reusing the same key keeps the probe idempotent.

This exists because the manual verification the old code required is a manual
step gating a silent failure, and those get skipped.

## Test

```sh
npm test    # test/run.cljs (mock) + test/r2.cljs (real R2 binding)
```

`test/run.cljs` runs the shared contract against a mock written here to the
shape `open` expects, plus the decisions made before any request is sent:
which profile each client shape yields, when `open` refuses, and the probe
against endpoints that do and do not enforce.

`test/r2.cljs` runs the same contract **through `r2-client`, against a real
R2Bucket binding** (miniflare). That path had no coverage at all — the mock
never calls `r2-client`, so the code translating a CAS into
`bucket.put(key, body, {onlyIf: {etagMatches}})` was untested while being
the client this adapter recommends. A mock cannot close that gap, because
the question is whether the R2 API behaves as the adapter assumes.

It found one. `probe-conditional-put!` sent a literal quoted sentinel as
its wrong ETag; R2's binding **rejects a quoted ETag outright** —
`Conditional ETag should not be wrapped in quotes` — so the probe threw
instead of answering, on the client it most needs to answer for. The mock
had accepted any string. `wrong-etag` now derives the value from the real
ETag by flipping its hex digits, which preserves whatever quoting and shape
the endpoint itself produced; when no hex digit exists to flip it reports
`:enforced? nil :inconclusive :cannot-derive-a-wrong-etag`, because "the
endpoint ignores preconditions" and "this probe could not ask" are
different answers.

Both suites carry an oracle that must be rejected, so a green race is
evidence rather than decoration: for R2 that is `r2-client` with the
precondition stripped out of the put — the shape a dropped or misspelled
`onlyIf` key would produce, which R2 ignores silently.

**Still not Cloudflare's production R2.** What this establishes is that
`r2-client` speaks the R2 API correctly and that its claimed profile
survives contention against an implementation of that API. Whether the
production service matches its own API implementation is what
`probe-conditional-put!` is for, and it has not been run against the real
endpoint yet.
