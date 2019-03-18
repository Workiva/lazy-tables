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

(ns lazy-tables.core-test
  (:require [clojure.math.combinatorics :as combo]
            [clojure.set :refer [intersection]]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [lazy-tables.core :refer :all])
  (:import (lazy_tables.impl Union Product Table)))

(deftest tables
  (testing "table construction, selection, projection, and eduction work as expected."
    (are [x y] (= x y)
      @(table) []

      @(table (repeat 2 {:a 1}))
      [{:a 1} {:a 1}]

      @(select even? (project inc (table (range 4))))
      [2 4]

      @(select TT (table (range 5)))
      [0 1 2 3 4]

      @(select FF (table (range 5)))
      []

      @(aggregate max (select TT (table (range 5))))
      4)))

(deftest unions
  (testing "union construction, selection, and eduction work as expected."
    (are [x y] (= x y)
      @(union)
      []

      @(union (table [1 2 3]) (table [4 5 6]))
      [1 2 3 4 5 6]

      @(select even? (union (table [1 2 3]) (table [4 5 6])))
      [2 4 6]

      @(select even?
               (project inc (union (table [1 2 3]) (table [4 5 6]))))
      [2 4 6])))

(deftest differences
  (testing "difference construction, selection, and eduction work as expected."
    (are [x y] (= x y)
      @(difference)
      []

      @(difference (table [1 2 3]) (table [1 2 3]))
      []

      @(difference (table [1 2 3]) (table [0 0 0]))
      [1 2 3]

      @(difference (table [0 0 0]) (table [1 2 3]))
      [0 0 0]

      @(select even? (difference (table [1 2 3]) (table [0 0 0])))
      [2]

      @(select even?
               (project inc (difference (table [1 2 3]) (table [4 5 6]))))
      [2 4])))

(deftest products
  (testing "product construction, selection, and eduction work as expected."
    (let [xs (table [1 2 3])
          ys (table [4 5 6])]
      (are [x y] (= x y)
        @(product)
        [[]]

        @(product xs (table))
        nil

        @(product xs ys)
        [[1 4] [1 5] [1 6]
         [2 4] [2 5] [2 6]
         [3 4] [3 5] [3 6]]

        @(select (fn [[x y]]
                   (and (even? x) (odd? y)))
                 (product xs ys))
        [[2 5]]

        @(select (s-and (fn [[x y]] (even? x))
                        (fn [[x y]] (odd? y)))
                 (product xs ys))
        [[2 5]]

        @(select (p-and even? odd?)
                 (product xs ys))
        [[2 5]]

        @(select (in identity :b :b)
                 (product (table [{:a 1 :b 1} {:a 2 :b 2}])
                          (table [{:b 2 :c 2} {:b 3 :c 3}])))
        [[{:a 2, :b 2} {:b 2, :c 2}]]))))

(deftest filter-promotion
  (let [abs (table (doall (for [n (range 100000)] {:a false :b n})))
        cds (table (doall (for [n (range 100000)] {:c n :d n})))
        efs (table (doall (for [n (range 100000)] [n (* 2 n)])))
        ghs (table (doall (for [n (range 100000)] [(* 5 n) n])))]
    (time (is (= [[{:a false, :b 1} {:c 1, :d 1} [1 2] [5 1]]]
                 @(select (s-and (in identity :b :c first second)
                                 (p-and TT
                                        (fn [x] (< 0 (:d x) 2))
                                        TT
                                        TT))
                          (product abs cds efs ghs)))))))

(deftest table-empty-tests
  ;; NOTE: The contract is: returns true if it can be determined
  ;; that the table is empty without evaluation; false elsewise
  (testing "empty tables are empty"
    (is (true? (table-empty? (table []))))
    (is (true? (table-empty? (table nil)))))
  (testing "non-empty tables are not empty"
    (is (false? (table-empty? (table [1]))))
    (is (false? (table-empty? (table #{4 5 6})))))
  (testing "union of empty tables is empty"
    (is (true? (table-empty? (union (table nil)
                                    (table nil)
                                    (table nil)))))
    (is (true? (table-empty? (union (product (table nil) (table [1 2 3]))
                                    (product (table nil) (table nil))
                                    (product (table [1 2]) (table nil)))))))
  (testing "union of non-empty tables is not empty"
    (is (false? (table-empty? (union (table [1 2])
                                     (table [3 4])))))
    (is (false? (table-empty? (union (product (table [1 2])
                                              (table [3 4]))
                                     (product (table [5 6])
                                              (table [7 8])))))))
  (testing "product containing an empty table is empty"
    (is (true? (table-empty? (product (table nil)
                                      (table [1 2 3])
                                      (table [4 5 6])))))
    (is (true? (table-empty? (product (product (table nil)
                                               (table [1 2]))
                                      (product (table [3 4])
                                               (table [5 6]))))))))

(def allowed-gen-types
  ;; 'gen/simple-type-printable' - doubles
  (gen/one-of [gen/int gen/large-integer gen/char-ascii gen/string-ascii gen/ratio gen/boolean
               gen/keyword gen/keyword-ns gen/symbol gen/symbol-ns gen/uuid]))

(def any-but-double
  ;; NaN /= NaN, so it breaks tests easily -- just ignore all doubles
  (gen/recursive-gen gen/container-type allowed-gen-types))

(defn gen-table [g]
  (gen/fmap table (gen/vector g 5)))

(defn colls=?
  "Compare two collections and ensure they have the same items (not necessarily in the same order)"
  [& colls]
  {:pre [(< 1 (count colls))]}
  (is (apply = (map frequencies colls))))

;;; Properties

(defspec simple-table-select-corresponds-to-filter
  (prop/for-all [q (gen/vector gen/int)]
    (colls=? (filter even? q)
             @(select even? (table q)))))

(defspec simple-table-project-corresponds-to-map
  (prop/for-all [q (gen/vector gen/int)]
    (colls=? (map inc q)
             @(project inc (table q)))))

(defspec simple-table-aggregate-corresponds-to-reduce
  (prop/for-all [q (gen/vector gen/int)]
    (is (= (reduce + q) @(aggregate + (table q))))))

(defspec s-and-applies-consecutively-to-table
  (prop/for-all [q (gen-table gen/int)]
    (colls=? @(select (s-and even? #(= 0 (mod % 5))) q)
             @(select #(= 0 (mod % 5)) (select even? q)))))

(defspec s-and-applies-consecutively-to-union
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(select (s-and even? #(= 0 (mod % 5))) (union q r))
             @(select #(= 0 (mod % 5)) (select even? (union q r))))))

(defspec par-applies-in-parallel-to-union
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(project (par inc dec) (union (product q r) (product r q)))
             @(union (project (par inc dec) (product q r))
                     (project (par inc dec) (product r q))))))

(defspec p-and-applies-in-parallel-to-union
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(select (p-and even? odd?) (union (product q r) (product r q)))
             @(union (select (p-and even? odd?) (product q r))
                     (select (p-and even? odd?) (product r q))))))

(defspec par-applies-in-parallel-to-cart-product
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(project (par inc dec) (product q r))
             @(product (project inc q)
                       (project dec r)))))

(defspec s-and-applies-consecutively-to-cart-product
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(select (s-and (fn [[a b]] (and (even? a) (odd? b)))
                             (fn [[a b]] (= 10 (+ a b))))
                      (product q r))
             @(select (fn [[a b]] (= 10 (= a b)))
                      (select (fn [[a b]] (and (even? a) (odd? b)))
                              (product q r))))))

(defspec p-and-applies-in-parallel-to-cart-product
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(select (p-and even? odd?) (product q r))
             @(product (select even? q)
                       (select odd? r)))))

;; Laws of relational algebra -- https://en.wikipedia.org/wiki/Relational_algebra
(defspec join-is-commutative
  ;; Commutative in that it returns the same entities joined, ignoring orderedness of tuples
  (prop/for-all [q (gen-table (gen/fmap #(hash-map :a %) gen/int))
                 r (gen-table (gen/fmap #(hash-map :b %) gen/int))]
    (colls=? (flatten @(select (in identity :b :a) (product q r)))
             (flatten @(select (in identity :a :b) (product r q))))))

(defspec join-is-associative
  (prop/for-all [q (gen-table (gen/fmap #(hash-map :a %) gen/int))
                 r (gen-table (gen/fmap #(hash-map :b %) gen/int))
                 s (gen-table (gen/fmap #(hash-map :c %) gen/int))]
    (is (= (map flatten @(select (in identity (comp :a first) :c) ; join :c to :b via :a ((A JOIN B) JOIN C)
                                 (product (select (in identity :b :a) (product q r)) s)))
           (map flatten @(select (in identity :a (comp :c second)) ; join :a to :b via :c (A JOIN (B JOIN C))
                                 (product q (select (in identity :c :b) (product r s)))))))))

(defspec project-distributes-over-union
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(project inc (union q r))
             @(union (project inc q) (project inc r)))))

(defspec project-over-cartesian-product
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(product (project inc q) (project dec r))
             @(project (fn [[a b]] [(inc a) (dec b)]) (product q r))
             @(project (par inc dec) (product q r)))))

(defspec union-is-commutative
  (prop/for-all [q (gen-table any-but-double)
                 r (gen-table any-but-double)]
    (colls=? @(union q r)
             @(union r q))))

(defspec union-is-associative
  (prop/for-all [q (gen-table any-but-double)
                 r (gen-table any-but-double)
                 s (gen-table any-but-double)]
    (colls=? @(union (union q r) s)
             @(union q (union r s)))))

;; NOTE: Cartesian product is not commutative: check both left/right distrbution over union
(defspec cart-product-distributes-over-union-left
  (prop/for-all [q (gen-table any-but-double)
                 r (gen-table any-but-double)
                 s (gen-table any-but-double)]
    (colls=? @(product q (union r s))
             @(union (product q r) (product q s)))))

(defspec cart-product-distributes-over-union-right
  (prop/for-all [q (gen-table any-but-double)
                 r (gen-table any-but-double)
                 s (gen-table any-but-double)]
    (colls=? @(product (union r s) q)
             @(union (product r q) (product s q)))))

(defspec disjunctive-selection-equiv-to-union-of-selections
  ;; Only true when ignoring duplicates (i.e., sets)
  (prop/for-all [q (gen-table gen/int)]
    (is (= (set @(union (select even? q) (select #(= 0 (mod % 5)) q)))
           (set @(select #(or (even? %) (= 0 (mod % 5))) q))))))

(defspec select-distributes-over-union
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (colls=? @(select even? (union q r))
             @(union (select even? q) (select even? r)))))

(defspec select-is-idempotent
  (prop/for-all [q (gen-table gen/int)]
    (colls=? @(select even? q)
             @(select even? (select even? q)))))

(defspec select-is-commutative
  (prop/for-all [ints (gen-table gen/int)]
    (colls=? @(select (s-and #(= 0 (mod % 5)) even?) ints)
             @(select (s-and even? #(= 0 (mod % 5))) ints)
             @(select #(= 0 (mod % 5)) (select even? ints))
             @(select even? (select #(= 0 (mod % 5)) ints)))))

(defspec selection-splitting
  (prop/for-all [ints (gen-table gen/int)]
    (colls=? @(select (fn [i] (and (even? i) (= 0 (mod i 5)))) ints)
             @(select (s-and even? #(= 0 (mod % 5))) ints)
             @(select even? (select #(= 0 (mod % 5)) ints)))))

(defspec select-over-cross-product
  ;; When a predicate `a` is equivalent to (and b c d),
  ;; (select a (X q r)) === (select (and b c d) (X q r)) === (select d (X (select b q) (select c r)))
  (prop/for-all [q (gen-table gen/int)
                 r (gen-table gen/int)]
    (letfn [(pred [[a b]] (and (even? a)
                               (odd? b)
                               (= 10 (+ a b))))]
      (colls=? @(select pred (product q r))
               @(select (s-and (fn [[a b]] (= 10 (+ a b)))
                               (p-and even? odd?))
                        (product q r))
               @(select (fn [[a b]] (= 10 (+ a b)))
                        (product (select even? q) (select odd? r)))))))

(defspec select-over-difference
  ;; (select pred (- a b)) === (- (select pred a) (select pred b)) === (- (select pred a) b)
  (prop/for-all [q (gen/vector gen/int)
                 r (gen/vector gen/int)]
    (let [qt (table q)
          rt (table r)]
      (is (= @(select even? (difference qt rt))
             @(difference (select even? qt) (select even? rt))
             @(difference (select even? qt) rt))))))

(defspec cartesian-product-over-intersection
  (prop/for-all [q (gen/set any-but-double {:max-elements 5})
                 r (gen/set any-but-double {:max-elements 5})
                 s (gen/set any-but-double {:max-elements 5})
                 t (gen/set any-but-double {:max-elements 5})]
    (is (= (set @(product (table (vec (intersection q r))) (table (vec (intersection s t)))))
           (intersection (set @(product (table (vec q)) (table (vec s))))
                         (set @(product (table (vec r)) (table (vec t)))))))))

(def set-ops [[combo/cartesian-product product]
              [concat union]])
(defn divides? [a] (fn [b] (= 0 (mod b a))))

(def int-projections [inc dec identity #(+ 5 %) #(- % 5)])
(def int-selectors [(divides? 2) (divides? 3)])

(defn generate-naive-and-lazy-tables-sets
  "Generates a pair of sets: one already evaluated naively/eagerly at construction time;
   the other a representation of set operations via lazy-tables.

  e.g., [[[1 3] [1 4] [2 3] [2 4]]
         (X (tab [1 2]) (tab [3 4]))]"
  [g depth branching-factor & {:keys [table-size] :or {table-size 2}}]
  (let [layer-order (repeatedly #(rand-nth set-ops))
        [root-layer-set-op root-layer-lazy-tables-ctor] (nth layer-order depth)]
    (letfn [(generate-layers [depth]
              (if (pos? depth)
                (gen/let [layer-items (gen/vector (generate-layers (dec depth)) branching-factor)]
                  (let [[set-op layer-type-ctor] (nth layer-order depth)]
                    [(map #(apply set-op (first %)) layer-items)
                     (map #(apply layer-type-ctor (second %)) layer-items)]))
                (gen/let [tables (gen/vector (gen/vector g table-size) branching-factor)]
                  [tables (map table tables)])))]
      (gen/let [layers (generate-layers (dec depth))]
        [(apply root-layer-set-op (first layers))
         (apply root-layer-lazy-tables-ctor (second layers))]))))

(defmulti exprtree type)
(defmethod exprtree Table [_] :tab)
(defmethod exprtree Product [p] [:product (map exprtree (:xss p))])
(defmethod exprtree Union [u] [:union (map exprtree (:xss u))])

(defn generate-int-projection [t]
  (if (coll? t)
    (let [[table-t table-items] t]
      (case table-t
        :product (apply par (map generate-int-projection table-items))
        ;; NOTE: generate-naive-and-lazy-tables-sets generates a table s.t. everything in a union has the same type.
        ;; Since all sides of the union have the same type, we can use just the first item.
        :union (proj (generate-int-projection (first table-items)))))
    (rand-nth int-projections)))

(defn generate-int-selection [t]
  (if (coll? t)
    (let [[table-t table-items] t]
      (case table-t
        :product (apply p-and (map generate-int-selection table-items))
        :union (pred (generate-int-selection (first table-items)))))
    (rand-nth int-selectors)))

(defspec eagerly-evaluated-table-equiv-to-lazy-tables-delayed-table
  (prop/for-all [[naive-set lazy-tables-set] (generate-naive-and-lazy-tables-sets any-but-double 4 2)]
    (colls=? naive-set @lazy-tables-set)))

(defspec lazy-tables-projection-equiv-to-set-projection
  (prop/for-all [[naive-set lazy-tables-set] (generate-naive-and-lazy-tables-sets gen/int 3 2)]
    (let [projection (generate-int-projection (exprtree lazy-tables-set))]
      (colls=? @(project projection (table naive-set))
               @(project projection lazy-tables-set)))))

(defspec lazy-tables-multiple-projections-equiv-to-set-projection
  (prop/for-all [[naive-set lazy-tables-set] (generate-naive-and-lazy-tables-sets gen/int 3 2)]
    (let [table-shape (exprtree lazy-tables-set)
          proj1 (generate-int-projection table-shape)
          proj2 (generate-int-projection table-shape)
          proj3 (generate-int-projection table-shape)]
      (colls=? @(->> lazy-tables-set (project proj1) (project proj2) (project proj3))
               @(project proj3 (table @(project proj2 (table @(project proj1 (table naive-set))))))))))

(defspec lazy-tables-select-equiv-to-naive-set-selection
  (prop/for-all [[naive-set lazy-tables-set] (generate-naive-and-lazy-tables-sets gen/int 3 2)]
    (let [selection (generate-int-selection (exprtree lazy-tables-set))]
      (colls=? @(select selection (table naive-set))
               @(select selection lazy-tables-set)))))

(defspec lazy-tables-multiple-selections-equiv-to-set-selection
  (prop/for-all [[naive-set lazy-tables-set] (generate-naive-and-lazy-tables-sets gen/int 3 2)]
    (let [table-shape (exprtree lazy-tables-set)
          sel1 (generate-int-selection table-shape)
          sel2 (generate-int-selection table-shape)
          sel3 (generate-int-selection table-shape)]
      (colls=? @(->> lazy-tables-set (select sel1) (select sel2) (select sel3))
               @(select sel3 (table @(select sel2 (table @(select sel1 (table naive-set))))))))))

(defspec lazy-tables-multiple-selections-and-projections-equiv-to-set-selection-and-projection
  (prop/for-all [[naive-set lazy-tables-set] (generate-naive-and-lazy-tables-sets gen/int 3 2)]
    (let [table-shape (exprtree lazy-tables-set)
          sel1 (generate-int-selection table-shape)
          sel2 (generate-int-selection table-shape)
          proj1 (generate-int-projection table-shape)
          proj2 (generate-int-projection table-shape)]
      (colls=? @(->> lazy-tables-set (project proj1) (select sel1) (project proj2) (select sel2))
               @(select sel2 (table @(project proj2 (table @(select sel1 (table @(project proj1 (table naive-set))))))))))))
