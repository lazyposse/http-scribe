(ns cloxy.core-test
  (:use [clojure
         [repl                :only [doc find-doc apropos    ]]
         [pprint              :only [pp pprint               ]]]
        [clojure.tools.trace  :only [trace deftrace trace-ns untrace-ns]]
        [clojure.java.javadoc :only [javadoc                 ]]
        [cloxy.core :only [prns]])
  (:use [clojure.test]
        [cloxy.core])
  (:require [clojure
             [data                 :as d   ]
             [inspector            :as ins ]
             [string               :as s   ]
             [walk                 :as w   ]
             [xml                  :as x   ]
             [zip                  :as z   ]]
            [clojure.java
             [shell                :as sh  ]
             [io                   :as io  ]]))


