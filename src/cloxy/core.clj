(ns cloxy.core
  (:use     [clojure.pprint :only [pprint print-table]]
            [clojure.string :only [split join]]
            [clojure.repl   :only [doc]]
            [table.core     :only [table]])
  (:require [clojure
             [string            :as str]
             [set               :as set]
             [walk              :as w]
             [xml               :as xml]]
            [clojure.java
             [shell             :as sh]
             [io                :as io]]
            [clj-http.client    :as c]
            [ring.adapter.jetty :as rj]))

;; utilities ==================================================================

(defn prns
  "Print a preview of a datastructure"
  ([                         s] (prns 2 s))
  ([depth                    s] (prns depth depth s))
  ([print-level print-length s] (binding [*print-level*  print-level
                                          *print-length* print-length]
                                  (pprint s))))

;; http cli ===================================================================

(def omdb-url "http://www.omdbapi.com/")

(defn omdb-q "Template for all omdb queries"
  [& [opts]]
  (c/request (merge {:debug  false
                     :method :get
                     :url    omdb-url}
                    opts)))

(comment
  "- omdb example:"
  (omdb-q {:query-params {"t" "True Grit", "y" "1969"}})

  "curl equivalent:"
  (sh/sh "curl" "-s" (str omdb-url "?t=True%20Grit&y=1969"))

  "- fake server example"
  (sh/sh "curl" "-s" "http://localhost:9090?t=True%20Grit&y=1969"))

;; http server ================================================================

(defn- show
  [x]
  (println)
  (println "show")
  (println (str "type="(type x)))
  (pprint x)
  x)

(defn wrap-debug "A middleware that debugs the request."
  [handler]
  (fn [request]
    (println "-------")
    (pprint  request)
    (handler request)))

(defn- response "Takes a body as a string, return the response body (string)"
  [body-str] (-> body-str
                 read-string
                 eval
                 str))

(defn- response "Takes a body as a string, return the response body (string)"
  [body-str] (str "hello world !, date=" (java.util.Date.)))

(defn handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (-> request
                :body
                slurp
                response)})

(def app
  (wrap-debug handler))

(defonce jetty-server
  (rj/run-jetty app {:port 3009
                     :join? false}))

(defn start   [] (.start jetty-server))
(defn stop    [] (.stop  jetty-server))
(defn restart [] (stop) (start))

(comment
  (start)
  (stop)
  (restart))

(comment "Usage:"
         "In a shell run:
curl http://localhost:3009/ -X POST -d '(with-out-str (print (range 100)))'")

