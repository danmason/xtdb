(ns memory-leak-investigation
  (:require [next.jdbc :as jdbc]
            [xtdb.node :as xtn])
  (:import [java.sql Connection]))

(def large-query
  "SELECT * FROM GENERATE_SERIES(1, 10000000) AS l(n) 
   LEFT JOIN GENERATE_SERIES(1, 10000000) 
   AS r(m) ON n = m 
   ORDER BY n
   LIMIT 100;")

(defn ->node []
  (xtn/start-node {:storage [:local {:path "dev/oom-investigation/objects"}]
                   :log [:local {:path "dev/oom-investigation/log"} ]
                   :server {:port 5432
                            :host "*"}
                   :healthz {:port 8080
                             :host "*"}}))
(defn run-query [node]
  (let [connection-builder (.createConnectionBuilder node)]
    (with-open [^Connection conn (.build connection-builder)]
      (let [result (jdbc/execute! conn [large-query])]
        (println "First ten results of Query:" (take 10 result))))))

(defn run-and-cancel-query
  ([node] (run-and-cancel-query node 100))
  ([node sleep-ms]
   (let [connection-builder (.createConnectionBuilder node)]
     (with-open [^Connection conn (.build connection-builder)]
       (let [stmt (.createStatement conn)]
         (future
           (Thread/sleep sleep-ms)
           (.cancel stmt))
         (try
           (.executeQuery stmt large-query)
           (catch Exception e
             (println "Query was cancelled:" (.getMessage e)))))))))

(comment

  (def node (->node))

  (dotimes [n 100] (run-query node))
  
  (run-and-cancel-query node)

  (run-and-cancel-query node 1000)
  
  ;; when done/resetting
  
  (.close node)
  )

;; memory usage increase in increments between connections
;; 1. verify that, try all on one connection.
;; 2. Whats keeping that around? Prepared statements perhaps?
;; 3. query.clj
;; memory node -> netty usage on tx log/object store table/blocks??

;;; TODO - local node it up
