(require
  '[clojure.java.io :as io]
  '[cljs.build.api :as api])

(defn delete-children-recursively! [f]
  (when (.isDirectory f)
    (doseq [f2 (.listFiles f)]
      (delete-children-recursively! f2)))
  (when (.exists f) (io/delete-file f)))

(defn build-cljs [file-name opts]
  (let [out-file (str "resources/nightcoders/" file-name ".js")
        out-dir (str "resources/nightcoders/" file-name ".out")]
    (println "Building" out-file)
    (delete-children-recursively! (io/file out-dir))
    (api/build "src" (merge
                       {:output-to     out-file
                        :output-dir    out-dir}
                       opts))
    (delete-children-recursively! (io/file out-dir))))

(build-cljs "main"
            {:main          '%s
             :optimizations :advanced})
