(ns nightcoders.fs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [leiningen.new.templates :as t]))

(def ^:const parent-dir "data")
(def ^:const pref-file "prefs.edn")

(defn project-exists? [user-id project-id]
  (.exists (io/file parent-dir (str user-id) (str project-id))))

(defn get-project-dir [user-id project-id]
  (io/file parent-dir (str user-id) (str project-id)))

(defn get-pref-file
  ([user-id]
   (io/file parent-dir (str user-id) pref-file))
  ([user-id project-id]
   (io/file (get-project-dir user-id project-id) pref-file)))

(defn get-source-dir [user-id project-id]
  (io/file (get-project-dir user-id project-id) "src" "nightcoders"))

(defn get-public-file [user-id project-id leaves]
  (apply io/file (get-project-dir user-id project-id) "target" "nightcoders" leaves))

(defn update-prefs [file prefs]
  (let [old-prefs (edn/read-string (slurp file))]
    (spit file (pr-str (merge old-prefs prefs)))))

(defn get-prefs [requester-id user-id project-id]
  (let [user-prefs (when requester-id
                     (edn/read-string (slurp (get-pref-file requester-id))))
        proj-prefs (when (= requester-id user-id)
                     (edn/read-string (slurp (get-pref-file user-id project-id))))]
    (merge user-prefs proj-prefs)))

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
              :namespace main-ns
              :path (t/name-to-path main-ns)}]
    (t/->files data
      ["src/{{path}}.cljs" (render "core.cljs" data)]
      ["src/nightcoders/index.html" (render "index.html" data)]
      ["resources/nightcoders/main.cljs.edn" (render "main.cljs.edn.txt" data)]
      ["prefs.edn" (render "prefs.edn" data)])))

(defn sanitize-name [s]
  (str/replace s #"\"" ""))

(defn name->ns [project-name]
  (str "nightcoders."
    (-> project-name
        str/lower-case
        (str/replace #"[ _]" "-")
        (str/replace #"[^a-z0-9\-]" ""))))

(defn create-project! [user-id project-id project-type project-name]
  (let [f (io/file parent-dir (str user-id) (str project-id))
        project-name (sanitize-name project-name)
        main-ns (name->ns project-name)]
    (when (seq project-name)
      (binding [leiningen.new.templates/*dir* (.getCanonicalPath f)]
        (case project-type
          :basic-web (basic-web project-name main-ns)))
      true)))

