(ns atomstream.impl.mirror)

(defmacro mirror-ns
  "Intern every public var of `src-ns` into the current namespace, preserving
   metadata. Optionally `:exclude` a set of symbols to redefine locally afterwards."
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
