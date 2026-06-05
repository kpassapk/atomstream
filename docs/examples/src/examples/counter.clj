(ns examples.counter
  (:require [atomstream.core :as charm]))

(def state (atom 0))

(def title-style
  (charm/style :fg charm/magenta :bold true))

(def count-style
  (charm/style :fg charm/cyan
               :padding [0 1]
               :border charm/rounded-border))

(defn update-fn [state msg]
  (cond
    ;; Quit on q or Ctrl+C
    (or (charm/key-match? msg "q")
        (charm/key-match? msg "ctrl+c"))
    [state charm/quit-cmd]

    ;; Increment on k or up arrow
    (or (charm/key-match? msg "k")
        (charm/key-match? msg :up))
    [(update state :count inc) nil]

    ;; Decrement on j or down arrow
    (or (charm/key-match? msg "j")
        (charm/key-match? msg :down))
    [(update state :count dec) nil]

    ;; Ignore other messages
    :else
    [state nil]))

(defn view [state]
  (str (charm/render title-style "Counter App") "\n\n"
       (charm/render count-style (str (:count state))) "\n\n"
       "j/k or arrows to change\n"
       "q to quit"))

(defn -main [& args]
  (charm/run {:init {:count 0}
              :update update-fn
              :view view
              :alt-screen true}))
