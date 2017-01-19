(ns nightcoders.loading
  (:require [nightcoders.auth :as auth])
  (:import goog.net.XhrIo))

(defn get-page []
  (.send XhrIo
    "index.html"
    (fn [e]
      (.open js/document)
      (.write js/document (.. e -target getResponseText))
      (.close js/document))
    "GET"))

(auth/load (fn [auth2]
             (let [user (some-> auth2 .-currentUser .get)]
               (if (some-> user .getBasicProfile)
                 (auth/auth-user user get-page)
                 (get-page)))))

