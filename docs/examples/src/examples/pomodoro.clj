(ns examples.pomodoro
  "Pomodoro CLI timer with work/break cycles.

   Usage:
     clj -M:pomodoro

   On start, choose between:
     - 25/5: 25 minutes work, 5 minutes break
     - 50/10: 50 minutes work, 10 minutes break

   Press q or Ctrl+C to quit."
  (:require [atomstream.core :as charm]
            [clojure.string :as str])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def modes
  [{:title "25/5 - Classic Pomodoro"
    :description "25 min work, 5 min break"
    :work-ms (* 25 60 1000)
    :break-ms (* 5 60 1000)}
   {:title "50/10 - Extended Focus"
    :description "50 min work, 10 min break"
    :work-ms (* 50 60 1000)
    :break-ms (* 10 60 1000)}])

;; ---------------------------------------------------------------------------
;; Time Formatting
;; ---------------------------------------------------------------------------

(defn format-remaining
  "Format milliseconds as human-readable remaining time."
  [ms]
  (let [total-seconds (max 0 (quot ms 1000))
        minutes (quot total-seconds 60)
        seconds (rem total-seconds 60)]
    (format "%02d:%02d" minutes seconds)))

;; ---------------------------------------------------------------------------
;; Styles
;; ---------------------------------------------------------------------------

(def title-style
  (charm/style :bold true))

(def work-color (charm/rgb 255 140 100))
(def break-color (charm/rgb 100 220 180))

(defn phase-style [phase]
  (charm/style :fg (if (= phase :work) work-color break-color) :bold true))

(def time-style
  (charm/style :fg (charm/rgb 140 140 140)))

(def hint-style
  (charm/style :fg (charm/rgb 100 100 100)))

(defn gradient-color
  "Interpolate color based on progress and phase."
  ([progress phase] (gradient-color progress phase false))
  ([progress phase dim?]
   (let [p (double progress)
         [r1 g1 b1 r2 g2 b2] (if (= phase :work)
                               ;; Work: orange to red
                               [255 180 100 255 80 80]
                               ;; Break: teal to green
                               [100 200 180 80 220 120])
         r (+ r1 (* p (- r2 r1)))
         g (+ g1 (* p (- g2 g1)))
         b (+ b1 (* p (- b2 b1)))
         factor (if dim? 0.4 1.0)]
     (charm/rgb (int (* r factor))
                (int (* g factor))
                (int (* b factor))))))

;; ---------------------------------------------------------------------------
;; View - Mode Selection
;; ---------------------------------------------------------------------------

(defn view-selecting [state]
  (let [{:keys [menu]} state]
    (str (charm/render title-style "Pomodoro Timer")
         "\n\n"
         "Select your focus mode:\n\n"
         (charm/list-view menu)
         "\n\n"
         (charm/render hint-style "↑/↓ to select, Enter to start, q to quit"))))

;; ---------------------------------------------------------------------------
;; View - Timer Running
;; ---------------------------------------------------------------------------

(defn view-running [state]
  (let [{:keys [phase timer total-ms cycle-count]} state
        remaining (max 0 (charm/timer-timeout timer))
        elapsed (- total-ms remaining)
        progress (if (pos? total-ms) (/ elapsed total-ms) 0.0)
        phase-label (if (= phase :work) "WORK" "BREAK")
        bar (charm/progress-bar :width 30
                                :percent progress
                                :bar-style :thick
                                :full-style (charm/style :fg (gradient-color progress phase))
                                :empty-style (charm/style :fg (gradient-color progress phase true)))]
    (str (charm/render (phase-style phase) phase-label)
         (charm/render hint-style (str " (cycle " cycle-count ")"))
         "\n\n"
         (charm/progress-view bar)
         "  "
         (charm/render time-style (format-remaining remaining))
         "\n\n"
         (charm/render hint-style "p to pause/resume, q to quit"))))

;; ---------------------------------------------------------------------------
;; View - Paused
;; ---------------------------------------------------------------------------

(defn view-paused [state]
  (let [{:keys [phase timer total-ms cycle-count]} state
        remaining (max 0 (charm/timer-timeout timer))
        elapsed (- total-ms remaining)
        progress (if (pos? total-ms) (/ elapsed total-ms) 0.0)
        phase-label (if (= phase :work) "WORK" "BREAK")
        bar (charm/progress-bar :width 30
                                :percent progress
                                :bar-style :thick
                                :full-style (charm/style :fg (gradient-color progress phase))
                                :empty-style (charm/style :fg (gradient-color progress phase true)))]
    (str (charm/render (phase-style phase) phase-label)
         (charm/render hint-style (str " (cycle " cycle-count ")"))
         "  "
         (charm/render (charm/style :fg (charm/rgb 255 200 100) :bold true) "PAUSED")
         "\n\n"
         (charm/progress-view bar)
         "  "
         (charm/render time-style (format-remaining remaining))
         "\n\n"
         (charm/render hint-style "p to resume, q to quit"))))

;; ---------------------------------------------------------------------------
;; Main View
;; ---------------------------------------------------------------------------

(defn view [state]
  (case (:screen state)
    :selecting (view-selecting state)
    :running (view-running state)
    :paused (view-paused state)))

;; ---------------------------------------------------------------------------
;; Timer Helpers
;; ---------------------------------------------------------------------------

(defn start-phase-timer [state phase]
  (let [mode (:mode state)
        duration (if (= phase :work)
                   (:work-ms mode)
                   (:break-ms mode))
        timer (charm/timer :timeout duration
                           :interval 100
                           :running true)
        [timer cmd] (charm/timer-init timer)]
    [(assoc state
            :screen :running
            :phase phase
            :timer timer
            :total-ms duration)
     cmd]))

;; ---------------------------------------------------------------------------
;; Update
;; ---------------------------------------------------------------------------

(defn update-fn [state msg]
  (let [{:keys [screen]} state]
    (cond
      ;; Global quit
      (or (charm/key-match? msg "q")
          (charm/key-match? msg "ctrl+c")
          (charm/key-match? msg "esc"))
      [state charm/quit-cmd]

      ;; Mode selection screen
      (= screen :selecting)
      (cond
        (charm/key-match? msg "enter")
        (let [selected (charm/list-selected-item (:menu state))
              new-state (assoc state :mode selected :cycle-count 1)]
          (start-phase-timer new-state :work))

        :else
        (let [[new-menu _] (charm/list-update (:menu state) msg)]
          [(assoc state :menu new-menu) nil]))

      ;; Running screen - pause toggle
      (and (= screen :running) (charm/key-match? msg "p"))
      (let [[new-timer _] (charm/timer-stop (:timer state))]
        [(assoc state :screen :paused :timer new-timer) nil])

      ;; Paused screen - resume
      (and (= screen :paused) (charm/key-match? msg "p"))
      (let [[new-timer cmd] (charm/timer-start (:timer state))]
        [(assoc state :screen :running :timer new-timer) cmd])

      ;; Running screen - timer tick
      (= screen :running)
      (let [[new-timer cmd] (charm/timer-update (:timer state) msg)
            new-state (assoc state :timer new-timer)]
        (if (charm/timer-timed-out? new-timer)
          ;; Phase complete - switch to next phase
          (let [{:keys [phase cycle-count]} state
                next-phase (if (= phase :work) :break :work)
                next-cycle (if (= phase :break) (inc cycle-count) cycle-count)]
            (start-phase-timer (assoc new-state :cycle-count next-cycle) next-phase))
          [new-state cmd]))

      :else
      [state nil])))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init []
  (let [menu (charm/item-list modes
                              :show-descriptions false
                              :cursor-style (charm/style :fg (charm/rgb 255 180 100) :bold true))]
    [{:screen :selecting
      :menu menu
      :mode nil
      :phase nil
      :timer nil
      :total-ms 0
      :cycle-count 0}
     nil]))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (if (some #{"-h" "--help"} args)
    (do
      (println "Pomodoro Timer")
      (println)
      (println "Usage: pomodoro")
      (println)
      (println "A CLI pomodoro timer with work/break cycles.")
      (println)
      (println "Modes:")
      (println "  25/5   25 minutes work, 5 minutes break")
      (println "  50/10  50 minutes work, 10 minutes break")
      (println)
      (println "Controls:")
      (println "  p          Pause/resume timer")
      (println "  q, Ctrl+C  Quit"))
    (do
      (charm/run {:init init
                  :update update-fn
                  :view view
                  :alt-screen false
                  :hide-cursor true})
      (println))))
