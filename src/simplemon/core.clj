(ns simplemon.core
  (:gen-class)
  (:require [incanter.core :as icore]
            [incanter.charts :as ichart]
            [overtone.at-at :as ovat]
            [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]
            [org.httpkit.client :as http-client]
            [org.httpkit.server :as http-server]
            [clojure.tools.cli :refer [parse-opts]]))

(def data (atom []))
(def target-url (atom ""))
(def at-pool (ovat/mk-pool))

(def chart-file (java.io.File/createTempFile "simplemon" ".png"))
(def chart-path (.getPath chart-file))
(.deleteOnExit chart-file)


(defn update-chart [data chart-path]
  (-> (ichart/time-series-plot
       :time
       :latency
       :x-label "date"
       :y-label "latency"
       :title (str "simplemon - " @target-url)
       :data (icore/to-dataset data))
      (icore/save chart-path :width 1000)))

(defn on-execute [target]
  (let [start (time/now)
        start-milli (coerce/to-long start)
        {:keys [status headers body error] :as resp} @(http-client/get
                                                      target
                                                      {:timeout 2000})]
    (if error
      (do
        (println "request error")
        (swap! data #(conj % {:time start-milli :latency -10})))
      (let [runtime (time/in-millis (time/interval start (time/now)))]
        (println "request took " runtime "ms")
        (swap! data #(conj % {:time start-milli :latency runtime}))))
    (update-chart @data chart-path)))

(defn run-scheduler [target interval]
  (reset! target-url target)
  (ovat/every interval #(on-execute target) at-pool))

(defn slurpb [is]
  "Convert an input stream is to byte array"
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (let [ba (byte-array 2000)]
      (loop [n (.read is ba 0 2000)]
        (when (> n 0)
          (.write baos ba 0 n)
          (recur (.read is ba 0 2000))))
      (.toByteArray baos))))

(defn app [req]
  {:status 200
   :headers {"Content-Type" "image/png"}
   :body (io/input-stream chart-path)})

(defn run-server
  ([] (run-server 8080))
  ([port]
   (http-server/run-server #(app %) {:port port})))

(def cmd-opts
  [["-p" "--port PORT" "Port Number" :default 8080 :parse-fn #(Integer/parseInt %)]
   ["-t" "--target TARGET" "Target url" :validate [#(string? %)]]
   ["-i" "--interval INTERVAL" "Update Interval" :default 60000 :parse-fn #(* 1000 (Integer/parseInt %))]
   ["-h" "--help"]])

(defn -main
  [& args]
  (println "starting up")
  (let [opts (parse-opts args cmd-opts)
        opts2 (:options opts)]
    (when (:help opts2)
      (println (:summary opts))
      (System/exit 0))
    (if (:errors opts)
      (do
        (println "Errors:")
        (doall (map println (:errors opts)))
        (println (:summary opts))
        (System/exit 1))
      (do
        (run-scheduler (:target opts2) (:interval opts2))
        (run-server (:port opts2))))))
