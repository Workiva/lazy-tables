# Lazy Tables [![Clojars Project](https://img.shields.io/clojars/v/com.workiva/lazy-tables.svg)](https://clojars.org/com.workiva/lazy-tables) [![CircleCI](https://circleci.com/gh/Workiva/lazy-tables/tree/master.svg?style=svg)](https://circleci.com/gh/Workiva/lazy-tables/tree/master)

A library for lazy relational algebra based on some [great work](https://dl.acm.org/citation.cfm?id=1706372) by Fritz Henglein

## Usage

The fundamental collection in the library is a 'table' which represents a loose covering over an existing collection:

```clojure
> (use 'lazy-tables.core)
;; basic operations on tables. We can dereference to force evaluation of the table:
> @(table)
[]

> @(table (repeat 2 {:a 1}))
[{:a 1} {:a 1}]

> @(select even? (project inc (table [0 1 2 3])))
[2 4]

;; vacuous true predicate
> @(select TT (table (range 5))) 
[0 1 2 3 4]

;; vacuous false predicate
> @(select FF (table (range 5)))
[]

;; NOTE: aggregates are currently only supported on tables
> @(aggregate max (select TT (table (range 5))))
4

;; we can use `union` to define the union over input tables:
> @(union)
[]

> @(union (table [1 2 3]) (table [4 5 6]))
[1 2 3 4 5 6]

> @(select even? (union (table [1 2 3]) (table [4 5 6])))
[2 4 6]

> @(select even?
           (project inc
                    (union (table [1 2 3]) (table [4 5 6]))))
[2 4 6]
```

The benefits of the library becomes apparent when we need to work with cartesian products of tables, to perform, say, joins.
The lazy algebraic approach to handling the tables used in this library dynamically and automatically exposes a set of
traditional relational algebra optimizations used by query engines, such as filter promotion by allowing lazy distribution over
the product operator.

```clojure
> (def xs (table [1 2 3]))
> (def ys (table [4 5 6]))

> @(product xs ys)
[[1 4] [1 5] [1 6]
 [2 4] [2 5] [2 6]
 [3 4] [3 5] [3 6]]

;; We can define our predicates naively, but this forces the realization of the intermediate collections
> @(select (fn [[x y]] (and (even? x) (odd? y)))
           (product xs ys))
[[2 5]]

;; the 's-and' predicate builder allows us to decompose the previous form a bit, but will still force
;; realization of any nested predicates that are naively defined.
> @(select (s-and (fn [[x y]] (even? x))
                  (fn [[x y]] (odd? y)))
           (product xs ys))
[[2 5]]

;; with 'p-and' we start to see real benefits. The library is smart enough to decompose and distribute
;; the selection of p-and forms over the inputs to a product. The following expression will be
;; O(|xs + ys + output|) since there's no need to realize the intermediate product.
> @(select (p-and even? odd?) (product xs ys))
[[2 5]]

;; finally, the 'in' form permits linear-time equivalence checks across multiple input collections to
;; a product. Essentially, the following rewrites the product to perform an asymptotically optimal equijoin
;; over the inputs to the product.
@(select (in identity :b :b)
                 (product (table [{:a 1 :b 1} {:a 2 :b 2}])
                          (table [{:b 2 :c 2} {:b 3 :c 3}])))
[[{:a 2, :b 2} {:b 2, :c 2}]]


```
Now, putting it all together, the following query is written naively as a selection over the cross product of
100k^4 (= 1e20) elements. Even so, the query is still able to execute in linear time in the inputs and output,
since we've defined our preducates in such a way that they can be decomposed and rewritten by the algebra:

```clojure
(deftest filter-promotion
  (let [abs (table (doall (for [n (range 100000)] {:a false :b n})))
        cds (table (doall (for [n (range 100000)] {:c n :d n})))
        efs (table (doall (for [n (range 100000)] [n (* 2 n)])))
        ghs (table (doall (for [n (range 100000)] [(* 5 n) n])))]
        ;; select from the product of abs cds efs ghs
        ;; where:
        ;;   :b in abs
        ;;   :c in cds
        ;;   first in efs
        ;;   second in ghs
        ;;   are all equivalent under the 'identity' function (so, where they're all equal)
        ;; and where :d in cds is between 0 and 2
    (time (is (= [[{:a false, :b 1} {:c 1, :d 1} [1 2] [5 1]]]          
                 @(select (s-and (in identity :b :c first second) 
                                 (p-and TT
                                        (fn [x] (< 0 (:d x) 2))
                                        TT
                                        TT))
                          (product abs cds efs ghs)))))))

> (filter-promotion)
"Elapsed time: 1661.159565 msecs"
nil
```

# Maintainers and Contributors

## Active Maintainers

  - Alex Alegre <alex.alegre@workiva.com>

## Previous Contributors

  - Houston King <houston.king@workiva.com>
