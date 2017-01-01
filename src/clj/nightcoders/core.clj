(ns nightcoders.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.util.response :refer [redirect]]
            [ring.util.request :refer [body-string]]
            [ring.util.mime-type :refer [ext-mime-type]]
            [org.httpkit.server :refer [run-server]]
            [nightcoders.db :as db]
            [nightcoders.fs :as fs]
            [nightcoders.build :as build])
  (:import [java.io File FilenameFilter]
           [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdToken$Payload GoogleIdTokenVerifier$Builder]
           [com.google.api.client.json.jackson2 JacksonFactory]
           [com.google.api.client.http.javanet NetHttpTransport]
           [java.util Collections])
  (:gen-class))

(def ^:const max-file-size (* 1024 1024 20))
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

(defn authorized? [request user-id]
  (= user-id (-> request :session :id str)))

(defn get-prefs [request user-id project-id]
  (fs/get-prefs (-> request :session :id str) user-id project-id))

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

(defn code-routes [request user-id project-id leaves]
  (case (first leaves)
    "completions" {:status 200
                   :headers {"Content-Type" "text/plain"}
                   :body "[]"}
    "tree" {:status 200
            :headers {"Content-Type" "text/plain"}
            :body (let [prefs (get-prefs request user-id project-id)
                        options (assoc @options
                                  :read-only? (not (authorized? request user-id))
                                  :url "../public/")]
                    (-> (fs/get-source-dir user-id project-id)
                        (file-node (fs/get-source-dir user-id project-id) prefs)
                        (assoc :primary-text (or (:project-name prefs) "Nightcoders"))
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
                    (let [mime-type (ext-mime-type (.getCanonicalPath f))]
                      (when (or (nil? mime-type)
                                (.startsWith mime-type "text"))
                        {:status 200
                         :headers {"Content-Type" "text/plain"}
                         :body f}))))
    "write-file" (when (authorized? request user-id)
                   (let [{:keys [path content]} (-> request body-string edn/read-string)
                         f (io/file (fs/get-source-dir user-id project-id) path)]
                     (if (> (count content) max-file-size)
                       {:status 400
                        :headers {}
                        :body "File too large."}
                       (do
                         (spit f content)
                         {:status 200}))))
    "new-file" (when (authorized? request user-id)
                 (when-let [{:keys [path contents]} (fs/get-file-path-and-contents (body-string request))]
                   (let [file (io/file (fs/get-source-dir user-id project-id) path)]
                     (when-not (.exists file)
                       (.mkdirs (.getParentFile file))
                       (spit file contents))
                     (-> (fs/get-pref-file user-id project-id)
                         (fs/update-prefs {:selection path}))
                     {:status 200})))
    "new-file-upload" (when (authorized? request user-id)
                        (let [files (-> request :params (get "files"))
                              files (if (map? files) [files] files)
                              src-dir (fs/get-source-dir user-id project-id)]
                          (doseq [{:keys [size tempfile filename]} files]
                            (when (<= size max-file-size)
                              (io/copy tempfile (io/file src-dir filename)))))
                        {:status 200})
    "rename-file" (when (authorized? request user-id)
                    (let [{:keys [from to]} (-> request body-string edn/read-string)
                          src-dir (fs/get-source-dir user-id project-id)
                          from-file (io/file src-dir from)
                          to-file (io/file src-dir to)]
                      (.mkdirs (.getParentFile to-file))
                      (.renameTo from-file to-file)
                      (delete-parents-recursively! src-dir from-file)
                      {:status 200}))
    "delete-file" (when (authorized? request user-id)
                    (let [src-dir (fs/get-source-dir user-id project-id)
                          file (->> request body-string (io/file src-dir))]
                      (delete-parents-recursively! src-dir file)
                      {:status 200}))
    "read-state" {:status 200
                  :headers {"Content-Type" "text/plain"}
                  :body (pr-str (get-prefs request user-id project-id))}
    "write-state" (let [prefs (edn/read-string (body-string request))]
                    (when-let [user-id (-> request :session :id)]
                      (fs/update-prefs (fs/get-pref-file user-id)
                        (select-keys prefs [:auto-save? :theme])))
                    (when (authorized? request user-id)
                      (let [pref-file (fs/get-pref-file user-id project-id)
                            old-prefs (edn/read-string (slurp pref-file))
                            new-prefs (select-keys prefs [:selection :expansions :deps :project-name :main-ns])]
                        (fs/update-prefs pref-file new-prefs)
                        (when (not= (select-keys old-prefs [:deps :main-ns])
                                    (select-keys new-prefs [:deps :main-ns]))
                          (build/restart user-id project-id))))
                    {:status 200})
    "status" (when (authorized? request user-id)
               (build/status-request request user-id project-id))
    (if-let [res (io/resource (str "nightlight-public/" (str/join "/" leaves)))]
      {:status 200
       :body (io/input-stream res)}
      {:status 200
       :body (io/input-stream (io/resource (str "public/" (str/join "/" leaves))))})))

(defn project-routes [request]
  (let [[user-id project-id mode & leaves] (filter seq (str/split (:uri request) #"/"))]
    (when (and (number? (edn/read-string user-id))
               (number? (edn/read-string project-id))
               (fs/project-exists? user-id project-id))
      (case mode
        nil (redirect (str "/" user-id "/" project-id "/code/"))
        "code" (if (seq leaves)
                 (code-routes request user-id project-id leaves)
                 {:status 200
                  :headers {"Content-Type" "text/html"}
                  :body (-> "public/loading.html" io/resource slurp)})
        "public" (if (seq leaves)
                   {:status 200
                    :body (fs/get-public-file user-id project-id leaves)}
                   (let [f (fs/get-public-file user-id project-id ["index.html"])]
                     (if (.exists f)
                       {:status 200
                        :headers {"Content-Type" "text/html"}
                        :body f}
                       {:status 200
                        :headers {"Content-Type" "text/html"}
                        :body (io/input-stream (io/resource "public/refresh.html"))})))))))

(defn handler [request]
  (case (:uri request)
    "/" {:status 200
         :headers {"Content-Type" "text/html"}
         :body (-> "public/nightcoders.html" io/resource slurp)}
    "/auth" (let [token (body-string request)]
              (if-let [payload (some-> verifier (.verify token) .getPayload)]
                {:status 200
                 :session {:email (.getEmail payload)
                           :id (fs/create-user! (.getEmail payload))}}
                {:status 403}))
    "/unauth" {:status 200
               :session {}}
    "/new-project" (when-let [user-id (-> request :session :id)]
                     (let [{:keys [project-name project-type]} (edn/read-string (body-string request))]
                       (if-let [project-id (fs/create-project! user-id project-type project-name)]
                         {:status 200
                          :body (str "/" user-id "/" project-id "/code/")}
                         {:status 403
                          :body "Invalid project name."})))
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
     (->> (merge {:port 0 :hosted? true} opts)
          (reset! options)
          (run-server (-> app wrap-session wrap-multipart-params))
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
  (start {:port 3000}))

