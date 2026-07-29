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
nbb --classpath "$(clojure -Spath -M:cljs-test)" test/run.cljs
```

Runs the shared contract — including its concurrent half, which this
adapter's mock passes — plus the decisions made before any request is sent:
which profile each client shape yields, when `open` refuses, and the probe
against endpoints that do and do not enforce.
