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

(ns lazy-tables.disc
  (:require [lazy-tables.protocols :refer [ext]]))

(defrecord EitherEntry [from k v])

(defn explode [projs sets]
  (assert (= (count projs) (count sets)))
  (mapcat (fn [i p s]
            (for [x @s]
              (->EitherEntry i (ext p x) x)))
          (range) projs sets))

(defn disc [f either-entries]
  (cond
    (empty? either-entries) []
    (= 1 (count either-entries)) [(:v (first either-entries))]
    :else
    (vals (group-by (comp f :k) either-entries))))

(defn split [n b] ;; n == *total* number of 'from' sets, even if not in each range
  (let [gb (group-by :from b)]
    (for [i (range n)]
      (map :v (get gb i)))))
