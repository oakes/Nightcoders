(ns nightcoders.fs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.new.templates :as t]))

(def ^:const parent-dir "data")
(def ^:const pref-file "prefs.edn")

(defn project-exists? [user-id project-id]
  (.exists (io/file parent-dir (str user-id) (str project-id))))

(defn get-pref-file
  ([user-id]
   (io/file parent-dir (str user-id) pref-file))
  ([user-id project-id]
   (io/file parent-dir (str user-id) (str project-id) pref-file)))

(defn get-public-file [user-id project-id leaves]
  (slurp (apply io/file parent-dir (str user-id) (str project-id) "target" "nightcoders" leaves)))

(defn create-user! [id]
  (let [f (io/file parent-dir (str id))]
    (.mkdirs f)
    (spit (io/file f pref-file)
      (pr-str {:plan :free
               :auto-save? true
               :theme :dark}))))

(defn basic-web
  [project-name main-ns]
  (let [render (t/renderer "basic-web")
        data {:app-name project-name
              :name (t/project-name project-name)
              :namespace main-ns
              :path (t/name-to-path main-ns)}]
    (t/->files data
      ["src/{{path}}.cljs" (render "core.cljs" data)]
      ["src/nightcoders/index.html" (render "index.html" data)]
      ["resources/nightcoders/main.cljs.edn" (render "main.cljs.edn.txt" data)])))

(defn create-project! [user-id project-id project-type project-name]
  (let [f (io/file parent-dir (str user-id) (str project-id))
        main-ns (->> (str/replace project-name #" " "-")
                     str/lower-case
                     (str "nightcoders.")
                     t/sanitize-ns)]
    (binding [leiningen.new.templates/*dir* (.getCanonicalPath f)]
      (case project-type
        :basic-web (basic-web project-name main-ns)))
    (spit (io/file f pref-file)
      (pr-str {:name project-name
               :ns main-ns}))))

