(defproject com.workiva/lazy-tables "1.0.0"
  :description "A set of tools for lazy relational algebra"
  :url "https://github.com/Workiva/lazy-tables"
  :license {:name "Apache License, Version 2.0"}
  :plugins [[lein-shell "0.5.0"]
            [lein-codox "0.10.6"]
            [lein-cljfmt "0.6.4"]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [org.clojure/test.check "0.10.0-alpha3"]
                 [potemkin "0.4.5"]]
  :source-paths      ["src"]
  :test-paths        ["test"]

  :aliases {"docs" ["do" "clean-docs," "codox"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}

  :codox {:output-path "documentation"}
  :cljfmt {:indentation? false}
  :repl-options {:init-ns lazy-tables.core})