(ns memory-usage
  (:require [xtdb.node :as xtn]))

(defn ->node []
  (xtn/start-node {:storage [:local {:path "dev/memory-usage/objects"}]
                   :log [:local {:path "dev/memory-usage/log"} ]
                   :server {:port 5432
                            :host "*"}
                   :healthz {:port 8080
                             :host "*"}}))

(comment
  
  (def node (->node))

  )
