(ns xtdb.test-mmap
  (:require [clojure.tools.logging :as log]
            [xtdb.node :as xtn]
            [xtdb.util :as util]
            [xtdb.db-catalog :as db-cat])
  (:import (java.nio ByteBuffer) 
           (java.util Random)
           (xtdb.database Database)))

(defn generate-random-byte-buffer ^ByteBuffer [buffer-size]
  (let [random (Random.)
        byte-buffer (ByteBuffer/allocate buffer-size)]
    (loop [i 0]
      (if (< i buffer-size)
        (do
          (.put byte-buffer (byte (.nextInt random 128)))
          (recur (inc i)))
        (.flip byte-buffer)))))

(defn create-500mb-buffer []
  (generate-random-byte-buffer (* 500 1024 1024)))

(defn run-test [node-opts] 
  (with-open [node (xtn/start-node node-opts)]
    (log/info "XTDB node started")

    (log/info "Waiting 1 minute for caches to warm up...")
    (Thread/sleep 60000)

    (let [^Database db (db-cat/primary-db node)
          buffer-pool (.getBufferPool db)
          test-path (util/->path "test-500mb.bin")
          test-buffer (create-500mb-buffer)]

      (log/info "Putting 500MB ByteBuffer into buffer pool...")
      (.putObject buffer-pool test-path test-buffer)
      (log/info "Successfully stored ByteBuffer")

      (log/info "Retrieving ByteBuffer from buffer pool...")
      (let [retrieved-data (.getByteArray buffer-pool test-path)]
        (log/info (format "Retrieved %d bytes" (count retrieved-data))))

      (Thread/sleep 10000)

      (log/info "Multiple retrievals from buffer pool...")
      (dotimes [i 5]
        (let [slice-data (.getByteArray buffer-pool test-path)]
          (log/info (format "Retrieval %d: %d bytes" (inc i) (count slice-data)))
          (Thread/sleep 10000)))

      ;; Keep running to observe memory patterns
      (try
        (while true
          (Thread/sleep 30000)
          (log/info "Still running - memory cache active"))
        (catch InterruptedException _
          (log/info "Test stopped"))))))
