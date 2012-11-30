(defproject cloxy/cloxy "1.0.0-SNAPSHOT"
  :description "FIX"
  :min-lein-version "2.0.0"
  :profiles {:dev
             {:dependencies
              [[table "0.3.1" :exclusions [com.datomic/datomic]]]}}
  :dependencies [[org.clojure/clojure                   "1.4.0" ]
                 [org.clojure/tools.trace               "0.7.3" ]
                 [clj-http                              "0.4.0" ]
                 [cheshire                              "4.0.0" ]
                 [ring                                  "1.1.6" ]
                 [ring-basic-authentication             "1.0.1" ]]
  :repositories {"sonatype"
                 "http://oss.sonatype.org/content/repositories/releases"})
