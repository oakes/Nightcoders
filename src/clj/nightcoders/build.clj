(ns nightcoders.build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :refer [send! with-channel on-receive on-close close]]
            [nightcoders.fs :as fs]
            [stencil.core :as stencil])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io PipedWriter PipedReader PrintWriter]
           [com.hypirion.io ClosingPipe Pipe]))

(def ^:const cljs-dep '[org.clojure/clojurescript "1.10.439"])
(def ^:const max-open-projects 5)

(defonce *state (atom {}))

(defn remove-returns [^String s]
  (str/escape s {\return ""}))

(defn pipe-into-console! [in-pipe channel]
  (let [ca (char-array 256)]
    (.start
      (Thread.
        (fn []
          (loop []
            (when-let [read (try (.read in-pipe ca)
                              (catch Exception _))]
              (when (pos? read)
                (let [s (remove-returns (String. ca 0 read))]
                  (send! channel s)
                  (Thread/sleep 100) ; prevent thread from being flooded
                  (recur))))))))))

(defn create-pipes []
  (let [out-pipe (PipedWriter.)
        in (LineNumberingPushbackReader. (PipedReader. out-pipe))
        pout (PipedWriter.)
        out (PrintWriter. pout)
        in-pipe (PipedReader. pout)]
    {:in in :out out :in-pipe in-pipe :out-pipe out-pipe}))

(defn stop-process!
  [process]
  (when-let [p @process]
    (.destroy p)
    (.waitFor p)
    (reset! process nil)))

(defn start-process!
  [process path args]
  (reset! process (.exec (Runtime/getRuntime)
                         (into-array args)
                         nil
                         (io/file path)))
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(when @process (.destroy @process))))
  (with-open [out (io/reader (.getInputStream @process))
              err (io/reader (.getErrorStream @process))
              in (io/writer (.getOutputStream @process))]
    (let [pump-out (doto (Pipe. out *out*) .start)
          pump-err (doto (Pipe. err *err*) .start)
          pump-in (doto (ClosingPipe. *in* in) .start)]
      (.join pump-out)
      (.join pump-err)
      (.waitFor @process)
      (reset! process nil))))

(defn create-deps-edn [{:keys [deps]}]
  (let [deps (if (-> (map first deps)
                     set
                     (contains? 'org.clojure/clojurescript))
               deps
               (cons cljs-dep deps))]
    (with-out-str
      (binding [*print-namespace-maps* false]
        (clojure.pprint/pprint
          {:paths ["src" "resources"]
           :deps (reduce
                   (fn [m [artifact version]]
                     (assoc m artifact {:mvn/version version}))
                   {}
                   deps)
           :aliases {:dev {:extra-deps {'com.bhauman/figwheel-main {:mvn/version "0.2.3"}
                                        'nightlight {:mvn/version "RELEASE"}}
                           :main-opts ["dev.clj"]}
                     :prod {:main-opts ["prod.clj"]}}})))))

(defn create-dev-cljs-edn [{:keys [main-ns]}]
  (with-out-str
    (clojure.pprint/pprint
      {:main          (symbol main-ns)
       :optimizations :none
       :output-to     "resources/nightcoders/main.js"
       :output-dir    "resources/nightcoders/main.out"
       :asset-path    "/main.out"})))

(defn create-java-policy [m]
  (-> (io/resource "template.java.policy")
      slurp
      (stencil/render-string m)))

(defn create-main-cljs-edn [{:keys [main-ns]}]
  (with-out-str
    (clojure.pprint/pprint
      {:require  [(symbol main-ns) 'nightlight.repl-server]
       :init-fns []
       :compiler-options {:infer-externs true}})))

(defn move-index-html [user-id project-id]
  (let [f (fs/get-project-dir user-id project-id)
        index-html (io/file f "target" "nightcoders" "index.html")
        index-old-html (io/file (.getParentFile index-html) "index-old.html")]
    (when (.exists index-html)
      (.renameTo index-html index-old-html))))

(defn start-boot-process! [user-id project-id channel pipes]
  (let [f (fs/get-project-dir user-id project-id)
        {:keys [in-pipe out]} pipes
        process (atom nil)
        prefs (fs/get-prefs user-id user-id project-id)
        index-html (io/file f "target" "nightcoders" "index.html")
        index-old-html (io/file (.getParentFile index-html) "index-old.html")]
    ;; save the readme again so old projects get the latest one
    (spit (io/file f "README.md")
      (slurp (io/resource "template.README.md")))
    ;; files for the clj tool
    (spit (io/file f "deps.edn") (create-deps-edn prefs))
    (spit (io/file f "dev.cljs.edn") (create-dev-cljs-edn prefs))
    (spit (io/file f "figwheel-main.edn")
      (slurp (io/resource "template.figwheel-main.edn")))
    (spit (io/file f "dev.clj")
      (slurp (io/resource "template.dev.clj")))
    (spit (io/file f "prod.clj")
      (format (slurp (io/resource "template.prod.clj"))
              (:main-ns prefs)))
    ;; files for boot
    (spit (io/file f "boot.properties")
      (slurp (io/resource "template.boot.properties")))
    (spit (io/file f "build.boot")
      (slurp (io/resource "template.build.boot")))
    (spit (io/file f "resources" "nightcoders" "main.cljs.edn")
      (create-main-cljs-edn prefs))
    (when-not (= user-id 1)
      (spit (io/file f "java.policy")
        (create-java-policy {:home (System/getProperty "user.home")
                             :server (.getCanonicalPath (io/file "."))
                             :project (.getCanonicalPath f)})))
    (when (.exists index-html)
      (.renameTo index-html index-old-html))
    (pipe-into-console! in-pipe channel)
    (.start
      (Thread.
        (fn []
          (binding [*out* out
                    *err* out]
            (try
              (println "Warming up...")
              (start-process! process (.getCanonicalPath f)
                ["java" "-jar" (-> "boot.jar" io/file .getCanonicalPath) "--no-colors" "dev"])
              (catch Exception e (some-> (.getMessage e) println))
              (finally
                (println "=== Finished ===")
                (when (and (not (.exists index-html))
                           (.exists index-old-html))
                  (.renameTo index-old-html index-html))))))))
    process))

(defn start [user-id project-id channel]
  (if (-> (get @*state user-id) count (< max-open-projects))
    (->> (create-pipes)
         (start-boot-process! user-id project-id channel)
         (hash-map :channel channel :process)
         (swap! *state assoc-in [user-id project-id]))
    (send! channel "Error: You have too many open projects.")))

(defn restart [user-id project-id]
  (when-let [{:keys [channel process]} (get-in @*state [user-id project-id])]
    (stop-process! process)
    (start user-id project-id channel)))

(defn status-request [request user-id project-id]
  (with-channel request channel
    (on-close channel
      (fn [status]
        (when-let [{:keys [process]} (get-in @*state [user-id project-id])]
          (stop-process! process))
        (swap! *state update user-id dissoc project-id)))
    (on-receive channel
      (fn [text]
        (when-not (get-in @*state [user-id project-id])
          (start user-id project-id channel))))))

(defn stop-project! [user-id project-id]
  (when-let [{:keys [channel process]} (get-in @*state [user-id project-id])]
    (close channel)
    (stop-process! process))
  (swap! *state update user-id dissoc project-id))

(defn stop-projects! [user-id]
  (doseq [[project-id project] (get @*state user-id)]
    (stop-project! user-id project-id))
  (swap! *state dissoc user-id))

