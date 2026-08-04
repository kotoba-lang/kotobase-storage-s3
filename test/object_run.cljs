(ns object-run
  "The large-object plane: contract conformance, plus the parts the contract
  cannot reach.

  The contract can check that a grant *reports* signing `content-length`. It
  cannot check that the signature actually covers it -- a provider could
  return `:signed-headers [\"host\" \"content-length\"]` next to a URL whose
  `X-Amz-SignedHeaders` says `host`, and the contract would pass while every
  grant remained a blank cheque. So this file signs with real credentials
  through real WebCrypto and reads the property back out of the URL S3 will
  actually verify against."
  (:require [clojure.string :as str]
            [kotobase.storage.object :as object]
            [kotobase.storage.object-async-contract :as contract]
            [kotobase.storage.object-s3 :as objs3]))

(def ^:private failures (atom 0))

(defn- expect [ok? message]
  (if ok?
    (println (str "ok  - " message))
    (do (js/console.error (str "FAIL: " message)) (swap! failures inc))))

(def ^:private creds
  ;; Not a secret: these never leave the process. The signature they produce
  ;; is what is under test, not the account.
  {:endpoint "https://s3.us-west-004.backblazeb2.com"
   :bucket "kotobase-test" :region "us-west-004"
   :access-key "AKIAEXAMPLE" :secret-key "wJalrXUtnFEMI/EXAMPLEKEY"})

(defn- proxied-client
  "An in-memory stand-in for a Workers R2 binding: no credentials, therefore
  no `:presign`, therefore a proxied store."
  []
  (let [objects (atom {})]
    {:get-object (fn [{:keys [key]}]
                   (js/Promise.resolve (when-let [b (get @objects key)]
                                         {:body b})))
     :put-object! (fn [{:keys [key body]}]
                    (swap! objects assoc key body)
                    (js/Promise.resolve {:etag "e"}))
     :head-object (fn [{:keys [key]}]
                    (js/Promise.resolve
                     (when-let [b (get @objects key)]
                       {:size-bytes (.-length b)})))
     :delete-object! (fn [{:keys [key]}]
                       (let [existed? (contains? @objects key)]
                         (swap! objects dissoc key)
                         (js/Promise.resolve {:deleted? existed?})))}))

(defn- presigning-client []
  (assoc (proxied-client) :presign (objs3/presigner creds)))

(def ^:private cid
  "bafkreiadsbmmn4waznesyuz3bjgrj33xzqhxrk6mz3ksq7meugrachh3qe")

(defn- query-param [url name]
  (.get (.-searchParams (js/URL. url)) name))

(defn -main [& _]
  (-> (contract/verify (objs3/open-objects {:client (proxied-client)}))
      (.then
       (fn [result]
         (expect (= :proxied-transfer (:profile result))
                 (str "a client with no credentials declares proxied: "
                      (pr-str result)))))

      (.then (fn [_] (contract/verify
                      (objs3/open-objects {:client (presigning-client)}))))
      (.then
       (fn [result]
         (expect (= :presigned-transfer (:profile result))
                 (str "a signing client declares presigned: " (pr-str result)))))

      ;; What the contract cannot see: the signature itself.
      (.then (fn [_]
               (object/-presign-put
                (objs3/open-objects {:client (presigning-client)})
                cid {:size-bytes 1073741824})))
      (.then
       (fn [grant]
         (let [signed (query-param (:href grant) "X-Amz-SignedHeaders")]
           (expect (str/includes? (or signed "") "content-length")
                   (str "the URL S3 verifies against really signs "
                        "content-length (X-Amz-SignedHeaders=" signed ")"))
           (expect (= "1073741824" (get (:headers grant) "content-length"))
                   "and the grant tells the client which length to send")
           (expect (str/includes? (:href grant) "X-Amz-Signature=")
                   "the grant is a signed URL, not a bare object path")
           (expect (some? (:expires-at grant))
                   "the grant carries an absolute expiry, not a duration")
           (expect (str/includes? (:href grant) (str "objects/" cid))
                   "objects live under objects/<cid>, beside blocks/ and refs/"))))

      ;; A GB PUT through a Worker is the bug this plane exists to remove, so
      ;; a caller must be able to demand the profile rather than discover it.
      (.then (fn [_]
               (expect (try (objs3/open-objects {:client (proxied-client)
                                                 :require-presigned? true})
                            false
                            (catch :default _ true))
                       "require-presigned? refuses to open on a client that
                        cannot sign, instead of silently proxying GB objects")))

      (.then (fn [_]
               (if (zero? @failures)
                 (println "large-object plane: all green")
                 (do (println (str "large-object plane: " @failures
                                   " FAILURE(S) above"))
                     (js/process.exit 1)))))
      (.catch (fn [error]
                (js/console.error (str "large-object runner threw: " error))
                (js/process.exit 1)))))

(-main)
