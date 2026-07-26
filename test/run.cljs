(ns run
  (:require [kotobase.storage.async-contract :as contract]
            [kotobase.storage.s3 :as s3]))

(defn client []
  (let [objects (atom {})
        version (atom 0)]
    {:get-object
     (fn [{:keys [key]}] (js/Promise.resolve (get @objects key)))
     :put-object!
     (fn [{:keys [key body if-match if-none-match]}]
       (let [current (get @objects key)
             won? (cond
                    if-match (= if-match (:etag current))
                    if-none-match (nil? current)
                    :else true)]
         (if-not won?
           (js/Promise.resolve nil)
           (let [etag (str "v" (swap! version inc))
                 result {:body body :etag etag}]
             (swap! objects assoc key result)
             (js/Promise.resolve result)))))}))

(-> (contract/verify (s3/open {:client (client) :prefix "test"}))
    (.then (fn [result] (println "S3 contract:" (pr-str result))))
    (.catch (fn [error] (js/console.error error) (js/process.exit 1))))
