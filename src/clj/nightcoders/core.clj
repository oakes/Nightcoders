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
  [project-file selected-file]
  (-> (.toURI project-file)
      (.relativize (.toURI selected-file))
      (.getPath)))

(defn file-node [^File file ^File source-dir {:keys [expansions] :as pref-state}]
  (let [path (get-relative-path source-dir file)
        children (->> (reify FilenameFilter
                        (accept [this dir filename]
                          (not (.startsWith filename "."))))
                      (.listFiles file)
                      (mapv #(file-node % source-dir pref-state)))
        node {:primary-text (.getName file)
              :value path
              :initially-open (contains? expansions path)}]
    (if (seq children)
      (assoc node :nested-items children)
      node)))

(defn update-prefs [file prefs]
  (let [old-prefs (edn/read-string (slurp file))]
    (spit file (pr-str (merge old-prefs prefs)))))

(defn authorized? [request user-id]
  (= user-id (-> request :session :id str)))

(defn get-prefs [request user-id project-id]
  (let [user-prefs (edn/read-string (slurp (fs/get-pref-file user-id)))
        proj-prefs (when (authorized? request user-id)
                     (edn/read-string (slurp (fs/get-pref-file user-id project-id))))]
    (merge user-prefs proj-prefs)))

(defn code-routes [request user-id project-id leaves]
  (case (first leaves)
    "tree" {:status 200
            :headers {"Content-Type" "text/plain"}
            :body (let [prefs (get-prefs request user-id project-id)
                        options (assoc @options :read-only? (not (authorized? request user-id)))]
                    (-> (fs/get-source-dir user-id project-id)
                        (file-node (fs/get-source-dir user-id project-id) prefs)
                        (assoc :primary-text (:name prefs))
                        (assoc :selection (:selection prefs))
                        (assoc :options options)
                        pr-str))}
    "read-file" (when-let [f (some->> request body-string (io/file (fs/get-source-dir user-id project-id)))]
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
                     :body f}))
    "write-file" (when (authorized? request user-id)
                   (let [{:keys [path content]} (-> request body-string edn/read-string)
                         f (io/file (fs/get-source-dir user-id project-id) path)]
                     (spit f content)
                     {:status 200}))
    "read-state" {:status 200
                  :headers {"Content-Type" "text/plain"}
                  :body (pr-str (get-prefs request user-id project-id))}
    "write-state" (let [prefs (edn/read-string (body-string request))]
                    (when-let [user-id (-> request :session :id)]
                      (update-prefs (fs/get-pref-file user-id)
                        (select-keys prefs [:auto-save? :theme])))
                    (when (authorized? request user-id)
                      (update-prefs (fs/get-pref-file user-id project-id)
                        (select-keys prefs [:selection :expansions])))
                    {:status 200})
    (if-let [res (io/resource (str "nightlight-public/" (str/join "/" leaves)))]
      {:status 200
       :body (io/input-stream res)}
      {:status 200
       :body (io/input-stream (io/resource (str "public/" (str/join "/" leaves))))})))

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
                  :body (-> "public/loading.html" io/resource slurp)})
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

