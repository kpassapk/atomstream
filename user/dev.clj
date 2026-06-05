(ns dev)

(comment
  (require '[clojure.test :refer [run-all-tests]])
  (run-all-tests #"atomstream.*-test"))

(comment
  (require '[portal.api :as p])
  (def p (p/open))
  (add-tap #'p/submit))
