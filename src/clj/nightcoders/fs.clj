(ns nightcoders.fs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [leiningen.new.templates :as t]
            [nightcoders.db :as db])
  (:import [org.eclipse.jgit.api InitCommand]))

(def ^:const parent-dir "data")
(def ^:const pref-file-name ".nightlight.edn")

(defn secure-file [parent-dir leaf-str]
  (let [file (io/file parent-dir leaf-str)
        parent-path (.toPath parent-dir)
        path (-> file .toPath .normalize)]
    (when (.startsWith path parent-path)
      file)))

(defn project-exists? [user-id project-id]
  (.exists (io/file parent-dir (str user-id) (str project-id))))

(defn get-user-dir [user-id]
  (io/file parent-dir (str user-id)))

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
  (let [user-prefs (when requester-id
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
  (let [last-dot (.lastIndexOf s ".")]
    (if (< last-dot 0)
      [s ""]
      [(subs s 0 last-dot)
       (subs s (inc last-dot))])))

(defn get-file-path-and-contents [s]
  (let [[path ext] (split-path-and-ext s)
        ext (str/lower-case ext)]
    (when (seq path)
      (if (#{"clj" "cljs" "cljc"} ext)
        {:path (str (sanitize-path path) "." ext)
         :contents (str "(ns " (sanitize-ns path) ")\n\n")}
        {:path s :contents ""}))))

(defn gen-project
  [template-name deps project-name main-ns path]
  (let [render (t/renderer template-name)
        data {:app-name project-name
              :namespace main-ns
              :path path}
        prefs {:project-name project-name
               :main-ns main-ns
               :deps deps
               :selection "*CONTROL-PANEL*"}]
    (t/->files data
      ["README.md" (io/input-stream (io/resource "template.README.md"))]
      ["src/nightcoders/{{path}}.cljs" (render "core.cljs" data)]
      ["src/nightcoders/index.html" (render "index.html" data)]
      ["src/nightcoders/style.css" (render "style.css" data)]
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
            :reagent (gen-project "reagent" '[[reagent "0.7.0"]] project-name main-ns path)
            :play-cljs (gen-project "play-cljs" '[[play-cljs "0.11.1"]] project-name main-ns path)))
        (-> (InitCommand.)
            (.setDirectory (io/as-file f))
            (.call))
        project-id))))

(defn get-relative-path
  "Returns the selected path as a relative URI to the project path."
  [project-file selected-file]
  (-> (.toURI project-file)
      (.relativize (.toURI selected-file))
      (.getPath)))

(defn delete-parents-recursively!
  "Deletes the given file along with all empty parents up to top-level-file."
  [top-level-file file]
  (when (and (zero? (count (.listFiles file)))
             (not (.equals file top-level-file)))
    (io/delete-file file true)
    (->> file
         .getParentFile
         (delete-parents-recursively! top-level-file)))
  nil)

(defn delete-children-recursively!
  "Deletes the children of the given dir along with the dir itself."
  [file]
  (when (.isDirectory file)
    (doseq [f (.listFiles file)]
      (delete-children-recursively! f)))
  (io/delete-file file)
  nil)

