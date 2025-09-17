(ns memory-leak-investigation
  (:require [next.jdbc :as jdbc]
            [xtdb.node :as xtn])
  (:import [java.sql Connection]))

(def large-query
  "SELECT * FROM GENERATE_SERIES(1, 10000000) AS l(n) 
   LEFT JOIN GENERATE_SERIES(1, 10000000) 
   AS r(m) ON n = m;")

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

(defn run-and-wait-query
  ([node] (run-and-wait-query node 1000))
  ([node wait-ms]
   (let [connection-builder (.createConnectionBuilder node)]
     (with-open [^Connection conn (.build connection-builder)
                  stmt (.createStatement conn)
                  rs (.executeQuery stmt large-query)]
       (.setFetchSize stmt 100)
       (prn "Waiting..." wait-ms)
       (Thread/sleep wait-ms)
       (prn "Done waiting")
       (loop [i 0 acc []]
         (if (and (< i 10) (.next rs))
           (recur (inc i) (conj acc (.getString rs 1))) ; or any column accessor
           acc))))))

(defn stress-queries
  [node n wait-ms]
  (doall
   (for [i (range n)]
     (future
       (println "Starting query" i)
       (try
         (run-and-wait-query node wait-ms)
         (catch Exception e
           (println "Query" i "failed:" (.getMessage e)))
         (finally
           (println "Finished query" i)))))))


(comment

  (def node (->node))

  (run-query node)
  
  (run-and-cancel-query node)

  (run-and-cancel-query node 1000)

  (run-and-wait-query node 120000)
  
  ;; when done/resetting
  
  (.close node)
  )

