(ns nightcoders.fs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [leiningen.new.templates :as t]
            [clj-jgit.porcelain :as jgit]
            [nightcoders.db :as db]))

(def ^:const parent-dir "data")
(def ^:const pref-file-name ".nightlight.edn")

(defn project-exists? [user-id project-id]
  (.exists (io/file parent-dir (str user-id) (str project-id))))

(defn get-project-dir [user-id project-id]
  (io/file parent-dir (str user-id) (str project-id)))

(defn get-pref-file
  ([user-id]
   (io/file parent-dir (str user-id) pref-file-name))
  ([user-id project-id]
   (io/file (get-project-dir user-id project-id) pref-file-name)))

(defn get-source-dir [user-id project-id]
  (io/file (get-project-dir user-id project-id) "src" "nightcoders"))

(defn get-public-file [user-id project-id leaves]
  (apply io/file (get-project-dir user-id project-id) "target" "nightcoders" leaves))

(defn update-prefs [file prefs]
  (let [old-prefs (edn/read-string (slurp file))]
    (spit file (pr-str (merge old-prefs prefs)))))

(defn get-prefs [requester-id user-id project-id]
  (let [user-prefs (when (seq requester-id)
                     (edn/read-string (slurp (get-pref-file requester-id))))
        proj-prefs (when (= requester-id user-id)
                     (edn/read-string (slurp (get-pref-file user-id project-id))))]
    (merge user-prefs proj-prefs)))

(defn create-user! [email]
  (let [id (db/insert-user! email)
        f (io/file parent-dir (str id))]
    (when-not (.exists f)
      (.mkdirs f)
      (spit (io/file f pref-file-name)
        (pr-str {:plan :free
                 :auto-save? true
                 :theme :dark})))
    id))

(defn sanitize-ns [s]
  (str "nightcoders."
    (-> s
        str/lower-case
        (str/replace #"[ _]" "-")
        (str/replace #"/" ".")
        (str/replace #"[^a-z0-9\-\.]" ""))))

(defn sanitize-path [s]
  (-> s
      str/lower-case
      (str/replace #"[ \-]" "_")
      (str/replace #"\." "/")
      (str/replace #"[^a-z0-9_/]" "")))

(defn split-path-and-ext [s]
  (let [last-dot (.lastIndexOf s ".")
        path (subs s 0 last-dot)
        ext (subs s (inc last-dot))]
    [path ext]))

(defn get-file-path-and-contents [s]
  (let [[path ext] (split-path-and-ext s)
        ext (str/lower-case ext)]
    (when (and (seq path)
               (not (.startsWith path ".")))
      (if (#{"clj" "cljs" "cljc"} ext)
        {:path (str (sanitize-path path) "." ext)
         :contents (str "(ns " (sanitize-ns path) ")\n\n")}
        {:path s :contents ""}))))

(defn basic-web
  [project-name main-ns path]
  (let [render (t/renderer "basic-web")
        data {:app-name project-name
              :namespace main-ns
              :path path}
        prefs {:project-name project-name
               :main-ns main-ns
               :deps '[[reagent "0.6.0"]]
               :selection "*CONTROL-PANEL*"}]
    (t/->files data
      ["README.md" (io/input-stream (io/resource "template.README.md"))]
      ["src/nightcoders/{{path}}.cljs" (render "core.cljs" data)]
      ["src/nightcoders/index.html" (render "index.html" data)]
      ["resources/nightcoders/main.cljs.edn" (render "main.cljs.edn.txt" data)]
      [pref-file-name (pr-str prefs)])))

(defn create-project! [user-id project-type project-name]
  (let [sanitized-name (str/replace project-name #"[\./]" "")
        main-ns (sanitize-ns sanitized-name)
        path (sanitize-path sanitized-name)]
    (when (seq path)
      (let [project-id (db/insert-project! user-id)
            f (io/file parent-dir (str user-id) (str project-id))]
        (binding [leiningen.new.templates/*dir* (.getCanonicalPath f)]
          (case project-type
            :basic-web (basic-web project-name main-ns path)))
        (jgit/git-init f)
        project-id))))

