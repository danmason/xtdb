(ns xtdb.otel
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [xtdb.node :as xtn])
  (:import (io.micrometer.tracing.otel.bridge OtelTracer OtelCurrentTraceContext OtelTracer$EventPublisher)
           (io.opentelemetry.api.common Attributes)
           (io.opentelemetry.sdk OpenTelemetrySdk)
           (io.opentelemetry.sdk.resources Resource)
           (io.opentelemetry.sdk.trace SdkTracerProvider)
           (io.opentelemetry.sdk.trace.export SimpleSpanProcessor)
           (io.opentelemetry.exporter.otlp.trace OtlpGrpcSpanExporter)
           (io.opentelemetry.semconv ResourceAttributes)
           (xtdb.api Xtdb$Config)
           (xtdb.api.metrics OtelConfig)))

(defn noop-event-publisher
  []
  (reify OtelTracer$EventPublisher
    (publishEvent [_ _])))

(defn create-tracer
  [{:keys [enabled? endpoint service-name span-processor]
    :or {enabled? false
         service-name "xtdb"}}]
  (when enabled?
    (try
      (let [;; Create a Resource with the service name
            resource (-> (Resource/getDefault)
                         (.merge (Resource/create
                                   (-> (Attributes/builder)
                                       (.put ResourceAttributes/SERVICE_NAME service-name)
                                       (.build)))))

            processor (or span-processor
                          (let [builder (cond-> (OtlpGrpcSpanExporter/builder)
                                          endpoint (.setEndpoint endpoint))]
                            (SimpleSpanProcessor/create (.build builder))))

            tracer-provider (-> (SdkTracerProvider/builder)
                                (.setResource resource)
                                (.addSpanProcessor processor)
                                (.build))

            otel-sdk       (-> (OpenTelemetrySdk/builder)
                               (.setTracerProvider tracer-provider)
                               (.build))

            otel-tracer    (.getTracer otel-sdk "xtdb")

            current-ctx    (OtelCurrentTraceContext.)
            event-pub      (noop-event-publisher)]

        (log/infof "OpenTelemetry tracer created for service: %s" service-name)

        (OtelTracer. otel-tracer current-ctx event-pub))

      (catch Exception e
        (log/warnf e "Failed to create OpenTelemetry tracer, tracing will be disabled")
        nil))))



(defmethod xtn/apply-config! :xtdb/otel [^Xtdb$Config config _
                                                      {:keys [enabled? endpoint service-name]}]
  (.otel config
         (cond-> (OtelConfig.)
           (some? enabled?) (.enabled enabled?)
           endpoint (.endpoint endpoint)
           service-name (.serviceName service-name))))

(defmethod ig/expand-key :xtdb/otel [k ^OtelConfig config]
  {k {:enabled? (.getEnabled config)
      :endpoint (.getEndpoint config)
      :service-name (.getServiceName config)
      :config (ig/ref :xtdb/config)}})

(defmethod ig/init-key :xtdb/otel [_ {:keys [enabled? endpoint service-name] {:keys [node-id]} :config}]
  (let [service-name (or service-name (str "xtdb-" node-id))]
    (create-tracer {:enabled? enabled?
                    :endpoint endpoint
                    :service-name service-name})))

(defmethod ig/halt-key! :xtdb/otel [_ tracer]
  ;; Tracer cleanup is handled by the underlying SDK
  nil)
