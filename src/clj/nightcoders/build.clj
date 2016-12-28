(ns nightcoders.build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :refer [send! with-channel on-receive on-close]]
            [nightcoders.fs :as fs])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io PipedWriter PipedReader PrintWriter]
           [com.hypirion.io ClosingPipe Pipe]))

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

(defn create-build-boot [deps]
  (-> (io/resource "build.boot.clj")
      slurp
      (format (str/join (map pr-str deps)))))

(defn create-main-cljs-edn [main-ns]
  (pr-str
    {:require  [main-ns 'nightlight.repl-server]
     :init-fns []
     :compiler-options {:parallel-build true}}))

(defn start-boot-process! [user-id project-id channel pipes]
  (let [f (fs/get-project-dir user-id project-id)
        {:keys [in-pipe out]} pipes
        process (atom nil)
        prefs (fs/get-prefs user-id user-id project-id)
        build-boot (create-build-boot
                     '[[org.clojure/clojurescript "1.9.229"]
                       [reagent "0.6.0"]])
        main-cljs-edn (create-main-cljs-edn (-> prefs :ns symbol))]
    (spit (io/file f "build.boot") build-boot)
    (spit (io/file f "resources" "nightcoders" "main.cljs.edn") main-cljs-edn)
    (pipe-into-console! in-pipe channel)
    (.start
      (Thread.
        (fn []
          (binding [*out* out
                    *err* out]
            (try
              (start-process! process (.getCanonicalPath f) ["boot" "run"])
              (catch Exception e (some-> (.getMessage e) println))
              (finally (println "=== Finished ===")))))))
    process))

(defn stop-process!
  [process]
  (when-let [p @process]
    (.destroy p))
  (reset! process nil))

(defn status-request [request user-id project-id]
  (with-channel request channel
    (on-close channel
      (fn [status]
        (when-let [process (get-in @state [user-id project-id])]
          (stop-process! process))
        (swap! state update user-id dissoc project-id)))
    (on-receive channel
      (fn [text]
        (when-not (get @state channel)
          (->> (create-pipes)
               (start-boot-process! user-id project-id channel)
               (swap! state assoc-in [user-id project-id])))))))

