(ns kotoba-git.ref-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba-git.object :as obj]
            [kotoba-git.repo :as repo]
            [kotoba-git.refs :as refs]
            [kotoba-git.ref-policy :as policy]))

(defn- linear-history [db0]
  (let [[db1 tree0] (obj/write-tree db0 [])
        [db2 c1] (obj/write-commit db1 {:tree tree0 :parents [] :author "a" :message "c1" :ts 1})
        [db3 c2] (obj/write-commit db2 {:tree tree0 :parents [c1] :author "a" :message "c2" :ts 2})
        [db c3] (obj/write-commit db3 {:tree tree0 :parents [c2] :author "a" :message "c3" :ts 3})]
    {:db db :c1 c1 :c2 c2 :c3 c3}))

(deftest creating-a-ref-is-always-a-fast-forward
  (let [{:keys [db c1]} (linear-history (repo/empty-repo))]
    (is (true? (policy/fast-forward? db nil c1)))))

(deftest advancing-along-the-same-linear-history-is-a-fast-forward
  (let [{:keys [db c1 c2 c3]} (linear-history (repo/empty-repo))]
    (is (true? (policy/fast-forward? db c1 c2)))
    (is (true? (policy/fast-forward? db c1 c3)))
    (is (true? (policy/fast-forward? db c2 c3)))))

(deftest a-no-op-update-to-the-same-commit-is-a-fast-forward
  (let [{:keys [db c2]} (linear-history (repo/empty-repo))]
    (is (true? (policy/fast-forward? db c2 c2)))))

(deftest moving-backward-is-not-a-fast-forward
  (let [{:keys [db c1 c3]} (linear-history (repo/empty-repo))]
    (is (false? (policy/fast-forward? db c3 c1)))))

(deftest moving-to-a-diverged-sibling-branch-is-not-a-fast-forward
  (let [db0 (repo/empty-repo)
        [db1 tree0] (obj/write-tree db0 [])
        [db2 c1] (obj/write-commit db1 {:tree tree0 :parents [] :author "a" :message "c1" :ts 1})
        [db3 branch-a] (obj/write-commit db2 {:tree tree0 :parents [c1] :author "a" :message "a" :ts 2})
        [db branch-b] (obj/write-commit db3 {:tree tree0 :parents [c1] :author "a" :message "b" :ts 2})]
    (is (false? (policy/fast-forward? db branch-a branch-b)))
    (is (false? (policy/fast-forward? db branch-b branch-a)))))

(deftest a-merge-commit-is-a-fast-forward-from-either-parent
  (let [db0 (repo/empty-repo)
        [db1 tree0] (obj/write-tree db0 [])
        [db2 c1] (obj/write-commit db1 {:tree tree0 :parents [] :author "a" :message "c1" :ts 1})
        [db3 branch-a] (obj/write-commit db2 {:tree tree0 :parents [c1] :author "a" :message "a" :ts 2})
        [db4 branch-b] (obj/write-commit db3 {:tree tree0 :parents [c1] :author "a" :message "b" :ts 2})
        [db merge] (obj/write-commit db4 {:tree tree0 :parents [branch-a branch-b] :author "a" :message "merge" :ts 3})]
    (is (true? (policy/fast-forward? db branch-a merge)))
    (is (true? (policy/fast-forward? db branch-b merge)))))

(deftest set-ref-ff-only-allows-fast-forwards-and-creation
  (let [{:keys [db c1 c2]} (linear-history (repo/empty-repo))
        db1 (policy/set-ref-ff-only! db "repo1" "refs/heads/main" c1)
        db2 (policy/set-ref-ff-only! db1 "repo1" "refs/heads/main" c2)]
    (is (= c2 (refs/get-ref db2 "repo1" "refs/heads/main")))))

(deftest set-ref-ff-only-rejects-a-non-fast-forward-and-leaves-the-ref-untouched
  (let [{:keys [db c1 c2]} (linear-history (repo/empty-repo))
        db1 (policy/set-ref-ff-only! db "repo1" "refs/heads/main" c2)]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (policy/set-ref-ff-only! db1 "repo1" "refs/heads/main" c1)))
    (testing "the ref itself never moved -- set-ref-ff-only! throws BEFORE writing"
      (is (= c2 (refs/get-ref db1 "repo1" "refs/heads/main"))))))

(deftest thrown-exception-carries-diagnostic-data
  (let [{:keys [db c1 c2]} (linear-history (repo/empty-repo))
        db1 (policy/set-ref-ff-only! db "repo1" "refs/heads/main" c2)]
    (try
      (policy/set-ref-ff-only! db1 "repo1" "refs/heads/main" c1)
      (is false "expected an exception to be thrown")
      (catch #?(:clj Exception :cljs js/Error) e
        (let [data (ex-data e)]
          (is (= "repo1" (:repo-id data)))
          (is (= "refs/heads/main" (:ref data)))
          (is (= c2 (:current data)))
          (is (= c1 (:proposed data))))))))
