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
    :dependencies (into '[[org.clojure/clojure "1.10.1" :scope "provided"]
                          [adzerk/boot-cljs "2.1.5" :scope "test"]
                          [adzerk/boot-reload "0.6.0" :scope "test"]
                          [pandeiro/boot-http "0.8.3" :scope "test"]
                          [javax.xml.bind/jaxb-api "2.3.0"] ; necessary for Java 9 compatibility
                          [nightlight "RELEASE"]]
                    dependencies)))

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]]
  '[nightlight.boot :refer [nightlight sandbox]])

(deftask dev []
  (comp
    (watch)
    (reload :asset-path "nightcoders" :cljs-asset-path ".")
    (sandbox :file "java.policy")
    (cljs :source-map true :optimizations :none :compiler-options {:asset-path "main.out"})
    (target)))

(deftask run []
  (comp
    (serve :dir "target/nightcoders" :port 9500)
    (dev)
    (nightlight :port 4000 :url "http://localhost:9500")))

(deftask build []
  (comp (cljs :optimizations :advanced) (target)))

