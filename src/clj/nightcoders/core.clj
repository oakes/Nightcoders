(ns nightcoders.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.util.response :refer [redirect]]
            [ring.util.request :refer [body-string]]
            [org.httpkit.server :refer [run-server]])
  (:import [java.io File FilenameFilter]
           [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdToken$Payload GoogleIdTokenVerifier$Builder]
           [com.google.api.client.json.jackson2 JacksonFactory]
           [com.google.api.client.http.javanet NetHttpTransport]
           [java.util Collections]))

(def ^:const max-file-size (* 1024 1024 2))
(def ^:const client-id "304442508042-58fmu8pd2u2l5irdbajiucm427aof93r.apps.googleusercontent.com")
(def verifier (-> (GoogleIdTokenVerifier$Builder. (NetHttpTransport.) (JacksonFactory.))
                  (doto (.setAudience (Collections/singletonList client-id)))
                  (.build)))

(defonce web-server (atom nil))
(defonce options (atom nil))

(defn form->serializable [form]
  (if (instance? Exception form)
    [(.getMessage form)]
    (pr-str form)))

(defn read-state []
  {:auto-save? true :theme :dark})

(defn file-node
  ([^File file]
   (let [pref-state (read-state)]
     (-> (file-node file pref-state)
         (assoc :selection (:selection pref-state))
         (assoc :options @options))))
  ([^File file {:keys [expansions] :as pref-state}]
   (let [path (.getCanonicalPath file)
         children (->> (reify FilenameFilter
                         (accept [this dir filename]
                           (not (.startsWith filename "."))))
                       (.listFiles file)
                       (mapv #(file-node % pref-state)))
         node {:primary-text (.getName file)
               :value path
               :initially-open (contains? expansions path)}]
     (if (seq children)
       (assoc node :nested-items children)
       node))))

(defn handler [request]
  (case (:uri request)
    "/" {:status 200
         :headers {"Content-Type" "text/html"}
         :body (-> "public/nightcoders.html" io/resource slurp)}
    "/tree" {:status 200
             :headers {"Content-Type" "text/plain"}
             :body "[]" #_(-> "." io/file .getCanonicalFile file-node pr-str)}
    "/read-file" (when-let [f (some-> request body-string io/file)]
                   (cond
                     (not (.isFile f))
                     {:status 400
                      :headers {}
                      :body "Not a file."}
                     (> (.length f) max-file-size)
                     {:status 400
                      :headers {}
                      :body "File too large."}
                     :else
                     {:status 200
                      :headers {"Content-Type" "text/plain"}
                      :body (slurp f)}))
    "/write-file" (let [{:keys [path content]} (-> request body-string edn/read-string)]
                    #_(spit path content)
                    {:status 200})
    "/read-state" {:status 200
                   :headers {"Content-Type" "text/plain"}
                   :body nil #_(pr-str (read-state))}
    "/write-state" {:status 200
                    :headers {"Content-Type" "text/plain"}
                    :body nil #_(spit pref-file (body-string request))}
    "/auth" (let [token (body-string request)]
              (if-let [payload (some-> verifier (.verify token) .getPayload)]
                {:status 200
                 :session {:email (.getEmail payload)}}
                {:status 403}))
    nil))

(defn print-server [server]
  (println
    (str "Started Nightcoders on http://localhost:"
      (-> server meta :local-port)))
  server)

(defn start
  ([opts]
   (-> handler
       (wrap-resource "nightlight-public")
       (wrap-resource "public")
       (start opts)))
  ([app opts]
   (when-not @web-server
     (->> (merge {:port 0} opts)
          (reset! options)
          (run-server (wrap-session app))
          (reset! web-server)
          print-server))))

(defn dev-start [opts]
  (when-not @web-server
    (.mkdirs (io/file "target" "public"))
    (-> handler
        (wrap-resource "nightlight-public")
        (wrap-file "target/public")
        (start opts))))

