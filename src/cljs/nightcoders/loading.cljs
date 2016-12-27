(ns nightcoders.loading
  (:require [reagent.core :as r]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui]
            [nightcoders.auth :as auth])
  (:import goog.net.XhrIo))

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme
                 (doto (aget js/MaterialUIStyles "DarkRawTheme")
                   (aset "palette" "accent1Color" "darkgray")
                   (aset "palette" "accent2Color" "darkgray")
                   (aset "palette" "accent3Color" "darkgray")))}
   [:span {:style {:margin "auto"
                   :position "absolute"
                   :top 0
                   :left 0
                   :right 0
                   :bottom 0
                   :height 80
                   :width 80}}
    [ui/circular-progress {:size 80 :thickness 5}]]])

(defn get-page []
  (.send XhrIo
    "index.html"
    (fn [e]
      (.open js/document)
      (.write js/document (.. e -target getResponseText))
      (.close js/document))
    "GET"))

(auth/load (fn [auth2]
             (let [user (-> auth2 .-currentUser .get)]
               (if (.getBasicProfile user)
                 (auth/auth-user user get-page)
                 (get-page)))))

(r/render-component [app] (.querySelector js/document "#app"))

