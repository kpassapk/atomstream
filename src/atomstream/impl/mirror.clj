(ns atomstream.impl.mirror
  "Re-export every public var of a source namespace into the current one.

   atomstream mirrors charm's namespaces verbatim so that an app can switch
   `charm.x` -> `atomstream.x` and keep working unchanged. Only the handful of
   vars atomstream needs to override (e.g. `run`) are excluded and redefined.")

(defmacro mirror-ns
  "Intern every public var of `src-ns` into the current namespace, preserving
   metadata (so macros stay macros, arglists/docs survive). Optionally
   `:exclude` a set of symbols to redefine locally afterwards."
  [src-ns & {:keys [exclude] :or {exclude #{}}}]
  `(do
     (require '~src-ns)
     (let [excluded# ~exclude]
       (doseq [[sym# var#] (ns-publics '~src-ns)
               :when (not (contains? excluded# sym#))]
         (intern '~(symbol (str *ns*))
                 (with-meta sym# (meta var#))
                 (if (bound? var#) (deref var#) nil))))
     nil))
