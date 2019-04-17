(defproject com.workiva/lazy-tables "0.1.1"
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

  :deploy-repositories {"clojars"
                        {:url "https://repo.clojars.org"
                         :username :env/clojars_username
                         :password :env/clojars_password
                         :sign-releases false}}

  :source-paths      ["src"]
  :test-paths        ["test"]

  :aliases {"docs" ["do" "clean-docs," "with-profile" "docs" "codox"]
            "clean-docs" ["shell" "rm" "-rf" "./documentation"]}

  :codox {:metadata {:doc/format :markdown}
          :themes [:rdash]
          :html {:transforms [[:title]
                              [:substitute [:title "Lazy-Tables API Docs"]]
                              [:span.project-version]
                              [:substitute nil]
                              [:pre.deps]
                              [:substitute [:a {:href "https://clojars.org/com.workiva/lazy-tables"}
                                            [:img {:src "https://img.shields.io/clojars/v/com.workiva/lazy-tables.svg"}]]]]}
          :output-path "documentation"}

  :cljfmt {:indentation? false}
  :repl-options {:init-ns lazy-tables.core}

  :profiles {:docs {:dependencies [[codox-theme-rdash "0.1.2"]]}})
