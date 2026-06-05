(ns atomstream.impl.ansi-html
  "Convert a charm view string (plain text + ANSI SGR escapes) into hiccup.

   This is what makes atomstream a *generic* cross-renderer: a charm app's
   view returns the very same ANSI string that drives the terminal, and we
   replay its SGR colour/attribute state into <span>s. No per-app, per-component
   web code is needed."
  (:require [charm.ansi.parser :as p]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Colour tables (16-colour palette tuned for a dark web background, plus the
;; xterm-256 cube/greyscale ramps).
;; ---------------------------------------------------------------------------

(def ^:private ansi16->hex
  {0 "#000000" 1 "#cc4444" 2 "#44cc44" 3 "#d6d633"
   4 "#4477cc" 5 "#d633d6" 6 "#33d6d6" 7 "#e6e6e6"
   8 "#808080" 9 "#ff4444" 10 "#44ff44" 11 "#ffff44"
   12 "#4499ff" 13 "#ff44ff" 14 "#44ffff" 15 "#ffffff"})

(defn- ansi256->hex [code]
  (cond
    (<= code 15) (ansi16->hex code)
    (>= code 232) (let [g (+ 8 (* (- code 232) 10))
                        h (format "%02x" g)]
                    (str "#" h h h))
    :else
    (let [n (- code 16)
          r (quot n 36)
          g (quot (mod n 36) 6)
          b (mod n 6)
          ->v (fn [c] (if (zero? c) 0 (+ 55 (* c 40))))]
      (format "#%02x%02x%02x" (->v r) (->v g) (->v b)))))

;; ---------------------------------------------------------------------------
;; SGR state machine
;; ---------------------------------------------------------------------------

(def ^:private empty-style
  {:fg nil :bg nil :bold false :faint false :italic false
   :underline false :reverse false :strike false})

(defn- consume-color
  "Handle the 38/48 extended-colour forms inside an SGR param list. `params` is
   the remaining seq starting just after the 38 or 48. Returns [hex rest]."
  [params]
  (case (first params)
    2 [(let [[r g b] (rest params)]
         (format "rgb(%d,%d,%d)" (or r 0) (or g 0) (or b 0)))
       (drop 4 params)]
    5 [(ansi256->hex (or (second params) 0)) (drop 2 params)]
    [nil (rest params)]))

(defn- apply-sgr
  "Fold one SGR escape's params into the running style map."
  [style params]
  (loop [s style, ps (seq (if (empty? params) [0] params))]
    (if-not ps
      s
      (let [c (first ps)]
        (cond
          (= c 0)  (recur empty-style (next ps))
          (= c 1)  (recur (assoc s :bold true) (next ps))
          (= c 2)  (recur (assoc s :faint true) (next ps))
          (= c 3)  (recur (assoc s :italic true) (next ps))
          (= c 4)  (recur (assoc s :underline true) (next ps))
          (= c 7)  (recur (assoc s :reverse true) (next ps))
          (= c 9)  (recur (assoc s :strike true) (next ps))
          (= c 22) (recur (assoc s :bold false :faint false) (next ps))
          (= c 23) (recur (assoc s :italic false) (next ps))
          (= c 24) (recur (assoc s :underline false) (next ps))
          (= c 27) (recur (assoc s :reverse false) (next ps))
          (= c 29) (recur (assoc s :strike false) (next ps))
          (and (>= c 30) (<= c 37)) (recur (assoc s :fg (ansi16->hex (- c 30))) (next ps))
          (= c 39) (recur (assoc s :fg nil) (next ps))
          (= c 38) (let [[hex more] (consume-color (next ps))]
                     (recur (assoc s :fg hex) (seq more)))
          (and (>= c 40) (<= c 47)) (recur (assoc s :bg (ansi16->hex (- c 40))) (next ps))
          (= c 49) (recur (assoc s :bg nil) (next ps))
          (= c 48) (let [[hex more] (consume-color (next ps))]
                     (recur (assoc s :bg hex) (seq more)))
          (and (>= c 90) (<= c 97)) (recur (assoc s :fg (ansi16->hex (+ 8 (- c 90)))) (next ps))
          (and (>= c 100) (<= c 107)) (recur (assoc s :bg (ansi16->hex (+ 8 (- c 100)))) (next ps))
          :else (recur s (next ps)))))))

(defn- style->css [{:keys [fg bg bold faint italic underline reverse strike]}]
  (let [[fg bg] (if reverse [(or bg "#0b0b10") (or fg "#e6e6e6")] [fg bg])]
    (->> [(when fg (str "color:" fg))
          (when bg (str "background:" bg))
          (when bold "font-weight:bold")
          (when faint "opacity:0.6")
          (when italic "font-style:italic")
          (when underline "text-decoration:underline")
          (when strike "text-decoration:line-through")]
         (remove nil?)
         (str/join ";"))))

;; ---------------------------------------------------------------------------
;; Public
;; ---------------------------------------------------------------------------

(defn ansi->hiccup
  "Turn a charm view string into a seq of hiccup nodes (plain strings and
   styled <span>s) suitable for dropping inside a <pre>."
  [s]
  (when (seq s)
    (loop [segs  (p/split-ansi s)
           style empty-style
           out   (transient [])]
      (if-let [{:keys [type content parsed]} (first segs)]
        (case type
          :text (recur (next segs) style
                       (conj! out (let [css (style->css style)]
                                    (if (str/blank? css)
                                      content
                                      [:span {:style css} content]))))
          :ansi (recur (next segs)
                       (if (= :sgr (:type parsed))
                         (apply-sgr style (:params parsed))
                         style)
                       out)
          (recur (next segs) style out))
        (persistent! out)))))
