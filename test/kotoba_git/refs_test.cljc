(ns kotoba-git.refs-test
  (:require [clojure.test :refer [deftest is testing]]
            [multiformats.core :as mf]
            [kotoba-git.refs :as refs]))

;; set-ref stores :o as a real ipld/link, which requires a genuine base32
;; 'b'-prefixed CIDv1 string -- these are fake-but-valid CIDs (raw-codec
;; hashes of distinct byte strings), standing in for real commit CIDs.
(def cid-1 (mf/cidv1-raw (.getBytes "commit-1" "UTF-8")))
(def cid-2 (mf/cidv1-raw (.getBytes "commit-2" "UTF-8")))
(def cid-3 (mf/cidv1-raw (.getBytes "commit-3" "UTF-8")))

(deftest set-and-get-ref
  (let [db (-> (refs/empty-refs)
               (refs/set-ref "repo1" "refs/heads/main" cid-1))]
    (is (= cid-1 (refs/get-ref db "repo1" "refs/heads/main")))
    (is (nil? (refs/get-ref db "repo1" "refs/heads/other")))))

(deftest moving-a-ref-replaces-the-old-target
  (let [db (-> (refs/empty-refs)
               (refs/set-ref "repo1" "refs/heads/main" cid-1)
               (refs/set-ref "repo1" "refs/heads/main" cid-2))]
    (is (= cid-2 (refs/get-ref db "repo1" "refs/heads/main")))))

(deftest list-refs-scoped-per-repo
  (let [db (-> (refs/empty-refs)
               (refs/set-ref "repo1" "refs/heads/main" cid-1)
               (refs/set-ref "repo1" "refs/heads/dev" cid-2)
               (refs/set-ref "repo2" "refs/heads/main" cid-3))]
    (is (= {"refs/heads/main" cid-1 "refs/heads/dev" cid-2} (refs/list-refs db "repo1")))
    (is (= {"refs/heads/main" cid-3} (refs/list-refs db "repo2")))))

(deftest persist-returns-a-snapshot-cid
  (let [store (atom {})
        put! (fn [cid bytes] (swap! store assoc cid bytes))
        db (-> (refs/empty-refs) (refs/set-ref "repo1" "refs/heads/main" cid-1))
        snapshot-cid (refs/persist! put! db nil)]
    (is (string? snapshot-cid))))
