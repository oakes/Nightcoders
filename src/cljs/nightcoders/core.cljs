(ns nightcoders.core
  (:require [reagent.core :as r]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui]
            [nightcoders.auth :as auth])
  (:import goog.net.XhrIo))

(defonce state (r/atom {}))

(auth/set-sign-in #(swap! state assoc :signed-in? %))
(auth/load (fn [_]))

(defn new-project [project-name]
  (let [template (:new-project-template @state)]
    (swap! state dissoc :new-project-template)
    (.send XhrIo
      "/new-project"
      (fn [e]
        (when (.isSuccess (.-target e))
          (set! (.-location js/window) (.. e -target getResponseText))))
      "POST"
      (pr-str {:project-type template
               :project-name project-name}))))

(defn new-project-dialog []
  (let [project-name (atom "")]
    [ui/dialog {:modal true
                :open (some? (:new-project-template @state))
                :actions
                [(r/as-element
                   [ui/flat-button {:on-click #(swap! state dissoc :new-project-template)
                                    :style {:margin "10px"}}
                    "Cancel"])
                 (r/as-element
                   [ui/flat-button {:on-click #(new-project @project-name)
                                    :style {:margin "10px"}}
                    "Create Project"])]}
     [ui/text-field
      {:floating-label-text "Choose a name for your project"
       :full-width true
       :on-change #(reset! project-name (.-value (.-target %)))}]]))

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
            :style {:display (if (:signed-in? @state) "none" "block")}}]
     [ui/raised-button {:on-click (fn []
                                    (auth/sign-out #(swap! state assoc :signed-in? false)))
                        :style {:display (if (:signed-in? @state) "block" "none")}}
      "Sign Out"]]
    [new-project-dialog]
    [ui/card
     [ui/card-text
      (if (:signed-in? @state)
        [:span
         [:p "Create a new project:"]
         [:a {:href "#"
              :on-click #(swap! state assoc :new-project-template :basic-web)}
          "Basic Web App"]]
        [:span
         [:h2 "Nightcoders"]
         [:p "Build web apps and games with ClojureScript, entirely in your browser."]
         [:p "Sign in with your Google account and start coding for free."]
         [:h2 "Reload your code"]
         [:p "Write your code in one tab, and see your app in another."]
         [:p "Your changes will be pushed to the app instantly without refreshing it."]
         [:h2 "Fire up a REPL"]
         [:p "For even more interactivity, you can start the REPL."]
         [:p "It’s like a little command prompt to poke and prod your app as you develop it."]
         [:h2 "Bring in libraries"]
         [:p "In the control panel, you can add any ClojureScript library you want."]
         [:h2 "Take it offline"]
         [:p "At any time, you can download your project and run it locally."]
         [:p "It even comes configured with Nightlight, an offline version of the editor this website uses."]])]]]])

(r/render-component [app] (.querySelector js/document "#app"))

