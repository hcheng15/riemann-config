; -*- mode: clojure; -*-
; vim: filetype=clojure
(require '[clj-http.client :as client] 
		 '[cheshire.core :as json]
		 '[riemann.query :as query])

(def version "1.0.0")
(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

(include "alerta.clj")

; configure the various servers that we listen on
(tcp-server)
(udp-server)
(ws-server)
(repl-server)
; listen on the carbon protocol
(graphite-server :host "0.0.0.0"
:port 3003
:protocol :udp
; assume that all incoming carbon metrics have a name in the form
; ENV.GRID.CLUSTER.HOSTNAME.SERVICE
:parser-fn (fn [{:keys [service] :as event}]
			(if-let [[env grid cluster host real-service]
					   (clojure.string/split service #"\.")]
				{:host (clojure.string/replace host #"_" ".")
				 :service real-service
				 :tags ["riemann:true" (str "environment:" env) (str "cluster:" cluster) (str "service:" grid)]}))
)

; reap expired events every 10 seconds
(periodically-expire 10)

; some helpful functions
(defn now []
	(let [now (java.util.Date.)]
		(Math/floor (/ (.getTime now) 1000))))

(defn service-is [service e] (= service (get e :service "")))

(defn switch-epoch-to-elapsed
	[& children]
	(fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))

(defn add-description
	[description & children]
	(fn [e] (apply with :description description children)))

(defn puppet-failed-description [e]
	(format "Puppet has not run for host %s" (:host e)))

(defn gu-transform [f & children]
	(fn [event] (let [transformed-event (f event)]
		(call-rescue transformed-event children))))

(defn puppet_update_fail []
	(let [total-puppets (:metric (first (.search (:index @core) (query/ast "service=\"pup_res_total\""))))]
	{:state "warning" :description (format "Puppet agent failed to update $pup_res_failed out of %d" total-puppets)}))

; thresholding
(let [index (default :ttl 300 (update-index (index)))
		dedup-alert (changed-state {:init "normal"} prn alerta)
		informational (with :state "normal" dedup-alert)
		normal (with :state "normal" dedup-alert)
		warning (with :state "warning" dedup-alert)
		major (with :state "major" dedup-alert)
		critical (with :state "critical" dedup-alert)]
	(streams
		index)

	(streams
		(throttle 1 30 heartbeat))

	(streams
		(let [hosts (atom #{})]
			(fn [event]
				(swap! hosts conj (:host event))
				(index {:service "unique hosts"
						:time (unix-time)
						:metric (count @hosts)}))))

	(streams
		(where* 
			(fn [e] 
				(let [boot-threshold (- (now) 7200)]
					(and (service-is "boottime" e) (> (:metric e) boot-threshold))))
			informational))

	(streams
		(where* (partial service-is "heartbeat")
			(splitp < metric
				90 critical
				normal)))

	(streams
		(where* (partial service-is "pup_last_run")
			(let [last-run-threshold (- (now) 7200)
				time-elapsed (fn [e] (- (now) (:metric e)))] 
				(splitp > metric
					last-run-threshold 
						(switch-epoch-to-elapsed 
							major)
					(switch-epoch-to-elapsed
						normal)))))

	(streams
		(by [:host :service]
			(where* (partial service-is "pup_res_failed")
				(splitp < metric
					0 (add-description  "Puppet resources are failing" warning)
					(add-description "Puppet is updating all resources" normal)))))

	(streams
		(by [:host]	
			(moving-time-window 15

				(smap (fn [events]
					(let [success-service-name "gu_200_ok_request_status_rate-frontend"
						error-service-name "gu_js_diagnostics_rate-frontend"
						service-filter (fn [service-name event] (and (:metric event) (= service-name (:service event))))
						sorted-events (->> events (sort-by :time) reverse)
						last-success (->> sorted-events (filter (partial service-filter success-service-name)) first)
						last-error (->> sorted-events (filter (partial service-filter error-service-name)) first)
						threshold 0.1]
						(if (and last-success last-error)
							(let [ratio (double (/ (:metric last-error) (+ (:metric last-success) (:metric last-error))))
									new-event {:host "riemann" :service "frontend_js_error_ratio" :metric ratio}]
								(do
									(info
										(format "Events seen %d; ratio %f; status %s"
											(count events)
											ratio
											(if (> ratio threshold) "bad" "okay")))
									(if (> ratio threshold)
										(call-rescue new-event (list critical))
										(call-rescue new-event (list normal)))))))))))))

