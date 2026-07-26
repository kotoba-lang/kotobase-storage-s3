(ns kotobase.storage.s3
  "S3-compatible and Cloudflare R2 implementation of kotobase-storage."
  (:require [kotobase.storage.core :as storage]
            [kotobase.storage.s3-sigv4 :as sigv4]))

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

(defrecord S3Storage [client prefix]
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
    #{:immutable-blocks :cid-addressed-read :conditional-ref
      :linearizable-ref :batch-get :batch-put}))

(defn open
  "Open an S3-compatible backend.

  CLIENT functions return Promises. `:put-object!` accepts `:if-match` or
  `:if-none-match` and returns nil on HTTP 409/412."
  [{:keys [client prefix]}]
  (doseq [operation [:get-object :put-object!]]
    (when-not (ifn? (get client operation))
      (throw (ex-info "S3 storage client is incomplete"
                      {:type :kotobase.storage/invalid-configuration
                       :missing operation}))))
  (->S3Storage client (clean-prefix prefix)))

(defn r2-client
  "Adapt a Cloudflare R2Bucket binding to the S3 client contract."
  [bucket]
  {:get-object
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

  CONFIG requires endpoint, bucket, region, access-key, and secret-key."
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
    {:get-object
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
