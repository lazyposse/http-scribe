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

(defn prns
  "Print a preview of a datastructure"
  ([                         s] (prns 2 s))
  ([depth                    s] (prns depth depth s))
  ([print-level print-length s] (binding [*print-level*  print-level
                                          *print-length* print-length]
                                  (pprint s))))

