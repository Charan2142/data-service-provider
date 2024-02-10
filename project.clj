(defproject data-service-provider "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [compojure "1.7.1"]
                 [ring/ring-core "1.11.0"]
                 [ring/ring-jetty-adapter "1.11.0"]
                 [utility "0.1.0-SNAPSHOT"]
                 [metosin/malli "0.8.2"]
                 [clj-http "3.12.3"]
                 [meander/epsilon "0.0.650"]
                 [data-transformation "0.1.0-SNAPSHOT"]]
  :repl-options {:init-ns data-service-provider.core})
