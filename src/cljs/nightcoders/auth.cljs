(ns nightcoders.auth
  (:import goog.net.XhrIo))

(defn auth-user [user cb]
  (.send XhrIo
    "/auth"
    (fn [e]
      (cb (.isSuccess (.-target e))))
    "POST"
    (.-id_token (.getAuthResponse user))))

(defn set-sign-in [cb]
  (aset js/window "signIn" #(auth-user % cb)))

(defn sign-out [cb]
  (-> (js/gapi.auth2.getAuthInstance)
      (.signOut)
      (.then cb)))

(defn load [cb]
  (js/gapi.load "auth2"
    (fn []
      (let [auth2 (js/gapi.auth2.init #js {"fetch_basic_profile" true})]
        (.then auth2 #(cb auth2))))))

