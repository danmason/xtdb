(ns ^:no-doc xtdb.status)

(defprotocol Status
  (status-map [_]))

(extend-protocol Status
  Object
  (status-map [_])

  nil
  (status-map [_]))
