(ns nightcoders.auth
  (:require [goog.object :as gobj])
  (:import goog.net.XhrIo))

(defn auth-user [user cb]
  (.send XhrIo
    "/auth"
    (fn [e]
      (cb (.isSuccess (.-target e)) (.. e -target getResponseText)))
    "POST"
    (.-id_token (.getAuthResponse user))))

(defn set-sign-in [cb]
  (gobj/set js/window "signIn" #(auth-user % cb)))

(defn unauth-user [cb]
  (.send XhrIo "/unauth" cb "POST"))

(defn sign-out [cb]
  (-> (js/gapi.auth2.getAuthInstance)
      (.signOut)
      (.then #(unauth-user cb))))

(defn load [cb]
  (if (and (exists? js/gapi) (exists? js/gapi.load))
    (js/gapi.load "auth2"
      (fn []
        (let [auth2 (js/gapi.auth2.init #js {"fetch_basic_profile" true})]
          (.then auth2 #(cb auth2)))))
    (cb nil)))

