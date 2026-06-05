(ns atomstream.impl.web
  (:require [atomstream.impl.ansi-html :as ah]
            [charm.message :as msg]
            [charm.program :as prog]
            [clojure.core.async :as a]
            [hyperlith.core :as h :refer [defaction defview]]))

;; ---------------------------------------------------------------------------
;; Shared wiring (one running app per JVM, like charm/run itself)
;; ---------------------------------------------------------------------------

(defonce ^:private ext-ch_ (atom nil))   ; web -> charm event channel
(defonce ^:private ansi_   (atom ""))    ; latest view string

(defn- dispatch! [m]
  (when-let [ch @ext-ch_] (a/put! ch m)))

;; ---------------------------------------------------------------------------
;; Browser key -> charm key
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; CSS
;; ---------------------------------------------------------------------------

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
;; run-with-web
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
      (println (str "atomstream web: http://localhost:" port))
      (try
        (prog/run (assoc opts :init wrap-init :update wrap-update :view wrap-view))
        (finally
          (a/close! ext-ch)
          ((:stop web)))))))
