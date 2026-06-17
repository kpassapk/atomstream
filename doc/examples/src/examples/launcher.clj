(ns ^{:atomstream/icon "🏠"} examples.launcher
  "TUI launcher that loads the other examples dynamically.

   Lists the example files in src/examples and lets you pick one. On selection
   it reads the example's source, evaluates it with sci. Picks up the program
   spec (the :init / :view / :update map) by intercepting `program/run`.

   When the example quits, control returns to the launcher.

   Run with:  clojure -M:sci -m examples.launcher   (or `bb launcher`)"
  (:require
   [atomstream.ansi.width :as ansi-width]
   [atomstream.components.help :as help]
   [atomstream.components.table :as table]
   [atomstream.components.viewport :as viewport]
   [atomstream.message :as msg]
   [atomstream.program :as program]
   [atomstream.style.border :as border]
   [atomstream.style.core :as style]
   [atomstream.style.overlay :as overlay]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [sci.core :as sci]))

(def ^:private examples-dir "src/examples")

;; ---------------------------------------------------------------------------
;; Metadata extraction — parse ns forms for icon & docstring
;; ---------------------------------------------------------------------------

(defn- parse-ns-metadata
  "Read source string, extract :atomstream/icon from ns metadata and the
   docstring. Returns {:icon :short-desc :long-desc}."
  [source]
  (let [form (try (read-string source) (catch Exception _ nil))]
    (when (and (seq? form) (= 'ns (first form)))
      (let [elements (rest form)
            docstring (first (filter string? (take 4 elements)))
            name-sym  (first elements)
            meta-map  (meta name-sym)
            icon      (:atomstream/icon meta-map)
            [short-desc long-desc]
            (when docstring
              (let [trimmed (str/trim docstring)
                    parts   (str/split trimmed #"\n\s*\n" 2)]
                (if (= 1 (count parts))
                  [(str/trim (first parts)) nil]
                  [(str/trim (first parts)) (str/trim (second parts))])))]
        {:icon       (or icon "  ")
         :short-desc (or short-desc "")
         :long-desc  (or long-desc "")}))))

(defn discover-examples
  "Find the example source files in src/examples, excluding this launcher.
   Returns a sorted seq of {:title :ns :file :icon :short-desc :long-desc}."
  []
  (->> (.listFiles (io/file examples-dir))
       (map #(.getName ^java.io.File %))
       (filter #(str/ends-with? % ".clj"))
       (remove #{"launcher.clj"})
       sort
       (mapv (fn [fname]
               (let [base   (str/replace fname #"\.clj$" "")
                     path   (str examples-dir "/" fname)
                     source (slurp path)
                     meta   (parse-ns-metadata source)]
                 (merge {:title (->> (str/split base #"_")
                                     (map str/capitalize)
                                     (str/join " "))
                         :ns    (str "examples." (str/replace base "_" "-"))
                         :file  path}
                        meta))))))

;; ---------------------------------------------------------------------------
;; sci loading
;;
;; Each example requires a handful of atomstream namespaces (the mirrors of
;; charm.*), sibling namespaces, java classes, and a couple of clojure.core fns
;; missing from sci's built-ins. We expose the host's real vars into the sci
;; context so the example runs against the real framework, but override
;; `program/run` to capture the app spec rather than start a TUI.
;; ---------------------------------------------------------------------------

(def ^:private atomstream-nses
  '[atomstream.ansi.width
    atomstream.components.help
    atomstream.components.list
    atomstream.components.progress
    atomstream.components.spinner
    atomstream.components.table
    atomstream.components.text-input
    atomstream.components.timer
    atomstream.components.viewport
    atomstream.message
    atomstream.program
    atomstream.style.border
    atomstream.style.core
    atomstream.style.overlay])

(defn- make-ctx
  "Build a sci context. `captured` is an atom that intercepted `run` fills with
   the example's app spec."
  [captured]
  (doseq [n atomstream-nses] (require n))
  (let [ns-map (into {}
                     (map (fn [n]
                            (let [vars (update-vals (ns-publics n) deref)]
                              [n (if (= n 'atomstream.program)
                                   (assoc vars 'run (fn [opts] (reset! captured opts) opts))
                                   vars)])))
                     atomstream-nses)]
    (sci/init
     {:namespaces (assoc ns-map
                         'clojure.java.io (update-vals (ns-publics 'clojure.java.io) deref)
                         ;; clojure.core fns sci 0.12.x doesn't ship
                         'clojure.core {'parse-long    parse-long
                                        'parse-double  parse-double
                                        'parse-boolean parse-boolean
                                        'slurp         slurp
                                        'spit          spit
                                        'tap>          tap>
                                        'requiring-resolve requiring-resolve})
      :classes    {'Math   Math      'System System 'Thread Thread
                   'java.lang.Math   Math
                   'java.lang.System System
                   'java.lang.Thread Thread
                   'java.io.File     java.io.File
                   'java.text.SimpleDateFormat java.text.SimpleDateFormat
                   'java.util.Date   java.util.Date}
      ;; Let examples require sibling example namespaces (e.g. the cheatsheet's
      ;; data ns) by loading their source from disk.
      :load-fn    (fn [{:keys [namespace]}]
                    (let [path (str "src/"
                                    (-> (str namespace)
                                        (str/replace "." "/")
                                        (str/replace "-" "_"))
                                    ".clj")]
                      (when (.exists (io/file path))
                        {:file path :source (slurp path)})))})))

(defn load-app
  "Evaluate an example's source with sci and return its app spec
   ({:init :update :view ...}), or nil if it didn't call `run` (e.g. it needs
   command-line args and printed usage instead)."
  [{:keys [ns file]}]
  (let [captured (atom nil)
        ctx      (make-ctx captured)]
    (try
      (sci/binding [sci/out *out* sci/err *err*]
        (sci/eval-string* ctx (slurp file))
        (sci/eval-string* ctx (str "(" ns "/-main)")))
      @captured
      (catch Throwable e
        (println "Failed to load" ns "-" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; Styles
;; ---------------------------------------------------------------------------

(def ^:private title-style  (style/style :fg style/magenta :bold true))
(def ^:private header-style (style/style :fg style/magenta :bold true))
(def ^:private cursor-style (style/style :fg :black :bg style/cyan :bold true))
(def ^:private modal-title-style  (style/style :fg style/cyan :bold true))
(def ^:private modal-key-style    (style/style :fg style/green :bold true))

;; ---------------------------------------------------------------------------
;; Init
;; ---------------------------------------------------------------------------

(defn init []
  (let [examples (discover-examples)
        rows     (mapv (fn [e]
                         [(:icon e) (:title e) (:short-desc e)])
                       examples)
        tbl      (table/table [{:title "Icon" :width 4}
                               {:title "Name" :width 18}
                               {:title "Description" :width 52}]
                              rows
                              :cursor 0
                              :header-style header-style
                              :cursor-style cursor-style)
        bindings (help/from-pairs
                  "j/k" "navigate" "enter" "run" "?" "info" "q" "quit")]
    [{:table    tbl
      :examples examples
      :mode     :browse
      :help     (help/help bindings)
      :width    80
      :height   24}
     nil]))

;; ---------------------------------------------------------------------------
;; Modal
;; ---------------------------------------------------------------------------

(defn- build-modal-content
  "Build modal text for the selected example."
  [example]
  (let [{:keys [icon title short-desc long-desc]} example
        lines (cond-> [(str icon " " (style/render modal-title-style title))
                       ""
                       short-desc]
                (seq long-desc)
                (into (concat [""] (str/split-lines long-desc)))

                true
                (into ["" (str (style/render modal-key-style "enter") "  launch")]))]
    (str/join "\n" lines)))

(defn- open-modal [state]
  (let [idx       (table/table-cursor (:table state))
        example   (nth (:examples state) idx)
        ov-width  (max 40 (- (:width state) 16))
        cnt-width (- ov-width 4)
        ov-height (max 5 (- (:height state) 10))
        content   (build-modal-content example)]
    (-> state
        (assoc :mode :modal)
        (assoc :modal {:example  example
                       :viewport (viewport/viewport content
                                                    :height ov-height
                                                    :width cnt-width)}))))

(defn- close-modal [state]
  (assoc state :mode :browse))

(defn- render-modal-panel [state]
  (let [{:keys [viewport]} (:modal state)
        example (:example (:modal state))
        title   (str " " (:title example) " ")
        content (viewport/viewport-view viewport)
        bordered (style/render (style/style :border border/rounded
                                            :border-fg style/cyan
                                            :padding [0 1])
                               content)
        bordered-lines (str/split-lines bordered)
        top-line (first bordered-lines)
        top-width (ansi-width/string-width top-line)
        title-styled (style/render modal-title-style title)
        corner-l "╭─"
        corner-r (let [title-area-width (ansi-width/string-width (str corner-l title))
                       remaining (max 0 (- top-width title-area-width 1))]
                   (str (apply str (repeat remaining "─")) "╮"))
        new-top (str (style/render (style/style :fg style/cyan) corner-l)
                     title-styled
                     (style/render (style/style :fg style/cyan) corner-r))
        final-lines (assoc (vec bordered-lines) 0 new-top)]
    (str/join "\n" final-lines)))

;; ---------------------------------------------------------------------------
;; Update
;; ---------------------------------------------------------------------------

(defn update-fn [state msg]
  (cond
    ;; Window resize
    (msg/window-size? msg)
    [(assoc state :width (:width msg) :height (:height msg)) nil]

    ;; Global quit
    (msg/key-match? msg "ctrl+c")
    [state program/quit-cmd]

    :else
    (case (:mode state)
      ;; ----- Browse -----
      :browse
      (cond
        (msg/key-match? msg "q")
        [(assoc state :action :quit) program/quit-cmd]

        (msg/key-match? msg "enter")
        (let [idx      (table/table-cursor (:table state))
              example  (nth (:examples state) idx)]
          [(assoc state :action :run :selected example) program/quit-cmd])

        (msg/key-match? msg "?")
        [(open-modal state) nil]

        :else
        (let [[tbl cmd] (table/table-update (:table state) msg)]
          [(assoc state :table tbl) cmd]))

      ;; ----- Modal -----
      :modal
      (cond
        (or (msg/key-match? msg "escape")
            (msg/key-match? msg "?")
            (msg/key-match? msg "q"))
        [(close-modal state) nil]

        (msg/key-match? msg "enter")
        (let [example (:example (:modal state))]
          [(assoc state :action :run :selected example) program/quit-cmd])

        (or (msg/key-match? msg "down") (msg/key-match? msg "j")
            (msg/key-match? msg "up") (msg/key-match? msg "k")
            (msg/key-match? msg "ctrl+d") (msg/key-match? msg "ctrl+u"))
        (let [[vp _] (viewport/viewport-update (get-in state [:modal :viewport]) msg)]
          [(assoc-in state [:modal :viewport] vp) nil])

        :else
        [state nil]))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn view [state]
  (let [base (str (style/render title-style "example launcher") "\n\n"
                  (table/table-view (:table state)) "\n\n"
                  (help/short-help-view (:help state)))]
    (if (= :modal (:mode state))
      (let [panel       (render-modal-panel state)
            base-lines  (str/split-lines base)
            panel-lines (str/split-lines panel)
            base-w      (reduce max 1 (map ansi-width/string-width base-lines))
            panel-w     (reduce max 1 (map ansi-width/string-width panel-lines))
            x           (max 0 (quot (- base-w panel-w) 2))
            y           (max 2 (quot (- (count base-lines) (count panel-lines)) 2))]
        (overlay/place-overlay base panel x y))
      base)))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn- run-launcher [run-fn]
  (run-fn {:init init :update update-fn :view view :alt-screen true}))

(defn -main [& args]
  (let [web-only? (some #{"--web-only"} args)
        run-fn    (if web-only? program/run-web-only program/run)]
    (loop []
      (let [final (run-launcher run-fn)]
        (when (= :run (:action final))
          (if-let [opts (load-app (:selected final))]
            (run-fn opts)
            (println "Example" (:ns (:selected final)) "did not start (needs CLI args?)"))
          (recur))))))

(comment
  ; "Start the cheatsheet app in the background. Returns a handle:
  ;    {:quit!  (fn [] ...) - stop the app
  ;     :result (promise)   - deref to get the final state}"
  ; []
  (def app (program/run-async {:init init
                               :update update-fn
                               :view view
                               :alt-screen true}))
  ((:quit! app))
  ,)
