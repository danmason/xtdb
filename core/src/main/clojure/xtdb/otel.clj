(ns xtdb.otel
  "OpenTelemetry tracing support for XTDB using clj-otel"
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [steffan-westcott.clj-otel.sdk.otel-sdk :as otel-sdk]
            [steffan-westcott.clj-otel.exporter.otlp.grpc.trace :as otlp-grpc]
            [xtdb.node :as xtn])
  (:import (xtdb.api Xtdb$Config)
           (xtdb.api.metrics OtelConfig)))

(defn create-otel-sdk
  "Create and configure an OpenTelemetry SDK instance.

  Options:
    :enabled? - Enable tracing (default: false)
    :endpoint - OTLP endpoint (e.g., 'http://localhost:4317')
    :service-name - Service name for traces (default: 'xtdb')"
  [{:keys [enabled? endpoint service-name]}]
  (when enabled?
    (let [span-exporter (otlp-grpc/span-exporter {:endpoint endpoint})
          sdk (otel-sdk/init-otel-sdk!
               service-name
               {:tracer-provider {:span-processors [{:exporters [span-exporter]}]}})]
      (log/infof "OpenTelemetry SDK created for service: %s" service-name)
      sdk)))

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
      :service-name (.getServiceName config)}})

(defmethod ig/init-key :xtdb/otel [_ {:keys [enabled? endpoint service-name]}]
  (create-otel-sdk {:enabled? enabled?
                    :endpoint endpoint
                    :service-name service-name}))

(defmethod ig/halt-key! :xtdb/otel [_ sdk]
  (when sdk
    (otel-sdk/close-otel-sdk! sdk)
    (log/info "OpenTelemetry SDK shut down")))
