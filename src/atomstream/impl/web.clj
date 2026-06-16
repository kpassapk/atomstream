(ns atomstream.impl.web
  (:require [atomstream.impl.ansi-html :as ah]
            [charm.message :as msg]
            [charm.program :as prog]
            [clojure.core.async :as a]
            [hyperlith.core :as h :refer [defaction defview]]))

(defonce ^:private ext-ch_ (atom nil))   ; web -> charm event channel
(defonce ^:private ansi_   (atom ""))    ; latest view string

(defn- dispatch! [m]
  (when-let [ch @ext-ch_] (a/put! ch m)))

(defn- web-key->charm [k]
  ;; Browser KeyboardEvent.key -> the key strings charm's key-match? expects.
  (case k
    "ArrowUp"    :up
    "ArrowDown"  :down
    "ArrowLeft"  :left
    "ArrowRight" :right
    "Enter"      :enter
    "Escape"     :escape
    "Backspace"  :backspace
    "Tab"        :tab
    "Shift"      :shift
    k))


(def css
  (h/static-css
   [["*, *::before, *::after" {:box-sizing :border-box :margin 0 :padding 0}]
    [:body {:background  "#0b0b10"
            :color       "#e6e6e6"
            :font-family "'JetBrains Mono','Menlo','Monaco',monospace"
            :font-size   :14px
            :line-height "1.25"}]
    [:.main {:padding :1rem}]
    ["pre#screen" {:white-space :pre
                   :font-family :inherit
                   :margin 0
                   :overflow :auto}]]))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defaction handler-key [{:keys [query-params]}]
  (when-let [k (get query-params "k")]
    (dispatch! (msg/key-press (web-key->charm k)))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def ^:private shim-headers
  (h/html
   [:link#css {:rel "stylesheet" :type "text/css" :href css}]
   [:title nil "atomstream"]
   [:meta {:name "description" :content "charm.clj app, rendered to the web"}]))

(defview handler-home {:path "/" :shim-headers shim-headers}
  [_]
  (h/html
   [:link#css {:rel "stylesheet" :type "text/css" :href css}]
   [:main#morph.main
    {:tabindex "0"
     :data-init "el.focus()"
     :data-on:keydown__window
     (str "const ae=document.activeElement;"
          "if(ae&&(ae.tagName==='INPUT'||ae.tagName==='TEXTAREA')&&ae!==el)return;"
          "if(evt.metaKey||evt.ctrlKey)return;"
          "evt.preventDefault();"
          "@post(`" handler-key "?k=${encodeURIComponent(evt.key)}`)")}
    (into [:pre#screen] (ah/ansi->hiccup @ansi_))]))

;; ---------------------------------------------------------------------------
;; Command execution (mirrors charm.program's private execute-cmd!)
;; ---------------------------------------------------------------------------

(defn- execute-cmd!
  "Execute a command and send the resulting message to msg-chan."
  [cmd msg-chan]
  (when cmd
    (case (:type cmd)
      :cmd   (a/go (try
                     (when-let [r ((:fn cmd))]
                       (a/>! msg-chan r))
                     (catch Exception e
                       (a/>! msg-chan (msg/error e)))))
      :batch (doseq [c (:cmds cmd)]
               (execute-cmd! c msg-chan))
      :sequence (a/go (doseq [c (:cmds cmd)]
                        (try
                          (when-let [r ((:fn c))]
                            (a/>! msg-chan r))
                          (catch Exception e
                            (a/>! msg-chan (msg/error e))))))
      nil)))

;; ---------------------------------------------------------------------------
;; Running
;; ---------------------------------------------------------------------------

(defn run-with-web
  [{:keys [init update view port] :or {port 8080} :as opts}]
  (let [ext-ch  (a/chan 256)
        sub-cmd (prog/cmd #(a/<!! ext-ch))]   ; blocks for next web event; re-armed each cycle
    (reset! ext-ch_ ext-ch)
    (let [wrap-init   (fn []
                        (let [r     (if (fn? init) (init) [init nil])
                              [s c] (if (vector? r) r [r nil])]
                          [s (prog/batch c sub-cmd)]))
          wrap-update (fn [s m]
                        (let [[s' c] (update s m)]
                          [s' (prog/batch c sub-cmd)]))
          wrap-view   (fn [s]
                        (let [out (view s)]
                          (reset! ansi_ out)
                          (h/refresh-all!)
                          out))
          web         (h/start-app {:ctx-start (fn [] {})
                                    :ctx-stop  (fn [_] nil)
                                    :port      port})]
      (try
        (prog/run (assoc opts :init wrap-init :update wrap-update :view wrap-view))
        (finally
          (a/close! ext-ch)
          ((:stop web)))))))

(defn run-web-only
  "Run a charm-style app with only the web frontend — no JLine terminal.
   Blocks until the app quits. Returns final state.

   Accepts same opts as run-with-web plus:
     :width   - virtual terminal width  (default 80)
     :height  - virtual terminal height (default 24)"
  [{:keys [init update view port width height]
    :or   {port 8080 width 80 height 24}}]
  (let [msg-chan (a/chan 256)
        ext-ch  (a/chan 256)
        running? (atom true)

        ;; Forward web events into msg-chan.
        ;; a/<!! returns nil on closed channel, ending the loop naturally.
        fwd-thread (doto (Thread.
                          (fn []
                            (loop []
                              (when-let [m (a/<!! ext-ch)]
                                (a/put! msg-chan m)
                                (recur)))))
                     (.setDaemon true)
                     (.start))

        ;; Wire up web event channel
        _ (reset! ext-ch_ ext-ch)

        ;; Init
        init-result (if (fn? init) (init) [init nil])
        [initial-state init-cmd] (if (vector? init-result)
                                   init-result
                                   [init-result nil])
        state (atom initial-state)

        ;; Render helper
        render! (fn [s]
                  (let [out (view s)]
                    (reset! ansi_ out)
                    (h/refresh-all!)))

        ;; Start web server
        web (h/start-app {:ctx-start (fn [] {})
                          :ctx-stop  (fn [_] nil)
                          :port      port})]
    (try
      ;; Execute init command
      (execute-cmd! init-cmd msg-chan)

      ;; Send initial window size
      (a/put! msg-chan (msg/window-size width height))

      ;; Render initial view
      (render! @state)

      ;; Event loop
      (loop []
        (when @running?
          (a/<!! (a/timeout 10))
          (when-let [m (a/poll! msg-chan)]
            (cond
              (msg/quit? m)
              (reset! running? false)

              (= :error (:type m))
              (do (reset! running? false)
                  (throw (:error m)))

              :else
              (let [[new-state cmd] (update @state m)]
                (reset! state new-state)
                (execute-cmd! cmd msg-chan)
                (render! new-state))))
          (when @running? (recur))))

      @state

      (finally
        (reset! running? false)
        (a/close! ext-ch)
        (a/close! msg-chan)
        ((:stop web))))))
