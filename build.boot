(set-env!
  :dependencies '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                  [adzerk/boot-reload "0.5.2" :scope "test"]
                  [org.clojure/test.check "0.9.0" :scope "test"]
                  [org.clojars.oakes/boot-tools-deps "0.1.4" :scope "test"]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[boot-tools-deps.core :refer [deps]])

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
  (comp
    (deps)
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
    (deps)
    (cljs :optimizations :advanced)
    (aot) (pom) (uber) (jar) (sift) (target)))

