(ns salava.core.components.config
  (:require [com.stuartsierra.component :as component]
            [clojure.java.io :as io]))

(defn- get-file [name]
  (if (= "/" (subs name 0 1))
    (io/file name)
    (io/resource name)))

(defn- load-config [base-path plugin]
  (let [config-file (get-file (str base-path "/" (name plugin) ".edn"))]
    (if-not (nil? config-file)
      (-> config-file slurp read-string))))


(defrecord Config [base-path config]
  component/Lifecycle

  (start [this]
    (let [core-conf (load-config base-path :core)
          config (reduce #(assoc %1 %2 (load-config base-path %2)) {} (:plugins core-conf))]

    (assoc this :config (assoc config :core core-conf))))

  (stop [this]
    (assoc this :config nil)))

(defn create [path]
  (println "loading config files from:" path)
  (map->Config {:base-path path}))
