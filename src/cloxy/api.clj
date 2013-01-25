(ns #^{:doc "Java API for controlling the app"}
  cloxy.api
  (:use [clojure.java.javadoc :only [javadoc]]
        [clojure.pprint       :only [pprint print-table]]
        [clojure.string       :only [split join]]
        [clojure.repl         :only [doc]]
        [table.core           :only [table]]
        [clojure.tools.trace  :only [trace deftrace trace-forms trace-ns
                                     untrace-ns trace-vars              ]])
  (:require [clojure
             [string     :as str]
             [set        :as set]
             [walk       :as w]
             [xml        :as xml]
             [data       :as data]]
            [clojure.java
             [shell      :as sh]
             [io         :as io]]
            [cloxy.core :as core])
  (:gen-class :name    cloxy.Api
              :methods [[#^{:static true} [loadScenario      [int int] double]]
                        [#^{:static true} [getScenarioErrors []        java.util.Map]]]))

;; Calls to httplica.core =====================================================


;;TODO rm useeless functions
(defn- load-scenario "Takes a named resource, and load the corresponding scenario. Ex (load-scenario \"myscenario.txt\")"
  [named-resource-str]
  (println "[load-scenario]" named-resource-str)
  (core/load-scenario named-resource-str))

(defn- scenario-errors "Return a map of errors in the previously executed scenario. Empty map if successful"
  []
  (core/scenario-errors))

;; Java callable methods ======================================================

(defn -loadScenario      "Load a scenario"             [named-resource-str ] (load-scenario named-resource-str))
(defn -getScenarioErrors "Returns the scenario status" [                   ] (scenario-errors))
(defn -startServer       "Start the server"            [port               ] (core/server-start port))
(defn -stopServer        "Stop the server"             [                   ] (core/server-stop))

;; Examples ===================================================================

(comment "start/stop examples"
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (-startServer 3009)
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (-loadScenario "import-one-model.scenario")
         (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/") nil
         (-getScenarioErrors)
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (-getScenarioErrors)
         (pprint (sh/sh "curl" "-v" "-s" "--user" "user:pass" "http://localhost:3009/clj/rulez/"))
         (-getScenarioErrors)
         (-stopServer))

;; ns related stuff ===========================================================

(comment "Remove the cloxy ns :" (do
                                   (remove-ns 'cloxy.api)
                                   (remove-ns 'cloxy.core)))

