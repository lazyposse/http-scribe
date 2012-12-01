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
            [clj-http.client :as c]))

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
  "example:"
  (omdb-q {:query-params {"t" "True Grit", "y" "1969"}})

  "curl equivalent:"
  (sh/sh "curl" "-s" (str omdb-url "?t=True%20Grit&y=1969")))

;; http server ================================================================

