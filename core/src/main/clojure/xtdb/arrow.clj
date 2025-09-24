(ns xtdb.arrow
  "Utilities for working with Arrow things"
  (:require xtdb.mirrors.time-literals
            [xtdb.serde :as serde]
            xtdb.serde.types
            [xtdb.trie :as trie]
            [xtdb.util :as util])
  (:import (java.nio.file Files)
           [java.util Spliterators]
           (org.apache.arrow.memory BufferAllocator RootAllocator)
           (xtdb.arrow NullVector Relation) 
           (xtdb.trie MemoryHashTrie)))

(defn read-arrow-stream-file
  ([path-ish]
   (with-open [al (RootAllocator.)]
     (read-arrow-stream-file al path-ish)))

  ([^BufferAllocator al, path-ish]
   (with-open [r (Relation/openFromArrowStream al (Files/readAllBytes (util/->path path-ish)))]
     {:schema (.getSchema r)
      :batch (.getAsMaps r)})))

(defn with-arrow-file
  ([path-ish f]
   (with-open [al (RootAllocator.)]
     (with-arrow-file al path-ish f)))

  ([^BufferAllocator al, path-ish f]
   (with-open [loader (Relation/loader al (util/->path path-ish))
               cursor (.openCursor loader al)]
     (f {:schema (.getSchema loader)
         :batches (->> (iterator-seq (Spliterators/iterator cursor))
                       (map (fn [^Relation rel]
                              (serde/->clj-types (.getAsMaps rel)))))}))))

(defn read-arrow-file
  ([al path-ish]
   (with-arrow-file al path-ish
     (fn [res]
       (update res :batches vec))))

  ([path-ish]
   (with-arrow-file path-ish
     (fn [res]
       (update res :batches vec)))))

(defn read-hash-trie-file [path-ish]
  (trie/<-MemoryHashTrie (MemoryHashTrie/fromProto (Files/readAllBytes (util/->path path-ish)) (NullVector. "_iid" 0))))

