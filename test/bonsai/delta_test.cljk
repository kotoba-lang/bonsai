(ns bonsai.delta-test
  (:require [bonsai.delta :as delta]
            [clojure.test :refer [deftest is]]))

(defn- utf8 [s]
  #?(:clj (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

(deftest literal-delta-roundtrip
  (let [base (utf8 "hello") result (utf8 "hello kotoba")]
    (is (= (vec result) (vec (delta/apply-delta base (delta/literal-delta base result)))))))

(deftest copy-and-insert-delta
  ;; base-size=5, result-size=6, copy offset=0 size=5, insert "!"
  (is (= (vec (utf8 "hello!"))
         (vec (delta/apply-delta (utf8 "hello")
                                 [5 6 0x90 5 1 33])))))

