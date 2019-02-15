(ns nightcoders.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compliment.core :as com]
            [dynadoc.watch :as dw]
            [hiccup.core :as h]
            [hiccup.util :refer [escape-html]]
            [nightcoders.build :as build]
            [nightcoders.db :as db]
            [nightcoders.fs :as fs]
            [nightlight.watch :as watch]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.reload :refer [wrap-reload]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.util.request :refer [body-string]]
            [ring.util.response :refer [redirect]])
  (:import [com.google.api.client.googleapis.auth.oauth2 GoogleIdToken GoogleIdToken$Payload GoogleIdTokenVerifier$Builder]
           [com.google.api.client.http.javanet NetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory]
           [java.io File FilenameFilter]
           [java.util Collections]
           [java.util.zip ZipEntry ZipOutputStream])
  (:gen-class))

(def ^:const max-file-size (* 1024 1024 2))
(def ^:const client-id "304442508042-58fmu8pd2u2l5irdbajiucm427aof93r.apps.googleusercontent.com")

(def verifier (-> (GoogleIdTokenVerifier$Builder. (NetHttpTransport.) (JacksonFactory.))
                  (doto (.setAudience (Collections/singletonList client-id)))
                  (.build)))

(defonce *web-server (atom nil))
(defonce *options (atom nil))

(defn form->serializable [form]
  (if (instance? Exception form)
    [(.getMessage form)]
    (pr-str form)))

(defn file-node [^File file ^File source-dir {:keys [expansions] :as pref-state}]
  (let [path (fs/get-relative-path source-dir file)
        children (->> (.listFiles file)
                      (mapv #(file-node % source-dir pref-state)))
        node {:primary-text (.getName file)
              :value path
              :initially-open (contains? expansions path)}]
    (if (seq children)
      (assoc node :nested-items children)
      node)))

(defn authorized? [request user-id]
  (= user-id (-> request :session :id)))

(defn get-prefs [request user-id project-id]
  (fs/get-prefs (-> request :session :id) user-id project-id))

(defn get-public-url [request user-id project-id]
  (str
    "http://" user-id "."
    (get-in request [:headers "host"])
    "/" project-id "/public/"))

(defn get-code-url [request user-id project-id]
  (let [host (get-in request [:headers "host"])
        host-parts (str/split host #"\.")
        host-parts (if (> (count host-parts) 1)
                     (rest host-parts)
                     host-parts)]
    (str
      "http://"
      (str/join "." host-parts)
      "/" user-id "/" project-id "/code/")))

(defn user-routes [request user-id path-parts]
  (let [[project-id mode & leaves] path-parts]
    (when-let [[user-id project-id] (try
                                      [(Integer/valueOf user-id)
                                       (Integer/valueOf project-id)]
                                      (catch Exception _))]
      (when (fs/project-exists? user-id project-id)
        (case mode
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
                          :body (io/input-stream (io/resource "public/refresh.html"))})))
          "code" (redirect (get-code-url request user-id project-id) 301)
          nil)))))

(defn localhost? [request]
  (let [^String host (get-in request [:headers "host"])]
    (.startsWith host "localhost")))

(defn code-routes [request user-id project-id leaves]
  (case (first leaves)
    "export.zip" (let [dir (fs/get-project-dir user-id project-id)
                       zip-file (io/file dir "export.zip")
                       excludes #{".git" "java.policy" "export.zip" "target" fs/pref-file-name}]
                   (with-open [zip (ZipOutputStream. (io/output-stream zip-file))]
                     (doseq [f (->> (reify FilenameFilter
                                      (accept [this dir filename]
                                        (not (excludes filename))))
                                    (.listFiles dir)
                                    (mapcat file-seq))
                             :when (.isFile f)]
                       (.putNextEntry zip (ZipEntry. (fs/get-relative-path dir f)))
                       (io/copy f zip)
                       (.closeEntry zip)))
                   {:status 200
                    :headers {"Content-Type" "application/zip"}
                    :body zip-file})
    "completions" {:status 200
                   :headers {"Content-Type" "text/plain"}
                   :body (let [{:keys [ext ns context-before context-after prefix text]}
                               (->> request body-string edn/read-string)]
                           (case ext
                             ("clj" "cljc")
                             (try
                               (->> {:ns ns
                                     :context (read-string (str context-before "__prefix__" context-after))}
                                    (com/completions prefix)
                                    (map (fn [{:keys [candidate]}]
                                           {:primary-text candidate
                                            :value candidate}))
                                    (filter #(not= text (:primary-text %)))
                                    (take 50)
                                    vec
                                    pr-str)
                               (catch Exception _ "[]"))
                             "cljs"
                             (->> (concat
                                   (vals (get @dw/*cljs-info 'cljs.core))
                                   (vals (get @dw/*cljs-info ns)))
                                  (filter #(-> % :sym str
                                               (str/starts-with? prefix)))
                                  (map (fn [{{:keys [arglists doc]} :meta sym :sym}]
                                         (let [s (str sym)]
                                           {:primary-text s
                                            :value s
                                            :arglists arglists
                                            :doc doc})))
                                  (filter #(not= text (:primary-text %)))
                                  set
                                  (sort-by :sym)
                                  (take 50)
                                  vec
                                  pr-str)))}
    "tree" {:status 200
            :headers {"Content-Type" "text/plain"}
            :body (let [prefs (get-prefs request user-id project-id)
                        url (if (localhost? request)
                              "../public/"
                              (get-public-url request user-id project-id))
                        options (assoc @*options
                                  :read-only? (not (authorized? request user-id))
                                  :url url)]
                    (-> (fs/get-source-dir user-id project-id)
                        (file-node (fs/get-source-dir user-id project-id) prefs)
                        (assoc :primary-text (or (:project-name prefs) "Nightcoders"))
                        (assoc :selection (:selection prefs))
                        (assoc :options options)
                        pr-str))}
    "read-file" (when-let [f (some->> request
                                      body-string
                                      (fs/secure-file (fs/get-source-dir user-id project-id)))]
                  (when (and (.isFile f)
                             (<= (.length f) max-file-size))
                    {:status 200
                     :headers {"Content-Type" "text/plain"}
                     :body f}))
    "write-file" (when (authorized? request user-id)
                   (let [{:keys [path content]} (-> request body-string edn/read-string)]
                     (when-let [f (fs/secure-file (fs/get-source-dir user-id project-id) path)]
                       (when (and (<= (count content) max-file-size)
                                  (.exists f))
                         (spit f content)
                         {:status 200}))))
    "new-file" (when (authorized? request user-id)
                 (when-let [{:keys [path contents]} (fs/get-file-path-and-contents (body-string request))]
                   (when-let [f (fs/secure-file (fs/get-source-dir user-id project-id) path)]
                     (when (not (.exists f))
                       (.mkdirs (.getParentFile f))
                       (spit f contents)
                       (-> (fs/get-pref-file user-id project-id)
                           (fs/update-prefs {:selection path}))
                       {:status 200}))))
    "new-file-upload" (when (authorized? request user-id)
                        (let [files (-> request :params (get "files"))
                              files (if (map? files) [files] files)
                              src-dir (fs/get-source-dir user-id project-id)]
                          (doseq [{:keys [size tempfile filename]} files]
                            (when-let [f (fs/secure-file src-dir filename)]
                              (when (<= size max-file-size)
                                (io/copy tempfile f)))))
                        {:status 200})
    "rename-file" (when (authorized? request user-id)
                    (let [{:keys [from to]} (-> request body-string edn/read-string)
                          src-dir (fs/get-source-dir user-id project-id)
                          from-file (fs/secure-file src-dir from)
                          to-file (fs/secure-file src-dir to)]
                      (when (and from-file to-file)
                        (.mkdirs (.getParentFile to-file))
                        (.renameTo from-file to-file)
                        (fs/delete-parents-recursively! src-dir from-file)
                        {:status 200
                         :headers {"Content-Type" "text/plain"}
                         :body (fs/get-relative-path src-dir to-file)})))
    "delete-file" (when (authorized? request user-id)
                    (let [src-dir (fs/get-source-dir user-id project-id)]
                      (when-let [f (->> request body-string (fs/secure-file src-dir))]
                        (fs/delete-parents-recursively! src-dir f)
                        {:status 200})))
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
    "index.html" (do
                   (when (authorized? request user-id)
                     (build/move-index-html user-id project-id))
                   {:status 200
                    :body (io/input-stream (io/resource "nightlight-public/index.html"))})
    (when-let [res (or (io/resource (str "nightlight-public/" (str/join "/" leaves)))
                       (io/resource (str "public/" (str/join "/" leaves))))]
      {:status 200
       :body (io/input-stream res)})))

(defn project-routes [request path-parts]
  (let [[user-id project-id mode & leaves] path-parts]
    (when-let [[user-id project-id] (try
                                      [(Integer/valueOf user-id)
                                       (Integer/valueOf project-id)]
                                      (catch Exception _))]
      (when (fs/project-exists? user-id project-id)
        (case mode
          nil (redirect (str "/" user-id "/" project-id "/code/"))
          "code" (if (seq leaves)
                   (code-routes request user-id project-id leaves)
                   {:status 200
                    :headers {"Content-Type" "text/html"}
                    :body (-> "public/loading.html" io/resource slurp)})
          "public" (if (localhost? request)
                     (user-routes request user-id (concat [project-id mode] leaves))
                     (redirect (get-public-url request user-id project-id) 301))
          nil)))))

(defn admin-page [page]
  (let [projects (db/select-projects! page)]
    (h/html
      [:html
       [:head
        [:meta {:charset "UTF-8"}]
        [:meta {:name "viewport" :content "user-scalable=no, width=device-width, initial-scale=1.0, maximum-scale=1.0"}]
        [:title "Admin"]]
       [:body
        [:table
         [:tr
          [:th "User ID"]
          [:th "Project ID"]
          [:th "Files"]]
         (for [{:keys [id user_id]} projects]
           (let [src-dir (fs/get-source-dir user_id id)]
             [:tr
              [:td user_id]
              [:td id]
              [:td
               (for [f (file-seq src-dir)
                     :let [n (.getName f)]
                     :when (and (.isFile f)
                                (not (.endsWith n ".cljs"))
                                (not (.endsWith n ".clj"))
                                (not (.endsWith n ".cljc"))
                                (not (.endsWith n ".html"))
                                (not (.endsWith n ".css")))]
                 [:div
                  [:a {:href (str "http://" user_id ".nightcoders.net/" id "/public/"
                               (fs/get-relative-path src-dir f))}
                   n]])]]))]
        (when (seq projects)
          [:a {:href (str "/admin/" (inc page))}
           "Next"])]])))

(defn main-routes [request path-parts]
  (case (:uri request)
    "/" {:status 200
         :headers {"Content-Type" "text/html"}
         :body (-> "public/nightcoders.html" io/resource slurp)}
    "/auth" (when-let [token (body-string request)]
              (if-let [payload (some-> verifier (.verify token) .getPayload)]
                (let [user-id (fs/create-user! (.getEmail payload))
                      projects (->> (fs/get-user-dir user-id)
                                    (.listFiles)
                                    (map (fn [f]
                                           (when (.isDirectory f)
                                             (-> (io/file f fs/pref-file-name)
                                                 slurp
                                                 edn/read-string
                                                 (assoc :project-id (Integer/valueOf (.getName f)))
                                                 (assoc :url (str "/" user-id "/" (.getName f) "/code/"))
                                                 (try (catch Exception _))))))
                                    (remove nil?))
                      session {:email (.getEmail payload) :id user-id}]
                  {:status 200
                   :session session
                   :headers {"Content-Type" "text/plain"}
                   :body (pr-str (assoc session :projects projects))})
                {:status 403}))
    "/unauth" {:status 200
               :session {}}
    "/new-project" (when-let [user-id (-> request :session :id)]
                     (let [{:keys [project-name project-type]} (-> request body-string edn/read-string)]
                       (if-let [project-id (fs/create-project! user-id project-type project-name)]
                         {:status 200
                          :body (str "/" user-id "/" project-id "/code/")}
                         {:status 403
                          :body "Invalid project name."})))
    "/delete-user" (when-let [user-id (-> request :session :id)]
                     (build/stop-projects! user-id)
                     (fs/delete-children-recursively! (fs/get-user-dir user-id))
                     {:status 200})
    "/delete-project" (when-let [user-id (-> request :session :id)]
                        (let [{:keys [project-id]} (-> request body-string edn/read-string)]
                          (when (number? project-id)
                            (build/stop-project! user-id project-id)
                            (fs/delete-children-recursively! (fs/get-project-dir user-id project-id))
                            {:status 200})))
    (if (-> path-parts first (= "admin"))
      (when (-> request :session :id (= 1))
        (let [page (or (some-> path-parts second Integer/valueOf)
                       0)]
          {:status 200
           :headers {"Content-Type" "text/html"}
           :body (admin-page page)}))
      (project-routes request path-parts))))

(defn handler [request]
  (if-let [^String host (get-in request [:headers "host"])]
    (let [host-parts (str/split host #"\.")
          path-parts (filter seq (str/split (:uri request) #"/"))]
      (if (.startsWith host "localhost")
        (main-routes request path-parts)
        (case (count host-parts)
          2 (main-routes request path-parts)
          3 (user-routes request (first host-parts) path-parts)
          nil)))
    {:status 403
     :body "Something is wrong with your request! Couldn't find host header."}))

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
   (db/start-ui)
   (watch/init-watcher!)
   (when-not @*web-server
     (->> (merge {:port 0 :hosted? true} opts)
          (reset! *options)
          (run-server (-> app wrap-session wrap-multipart-params wrap-content-type wrap-gzip))
          (reset! *web-server)
          print-server))))

(defn dev-start [opts]
  (when-not @*web-server
    (.mkdirs (io/file "target" "public"))
    (-> #'handler
        (wrap-reload)
        (wrap-file "target/public")
        (start opts))))

(defn -main []
  (start {:port 3001}))
