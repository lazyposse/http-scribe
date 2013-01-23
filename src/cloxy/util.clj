(ns #^{:doc "Common utils for the app"}
  cloxy.util
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
            [clojure.test       :as t]))

;; trace conf =================================================================

(alter-var-root #'clojure.tools.trace/tracer
                (letfn [(trunc-str [max s]
                          (if (< max (count s))
                            (str (subs s 0 (- max 3)) "...")
                            s))
                        (tracer    [name value]
                          (->> value
                               (str "TRACE" (when name (str " " name)) ": ")
                               (trunc-str 80)
                               println))]
                  (constantly tracer)))

(def #^{:doc     "Tracing enabled?"
        :private true}
  tracer (atom false))

(defn get-*ns*-name "Returns the name of the current ns"
  [] (ns-name *ns*))

(defn trace-toggle "Reverse the value of tracer, return true if enabled, false otherwise"
  []
  (let [*ns*-name (get-*ns*-name)]
    (swap! tracer not)
    (if @tracer
      (do (println (str "Tracer enabled "))
          (trace-ns *ns*-name)
          true)
      (do (println (str "Tracer disabled "))
          (untrace-ns  *ns*-name)
          false))))

;; pretty printing  ===========================================================

(comment "inprogress"(defmulti ppr-transform "Prepare a form for pretty printing"
   (fn [x] (cond (= [] (:keys x )))))

 (defn ppr "Pretty print a datastructure"
   ))

(defn prns
  "Print a preview of a datastructure"
  ([                         s] (prns 2 s))
  ([depth                    s] (prns depth depth s))
  ([print-level print-length s] (binding [*print-level*  print-level
                                          *print-length* print-length]
                                  (pprint s))))

(defn pprint-to-str "Pretty print into a string"
  [x] (with-out-str (pprint x)))

