(ns nightcoders.core
  (:require [reagent.core :as r]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui]))

(defonce state (r/atom {}))

(aset js/window "signIn"
  (fn [user]
    (swap! state assoc :user (.getBasicProfile user))))

(defn sign-out []
  (-> (js/gapi.auth2.getAuthInstance)
      (.signOut)
      (.then (fn []
               (swap! state dissoc :user)))))

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme
                 (doto (aget js/MaterialUIStyles "DarkRawTheme")
                   (aset "palette" "accent1Color" "darkgray")
                   (aset "palette" "accent2Color" "darkgray")
                   (aset "palette" "accent3Color" "darkgray")))}
   [:span
    [ui/card
     [ui/card-text
      [:div {:style {:display "inline-block"}}
       [:div {:class "g-signin2"
              :data-onsuccess "signIn"
              :style {:display (if (:user @state) "none" "block")}}]
       [ui/raised-button {:on-click sign-out
                          :style {:display (if (:user @state) "block" "none")}}
        "Sign Out"]]
      [:div {:style {:margin "10px"}}
       "Build web apps and games with ClojureScript, a simple and powerful programming language."]]]]])

(js/gapi.load "auth2"
  (fn []
    (js/gapi.auth2.init #js {"fetch_basic_profile" true})))

(r/render-component [app] (.querySelector js/document "#app"))

