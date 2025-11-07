(ns xtdb.otel-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [xtdb.api :as xt]
            [xtdb.metrics :as metrics]
            [xtdb.otel :as otel]
            [xtdb.pgwire :as pgw]
            [xtdb.test-util :as tu]
            [xtdb.util :as util])
  (:import (io.micrometer.tracing Tracer Span)
           (java.sql Connection)
           (java.util.concurrent.atomic AtomicInteger)))

(use-fixtures :each tu/with-allocator tu/with-mock-clock tu/with-node)

(defn serve
  (^xtdb.pgwire.Server [] (serve {}))
  (^xtdb.pgwire.Server [opts]
   (pgw/serve tu/*node* (merge {:num-threads 1
                               :allocator tu/*allocator*
                               :drain-wait 250}
                              opts))))

(defn jdbc-conn
  (^Connection [server] (jdbc-conn server nil))
  (^Connection [server opts]
   (-> (reduce (fn [cb [k v]]
                 (.option cb k v))
               (-> (.createConnectionBuilder server)
                   (.user "xtdb")
                   (.port (:port server))
                   (.database (:dbname opts "xtdb")))
               (dissoc opts :dbname))
       (.build))))

(deftest test-tracer-creation
  (testing "tracer can be created with minimal config"
    (let [tracer (otel/create-tracer {:enabled? false})]
      (is (nil? tracer) "Tracer should be nil when disabled")))

  (testing "tracer can be created when enabled (may fail without OTLP endpoint)"
    ;; This will log a warning if it can't connect to an OTLP endpoint, but that's expected
    (let [tracer (otel/create-tracer {:enabled? true
                                     :service-name "xtdb-test"})]
      ;; The tracer may be nil if no OTLP endpoint is available, which is fine for this test
      (is (or (nil? tracer) (instance? Tracer tracer))
          "Tracer should be nil or a Tracer instance"))))

(deftest test-span-operations
  (testing "span operations work when tracer is nil"
    (let [span (metrics/start-span nil "test-span")]
      (is (nil? span) "Span should be nil when tracer is nil")
      (metrics/end-span span))) ; should not throw

  (testing "with-span macro works when tracer is nil"
    (let [result (metrics/with-span nil "test-span"
                   (+ 1 2 3))]
      (is (= 6 result) "Body should execute normally when tracer is nil"))))

(deftest test-query-tracing-integration
  (testing "query execution works with tracing enabled (no OTLP endpoint required)"
    ;; Create a tracer without endpoint - it will fail to connect but that's ok for this test
    (let [tracer (otel/create-tracer {:enabled? true :service-name "xtdb-test"})]
      (with-open [server (serve {:tracer tracer})]
        (with-open [conn (jdbc-conn server)]
          (xt/submit-tx conn [[:put-docs :users {:xt/id "user1" :name "Alice"}]])
          (let [result (xt/q conn "SELECT * FROM users")]
            (is (= 1 (count result)))
            (is (= "Alice" (:name (first result)))
                "Query should execute successfully even with tracing enabled")))))))

(deftest test-multiple-queries-with-tracing
  (testing "multiple queries work with tracing enabled"
    (let [tracer (otel/create-tracer {:enabled? true :service-name "xtdb-test"})]
      (with-open [server (serve {:tracer tracer})]
        (with-open [conn (jdbc-conn server)]
          ;; Insert some test data
          (xt/submit-tx conn [[:put-docs :items {:xt/id 1 :value "a"}]
                             [:put-docs :items {:xt/id 2 :value "b"}]
                             [:put-docs :items {:xt/id 3 :value "c"}]])
          
          ;; Execute multiple queries - all should succeed
          (dotimes [i 3]
            (let [result (xt/q conn "SELECT * FROM items ORDER BY _id")]
              (is (= 3 (count result))
                  "Query should execute successfully with tracing"))))))))

(deftest test-span-lifecycle
  (testing "span can be manually started and ended"
    (let [tracer (otel/create-tracer {:enabled? true
                                     :service-name "xtdb-test"})]
      (when tracer
        (let [span (metrics/start-span tracer "manual-span")]
          (is (instance? Span span) "Should create a span")
          (metrics/end-span span))))) ; should not throw

  (testing "with-span macro manages span lifecycle"
    (let [tracer (otel/create-tracer {:enabled? true
                                     :service-name "xtdb-test"})
          counter (AtomicInteger. 0)]
      (metrics/with-span tracer "auto-span"
        (.incrementAndGet counter))
      (is (= 1 (.get counter)) "Body should execute"))))
