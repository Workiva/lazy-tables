;; Copyright 2017-2019 Workiva Inc.
;; 
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;; 
;;     http://www.apache.org/licenses/LICENSE-2.0
;; 
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns lazy-tables.impl
  "Implementation details encoding the lazy relational algebra. Not intended for public use; may change at any time."
  (:require [lazy-tables.disc :as disc]
            [lazy-tables.protocols :refer :all]
            [clojure.math.combinatorics :as combo])
  (:import (clojure.core Eduction)
           (clojure.lang IDeref IFn IRecord IPersistentMap)
           (java.util Map)))

(extend-type IFn
  FunctionalExtension
  (ext [f x] (f x))
  Satisfies
  (sat [f x] (boolean (f x))))

(prefer-method print-method Map IDeref)
(prefer-method print-method IPersistentMap IDeref)
(prefer-method print-method IRecord IDeref)

;; TODO: can we deprecate proj and pred in favor of just ifns?
(defrecord Proj [f]
  FunctionalExtension
  (ext [_ x] (f x)))

(defrecord Pred [f]
  Satisfies
  (sat [_ x] (boolean (f x))))

(defrecord Par [projs]
  FunctionalExtension
  (ext [_ xs]
    (map (fn [pj x] (ext pj x))
         projs xs)))

(defrecord SAnd [preds]
  Satisfies
  (sat [_ x] (every? true? (map sat preds (repeat x)))))

(defrecord PAnd [preds]
  Satisfies
  (sat [_ xs] (every? true? (map sat preds xs))))

(defrecord In [e par])

(defn ^:private apply-ops [ops xs]
  (reduce (fn [res op-group]
            (case (ffirst op-group)
              :eduction  (Eduction. (apply comp (map second op-group)) res)
              :reduction (reduce (apply comp (map second op-group)) res)))
          xs
          (partition-by first ops)))

(declare select project)
(defrecord Table [ops xs]
  IDeref
  (deref [_] (apply-ops ops xs))
  Tabula
  (select* [_ p]
    (->Table
     (conj ops [:eduction (filter (partial sat p))])
     xs))
  (project* [_ p]
    (->Table
     (conj ops [:eduction (map (partial ext p))])
     xs))
  (aggregate* [_ aggr]
    (->Table
     (conj ops [:reduction (:f aggr)])
     xs)))

(defrecord Union [xss]
  IDeref
  ;; TODO: investigate parallel realization
  (deref [_] (reduce into [] (map deref xss)))
  Tabula
  (select* [_ p] (->Union (map select (repeat p) xss)))
  (project* [_ p] (->Union (map project (repeat p) xss))))

(defrecord Product [xss]
  IDeref
  ;; TODO: investigate parallel realization
  (deref [_] (apply combo/cartesian-product (map deref xss)))

  Tabula
  (select* [this p]
    (condp instance? p
      ;; TODO: switch to a delayed realization
      Pred (->Table [[:eduction (filter (partial sat p))]] @this)

      SAnd (loop [s this, [p & ps] (:preds p)]
             (if (nil? p)
               s
               (recur (select p s) ps)))

      PAnd (->Product (map select (:preds p) xss))

      In (let [exploded (disc/explode (:projs (:par p)) xss)
               bs       (disc/disc (:e p) exploded)]
           (->Union (map (fn [b]
                           (->Product (map (partial ->Table [])
                                           (disc/split (count xss) b))))
                         bs)))))

  (project* [this p]
    (condp instance? p
      Proj (->Table [[:eduction (map (partial ext p))]] @this)
      Par  (->Product (map project (:projs p) xss)))))

(defrecord Difference [xss]
  IDeref
  (deref [_]
    (let [xss' (map deref xss)
          xs (first xss')
          to-remove (reduce into #{} (rest xss'))]
      (remove to-remove xs)))
  Tabula
  (select* [_ p] (->Difference (map select (repeat p) xss)))
  (project* [_ p] (->Difference (map project (repeat p) xss))))

(defrecord Aggregate [f])

(defn proj
  "Wrap a clojure function in a projection type -- not typically necessary
   as the apis all accept Clojure IFns."
  [f]
  (if (ifn? f) (->Proj f) f))

(defn pred
  "Wrap a clojure function in a predicate type -- not typically necessary
   as the apis all accept Clojure IFns"
  [f]
  (if (ifn? f) (->Pred f) f))

;; Vacuous true and false predicates
(def TT
  "Vacuously true predicate.

  @(select TT table) === @table"
  (reify Satisfies (sat [_ _] true)))

(def FF
  "Vacuously false predicate.

  @(select FF tab) === []"
  (reify Satisfies (sat [_ _] false)))

(defn project
  "Lazily apply a projection to a table."
  [projection table]
  (project* table (proj projection)))

(defn select
  "Lazily apply a selection to a table."
  [predicate table]
  (cond
    (identical? TT predicate) table
    (identical? FF predicate) (->Table [] [])
    :else (select* table (pred predicate))))
