(set-env!
  :source-paths #{"src"}
  :resource-paths #{"src" "resources"}
  :dependencies '[[org.clojure/clojure "1.9.0" :scope "provided"]
                  [adzerk/boot-cljs "2.1.4" :scope "test"]
                  [adzerk/boot-reload "0.5.2" :scope "test"]
                  [pandeiro/boot-http "0.8.3" :scope "test"]
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
    (serve :dir "target/nightcoders" :port 3000)
    (dev)
    (nightlight :port 4000 :url "http://localhost:3000")))

(deftask build []
  (comp (cljs :optimizations :advanced) (target)))

