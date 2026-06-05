(ns examples.file-browser
  "File browser demonstrating list component with a details pane."
  (:require
    [atomstream.ansi.width :as w]
    [atomstream.components.help :as help]
    [atomstream.core :as charm]
    [atomstream.style.border :as border]
    [atomstream.style.core :as style]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [java.io File]
    [java.text SimpleDateFormat]
    [java.util Date]))

(def title-style
  (style/style :fg charm/magenta :bold true))

(def path-style
  (style/style :fg 240))

(def detail-label-style
  (style/style :fg 240))

(def detail-value-style
  (style/style :fg charm/cyan))

(def help-bindings
  (charm/help-from-pairs
   "j/k" "navigate"
   "Enter/l" "open"
   "Backspace/h" "back"
   "q" "quit"))

(def ^:private details-width 36)

;; Header (title + path + blank line) + blank line before help + help line
(def ^:private chrome-height 5)

(defn format-size
  "Format file size in human-readable format."
  [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    (< bytes (* 1024 1024 1024)) (format "%.1f MB" (/ bytes 1024.0 1024.0))
    :else (format "%.1f GB" (/ bytes 1024.0 1024.0 1024.0))))

(defn format-date
  "Format timestamp as date string."
  [timestamp]
  (.format (SimpleDateFormat. "yyyy-MM-dd HH:mm") (Date. timestamp)))

(defn file-info
  "Get info map for a file."
  [^File f]
  {:name (.getName f)
   :path (.getAbsolutePath f)
   :directory? (.isDirectory f)
   :size (.length f)
   :modified (.lastModified f)
   :readable? (.canRead f)
   :writable? (.canWrite f)
   :hidden? (.isHidden f)})

(defn list-directory
  "List files in a directory."
  [path]
  (let [dir (io/file path)
        files (.listFiles dir)]
    (when files
      (->> files
           (map file-info)
           (sort-by (juxt (comp not :directory?) :name))
           vec))))

(defn file->list-item
  "Convert file info to list item format."
  [info]
  (let [icon (if (:directory? info) "/" "")]
    {:title (str (:name info) icon)
     :description (if (:directory? info)
                    "Directory"
                    (format-size (:size info)))
     :data info}))

(defn- list-width [term-width]
  (max 20 (- term-width details-width)))

(defn- list-height
  "Number of list items visible. Each item takes 2 lines (title + description)."
  [term-height]
  (max 3 (quot (- term-height chrome-height) 2)))

(defn- make-file-list [items term-width term-height]
  (charm/item-list items
                   :height (list-height term-height)
                   :width (list-width term-width)
                   :show-descriptions true
                   :cursor-style (charm/style :fg charm/cyan :bold true)))

(defn init []
  (let [start-path (System/getProperty "user.dir")
        files (list-directory start-path)
        items (mapv file->list-item files)]
    [{:current-path start-path
      :files files
      :items items
      :term-width 80
      :term-height 24
      :file-list (make-file-list items 80 24)
      :help (charm/help help-bindings :width 60)}
     nil]))

(defn navigate-to
  "Navigate to a directory."
  [state path]
  (let [files (list-directory path)]
    (if files
      (let [items (mapv file->list-item files)]
        (assoc state
               :current-path path
               :files files
               :items items
               :file-list (make-file-list items (:term-width state) (:term-height state))))
      state)))

(defn go-up
  "Navigate to parent directory."
  [state]
  (let [parent (.getParent (io/file (:current-path state)))]
    (if parent
      (navigate-to state parent)
      state)))

(defn enter-selected
  "Enter selected directory or do nothing for files."
  [state]
  (let [selected (charm/list-selected-item (:file-list state))]
    (when-let [info (:data selected)]
      (if (:directory? info)
        (navigate-to state (:path info))
        state))))

(defn update-fn [state msg]
  (cond
    ;; Quit
    (or (charm/key-match? msg "q")
        (charm/key-match? msg "ctrl+c")
        (charm/key-match? msg "esc"))
    [state charm/quit-cmd]

    ;; Window resize
    (charm/window-size? msg)
    (let [w (:width msg)
          h (:height msg)]
      [(assoc state
              :term-width w
              :term-height h
              :file-list (make-file-list (:items state) w h))
       nil])

    ;; Go up directory
    (or (charm/key-match? msg "backspace")
        (charm/key-match? msg "h")
        (charm/key-match? msg :left))
    [(go-up state) nil]

    ;; Enter directory
    (or (charm/key-match? msg "enter")
        (charm/key-match? msg "l")
        (charm/key-match? msg :right))
    [(or (enter-selected state) state) nil]

    ;; Pass to list for navigation
    :else
    (let [[new-list cmd] (charm/list-update (:file-list state) msg)]
      [(assoc state :file-list new-list) cmd])))

(defn render-details
  "Render the details pane for selected file."
  [state]
  (if-let [selected (charm/list-selected-item (:file-list state))]
    (let [info (:data selected)]
      (str (style/render detail-label-style "Name     ")
           (style/render detail-value-style (:name info)) "\n"
           (style/render detail-label-style "Type     ")
           (style/render detail-value-style (if (:directory? info) "Directory" "File")) "\n"
           (style/render detail-label-style "Size     ")
           (style/render detail-value-style (format-size (:size info))) "\n"
           (style/render detail-label-style "Modified ")
           (style/render detail-value-style (format-date (:modified info))) "\n"
           (style/render detail-label-style "Access   ")
           (style/render detail-value-style
                         (str (when (:readable? info) "r")
                              (when (:writable? info) "w")
                              (when (:hidden? info) " (hidden)")))))
    (style/render detail-label-style "No file selected")))

(defn- two-columns
  "Join left and right text blocks into a fixed-width, fixed-height grid.
   Each row is: left padded to left-width, then right. Rows are filled to height."
  [left right left-width height]
  (let [left-lines (str/split-lines left)
        right-lines (str/split-lines right)
        render-row (fn [i]
                     (str (w/pad-right (nth left-lines i "") left-width)
                          (nth right-lines i "")))]
    (str/join "\n" (map render-row (range height)))))

(defn view [state]
  (let [file-list-view (charm/list-view (:file-list state))
        details-view (render-details state)
        details-style (style/style :border border/rounded
                                   :border-fg 240
                                   :padding [0 1]
                                   :width (- details-width 2))
        content-height (* (list-height (:term-height state)) 2)
        left-w (list-width (:term-width state))]
    (str (style/render title-style "File Browser") "\n"
         (style/render path-style (:current-path state)) "\n\n"
         (two-columns file-list-view
                      (style/render details-style details-view)
                      left-w
                      content-height)
         "\n"
         (help/short-help-view (:help state)))))

(defn -main [& _args]
  (charm/run {:init init
              :update update-fn
              :view view
              :alt-screen true}))
