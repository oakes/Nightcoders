(require
  '[nightlight.core :as nightlight]
  '[figwheel.main :as figwheel])

(nightlight/start {:port 4000 :url "http://localhost:9500"})
(figwheel/-main "--build" "dev")
