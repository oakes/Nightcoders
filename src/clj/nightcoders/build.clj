(ns nightcoders.build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :refer [send! with-channel on-receive on-close close]]
            [nightcoders.fs :as fs]
            [stencil.core :as stencil])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io PipedWriter PipedReader PrintWriter]
           [com.hypirion.io ClosingPipe Pipe]))

(def ^:const cljs-dep '[org.clojure/clojurescript "1.9.229"])
(def ^:const max-open-projects 5)

(defonce state (atom {}))

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

(defn create-build-boot [{:keys [deps]}]
  (let [deps (if (-> (map first deps)
                     set
                     (contains? 'org.clojure/clojurescript))
               deps
               (cons cljs-dep deps))]
    (-> (io/resource "template.build.boot")
        slurp
        (format (str/join "\n                  "
                  (map pr-str deps))))))

(defn create-java-policy [m]
  (-> (io/resource "template.java.policy")
      slurp
      (stencil/render-string m)))

(defn create-main-cljs-edn [{:keys [main-ns]}]
  (pr-str
    {:require  [(symbol main-ns) 'nightlight.repl-server]
     :init-fns []
     :compiler-options {:parallel-build true}}))

(defn start-boot-process! [user-id project-id channel pipes]
  (let [f (fs/get-project-dir user-id project-id)
        {:keys [in-pipe out]} pipes
        process (atom nil)
        prefs (fs/get-prefs user-id user-id project-id)
        index-html (io/file f "target" "nightcoders" "index.html")
        index-old-html (io/file (.getParentFile index-html) "index-old.html")]
    (spit (io/file f "boot.properties")
      (slurp (io/resource "template.boot.properties")))
    (spit (io/file f "build.boot")
      (create-build-boot prefs))
    (spit (io/file f "java.policy")
      (create-java-policy {:home (System/getProperty "user.home")
                           :server (.getCanonicalPath (io/file "."))
                           :project (.getCanonicalPath f)}))
    (spit (io/file f "resources" "nightcoders" "main.cljs.edn")
      (create-main-cljs-edn prefs))
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
  (if (-> (get @state user-id) count (< max-open-projects))
    (->> (create-pipes)
         (start-boot-process! user-id project-id channel)
         (hash-map :channel channel :process)
         (swap! state assoc-in [user-id project-id]))
    (send! channel "Error: You have too many open projects.")))

(defn restart [user-id project-id]
  (when-let [{:keys [channel process]} (get-in @state [user-id project-id])]
    (stop-process! process)
    (start user-id project-id channel)))

(defn status-request [request user-id project-id]
  (with-channel request channel
    (on-close channel
      (fn [status]
        (when-let [{:keys [process]} (get-in @state [user-id project-id])]
          (stop-process! process))
        (swap! state update user-id dissoc project-id)))
    (on-receive channel
      (fn [text]
        (when-not (get-in @state [user-id project-id])
          (start user-id project-id channel))))))

(defn stop-project! [user-id project-id]
  (when-let [{:keys [channel process]} (get-in @state [user-id project-id])]
    (close channel)
    (stop-process! process))
  (swap! state update user-id dissoc project-id))

(defn stop-projects! [user-id]
  (doseq [[project-id project] (get @state user-id)]
    (stop-project! user-id project-id))
  (swap! state dissoc user-id))

