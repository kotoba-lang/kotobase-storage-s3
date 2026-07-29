(ns kotobase.storage.s3
  "S3-compatible and Cloudflare R2 implementation of kotobase-storage.

  **\"S3-compatible\" does not imply a conditional PUT.** The ref CAS here is
  an ETag precondition, and whether it holds is a property of the endpoint,
  not of this code:

  - **R2** evaluates `onlyIf.etagMatches` natively. Linearizable.
  - **AWS S3** supports `If-None-Match: *` and `If-Match` on PutObject.
    Linearizable, once confirmed for the account/region in use.
  - **Backblaze B2** has NO conditional write on either its native or its
    S3-compatible API. A PUT carrying `If-Match` succeeds regardless, so a
    read-then-write CAS silently loses updates under concurrency.

  This namespace therefore does not decide the profile from the fact that a
  client was supplied. Each client declares what its endpoint actually
  enforces, `open` refuses to promote an unverified one, and
  `probe-conditional-put!` turns \"verified\" into something a deployment
  runs rather than something a comment asserts.

  An earlier version declared `:linearizable-ref` unconditionally, for any
  endpoint. The older, production `kotobase-peer` object store had this
  right already -- it fails closed behind `MERKLE_S3_CONDITIONAL_HEAD` with
  the note \"only for a backend with conditional PutObject\" -- and this is
  that discipline, made executable."
  (:require [clojure.string :as str]
            [kotobase.storage.core :as storage]
            [kotobase.storage.s3-sigv4 :as sigv4]))

(def conditional-put-modes
  "What a client says its endpoint does with `If-Match`/`If-None-Match`.

  `:native` — the store evaluates preconditions (R2 bindings).
  `:verified` — an HTTP endpoint that `probe-conditional-put!` confirmed.
  `:unverified` — an HTTP endpoint nobody has checked. The default, because
  it is the only safe assumption: an endpoint that ignores the header
  reports success, so guessing wrong is silent."
  #{:native :verified :unverified})

(defn- clean-prefix [prefix]
  (let [value (or prefix "kotobase")]
    (str (.replace value #"^/+|/+$" "") "/")))

(defn- block-key [prefix cid] (str prefix "blocks/" cid))
(defn- ref-key [prefix name]
  (str prefix "refs/" (js/encodeURIComponent name)))

(defn- bytes= [left right]
  (and (= (.-length left) (.-length right))
       (loop [index 0]
         (cond
           (= index (.-length left)) true
           (= (aget left index) (aget right index)) (recur (inc index))
           :else false))))

(defrecord S3Storage [client prefix ref-profile]
  storage/IBlockStore
  (-put-blocks! [_ blocks]
    (-> (mapv
         (fn [{:keys [cid bytes]}]
           (let [key (block-key prefix cid)]
             (-> ((:put-object! client)
                  {:key key :body bytes :if-none-match "*"})
                 (.then
                  (fn [created]
                    (if created
                      cid
                      (-> ((:get-object client) {:key key})
                          (.then
                           (fn [existing]
                             (if (and existing
                                      (bytes= bytes (:body existing)))
                               cid
                               (js/Promise.reject
                                (ex-info
                                 "CID already has different bytes"
                                 {:type :kotobase.storage/cid-collision
                                  :cid cid}))))))))))))
         blocks)
        clj->js js/Promise.all
        (.then (fn [_] (mapv :cid blocks)))))
  (-get-blocks [_ cids]
    (-> (mapv
         (fn [cid]
           (-> ((:get-object client) {:key (block-key prefix cid)})
               (.then (fn [object]
                        (when object [cid (:body object)])))))
         cids)
        clj->js js/Promise.all
        (.then (fn [entries] (into {} (keep identity (array-seq entries)))))))

  storage/IRefStore
  (-read-ref [_ name]
    (-> ((:get-object client) {:key (ref-key prefix name)})
        (.then (fn [object]
                 (when object
                   {:cid (if (string? (:body object))
                           (:body object)
                           (.decode (js/TextDecoder.) (:body object)))
                    :version (:etag object)})))))
  (-compare-and-set-ref! [this name expected next]
    (-> (storage/-read-ref this name)
        (.then
         (fn [current]
           (if (not= expected (:cid current))
             {:published? false :current (:cid current)
              :version (:version current)}
             (-> ((:put-object! client)
                  (cond-> {:key (ref-key prefix name) :body next}
                    (:version current) (assoc :if-match (:version current))
                    (nil? current) (assoc :if-none-match "*")))
                 (.then
                  (fn [result]
                    (if result
                      {:published? true :current next
                       :version (:etag result)}
                      (-> (storage/-read-ref this name)
                          (.then
                           (fn [winner]
                             {:published? false :current (:cid winner)
                              :version (:version winner)}))))))))))))

  storage/IBackendCapabilities
  (-capabilities [_]
    (conj #{:immutable-blocks :cid-addressed-read :conditional-ref
            :batch-get :batch-put}
          ;; Not a constant. `-compare-and-set-ref!` above is a
          ;; read-then-conditional-write, which is linearizable exactly
          ;; when the endpoint honours the precondition and lost-update
          ;; prone when it does not -- same code, different guarantee.
          ref-profile)))

(defn conditional-put
  "What CLIENT declares about its endpoint's preconditions.

  Unmarked clients are `:unverified`. That is the deliberate default:
  treating an unknown endpoint as linearizable is the assumption whose
  failure is silent."
  [client]
  (let [declared (:conditional-put client)]
    (if (contains? conditional-put-modes declared) declared :unverified)))

(defn open
  "Open an S3-compatible backend.

  CLIENT functions return Promises. `:put-object!` accepts `:if-match` or
  `:if-none-match` and returns nil on HTTP 409/412.

  The ref profile follows the client's `:conditional-put` marker:
  `:native`/`:verified` give `:linearizable-ref`, anything else gives
  `:single-writer-ref` -- which is not a smaller feature but a truthful
  one, and the conformance suite treats the two differently.

  Pass `:require-linearizable? true` to refuse to open at all against an
  unverified endpoint. Deployments that put more than one writer on a ref
  should set it: the alternative is discovering the endpoint's behaviour
  from a missing commit."
  [{:keys [client prefix require-linearizable?]}]
  (doseq [operation [:get-object :put-object!]]
    (when-not (ifn? (get client operation))
      (throw (ex-info "S3 storage client is incomplete"
                      {:type :kotobase.storage/invalid-configuration
                       :missing operation}))))
  (let [mode (conditional-put client)
        linearizable? (contains? #{:native :verified} mode)]
    (when (and require-linearizable? (not linearizable?))
      (throw (ex-info
              (str "refusing to open a linearizable-ref backend on an endpoint "
                   "whose conditional PUT is " (name mode)
                   " -- run probe-conditional-put! against it, or drop "
                   ":require-linearizable? and accept the single-writer profile")
              {:type :kotobase.storage/unverified-conditional-put
               :conditional-put mode})))
    (->S3Storage client (clean-prefix prefix)
                 (if linearizable? :linearizable-ref :single-writer-ref))))

(defn wrong-etag
  "An ETag guaranteed to differ from `etag`, in whatever shape `etag`
   already has.

  Derived rather than invented, because ETag syntax is not portable across
  the two clients here. HTTP `If-Match` takes a quoted entity-tag; R2's
  binding takes the bare value and **rejects a quoted one outright** --
  `Conditional ETag should not be wrapped in quotes`. So a literal
  sentinel is wrong for one of them whichever way it is written, and it
  fails loudly on R2 rather than reporting: the probe throws instead of
  answering, on the client it most needs to answer for. Flipping the hex
  digits of the real ETag keeps the quoting, length and shape the endpoint
  itself produced, and differs in every one of them.

  Found by running the probe against a real R2 binding. The mock this repo
  tests `S3Storage` against accepted any string, so nothing here could
  have surfaced it."
  [etag]
  (when etag
    (let [flipped (str/replace (str etag) #"[0-9a-fA-F]"
                               (fn [d] (if (= d "0") "1" "0")))]
      (if (= flipped (str etag))
        ;; No hex digit to flip -- an opaque tag this cannot safely mutate.
        ;; Say so by returning nil; the caller reports the probe as
        ;; inconclusive rather than pretending to a result.
        nil
        flipped))))

(defn probe-conditional-put!
  "Ask a live endpoint whether it actually enforces preconditions.

  -> Promise<{:enforced? bool :checks {...}}>. Writes and leaves one small
  object under `<prefix>probe/conditional-put`; reusing the same key keeps
  the probe idempotent rather than littering the bucket.

  Two preconditions that MUST fail are attempted against an object known
  to exist: `If-None-Match: *` (the object is there) and `If-Match` with a
  wrong ETag. An endpoint that accepts either is ignoring the header, and
  the read-then-write CAS above will lose updates on it under concurrency
  while reporting success.

  This exists because the older production code required an operator to
  verify conditional PutObject by hand before enabling it, and a manual
  step that gates a silent failure is a manual step that gets skipped."
  [{:keys [client prefix]}]
  (let [key (str (clean-prefix prefix) "probe/conditional-put")
        body (js/Uint8Array. #js [107 116 98 45 112 114 111 98 101])
        put (:put-object! client)
        get* (:get-object client)]
    (-> (put {:key key :body body})
        (.then (fn [_] (get* {:key key})))
        (.then
         (fn [existing]
           (let [etag (:etag existing)]
             (-> (js/Promise.all
                  #js [(put {:key key :body body :if-none-match "*"})
                       (put {:key key :body body
                             :if-match (wrong-etag etag)})])
                 (.then
                  (fn [[on-existing on-wrong-etag]]
                    (let [checks {:if-none-match-rejected? (nil? on-existing)
                                  :if-match-rejected? (nil? on-wrong-etag)
                                  :etag-returned? (some? etag)}]
                      (if (nil? (wrong-etag etag))
                        ;; The If-Match half could not be posed at all. Do
                        ;; not fold that into `:enforced? false` -- "the
                        ;; endpoint ignores preconditions" and "this probe
                        ;; could not ask" are different answers, and only
                        ;; one of them is about the endpoint.
                        {:enforced? nil
                         :inconclusive :cannot-derive-a-wrong-etag
                         :checks (dissoc checks :if-match-rejected?)}
                        {:enforced? (every? true? (vals checks))
                         :checks checks})))))))))))

(defn r2-client
  "Adapt a Cloudflare R2Bucket binding to the S3 client contract.

  Marked `:native`: R2 evaluates `onlyIf` itself, so the precondition is
  the store's and not this code's."
  [bucket]
  {:conditional-put :native
   :get-object
   (fn [{:keys [key]}]
     (-> (.get bucket key)
         (.then
          (fn [object]
            (when object
              (-> (.arrayBuffer object)
                  (.then
                   (fn [buffer]
                     {:body (js/Uint8Array. buffer)
                      :etag (.-etag object)}))))))))
   :put-object!
   (fn [{:keys [key body if-match if-none-match]}]
     (-> (.put bucket key body
               (when (or if-match if-none-match)
                 #js {:onlyIf
                      (if if-match
                        #js {:etagMatches if-match}
                        #js {:etagDoesNotMatch if-none-match})}))
         (.then
          (fn [result]
            (when result {:etag (.-etag result)})))))})

(defn signed-client
  "Build a real S3-compatible HTTP client using AWS Signature Version 4.

  CONFIG requires endpoint, bucket, region, access-key, and secret-key.

  `:conditional-put` defaults to `:unverified`, because \"speaks the S3
  API\" and \"evaluates If-Match\" are different claims and this code cannot
  tell them apart from the outside. AWS S3 supports both preconditions on
  PutObject; Backblaze B2 supports neither and accepts the PUT anyway.
  Pass `:conditional-put :verified` only for an endpoint
  `probe-conditional-put!` has actually answered for."
  [config]
  (let [request
        (fn [method key body headers]
          (-> (sigv4/signed-request
               (merge config {:method method :key key
                              :body body :headers headers}))
              (.then
               (fn [{:keys [url headers]}]
                 (js/fetch
                  url #js {:method method :headers headers :body body})))))]
    {:conditional-put (or (:conditional-put config) :unverified)
     :get-object
     (fn [{:keys [key]}]
       (-> (request "GET" key nil nil)
           (.then
            (fn [response]
              (cond
                (= 404 (.-status response)) nil
                (.-ok response)
                (-> (.arrayBuffer response)
                    (.then
                     (fn [buffer]
                       {:body (js/Uint8Array. buffer)
                        :etag (.get (.-headers response) "etag")})))
                :else
                (js/Promise.reject
                 (ex-info "S3 GET failed"
                          {:key key :status (.-status response)})))))))
     :put-object!
     (fn [{:keys [key body if-match if-none-match]}]
       (let [headers (cond
                       if-match {"if-match" if-match}
                       if-none-match {"if-none-match" if-none-match}
                       :else nil)]
         (-> (request "PUT" key body headers)
             (.then
              (fn [response]
                (cond
                  (.-ok response)
                  {:etag (.get (.-headers response) "etag")}
                  (contains? #{409 412} (.-status response)) nil
                  :else
                  (js/Promise.reject
                   (ex-info "S3 conditional PUT failed"
                            {:key key :status (.-status response)}))))))))}))
