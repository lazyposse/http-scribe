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

(deftest client->proxy->url-test
  (is (= "http://localhost:8080/foo/bar/a/b?hello=world"
         (client->proxy->url {:uri          "/fake-server/a/b"
                              :scheme       :http
                              :query-string "hello=world"}
                             #"^/fake-server"
                             "localhost:8080/foo/bar"))))

(comment (deftest itest-proxy-and-real-must-be-same
           (is (= (c/get "http://www.omdbapi.com/?t=True%20Grit&y=1969"  {:as :json})
                  (c/get "http://localhost:3009/o/?t=True%20Grit&y=1969" {:as :json})))))
