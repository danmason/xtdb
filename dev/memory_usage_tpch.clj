(ns memory-usage-tpch
  (:require [next.jdbc :as jdbc]
            [xtdb.datasets.tpch :as tpch]))

(defn ->connection []
  (jdbc/get-connection {:dbname "xtdb"
                        :host "localhost"
                        :port 5432
                        :classname "xtdb.jdbc.Driver"
                        :dbtype "xtdb"}))

(defn submit-and-query-tpch []
  ;; submit TPCH SF 0.5
  (with-open [conn (->connection)]
    (tpch/submit-dml-jdbc! conn 0.5))

  ;; followed by these queries:
  (with-open [conn (->connection)]
    (jdbc/execute! conn ["select count(*) from orders FOR VALID_TIME ALL;"])
    (jdbc/execute! conn ["select count(*) from partsupp;"])
    (jdbc/execute! conn ["select count(*) from lineitem;"])))

(comment
  
  (submit-and-query-tpch)
  
  )
