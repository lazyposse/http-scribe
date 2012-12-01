(ns cloxy.core
  (:use     [clojure.pprint :only [pprint print-table]]
            [clojure.string :only [split join]]
            [clojure.repl   :only [doc]]
            [table.core     :only [table]])
  (:require [clojure
             [string         :as str]
             [set            :as set]
             [walk           :as w]
             [xml            :as xml]]
            [clojure.java
             [shell          :as sh]
             [io             :as io]]
            [clj-http.client :as client]))

;; utilities ==================================================================

(defn prns
  "Print a preview of a datastructure"
  ([                         s] (prns 2 s))
  ([depth                    s] (prns depth depth s))
  ([print-level print-length s] (binding [*print-level*  print-level
                                          *print-length* print-length]
                                  (pprint s))))

;; http =======================================================================

(def activiti-base-url "http://ec2-79-125-75-236.eu-west-1.compute.amazonaws.com:8080/activiti-rest/service")

(defn activiti-q "Template for all activiti queries"
  [method path & [opts]]
  (let [p (merge {:debug       true
                  :method      method
                  :url         (str activiti-base-url path)
                  :basic-auth  ["kermit" "kermit"]}
                 opts)]
    (c/request p)))
