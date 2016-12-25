(ns nightcoders.core
  (:require [reagent.core :as r]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui]))

(defonce state (r/atom {}))

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme
                 (doto (aget js/MaterialUIStyles "DarkRawTheme")
                   (aset "palette" "accent1Color" "darkgray")
                   (aset "palette" "accent2Color" "darkgray")
                   (aset "palette" "accent3Color" "darkgray")))}
   [:span
    [:div {:class "g-signin2"
           :data-onsuccess "onSignIn"
           :style {:display (if (:user @state) "none" "block")}}]
    [ui/card
     [ui/card-text
      "Build apps and games with ClojureScript, a simple and powerful programming language."]]]])

(aset js/window "onSignIn"
  (fn [user]
    (swap! state assoc :user (.getBasicProfile user))))

(js/gapi.load "auth2"
  (fn []
    (js/gapi.auth2.init #js {"fetch_basic_profile" true})))

(r/render-component [app] (.querySelector js/document "#app"))

