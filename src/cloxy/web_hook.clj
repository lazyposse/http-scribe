(ns #^{:doc "HTTP entry point for this program"}
  cljsta.web-hook
  (:use [ring.adapter.jetty     :only [run-jetty]]
        [ring.middleware.params :only [wrap-params]]
        [clojure.tools.trace    :only [trace]]
        [clojure
         [pprint                :only [pprint pp]]
         [repl                  :only [doc find-doc apropos]]]
        [clojure.java.javadoc   :only [javadoc]]
        [clojure.tools.trace    :only [trace deftrace trace-forms trace-ns untrace-ns trace-vars]]
        [table.core             :only [table]])
  (:require [clojure.string                       :as s]
            [clojure.java.io                      :as io]
            [clj-http.client                      :as c]
            [ring.middleware.basic-authentication :as a]
            [ring.adapter.jetty                   :as rj]))

(defn show
  [x]
  (println)
  (println "show")
  (println (str "type="(type x)))
  (pprint x)
  x)

(defn- response "Takes a body as a string, return the response body (string)"
  [body-str] (-> body-str
                 read-string
                 eval
                 str))

(defn handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (-> request
                :body
                slurp
                response)})

(def jetty-server
  (rj/run-jetty handler {:port  3009
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

