(ns nightcoders.core
  (:require [reagent.core :as r]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui])
  (:import goog.net.XhrIo))

(defonce state (r/atom {}))

(defn auth-user [user]
  (.send XhrIo
    "/auth"
    (fn [e]
      (when (.isSuccess (.-target e))
        (swap! state assoc :user (.getBasicProfile user))))
    "POST"
    (.-id_token (.getAuthResponse user))))

(aset js/window "signIn" auth-user)

(defn sign-out []
  (-> (js/gapi.auth2.getAuthInstance)
      (.signOut)
      (.then #(swap! state dissoc :user))))

(defn new-project [template]
  (.send XhrIo
    "/new-project"
    (fn [e]
      (when (.isSuccess (.-target e))))
    "POST"
    template))

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme
                 (doto (aget js/MaterialUIStyles "DarkRawTheme")
                   (aset "palette" "accent1Color" "darkgray")
                   (aset "palette" "accent2Color" "darkgray")
                   (aset "palette" "accent3Color" "darkgray")))}
   [:span
    [:div {:style {:margin "10px"
                   :display "inline-block"}}
     [:div {:class "g-signin2"
            :data-onsuccess "signIn"
            :style {:display (if (:user @state) "none" "block")}}]
     [ui/raised-button {:on-click sign-out
                        :style {:display (if (:user @state) "block" "none")}}
      "Sign Out"]]
    [ui/card
     [ui/card-text
      (if (:user @state)
        [:span
         [:p "Create a new project:"]
         [:a {:href "#"
              :on-click #(new-project "web-app")}
          "Basic Web App"]]
        [:span
         [:p "Build web apps and games with ClojureScript, a simple and powerful programming language."]
         [:p "Sign in with your Google account and start coding for free."]])]]]])

(js/gapi.load "auth2"
  (fn []
    (js/gapi.auth2.init #js {"fetch_basic_profile" true})))

(r/render-component [app] (.querySelector js/document "#app"))

