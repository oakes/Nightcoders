(set-env!
  :source-paths #{"src/clj" "src/cljs"}
  :resource-paths #{"src/clj" "src/cljs" "resources"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-1" :scope "test"]
                  [adzerk/boot-reload "0.4.12" :scope "test"]
                  [org.clojure/test.check "0.9.0" :scope "test"]
                  ; cljs deps
                  [org.clojure/clojurescript "1.9.229" :scope "test"]
                  [paren-soup "2.7.1" :scope "test"]
                  [reagent "0.6.0" :exclusions [cljsjs/react cljsjs/react-dom] :scope "test"]
                  [cljs-react-material-ui "0.2.34" :scope "test"]
                  [nightlight "1.4.0"]
                  ; clj deps
                  [org.clojure/clojure "1.8.0"]
                  [ring "1.5.0"]
                  [http-kit "2.2.0"]
                  [com.google.api-client/google-api-client "1.20.0"]
                  [org.clojure/java.jdbc "0.7.0-alpha1"]
                  [com.h2database/h2 "1.4.193"]
                  [leiningen "2.7.0" :exclusions [leiningen.search]]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[nightcoders.core :refer [dev-start]]
  '[clojure.spec.test :refer [instrument]])

(task-options!
  pom {:project 'nightcoders
       :version "1.0.0-SNAPSHOT"
       :description "A web-based ClojureScript IDE"
       :url "https://github.com/oakes/Nightcoders.net"}
  sift {:include #{#"\.jar$"}}
  aot {:namespace '#{nightcoders.core}}
  jar {:main 'nightcoders.core})

(deftask run []
  (comp
    (watch)
    (reload :asset-path "public")
    (cljs :source-map true :optimizations :none)
    (with-pass-thru _
      (instrument)
      (dev-start {:port 3000}))
    (target)))

(deftask build []
  (comp
    (cljs :optimizations :simple)
    (aot) (pom) (uber) (jar) (sift) (target)))

