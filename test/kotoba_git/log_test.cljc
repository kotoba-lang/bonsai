(ns kotoba-git.log-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba-git.object :as obj]
            [kotoba-git.log :as log]))

(defn- new-store []
  (let [store (atom {})]
    {:put! (fn [cid bytes] (swap! store assoc cid bytes))
     :get-fn (fn [cid] (get @store cid))}))

(defn- linear-history [put!]
  (let [tree0 (obj/write-tree! put! [])
        c1 (obj/write-commit! put! {:tree tree0 :parents [] :author "a" :message "c1" :ts 1})
        c2 (obj/write-commit! put! {:tree tree0 :parents [c1] :author "a" :message "c2" :ts 2})
        c3 (obj/write-commit! put! {:tree tree0 :parents [c2] :author "a" :message "c3" :ts 3})]
    {:c1 c1 :c2 c2 :c3 c3}))

(deftest log-walks-first-parent-newest-first
  (let [{:keys [put! get-fn]} (new-store)
        {:keys [c1 c2 c3]} (linear-history put!)]
    (is (= [c3 c2 c1] (mapv :cid (log/log get-fn c3))))
    (is (= ["c3" "c2" "c1"] (mapv :message (log/log get-fn c3))))))

(deftest ancestors-covers-merge-commits
  (let [{:keys [put! get-fn]} (new-store)
        tree0 (obj/write-tree! put! [])
        c1 (obj/write-commit! put! {:tree tree0 :parents [] :author "a" :message "c1" :ts 1})
        branch-a (obj/write-commit! put! {:tree tree0 :parents [c1] :author "a" :message "a" :ts 2})
        branch-b (obj/write-commit! put! {:tree tree0 :parents [c1] :author "a" :message "b" :ts 2})
        merge (obj/write-commit! put! {:tree tree0 :parents [branch-a branch-b] :author "a" :message "merge" :ts 3})]
    (is (= #{c1 branch-a branch-b merge} (log/ancestors get-fn merge)))))

(deftest missing-since-finds-new-commit-tree-and-blob
  (let [{:keys [put! get-fn]} (new-store)
        blob1 (obj/write-blob! put! (.getBytes "v1" "UTF-8"))
        tree1 (obj/write-tree! put! [{:name "f.txt" :cid blob1 :kind :blob}])
        c1 (obj/write-commit! put! {:tree tree1 :parents [] :author "a" :message "c1" :ts 1})
        blob2 (obj/write-blob! put! (.getBytes "v2" "UTF-8"))
        tree2 (obj/write-tree! put! [{:name "f.txt" :cid blob2 :kind :blob}])
        c2 (obj/write-commit! put! {:tree tree2 :parents [c1] :author "a" :message "c2" :ts 2})]
    (testing "peer already has everything up to c1"
      (let [have (conj (log/ancestors get-fn c1) tree1 blob1)
            missing (log/missing-since get-fn c2 have)]
        (is (= #{c2 tree2 blob2} missing))))
    (testing "peer has nothing"
      (is (= #{c1 tree1 blob1 c2 tree2 blob2} (log/missing-since get-fn c2 #{}))))))
