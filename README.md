# kotobase-storage-s3

S3-compatible/R2 adapter for `kotobase-storage`. Immutable blocks are stored
under `blocks/<cid>` and refs under `refs/<name>`. Ref publication uses ETag
`If-Match`/`If-None-Match`.

Block creation is conditional and rejects a CID/bytes collision.
`signed-client` provides AWS Signature Version 4 HTTP requests; `r2-client`
accepts a Worker R2 binding.
