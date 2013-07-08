(ns cloxy.core
  (:use [clojure.java.javadoc :only [javadoc]]
        [clojure.pprint       :only [pprint print-table]]
        [clojure.string       :only [split join]]
        [clojure.repl         :only [doc dir]]
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
            [clojure.test       :as t]
            [cloxy.util         :as u]
            [gui-diff.core      :as gd]))

;; app state ==================================================================

(def #^{:private true, :doc "The app state at an intial status"}
  app-state-default
  {:conf {:record {:request {:ignore [:ssl-client-cert
                                      :remote-addr
                                      :server-name
                                      :server-port
                                      {:headers ["host"]}]}
                   :response {:ignore [:trace-redirects
                                       :request-time
                                       {:headers ["date"
                                                  "server"]}]}}}
   :mode :replay
   :replay {:expected []
            :actual   []
            :last-ok? true}})

(def #^{:private true, :doc "Holds the state of the application"}
  app-state
  (atom app-state-default))

;; replay java api ============================================================

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
                                       (assoc-in [:replay :expected] scenario)
                                       (assoc-in [:replay :actual  ] [])
                                       (assoc-in [:replay :last-ok?] true))))))

(defn- lines "Takes objects, join it with linebreaks"
  [& l] (str/join \newline l))

(defn- state-get-replay-count "Returns the count of request for either :expected or :actual"
  [kind]
  (->> @app-state
       :replay
       kind
       count))

(defn- state-get-expected-req "In case of an unexpected request: returns the expected one"
  []
  (-> @app-state
      :replay
      :expected
      (get (dec (state-get-replay-count :actual)))
      :request))

(defn- state-get-last-received-req "Return the last received request"
  []
  (-> @app-state
      :replay
      :actual
      last))

(defn- scenario-status "Return the status of a scenario: :ok, :ko-unexpected-request, :ko-not-enough-requests"
  [appstate] ;; TODO no need to pass the appstate
  (let [replay          (:replay appstate) ;; TODO rm unecessary lets
        expected-count  (state-get-replay-count :expected)
        actual          (:actual replay)
        actual-count    (state-get-replay-count :actual)]
    (cond  (empty? actual)                    :ko-not-enough-requests
           (not (:last-ok? replay))           :ko-unexpected-request
           (not= expected-count actual-count) :ko-not-enough-requests
           :else                              :ok)))

;; TODO to rework
(defn- scenario-errors-human-msg "Takes an app-state map and returns a human-formatted string message"
  [appstate]
  (let [scenario                 (get-in appstate [:replay :scenario])
        scenario-total-steps     (count (:loaded  scenario))
        scenario-current         (:current scenario)
        scenario-remaining-steps (count scenario-current)
        scenario-executed-steps  (- scenario-total-steps scenario-remaining-steps)]
    (format (lines "                                          "
                   "     ***********************              "
                   "     *** Scenario failed ***              "
                   "     ***********************              "
                   "                                          "
                   "Nb of correct request/response: %s of %s  "
                   "                                          "
                   "An error was found at step       : %s     "
                   "                                          "
                   "      Expected request:                   "
                   "      =================                   "
                   "                                          "
                   "%s                                        "
                   "                                          "
                   "      But was         :                   "
                   "      =================                   "
                   "                                          "
                   "%s                                        ")
            scenario-executed-steps
            (inc scenario-executed-steps)
            (with-out-str (pprint (get-in scenario   [:current 0 :request])))
            (with-out-str (pprint (get-in appstate [:replay :last-request]))))))

(defn- scenario-error-get-ko-msg "Return a human readbale message in case of failure"
  [status]
  (let [expected-count (state-get-replay-count :expected)
        actual-count   (state-get-replay-count :actual)]
    (cond
      (zero? actual-count)               (format "Expected %s request(s), but none received so far   " expected-count)
      (= status :ko-not-enough-requests) (format "Expected %s request(s), but received only %s so far" expected-count actual-count)
      (= status :ko-unexpected-request)  (format (lines "Problem with received request number %s     "
                                                        "                                            "
                                                        "Expected:                                   "
                                                        "                                            "
                                                        "%s                                          "
                                                        "But was:                                    "
                                                        "                                            "
                                                        "%s                                          "
                                                        "                                            ")
                                                 actual-count
                                                 (u/pprint-to-str (state-get-expected-req))
                                                 (u/pprint-to-str (state-get-last-received-req))))))

(defn scenario-errors "Return a map of errors in the previously executed scenario. Empty map if successful"
  []
  (let [status (scenario-status @app-state)]
    (if (= status :ok)
      {}
      {"errorMessage" (scenario-error-get-ko-msg status)})))

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

;; http server: utils =========================================================

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

(defn- wrap-stringify-req-input-stream "A middleware that turn the input stream of the request body into a string"
  [handler]
  (fn [request]
    (handler (update-in request [:body] slurp))))

;; http server: recording =====================================================

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
    (let [resp (handler request)]
      (record-req-resp request resp)
      resp)))

;; http server: replay ========================================================

(defmulti replay-encode-body "Takes a body as a datastructure, return a string version of it"
  :type)

(defmethod replay-encode-body :json
  [{content :content}] (c/json-encode content))

(defmethod replay-encode-body :default
  [x] x)

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
  [req]
  (if (:body req)
    (update-in req [:body] replay-encode-body)
    req))

(defn- replay-store-incoming-req! "Store the incoming request in the app state"
  [req]
  (swap! app-state update-in [:replay :actual] conj req))

(defn- replay-get-resp "Takes an incoming request, returns the corresponding scenario response or nil if do not match"
  [req]
  (let [replay            (:replay @app-state)
        received-count    (count (:actual replay))
        expected-req-resp (get-in replay [:expected (dec received-count)])]
    (when (replay-req-match? req (:request expected-req-resp))
      (req-body-encode (:response expected-req-resp)))))

(defn- replay-set-last-request-ok! "Set the last-request ok in the state of the app"
  [ok?] (swap! app-state assoc-in [:replay :last-ok?] ok?))

(defn- replay-last-request-ok? "Get the last-request ok in the state of the app"
  [] (get-in @app-state [:replay :last-ok?]))

(defn- replay-handle-req "Takes a request and update the state of the application with it, returns the matched response or nil if none"
  [req]
  (when (replay-last-request-ok?)
    (replay-store-incoming-req! req)
    (if-let [resp (replay-get-resp req)]
      (do (replay-set-last-request-ok! true)
          resp)
      (do (replay-set-last-request-ok! false)
          nil))))

(defn- wrap-replay "A middleware that takes a confuration and replay it to its client"
  [handler conf]
  (fn [request]
    (replay-handle-req request)))

;; http server: main ==========================================================

(defn- mode-get "Returns the mode of the app, currently :record or :replay"
  [] (->> @app-state
          :mode))

(defn handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    (-> request
                :body
                slurp)})

(def app
  (case (mode-get)
    :record (-> handler
                (wrap-proxy routing)
                wrap-record
                wrap-stringify-req-input-stream
                )
    :replay (-> handler
                (wrap-replay (:conf @app-state))
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

(declare server-stop
         jetty-server)

(defn- jetty-server-defined?
  [] (bound? #'jetty-server))

;; Stop jetty-server, if it exists
(if (jetty-server-defined?) (server-stop))

(defn server-start
  ([]     (server-start 3009))
  ([port] (if (jetty-server-defined?)
            (.start (var-get (resolve 'jetty-server)))
            (def jetty-server
              (rj/run-jetty app {:port  port
                                 :join? false})))))

(defn server-stop    [] (.stop  jetty-server))

(defn server-restart [] (server-stop) (server-start))

(comment "start / stop /restart the proxy"
  (server-start)
  (server-stop)
  (server-restart))

(comment "start/stop examples"
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (server-start)
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (server-stop)
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (server-start)
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         )

(comment "Query the proxy via curl"
         "In a shell run:"
         (sh/sh "curl" "-s" "http://localhost:3009"))

;; ns related stuff ===========================================================

(comment "Remove the cloxy ns :" (remove-ns 'cloxy.core)
         )
(comment "Enable/disable trace:" (u/trace-toggle)
         )

