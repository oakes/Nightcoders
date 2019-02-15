(set-env!
  :source-paths #{"src"}
  :resource-paths #{"src" "resources"}
  :dependencies '[[org.clojure/clojure "1.10.0" :scope "provided"]
                  [adzerk/boot-cljs "2.1.5" :scope "test"]
                  [adzerk/boot-reload "0.6.0" :scope "test"]
                  [pandeiro/boot-http "0.8.3" :scope "test"]
                  [javax.xml.bind/jaxb-api "2.3.0"] ; necessary for Java 9 compatibility
                  [nightlight "RELEASE"]
                  %s])

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
    (serve :dir "target/nightcoders" :port 3001)
    (dev)
    (nightlight :port 4000 :url "http://localhost:3001")))

(deftask build []
  (comp (cljs :optimizations :advanced) (target)))

