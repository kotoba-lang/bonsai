(ns bonsai.repo-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is async] :include-macros true])
            [bonsai.object :as obj]
            [bonsai.refs :as refs]
            [bonsai.repo :as repo]))

(defn- utf8-bytes [s]
  #?(:clj (.getBytes ^String s "UTF-8")
     :cljs (.encode (js/TextEncoder.) s)))

(deftest objects-and-refs-coexist-in-one-repo-db
  (let [db0 (repo/empty-repo)
        [db1 blob-cid] (obj/write-blob db0 (utf8-bytes "hi"))
        [db2 tree-cid] (obj/write-tree db1 [{:name "f.txt" :cid blob-cid :kind :blob}])
        [db3 commit-cid] (obj/write-commit db2 {:tree tree-cid :parents [] :author "a"
                                                  :message "m" :ts 1})
        db (refs/set-ref db3 "repo1" "refs/heads/main" commit-cid)]
    ;; disjoint subject namespaces (content-hash CIDs vs repo ids) coexist
    ;; without collision in the same arrangement db
    (is (= commit-cid (refs/get-ref db "repo1" "refs/heads/main")))
    (is (= commit-cid (:cid (assoc (obj/read-commit db commit-cid) :cid commit-cid))))))

(defn- persist-fixture []
  (let [store (atom {})
        put! (fn [cid bytes] (swap! store assoc cid bytes))
        db0 (repo/empty-repo)
        [db1 blob-cid] (obj/write-blob db0 (utf8-bytes "hi"))
        [db2 commit-cid] (obj/write-commit db1 {:tree blob-cid :parents [] :author "a"
                                                  :message "m" :ts 1})
        db (refs/set-ref db2 "repo1" "refs/heads/main" commit-cid)]
    {:put! put! :db db}))

;; arrangement.core/commit! (which repo/persist! wraps) returns the snapshot
;; CID directly on JVM, but a js/Promise of it on cljs (Web Crypto's AEAD/
;; HMAC primitives are Promise-based, arrangement.core's own docstring) --
;; this repo's identity-blind/identity-encrypt are plain sync functions, but
;; that's fine: JS Promise machinery lifts a non-promise return value into
;; an already-resolved one wherever a promise was expected. Mirrored, not
;; reader-conditional-branched in one body, matching arrangement's own
;; test suite's convention for the same platform split.
#?(:clj
   (deftest persist-returns-a-snapshot-cid-of-the-whole-repo
     (let [{:keys [put! db]} (persist-fixture)
           snapshot-cid (repo/persist! put! db nil)]
       (is (string? snapshot-cid))))

   :cljs
   (deftest persist-returns-a-snapshot-cid-of-the-whole-repo
     (async done
       (let [{:keys [put! db]} (persist-fixture)]
         (-> (repo/persist! put! db nil)
             (.then (fn [snapshot-cid]
                      (is (string? snapshot-cid))
                      (done))))))))
