(ns {{namespace}}
  (:require [reagent.core :as r]))

(def state (r/atom {:text "Hello, world!"}))

(defn content []
  [:div (:text @state)])

(r/render-component [content] (.querySelector js/document "#content"))

