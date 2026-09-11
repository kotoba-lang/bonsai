(ns bonsai.git-object-test
  (:require #?(:clj [clojure.java.shell :as shell])
            [arrangement.core :as arr]
            [bonsai.git-object :as git-object]
            [bonsai.repo :as repo]
            [clojure.test :refer [deftest is testing]]))

(defn- utf8 [s]
  #?(:clj (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

(deftest git-blob-golden-vectors
  (testing "canonical vectors published by Git tooling"
    (is (= "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"
           (git-object/object-oid "blob" (utf8 ""))))
    (is (= "ce013625030ba8dba906f756967f9e9ca394464a"
           (git-object/object-oid "blob" (utf8 "hello\n"))))))

#?(:clj
   (deftest oid-conforms-to-installed-git
     (doseq [content ["" "hello\n" "kotoba 日本語\n"]]
       (let [tmp (java.io.File/createTempFile "bonsai-git-object-" ".txt")]
         (try
           (spit tmp content)
           (let [{:keys [exit out err]} (shell/sh "git" "hash-object" (.getPath tmp))]
             (is (zero? exit) err)
             (is (= (.trim ^String out)
                    (git-object/object-oid "blob" (utf8 content)))))
           (finally (.delete tmp)))))))

(deftest frame-roundtrip-and-strict-size
  (let [body (utf8 "hello\n")
        framed (git-object/frame "blob" body)
        parsed (git-object/parse-frame framed)]
    (is (= "blob" (:type parsed)))
    (is (= 6 (:size parsed)))
    (is (= (vec body) (vec (:body parsed))))
    (is (= :size-mismatch
           (try (git-object/parse-frame (utf8 "blob 7\u0000hello\n"))
                nil
                (catch #?(:clj Exception :cljs js/Error) e (:reason (ex-data e))))))))

(deftest oid-cid-bridge-is-queryable-and-verified
  (let [db0 (repo/empty-repo)
        [db {:keys [oid cid]}] (git-object/write-object db0 "blob" (utf8 "hello\n"))
        object (git-object/read-object db cid)]
    (is (= "ce013625030ba8dba906f756967f9e9ca394464a" oid))
    (is (= cid (git-object/cid-for-oid db oid)))
    (is (= oid (:oid object)))
    (is (= "blob" (:type object)))
    (is (= (vec (utf8 "hello\n")) (vec (:body object))))
    (testing "a mutated bridge fails closed"
      (let [bad (-> db
                    (arr/retract-quad {:s (str "git.sha1/" oid) :p "git.oid/cid" :o cid})
                    (arr/assert-quad {:s (str "git.sha1/" oid) :p "git.oid/cid" :o "wrong"}))]
        (is (= :bridge-mismatch
               (try (git-object/read-object bad cid)
                    nil
                    (catch #?(:clj Exception :cljs js/Error) e (:reason (ex-data e))))))))))
