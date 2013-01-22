(ns cloxy.core
  (:use [clojure.java.javadoc :only [javadoc]]
        [clojure.pprint       :only [pprint print-table]]
        [clojure.string       :only [split join]]
        [clojure.repl         :only [doc]]
        [table.core           :only [table]]
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
            [ring.adapter.jetty :as rj]
            [clojure.test       :as t]))

;; trace conf =================================================================

(defn tracer "Redefine clojure.tools.trace/tracer to print at most 80 characters.
If we don't do that and the input/outputs are big, its very difficult to see the traces."
  [name value]
  (let [s (str "TRACE" (when name (str " " name)) ": " value)]
   (println (subs s
                  0 (min 80 (count s))))))

(alter-var-root #'clojure.tools.trace/tracer
                (constantly tracer))

;; app state ==================================================================

(def #^{:private true, :doc "Holds the state of the application"}
  app-state
  (atom {:conf {:record {:request {:ignore [:ssl-client-cert
                                            :remote-addr
                                            :server-name
                                            :server-port
                                            {:headers ["host"]}]}
                         :response {:ignore [:trace-redirects
                                             :request-time
                                             {:headers ["date"
                                                        "server"]}]}}}
         :mode :replay
         :replay {:scenario {:loaded  []
                             :current []}
                  :last-request nil}}))

(add-watch app-state nil (fn [key, the-atom, old-state, new-state]
                           (println "---------- <app-state-changed> ----------")
                           #_(println "    new state:")
                           (pprint (map (fn [rr] (get-in rr [:request :uri])) (get-in new-state [:replay :scenario :current])))
                           (println "---------- </app-state-changed> ---------")))

;; java api ===================================================================

(defn- named-resource->scenario "Takes a named resource string, return the req/resp datastructure"
  [named-resource-str]
  (->> named-resource-str
       io/resource
       slurp
       read-string))

(defn load-scenario "Takes a named resource, and load the corresponding scenario. Ex (load-scenario \"myscenario.txt\")"
  [named-resource-str]
  (let [scenario (named-resource->scenario named-resource-str)]
    (swap! app-state (fn [appstate] (-> appstate
                                       (assoc-in [:replay :scenario :loaded ] scenario)
                                       (assoc-in [:replay :scenario :current] scenario))))))

(defn- lines "Takes objects, join it with linebreaks"
  [& l] (str/join \newline l))

(defn scenario-errors "Return a map of errors in the previously executed scenario. Empty map if successful"
  []
  (let [scenario                 (get-in @app-state [:replay :scenario])
        scenario-total-steps     (count (:loaded  scenario))
        scenario-current         (:current scenario)
        scenario-remaining-steps (count scenario-current)
        scenario-executed-steps  (- scenario-total-steps scenario-remaining-steps)]
    (if (zero? scenario-remaining-steps)
      {}
      (format (lines "Nb of req/resp executed correctly: %s"
                     "An error was found at step       : %s"
                     "    * Expected request           : %s"
                     "    * But was                    : %s")
              scenario-executed-steps
              (inc scenario-executed-steps)
              (get-in scenario   [:current 0 :request])
              (get-in @app-state [:replay :current])))))

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

(comment "omdb via proxy"
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

(defn- get-route-entry "Takes a request and a routing map, if the uri of the request match with one of the keys of the routing map, then return the pair uri/replacement-url"
  [request routing]
  (->> routing
       keys
       (map (fn [x] [(re-find x (:uri request)) x]))
       (filter first)
       first
       second
       (find routing)))

(defn- client->proxy->url "Take a client request, return the url of the real server"
  [request match replacement]
  (let [fullqstring (if-let [qr (:query-string request)]
                      (str "?" qr))]
    (-> request
        :uri
        (str/replace-first match replacement)
        (->> (str (name (:scheme request)) "://"))
        (str fullqstring))))

(defn- proxy-request "a request setup for the proxy"
  [request]
  (-> request
      (assoc :throw-exceptions false)
      c/request))

(defn wrap-proxy "A middleware that will relay the request to another server, depending on its routing table"
  [handler routing]
  (fn [request]
    (println "---> wrap-proxy")
    (if-let [[match repl] (get-route-entry request routing)]
      (proxy-request (-> request
                         (assoc     :url (client->proxy->url request match repl))
                         (update-in [:headers] dissoc "content-length")))
      (handler request))))

(def wrap-record-state (atom []))

(defn- req-cleanup "Takes a request map and clean it up according to the app config"
  [conf request]
  (let [ignore-seq     (get-in conf [:record :request :ignore])
        ignore-headers (get-in (first (filter :headers ignore-seq))
                               [:headers])]
    (-> ignore-seq
        (->> (cons request))
        (->> (apply dissoc))
        (update-in [:headers] #(apply dissoc (cons % ignore-headers))))))

(defn- resp-cleanup "Takes a respponse and clean it up according to the app config"
  [conf resp]
  (let [ignore-seq     (get-in conf [:record :response :ignore])
        ignore-headers (get-in (first (filter :headers ignore-seq))
                               [:headers])]
    (-> ignore-seq
        (->> (cons resp))
        (->> (apply dissoc))
        (update-in [:headers] #(apply dissoc (cons % ignore-headers))))))

(defn- record-req-resp "Takes a request and a response, and record it"
  [req resp]
  (swap! wrap-record-state
         conj
         {:request  (req-cleanup  (:conf @app-state) req)
          :response (resp-cleanup (:conf @app-state) resp)}))

(defn wrap-record "A middleware that records the http request / response into an atom"
  [handler]
  (fn [request]
    (println "---> wrap-record")
    (let [resp (handler request)]
      (do (println "about to conj into the record atom:")
          (pprint {:request  request, :response resp})
          (record-req-resp request resp))
      resp)))

(defn- wrap-stringify-req-input-stream "A middleware that turn the input stream of the request body into a string"
  [handler]
  (fn [request]
    (println "---> wrap-stringify-req-input-stream")
    (handler (update-in request [:body] slurp))))

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

(defn- mode-get "Returns the mode of the app, currently :record or :replay"
  [] (->> @app-state
          :mode))

(defmulti encode-body "Takes a body as a datastructure, return a string version of it"
  :type)

(defmethod encode-body :json
  [{content :content}] (c/json-encode content))

(defn- admin-req? "Predicate to check if a request is an administrative one"
  [request] (.startsWith (:uri "/admin/")))

(defn- submap? "Predicate to check if m2 is a submap of m1"
  [m1 m2]
  (every? (fn [[k v]] (= (m1 k) v))
          m2))

(t/deftest submap?-test
  (t/are [m1 m2 ex] (= (submap? m1 m2) ex)
         {         } {              } true
         {:a 1     } {              } true
         {:a 1     } {:a 1          } true
         {:a 1 :b 2} {:a 1          } true
         {:a 1 :b 2} {:a 1 :b 2     } true
         {:a 1 :b 2} {:a 1 :b 2 :c 3} false
         {:a 1 :b 2} {          :c 3} false))

(defn- replay-req-match? "Predicate to check if a incomming request and a scenario request are matching"
  [req req-scenario]
  (and (submap? (:headers req)
                (:headers req-scenario))
       (submap? (dissoc req          :headers)
                (dissoc req-scenario :headers))))

(t/deftest replay-req-match?-test
  (t/are [req req-scenario ex] (= (replay-req-match? req req-scenario) ex)
         {:a 1 :b 2} {              } true
         {:a 1 :b 2} {:a 1          } true
         {:a 1 :b 2} {:a 1 :b 2     } true
         {:a 1 :b 2} {:a 1 :b 2 :x 4} false
         {:a 1 :b 2} {          :x 4} false
         {:a 1 :headers {:h1 1}} {:a 1                       } true
         {:a 1 :headers {:h1 1}} {:a 1 :headers {:h1 1      }} true
         {:a 1 :headers {:h1 1}} {:a 1 :headers {:h1 1 :h2 2}} false))

(defn- req-body-encode "takes a req and encode its body if necessary"
  [req] (if (:body req)
          (update-in req [:body] encode-body)
          req))

(defn- replay-req-match-scenario? "Predicate to check if the request matches the current running scenario"
  [request curr-scenario]
  (->> curr-scenario
       first
       :request
       req-body-encode
       (replay-req-match? request)))

(defn- vec-rest "takes a vector, returns a vector without the first element"
  [v]
  (if (seq v)
    (subvec v 1)
    v))

(t/deftest vec-rest-test
  (t/are [v ex] (= (vec-rest v) ex)
         (vec-rest [   ]) []
         (vec-rest [1  ]) []
         (vec-rest [1 2]) []))

(defn- replay-handle-req "Takes a request and update the state of the application with it, returns the matched response or nil"
  [request]
  (println "[replay-handle-req] processing ...")
  (let [scenario (get-in @app-state [:replay :scenario :current])]
    (swap! app-state assoc-in [:replay :last-request] request)
    (if (replay-req-match-scenario? request scenario)
      (do (println "[replay-handle-req] Request matched succefully" )
          (let [resp (get-in @app-state [:replay :scenario :current 0 :response])]
            (swap! app-state (fn [as] (update-in as [:replay :scenario :current] vec-rest)))
            resp))
      (do (println "[replay-handle-req] No match for incomming request!")
          (println "    Expected:")
          (pprint  (get-in scenario [0 :request]))
          (println "    But was :")
          (pprint  request)))))

(defn- wrap-replay "A middleware that takes a confuration and replay it to its client"
  [handler conf]
  (fn [request]
    (do (println "[wrap-replay] date=" (java.util.Date.))
        (if-let [resp (replay-handle-req request)]
       (do (println "[wrap-replay] response found")
           resp)
       (do (println "Nothing found for the given request")
           (throw (RuntimeException. "Nothing found for the given request")))))))

(def app
  (case (mode-get)
    :record (-> handler
                (wrap-proxy routing)
                wrap-record
                wrap-stringify-req-input-stream
                )
    :replay (-> handler
                (wrap-replay (replay-conf-get))
                wrap-stringify-req-input-stream)))

(comment "local fake server via proxy
  - Say you have a server on localhost:8080"
         (:body (c/get "http://localhost:8080/hello/world?foo=bar"))
         "
  - Then with the routing (see the routing var above), you can call:"
         (:body (c/get "http://localhost:3009/fake-server/hello/world?foo=bar"))
         "
  - You will call the proxy but, get the response from the real server")

(comment "local fake server via proxy + body"
         "  - direct conn:"
         (c/put "http://localhost:8080/hello/world?foo=bar"
                {:body             "this is the body"
                 :throw-exceptions false})
         "  - proxy:"
         (c/put "http://localhost:3009/fake-server/hello/world?foo=bar"
                {:body             "this is the body"
                 :throw-exceptions false}))

;; app lifecycle ==============================================================

(def #^{:doc "Home directory of the application"
        :private true} home-dir (str (System/getProperty "user.home") ".cloxy"))

(defn create-home-dir "Create the home dir of the app"
  [] (.mkdir (io/file home-dir)))

(create-home-dir)

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

(comment "start / stop /restart the proxy"
  (start)
  (stop)
  (restart))

(comment "Query the proxy via curl"
         "In a shell run:"

         (sh/sh "curl" "-s" "http://localhost:3009"))

(comment "Remove the cloxy ns"
         (remove-ns 'cloxy.core))
