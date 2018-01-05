(set-env!
  :dependencies '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                  [adzerk/boot-reload "0.5.2" :scope "test"]
                  [org.clojure/test.check "0.9.0" :scope "test"]
                  [javax.xml.bind/jaxb-api "2.3.0" :scope "test"]
                  [seancorfield/boot-tools-deps "0.1.4" :scope "test"]])

(require
  '[clojure.edn :as edn]
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[boot-tools-deps.core :refer [deps]])

(task-options!
  pom {:project 'nightcoders
       :version "1.0.0-SNAPSHOT"
       :description "A cloud IDE for ClojureScript"
       :url "https://github.com/oakes/Nightcoders.net"
       :dependencies (->> "deps.edn"
                          slurp
                          edn/read-string
                          :deps
                          (reduce
                            (fn [deps [artifact info]]
                              (if-let [version (:mvn/version info)]
                                (conj deps
                                  (transduce cat conj [artifact version]
                                    (select-keys info [:scope :exclusions])))
                                deps))
                            []))}
  sift {:include #{#"\.jar$"}}
  aot {:namespace '#{nightcoders.core}}
  jar {:main 'nightcoders.core
       :file "nightcoders.jar"})

(deftask run []
  (comp
    (deps :aliases [:cljs])
    (watch)
    (reload :asset-path "public")
    (cljs :source-map true :optimizations :none)
    (with-pass-thru _
      (require
        '[clojure.spec.test.alpha :refer [instrument]]
        '[nightcoders.core :refer [dev-start]])
      ((resolve 'instrument))
      ((resolve 'dev-start) {:port 3000}))
    (target)))

(deftask build []
  (comp
    (deps :aliases [:cljs])
    (cljs :optimizations :advanced)
    (aot) (pom) (uber) (jar) (sift) (target)))

