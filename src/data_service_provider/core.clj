(ns data-service-provider.core
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [ring.adapter.jetty :as jetty]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [utility.utilities :as util]
            [meander.epsilon :as meander]
            [data-transformation.cache :as cache]
            [data-transformation.transform :as dt]
            [clj-http.client :as client]))

(def analytics-schema (-> "./resources/analytics-schema.edn" slurp read-string))

(defn- validate-schema
  ([payload]
   (let [schema-id (get payload :analytics-type :default)]
     (validate-schema payload (keyword schema-id))))
  ([payload schema-id]
   (m/validate
     (m/schema (get analytics-schema schema-id))
     payload)))

;; Handler functions will return the response
(defn data-load-handler [request]
  (println "In data load of Data service provider")
  (let [received-data (-> request :body slurp util/json->map)
        schema-validation (validate-schema received-data)
        _ (println "Schema validation " schema-validation)
        request-body (slurp "./resources/processed-data.json")]
    (client/post "http://localhost:8081/on_data_load"
                 {:body   request-body
                  :accept :json})
    ;; Throw error messages if schema validation fails
    #_(if true
        {:status  200
         :headers {"Content-Type" "application/json"}
         :body    (-> {:ack {:message "Order book received"}}
                      util/map->json)}
        {:status  400
         :headers {"Content-Type" "application/json"}
         :body    (-> {:code "101" :path "/data_load" :message "Invalid Schema"}
                      util/map->json)}
        )
    ))

(defn- transform-to-ondc-schema
  "Given a payload, will transform the
  data into the ONDC schema format required for further
  analysis using mapper identifier. If mapper identifier
  is not found, default mapper will be executed."
  ([payload]
   (transform-to-ondc-schema payload :default))
  ([payload mapper-id]
   (let [ex-txf (-> payload (dt/execute-transformation mapper-id))
         transformed-data (apply merge-with into ex-txf)]
     (-> {:analytics-type mapper-id}
         (merge transformed-data)
         util/map->json))))

(let [mapper-identifiers {"demand forecast" :demand-forecast}]
  (defn data-analytics-handler
    [request]
    (println "In ingest data")
    (let [request-data (some-> request :body slurp util/json->map)
          store-id (:storefrontId request-data)
          analytics-type (:analyticsType request-data)
          mapper-id (get mapper-identifiers (.toLowerCase analytics-type) :default)
          _ (if (nil? store-id)
              (throw (Exception. "Unable to find the store id")))
          ;; get the order data based on the storefront id
          order-data (-> (str "http://localhost:8081/storefront/" store-id "/orders")
                         client/get
                         :body
                         util/json->map)]
      ;; DONE : Transform the data as per the schema
      ;; push the retrieve order data to /data_load API
      ;; TODO : this should be an asynchronous call
      (client/post "http://localhost:8080/data_load"
                   {:body   (transform-to-ondc-schema order-data mapper-id)
                    :accept :json})
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (-> {:ack {:message "Received Order Data"}}
                    util/map->json)})))

(defn push-data-for-analytics
  [request]
  (let [request-body (-> request :body slurp util/json->map util/map->json)
        _ (client/post "http://localhost:8082/ingest_data"
                       {:body   request-body
                        :accept :json})])
  {:status  200
   :headers {"Content-Type" "application/json"}
   :body    (-> {:ack {:message "Pushed Data successfully"}}
                util/map->json)})

(defn get-store-front-orders-data
  [request]
  (println "Retrieving store front orders data")
  (let [order-schema (-> "./resources/store-orders-schema.edn" slurp read-string m/schema)
        order-data (->> (reduce
                          (fn [agg _]
                            (conj agg (mg/generate order-schema)))
                          [] (range (rand-int 20)))
                        (assoc-in {:orderBook {:orders []}} [:orderBook :orders]))]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (-> order-data
                  util/map->json)}))

(defn on-data-loader
  [request]
  (println "In on data loader. seller app")
  ;; get the analysed data from data load and will persist the data
  (let [analysed-data (-> request :body slurp)]
    (client/post "https://api.airtable.com/v0/appU8LVfAMVOsvArq/DemandForecast"
                 {:headers {"Authorization" "Bearer patGdvu9BkW9THs6j.63ad36d82e42ee833640c993d750b18f7acc1f29d753013fd4aa463cc299fbbd"
                            "Content-Type"  "application/json"}
                  :body    (util/map->json {:records (into []
                                                           (meander/search (-> (slurp "./resources/processed-data.json")
                                                                               util/json->map)
                                                                           (meander/scan {:storeFrontProductId ?id, :totalPrice ?tp, :basePrice ?bp, :foreCastedDemand ?fd})
                                                                           {:fields {:storeFrontProductId ?id, :totalPrice ?tp, :basePrice ?bp, :foreCastedDemand ?fd}}
                                                                           ))})})
    (println "Sent analysed data to airtable")
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (-> {:ack {:message analysed-data}}
                  util/map->json)}))

(defroutes tsp
           (POST "/data_load" request data-load-handler)
           (route/not-found "API path found"))

(defroutes seller-app
           (POST "/push_data_for_analytics" request push-data-for-analytics)
           (POST "/on_data_load" request on-data-loader)
           (GET "/storefront/:id/orders" params get-store-front-orders-data)
           (route/not-found "API path found"))

(defroutes collector-service
           (POST "/ingest_data" request data-analytics-handler)
           (route/not-found "API path found"))

(defn -main
  [& args]
  ;; load the mapper at beginning
  (cache/load-mappers "./resources/mappers")
  ;; tsp will run on port 8080
  (jetty/run-jetty tsp {:port  8080
                        :join? false})
  ;; seller app runs on 8081
  (jetty/run-jetty seller-app {:port  8081
                               :join? false})
  ;; collector service runs on 8082
  (jetty/run-jetty collector-service {:port  8082
                                      :join? false}))

(comment
  ;; runs the server on the port
  (jetty/run-jetty app {:port  8081
                        :join? false})

  (client/post "https://api.airtable.com/v0/appU8LVfAMVOsvArq/DemandForecast"
               {:headers {"Authorization" " Bearer patGdvu9BkW9THs6j.63ad36d82e42ee833640c993d750b18f7acc1f29d753013fd4aa463cc299fbbd"
                          "Content-Type"  "application/json"}
                :body    (util/map->json {:records (into [] (meander/search (-> (slurp "./resources/processed-data.json") util/json->map)
                                                                            (meander/scan {:storeFrontProductId ?id, :totalPrice ?tp, :basePrice ?bp, :foreCastedDemand ?fd})
                                                                            {:fields {:storeFrontProductId ?id, :totalPrice ?tp, :basePrice ?bp, :foreCastedDemand ?fd}}
                                                                            ))})}
               )

  ;curl -X POST https://api.airtable.com/v0/appU8LVfAMVOsvArq/DemandForecast    \
  ;-H "Authorization: Bearer patGdvu9BkW9THs6j.63ad36d82e42ee833640c993d750b18f7acc1f29d753013fd4aa463cc299fbbd" \
  ;-H "Content-Type: application/json" \
  ;--data '{
  ;         "records": [
  ;                     {"fields" : {
  ;                                  "storeFrontProductId": "SF-PID1",
  ;                                  "totalPrice": 133,
  ;                                  "basePrice": 140,
  ;                                  "foreCastedDemand": 27
  ;                                  }}
  ;                     ]
  ;         }'

  (do
    (require '[malli.core :as m])
    (require '[malli.generator :as mg])
    (require '[malli.provider :as mp]))

  (def order-schema (-> "./resources/store-orders-schema.edn" slurp read-string m/schema))

  (->> (reduce
         (fn [agg _]
           (conj agg (mg/generate order-schema)))
         [] (range 1))
       (assoc-in {:orderBook {:orders []}} [:orderBook :orders])
       )


  ;; seller  -> seller app ( get analytics data ) -> collector ( data collect ) -> data service provider
  ;; orders for store id -> filter PII data and extract and transform
  ;; call data load api
  ;; schema validate -> push for further analytics -> will push to db
  )