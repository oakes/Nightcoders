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

(defn signin-signout []
  [:div {:style {:margin "10px"
                 :display "inline-block"}}
   [:div {:class "g-signin2"
          :data-onsuccess "signIn"
          :style {:display (if (:signed-in? @state) "none" "block")}}]
   [ui/raised-button {:on-click (fn []
                                  (auth/sign-out #(swap! state assoc :signed-in? false)))
                      :style {:display (if (:signed-in? @state) "block" "none")}}
    "Sign Out"]])

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

(defn templates []
  [:div {:class "card-group"}
   [ui/card {:style {:margin "10px"}}
    [ui/card-text
     [:span
      [:p "Create a new project:"]
      [:a {:href "#"
           :on-click #(swap! state assoc :new-project-template :basic-web)}
       "Basic Web App"]]]]])

(defn intro []
  [:div {:class "card-group"}
   [ui/card {:style {:margin "10px"}}
    [ui/card-text
     [:p "Build web apps and games with ClojureScript, entirely in your browser."]
     [:p "Sign in with your Google account and start coding for free."]]]])

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme
                 (doto (aget js/MaterialUIStyles "DarkRawTheme")
                   (aset "palette" "accent1Color" "darkgray")
                   (aset "palette" "accent2Color" "darkgray")
                   (aset "palette" "accent3Color" "darkgray")))}
   [:div
    [signin-signout]
    [new-project-dialog]
    (if (:signed-in? @state)
      [templates]
      [intro])
    [:div {:class "card-group"}
     [ui/card {:class "small-card"}
      [ui/card-text
       [ui/card-title {:title "Reload instantly"
                       :style {:text-align "center"}}]
       "Write your code in one tab, and see your app in another. Changes are pushed down without refreshing."]]
     [ui/card {:class "small-card"}
      [ui/card-text
       [ui/card-title {:title "Fire up a REPL"
                       :style {:text-align "center"}}]
       "For even more interactivity, you can start the REPL to poke and prod your app as you develop it."]]]
    [:div {:class "card-group"}
     [ui/card {:class "small-card"}
      [ui/card-text
       [ui/card-title {:title "Bring in libraries"
                       :style {:text-align "center"}}]
       "You can add any ClojureScript library you want — including popular ones like core.async or Reagent."]]
     [ui/card {:class "small-card"}
      [ui/card-text
       [ui/card-title {:title "Take it offline"
                       :style {:text-align "center"}}]
       "Download your project at any time. It'll come with "
       [:a {:href "https://sekao.net/nightlight/" :target "_blank"} "Nightlight"]
       ", an offline version of this website."]]]]])

(r/render-component [app] (.querySelector js/document "#app"))

