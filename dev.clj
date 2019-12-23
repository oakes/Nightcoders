(require
  '[orchestra.spec.test :as st]
  '[expound.alpha :as expound]
  '[clojure.spec.alpha :as s]
  '[figwheel.main :as figwheel]
  '[nightcoders.core :as nightcoders])

(st/instrument)
(alter-var-root #'s/*explain-out* (constantly expound/printer))
(nightcoders/dev-start {:port 3000})
(figwheel/-main "--background-build" "loading" "--build" "dev")
