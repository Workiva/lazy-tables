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

(ns lazy-tables.core
  (:require [lazy-tables.impl :as impl]
            [lazy-tables.protocols :as p]
            [potemkin :refer [import-vars]])
  (:import (lazy_tables.impl Table Union Product Difference)))

(import-vars [lazy-tables.impl select project proj pred TT FF])

(defn par
  "Wrap a number of functions for parallel application to products. Distributes over union.

  (project (par f g) (product xs ys))
  ;; equivalent to
  (product (project f xs) (project g ys)

  (project (par f g) (union (product ws xs) (product ys zs)))
  ;; equivalent to
  (union (project (par f g) (product ws xs)) (project (par f g) (product ys za)))"
  [& fs]
  (impl/->Par (map proj fs)))

(defn s-and
  "Wrap a number of predicates for consecutive selection.

  (select (s-and p q) (product xs ys))
  ;; equivalent to
  (select q (select p (product xs ys))

  Note that this will force the product before selection.

  (select (s-and p q) (union xs ys))
  ;; equivalent to
  (select q (select p (union xs ys))"
  [& preds]
  (impl/->SAnd (map pred preds)))

(defn p-and
  "Wrap a number of predicates for parallel selection on products. Distributes over union.

  (select (par p q) (product xs ys))
  ;; equivalent to
  (product (select p xs) (select q ys))

  (select (par p q) (union (product ws xs) (product ys zs))
  ;; equivalent to
  (union (select (par p q) (product ws xs)) (select (par p q) (product ys zs)))"
  [& preds]
  (impl/->PAnd (map pred preds)))

(defn in
  "Create a selection over a projection for use on a lazy-table.

   Selects matching values equivalence function equiv after projecting the
   lazy-tables using the projection conditions. Encodes an equijoin.

   => @(select (in identity :a :b)
               (product (table [{:a 3 :b 2}
                                {:a 0 :b 0}])
                        (table [{:a 4 :b 3}
                                {:a 1 :b 1}])))

   [({:a 3 :b 2} {:a 4 :b 3})]"
  [equiv & projs]
  (impl/->In equiv (apply par projs)))

(defn table
  "Create a lazy-tables table from a clojure sequence"
  ([]   (table []))
  ([xs] (impl/->Table [] xs)))

(defn union
  "Create a lazy union of tables"
  [& tables]
  (impl/->Union tables))

(defn difference
  "Create a lazy difference of tables."
  [& tables]
  (impl/->Difference tables))

(defn product
  "Create a lazy product of tables."
  [& tables]
  (impl/->Product tables))

(defn aggr
  "Wraps an aggregate function of the form (fn [acc val]) for application to a table"
  [f]
  (if (ifn? f)
    (impl/->Aggregate f)
    f))

(defn aggregate
  "Apply an aggregate function to a table. Only applies to tables, not unions or products."
  [f table]
  (p/aggregate* table (aggr f)))

(defmulti table-empty?
  "Returns true if the table would evaluate to empty; false if it cannot be determined without evaluation."
  type)

(defmethod table-empty? Table [{:keys [xs]}]
  (empty? xs))

(defmethod table-empty? Product [{:keys [xss]}]
  (reduce (fn [_ tab] (if (table-empty? tab) (reduced true) false)) true xss))

(defmethod table-empty? Union [{:keys [xss]}]
  (reduce (fn [_ tab] (if (table-empty? tab) true (reduced false))) true xss))

(defmethod table-empty? Difference [{:keys [xss]}]
  (reduce (fn [_ tab] (if (table-empty? tab) true (reduced false))) true xss))
