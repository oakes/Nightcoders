(ns nightcoders.completions
  (:require [cljs.reader :refer [read-string]]
            [goog.dom :as gdom]
            [goog.events :as events]
            [nightcoders.constants :as constants]
            [paren-soup.core :as paren-soup]
            [paren-soup.dom :as dom])
  (:import goog.net.XhrIo))

(defn download-completions [info completions]
  (.send XhrIo
         "completions"
         (fn [e]
           (reset! completions (read-string (.. e -target getResponseText))))
         "POST"
         (pr-str info)))

(defn select-completion [editor {:keys [context-before context-after start-position]} text]
  (when-let [top-level-elem (dom/get-focused-top-level)]
    (when (seq text)
      (gdom/setTextContent top-level-elem
        (str context-before text context-after))
      (let [pos (+ start-position (count text))]
        (dom/set-cursor-position! top-level-elem [pos pos]))
      (paren-soup/refresh-after-cut-paste! editor))))

(defn refresh-completions [extension completions]
  (when (constants/completion-exts extension)
    (if-let [info (dom/get-completion-info)]
      (download-completions (assoc info :ext extension) completions)
      (reset! completions nil))))

(defn completion-shortcut? [e]
  (and (= 9 (.-keyCode e))
       (not (.-shiftKey e))
       (dom/get-completion-info)
       (some-> (dom/get-focused-top-level)
               (dom/get-cursor-position true)
               set
               count
               (= 1))))

(defn init-completions [extension editor-atom elem completions]
  (when (constants/completion-exts extension)
    (events/listen elem "keydown"
      (fn [e]
        (when (completion-shortcut? e)
          (when-let [comps @completions]
            (when-let [info (dom/get-completion-info)]
              (select-completion @editor-atom info (:value (first comps)))
              (refresh-completions extension completions))))))))
