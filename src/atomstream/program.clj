(ns atomstream.program
  "Web-enabled mirror of charm.program.

   charm.clj exposes its API as per-namespace requires (charm.style.core,
   charm.components.timer, ...). atomstream mirrors that layout under
   `atomstream.*`; this namespace mirrors `charm.program` while overriding
   `run`/`run-async` so a charm app also renders to the web. Replace the
   `charm.` prefix with `atomstream.` in your requires and the app is otherwise
   unchanged."
  (:require [atomstream.impl.mirror :refer [mirror-ns]]
            [atomstream.impl.web :as web]))

(mirror-ns charm.program :exclude '#{run run-async})

(defn run
  "Like charm.program/run, but also serves the app on the web (default port
   8080, override with :port). Same options otherwise."
  [opts]
  (web/run-with-web opts))

(defn run-async
  "Like charm.program/run-async. Runs the web-enabled program on a daemon
   thread. Returns {:quit! fn :result promise}."
  [opts]
  (let [running? (atom true)
        result   (promise)]
    (doto (Thread. (fn [] (deliver result (run (assoc opts :running? running?)))))
      (.setDaemon true)
      (.start))
    {:quit!  (fn [] (reset! running? false))
     :result result}))
