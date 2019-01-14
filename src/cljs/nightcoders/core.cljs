(ns nightcoders.core
  (:require [reagent.core :as r]
            [cljs.reader :refer [read-string]]
            [cljsjs.material-ui]
            [cljs-react-material-ui.core :refer [get-mui-theme]]
            [cljs-react-material-ui.reagent :as ui]
            [nightcoders.auth :as auth]
            [goog.object])
  (:import goog.net.XhrIo))

(defonce *state (r/atom {}))

(auth/set-sign-in (fn [success? user]
                    (when success?
                      (swap! *state assoc
                        :signed-in? true
                        :user (read-string user)))))

(auth/load (fn [auth2]
             (when-not auth2
               (swap! *state assoc :blocked? true))))

(defn signin-signout []
  [:div {:class "signin-signout"}
   [:div {:class "g-signin2"
          :data-onsuccess "signIn"
          :style {:display (if (:signed-in? @*state) "none" "block")}}]
   [ui/raised-button {:on-click (fn []
                                  (auth/sign-out #(swap! *state assoc :signed-in? false)))
                      :style {:display (if (:signed-in? @*state) "block" "none")}}
    "Sign Out"]])

(defn new-project [project-name template]
  (.send XhrIo
    "/new-project"
    (fn [e]
      (when (.isSuccess (.-target e))
        (set! (.-location js/window) (.. e -target getResponseText))))
    "POST"
    (pr-str {:project-type template
             :project-name project-name})))

(defn delete-user []
  (.send XhrIo
    "/delete-user"
    (fn []
      (auth/sign-out #(.reload js/window.location)))
    "POST"))

(defn delete-project [project-id]
  (.send XhrIo
    "/delete-project"
    #(.reload js/window.location)
    "POST"
    (pr-str {:project-id project-id})))

(defn new-project-dialog []
  (let [project-name (r/atom nil)]
    (fn []
      [ui/dialog {:modal true
                  :open (= :new-project (:dialog @*state))
                  :actions
                  [(r/as-element
                     [ui/flat-button {:on-click (fn []
                                                  (swap! *state dissoc :dialog :new-project-template)
                                                  (reset! project-name nil))
                                      :style {:margin "10px"}}
                      "Cancel"])
                   (r/as-element
                     [ui/flat-button {:on-click (fn []
                                                  (new-project @project-name (:new-project-template @*state))
                                                  (swap! *state dissoc :dialog :new-project-template)
                                                  (reset! project-name nil))
                                      :disabled (not (seq @project-name))
                                      :style {:margin "10px"}}
                      "Create Project"])]}
       [ui/text-field
        {:floating-label-text "Choose a name for your project"
         :full-width true
         :on-change #(reset! project-name (.-value (.-target %)))}]])))

(defn delete-project-dialog []
  (let [email (r/atom nil)]
    (fn []
      (when-let [{:keys [project-name project-id]} (:project @*state)]
        [ui/dialog {:modal true
                    :open (= :delete-project (:dialog @*state))
                    :actions
                    [(r/as-element
                       [ui/flat-button {:on-click (fn []
                                                    (swap! *state dissoc :dialog :project)
                                                    (reset! email nil))
                                        :style {:margin "10px"}}
                        "Cancel"])
                     (r/as-element
                       [ui/flat-button {:on-click (fn []
                                                    (delete-project project-id)
                                                    (swap! *state dissoc :dialog :project)
                                                    (reset! email nil))
                                        :disabled (not= @email (-> @*state :user :email))
                                        :style {:margin "10px"}}
                        "Delete Project"])]}
         [ui/text-field
          {:floating-label-text (str "Enter your email to confirm you want to delete " project-name)
           :full-width true
           :on-change #(reset! email (.-value (.-target %)))}]]))))

(defn delete-user-dialog []
  (let [email (r/atom nil)]
    (fn []
      [ui/dialog {:modal true
                  :open (= :delete-user (:dialog @*state))
                  :actions
                  [(r/as-element
                     [ui/flat-button {:on-click (fn []
                                                  (swap! *state dissoc :dialog)
                                                  (reset! email nil))
                                      :style {:margin "10px"}}
                      "Cancel"])
                   (r/as-element
                     [ui/flat-button {:on-click (fn []
                                                  (delete-user)
                                                  (swap! *state dissoc :dialog)
                                                  (reset! email nil))
                                      :disabled (not= @email (-> @*state :user :email))
                                      :style {:margin "10px"}}
                      "Delete Account"])]}
         [ui/text-field
          {:floating-label-text "Enter your email to confirm you want to delete your entire account"
           :full-width true
           :on-change #(reset! email (.-value (.-target %)))}]])))

(defn templates []
  [:div {:class "card-group"}
   [:div {:style {:margin "10px"}}
    [:center
     [:h3 "Create a new project:"]
     [ui/raised-button {:class "btn"
                        :on-click #(swap! *state assoc :dialog :new-project :new-project-template :reagent)}
      "Web App"]
     [ui/raised-button {:class "btn"
                        :on-click #(swap! *state assoc :dialog :new-project :new-project-template :play-cljs)}
      "Game"]
     [ui/raised-button {:class "btn"
                        :on-click #(swap! *state assoc :dialog :new-project :new-project-template :edna)}
      "Music"]
     (when (seq (-> @*state :user :projects))
       [:span
        [:h3 "Open an existing project:"]
        (for [{:keys [url project-name project-id] :as project} (-> @*state :user :projects)]
          [ui/chip {:key project-id
                    :style {:margin "10px"}
                    :on-click #(set! (.-location js/window) url)
                    :on-request-delete #(swap! *state assoc :dialog :delete-project :project project)}
           [:div {:style {:min-width "100px"}} project-name]])])]]])

(defn intro []
  [:div {:class "card-group"}
   [:div {:style {:margin "10px"
                  :text-align "center"}}
    [:p "Build web apps and games with ClojureScript, entirely in your browser."]
    [:p "Sign in with your Google account and start coding for free."]
    (when (:blocked? @*state)
      [:p [:b [:i "It looks like something in your browser is blocking the Google sign on!"]]])
    [:img {:src "screenshot.png"
           :style {:width "95%"
                   :margin-bottom "10px"}}]]])

(defn app []
  [ui/mui-theme-provider
   {:mui-theme (get-mui-theme (goog.object/get js/MaterialUIStyles "DarkRawTheme"))}
   [:div
    [signin-signout]
    [new-project-dialog]
    [delete-project-dialog]
    [delete-user-dialog]
    (if (:signed-in? @*state)
      [templates]
      [intro])
    [:div {:class "card-group"}
     [:center
      [:p "You can support this website via "
       [:a {:href "https://www.patreon.com/sekao" :target "_blank"}
        "my patreon"]]]]
    [:div {:class "card-group"}
     [:div {:class "small-card"}
      [:center [:h2 "Bring in libraries"]]
      [:p "You can add any ClojureScript library you want — including popular ones like core.async and Reagent."]
      [:img {:src "libraries.png"
             :class "small-img"}]]
     [:div {:class "small-card"}
      [:center [:h2 "Take it offline"]]
      [:p
       "Download your project at any time. It'll come with "
       [:a {:href "https://sekao.net/nightlight/" :target "_blank"} "Nightlight"]
       ", an offline version of this website."]
      [:img {:src "export.png"
             :class "small-img"}]]]
    [:div {:class "card-group"}
     [:div {:class "small-card"}
      [:center [:h2 "Reload instantly"]]
      [:p "Write your code in one tab, and see your app in another. Changes are pushed down without refreshing."]
      [:img {:src "reload.png"
             :class "small-img"}]]
     [:div {:class "small-card"}
      [:center [:h2 "Fire up a REPL"]]
      [:p "For even more interactivity, you can start the REPL to poke and prod your app as you develop it."]
      [:img {:src "repl.png"
             :class "small-img"}]]]
    [:div
     [:center
      (when (:signed-in? @*state)
        [:p [:a {:href "#" :on-click #(swap! *state assoc :dialog :delete-user)}
             "Delete your entire account"]])
      [:p]
      [:p "Made by "
       [:a {:href "https://sekao.net/" :target "_blank"} "Zach Oakes"]]
      [:p "Feedback and discussion welcome on "
       [:a {:href "https://www.reddit.com/r/Nightcode/" :target "_blank"} "/r/Nightcode"]]]]]])

(r/render-component [app] (.querySelector js/document "#app"))
