(ns atomstream.core
  "Drop-in replacement for charm.core that also renders to the web.

   Mirrors every var of charm.core verbatim, then overrides `run`/`run-async`
   so that running a charm app simultaneously serves it on the web. Swap
   `[charm.core :as charm]` -> `[atomstream.core :as charm]` and the app is
   unchanged."
  (:require [atomstream.impl.mirror :refer [mirror-ns]]
            [atomstream.impl.web :as web]
            [charm.program :as prog]))

(mirror-ns charm.core :exclude '#{run run-async})

(defn run
  "Like charm.core/run, but also serves the app on the web (default port 8080,
   override with :port). Same options otherwise."
  [opts]
  (web/run-with-web opts))

(defn run-async
  "Like charm.core/run-async. Runs the web-enabled program on a daemon thread.
   Returns {:quit! fn :result promise}."
  [opts]
  (let [running? (atom true)
        result   (promise)]
    (doto (Thread. (fn [] (deliver result (run (assoc opts :running? running?)))))
      (.setDaemon true)
      (.start))
    {:quit!  (fn [] (reset! running? false))
     :result result}))
