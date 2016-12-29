(set-env!
  :source-paths #{"src"}
  :resource-paths #{"src" "resources"}
  :dependencies '[[adzerk/boot-cljs "1.7.228-2" :scope "test"]
                  [adzerk/boot-reload "0.4.13" :scope "test"]
                  [nightlight "1.3.3"]
                  %s])

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]])

(deftask run []
  (comp
    (watch)
    (reload
      :asset-path "nightcoders"
      :cljs-asset-path ".")
    (cljs
      :source-map true
      :optimizations :none)
    (target)))

