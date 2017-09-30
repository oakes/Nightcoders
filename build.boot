(set-env!
  :source-paths #{"src/clj" "src/cljs"}
  :resource-paths #{"src/clj" "src/cljs" "resources"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-2" :scope "test"]
                  [adzerk/boot-reload "0.4.12" :scope "test"]
                  [org.clojure/test.check "0.9.0" :scope "test"]
                  ; cljs deps
                  [org.clojure/clojurescript "1.9.908" :scope "test"]
                  [paren-soup "2.9.0" :scope "test"]
                  [reagent "0.7.0" :exclusions [cljsjs/react cljsjs/react-dom] :scope "test"]
                  [cljs-react-material-ui "0.2.34" :scope "test"]
                  [cljsjs/google-platformjs-extern "1.0.0-0" :scope "test"]
                  [nightlight "1.9.0"]
                  ; clj deps
                  [org.clojure/clojure "1.8.0"]
                  [ring "1.5.1"]
                  [http-kit "2.2.0"]
                  [javax.xml.bind/jaxb-api "2.3.0"] ; necessary for Java 9 compatibility
                  [com.google.api-client/google-api-client "1.20.0"]
                  [org.clojure/java.jdbc "0.7.0-alpha1"]
                  [com.h2database/h2 "1.4.193"]
                  [leiningen "2.7.1" :exclusions [leiningen.search]]
                  [org.eclipse.jgit/org.eclipse.jgit "4.6.0.201612231935-r"]
                  [bk/ring-gzip "0.2.1"]])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[nightcoders.core :refer [dev-start]]
  '[clojure.spec.test.alpha :refer [instrument]])

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
    (watch)
    (reload :asset-path "public")
    (cljs :source-map true :optimizations :none)
    (with-pass-thru _
      (instrument)
      (dev-start {:port 3000}))
    (target)))

(deftask build []
  (comp
    (cljs :optimizations :advanced)
    (aot) (pom) (uber) (jar) (sift) (target)))

