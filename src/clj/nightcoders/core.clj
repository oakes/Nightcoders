(ns nightcoders.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.util.response :refer [redirect]]
            [ring.util.request :refer [body-string]]
            [org.httpkit.server :refer [run-server]]
            [nightcoders.db :as db]
            [nightcoders.fs :as fs])
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

(defn get-relative-path
  "Returns the selected path as a relative URI to the project path."
  [project-path selected-path]
  (-> (.toURI (io/file project-path))
      (.relativize (.toURI (io/file selected-path)))
      (.getPath)))

(defn file-node [^File file {:keys [expansions] :as pref-state}]
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
      node)))

(defn update-prefs [file prefs]
  (let [old-prefs (edn/read-string (slurp file))]
    (spit file (pr-str (merge old-prefs prefs)))))

(defn code-routes [request user-id project-id leaves]
  (case (first leaves)
    "tree" {:status 200
            :headers {"Content-Type" "text/plain"}
            :body (let [prefs (when (= user-id (-> request :session :id str))
                                (edn/read-string (slurp (fs/get-pref-file user-id project-id))))
                        options (assoc @options :read-only? (not= user-id (-> request :session :id str)))]
                    (-> (fs/get-source-dir user-id project-id)
                        (file-node prefs)
                        (assoc :selection (:selection prefs))
                        (assoc :options options)
                        pr-str))}
    "read-file" (when-let [f (some-> request body-string io/file)]
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
    "write-file" (let [{:keys [path content]} (-> request body-string edn/read-string)]
                   #_(spit path content)
                   {:status 200})
    "read-state" {:status 200
                  :headers {"Content-Type" "text/plain"}
                  :body (let [user-prefs (edn/read-string (slurp (fs/get-pref-file user-id)))
                              proj-prefs (when (= user-id (-> request :session :id str))
                                           (edn/read-string (slurp (fs/get-pref-file user-id project-id))))]
                          (pr-str (merge user-prefs proj-prefs)))}
    "write-state" (let [prefs (edn/read-string (body-string request))
                        f (fs/get-pref-file user-id)]
                    (update-prefs (fs/get-pref-file user-id)
                      (select-keys prefs [:auto-save? :theme]))
                    (when (= user-id (-> request :session :id str))
                      (update-prefs (fs/get-pref-file user-id project-id)
                        (select-keys prefs [:selection :expansions])))
                    {:status 200})
    {:status 200
     :body (-> (str "nightlight-public/" (str/join "/" leaves)) io/resource io/input-stream)}))

(defn project-routes [request]
  (let [[ids mode & leaves] (filter seq (str/split (:uri request) #"/"))
        [user-id project-id] (str/split ids #"\.")]
    (when (and (number? (edn/read-string user-id))
               (number? (edn/read-string project-id))
               (fs/project-exists? user-id project-id))
      (case mode
        nil (redirect (str "/" user-id "." project-id "/code/"))
        "code" (if (seq leaves)
                 (code-routes request user-id project-id leaves)
                 {:status 200
                  :headers {"Content-Type" "text/html"}
                  :body (-> "nightlight-public/index.html" io/resource slurp)})
        "public" (if (seq leaves)
                   {:status 200
                    :body (slurp (fs/get-public-file user-id project-id leaves))}
                   {:status 200
                    :headers {"Content-Type" "text/html"}
                    :body (fs/get-public-file user-id project-id "index.html")})))))

(defn handler [request]
  (case (:uri request)
    "/" {:status 200
         :headers {"Content-Type" "text/html"}
         :body (-> "public/nightcoders.html" io/resource slurp)}
    "/auth" (let [token (body-string request)]
              (if-let [payload (some-> verifier (.verify token) .getPayload)]
                (let [{:keys [new? id]} (db/insert-user! (.getEmail payload))]
                  (when new?
                    (fs/create-user! id))
                  {:status 200
                   :session {:email (.getEmail payload)
                             :id id}})
                {:status 403}))
    "/new-project" (when-let [user-id (-> request :session :id)]
                     (let [project-id (db/insert-project! user-id)
                           {:keys [project-name project-type]} (edn/read-string (body-string request))]
                       (fs/create-project! user-id project-id project-type project-name)
                       {:status 200
                        :body (str "/" user-id "." project-id "/code/")}))
    (project-routes request)))

(defn print-server [server]
  (println
    (str "Started Nightcoders on http://localhost:"
      (-> server meta :local-port)))
  server)

(defn start
  ([opts]
   (-> handler
       (wrap-resource "public")
       (start opts)))
  ([app opts]
   (db/create-tables)
   (when-not @web-server
     (->> (merge {:port 0
                  :hosted? true
                  :custom-nodes [{:primary-text "Status"
                                  :value "*STATUS*"
                                  :style {:font-weight "bold"}}]}
            opts)
          (reset! options)
          (run-server (-> app wrap-session))
          (reset! web-server)
          print-server))))

(defn dev-start [opts]
  (when-not @web-server
    (db/start-ui)
    (.mkdirs (io/file "target" "public"))
    (-> handler
        (wrap-file "target/public")
        (start opts))))

(defn -main []
  (start {:port 80}))

