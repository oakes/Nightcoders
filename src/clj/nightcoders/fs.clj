(ns nightcoders.fs
  (:require [clojure.java.io :as io]))

(def ^:const parent-dir "data")

(defn create-user! [id]
  (let [f (io/file parent-dir (str id))]
    (.mkdirs f)
    (spit (io/file f "prefs.edn")
      (pr-str {:plan :free}))))

(defn create-project! [user-id project-id project-type project-name]
  (let [f (io/file parent-dir (str user-id) (str project-id))]
    (.mkdirs f)
    (spit (io/file f "prefs.edn")
      (pr-str {:name project-name}))))

