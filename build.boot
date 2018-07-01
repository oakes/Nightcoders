(defn read-deps-edn [aliases-to-include]
  (let [{:keys [paths deps aliases]} (-> "deps.edn" slurp clojure.edn/read-string)
        deps (->> (select-keys aliases aliases-to-include)
                  vals
                  (mapcat :extra-deps)
                  (into deps)
                  (reduce
                    (fn [deps [artifact info]]
                      (if-let [version (:mvn/version info)]
                        (conj deps
                          (transduce cat conj [artifact version]
                            (select-keys info [:scope :exclusions])))
                        deps))
                    []))]
    {:dependencies deps
     :source-paths (set paths)
     :resource-paths (set paths)}))

(let [{:keys [source-paths resource-paths dependencies]} (read-deps-edn [])]
  (set-env!
    :source-paths source-paths
    :resource-paths resource-paths
    :dependencies (into '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                          [adzerk/boot-reload "0.5.2" :scope "test"]
                          [javax.xml.bind/jaxb-api "2.3.0"] ; necessary for Java 9 compatibility
                          [orchestra "2017.11.12-1" :scope "test"]]
                        dependencies)))

(require
  '[orchestra.spec.test :refer [instrument]]
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[nightlight.boot :refer [nightlight]])

(task-options!
  pom {:project 'nightcoders
       :version "1.0.0-SNAPSHOT"
       :description "A cloud IDE for ClojureScript"
       :url "https://github.com/oakes/Nightcoders.net"}
  sift {:include #{#"\.jar$"}}
  aot {:namespace '#{nightcoders.core}}
  jar {:main 'nightcoders.core
       :file "nightcoders.jar"})

(deftask run []
  (set-env! :dependencies #(into (set %) (:dependencies (read-deps-edn [:cljs]))))
  (comp
    (nightlight :port 4000)
    (watch)
    (reload :asset-path "public")
    (cljs :source-map true :optimizations :none)
    (with-pass-thru _
      (require '[nightcoders.core :refer [dev-start]])
      (instrument)
      ((resolve 'dev-start) {:port 3000}))
    (target)))

(def jar-exclusions
  ;; the standard exclusions don't work on windows,
  ;; because we need to use backslashes
  (conj boot.pod/standard-jar-exclusions
    #"(?i)^META-INF\\[^\\]*\.(MF|SF|RSA|DSA)$"
    #"(?i)^META-INF\\INDEX.LIST$"))

(deftask build []
  (set-env! :dependencies #(into (set %) (:dependencies (read-deps-edn [:cljs]))))
  (comp
    (cljs :optimizations :advanced)
    (aot) (pom) (uber :exclude jar-exclusions) (jar) (sift) (target)))

