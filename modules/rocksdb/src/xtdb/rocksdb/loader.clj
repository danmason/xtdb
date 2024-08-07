(ns ^{:clojure.tools.namespace.repl/load false}
    xtdb.rocksdb.loader
  (:require [clojure.java.io :as io]
            [xtdb.io :as xio])
  (:import org.rocksdb.util.Environment
           org.rocksdb.NativeLibraryLoader))

(defn- load-rocksdb-native-lib []
  (let [tmp (doto (io/file (System/getProperty "java.io.tmpdir") "xtdb_rocksdb-6.12.7") .mkdirs)
        library (io/file tmp (Environment/getJniLibraryFileName "rocksdb"))]
    (.loadLibrary (NativeLibraryLoader/getInstance) (str tmp))
    (str library)))

(defonce rocksdb-library-path (load-rocksdb-native-lib))
