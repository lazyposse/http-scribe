(ns cloxy.core
  (:use     [clojure.pprint :only [pprint print-table]]
            [clojure.string :only [split join]]
            [clojure.repl   :only [doc]]
            [table.core     :only [table]]
            [clojure.tools.trace  :only [trace deftrace trace-forms trace-ns
                                         untrace-ns trace-vars              ]])
  (:require [clojure
             [string            :as str]
             [set               :as set]
             [walk              :as w]
             [xml               :as xml]
             [data              :as data]]
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

(defn- get-fake-server
  [] (:body (c/get "http://localhost:8080/hi/dude")))

(defn- get-proxy
  [] (:body (c/get "http://localhost:3009")))

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
    (println "---------- Proxy received request ----------")
    (pprint  request)
    (handler request)))

(def routing
  {#"^/bobby"       "www.google.com"
   #"^/fake-server" "localhost:8080/foo/bar"
   #"^/o"           "www.omdbapi.com"})

(comment "Demo of the omdbapi proxied (results must be the same):"
         (pprint (:body (c/get "http://www.omdbapi.com/?t=True%20Grit&y=1969"  {:as :json})))
         (pprint (:body (c/get "http://localhost:3009/o/?t=True%20Grit&y=1969" {:as :json}))))

(defn get-route-entry "Takes a request and a routing map, if the uri of the request match with one of the keys of the routing map, then return the pair uri/replacement-url"
  [request routing]
  (->> routing
       keys
       (map (fn [x] [(re-find x (:uri request)) x]))
       (filter first)
       first
       second
       (find routing)))

(defn client->proxy->url "Take a client request, return the url of the real server"
  [request match replacement]
  (-> request
      :uri
      (str/replace-first match replacement)
      (->> (str (name (:scheme request)) "://"))
      (str "?" (:query-string request))))

(defn wrap-proxy "A middleware that will relay the request to another server, depending on its routing table"
  [handler routing]
  (fn [request]
    (if-let [[match repl] (get-route-entry request routing)]
      (c/request (-> request
                     (assoc     :url (client->proxy->url request match repl))
                     (update-in [:headers] dissoc "content-length")))
      (handler request))))

(def wrap-record-state (atom []))

(defn wrap-record "A middleware that records the http request / response into an atom"
  [handler]
  (fn [request]
    (println "was hrere ----------------------------")
    (let [resp (handler request)]
      (swap! wrap-record-state
             conj
             {:request request :response response})
      resp)))

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
  (-> handler
      (wrap-proxy routing)
      wrap-record
      #_wrap-debug
      ))

(comment "Example:
  - Say you have a server on localhost:8080"
         (:body (c/get "http://localhost:8080/hello/world?foo=bar"))
         "
  - Then with the routing (see the routing var above), you can call:"
         (:body (c/get "http://localhost:3009/fake-server/hello/world?foo=bar"))
         "
  - You will call the proxy but, get the response from the real server")

(comment "Example with a body"
         "  - direct conn:"
         (c/put "http://localhost:8080/hello/world?foo=bar"
                {:body "this is the body"})
         "  - proxy:"
         (c/put "http://localhost:3009/fake-server/hello/world?foo=bar"
                {:body "this is the body"}))

;; http server lifecycle ======================================================

;; Stop jetty-server, if it exists
(declare stop)
(if (resolve 'jetty-server) (stop))

(def jetty-server
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
         "In a shell run:"

         (sh/sh "curl" "-s" "http://localhost:3009"))

(comment "in last ressorts"
         (remove-ns 'cloxy.core))

































