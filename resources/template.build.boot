(set-env!
  :source-paths #{"src"}
  :resource-paths #{"src" "resources"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-2" :scope "test"]
                  [adzerk/boot-reload "0.4.13" :scope "test"]
                  [pandeiro/boot-http "0.7.3" :scope "test"]
                  [nightlight "1.3.3"]
                  %s])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]]
  '[nightlight.boot :refer [nightlight]]
  '[clojure.java.io :as io])

(deftask dev []
  (comp
    (watch)
    (reload :asset-path "nightcoders" :cljs-asset-path ".")
    (with-pass-thru _
      (when (.exists (io/file "java.policy"))
        (System/setProperty "java.security.policy" "java.policy")
        (System/setSecurityManager (proxy [SecurityManager] []))))
    (cljs :source-map true :optimizations :none)
    (target)))

(deftask run []
  (comp
    (serve :dir "target/nightcoders" :port 3000)
    (dev)
    (nightlight :port 4000 :url "http://localhost:3000")))

(deftask build []
  (comp (cljs :optimizations :advanced) (target)))

