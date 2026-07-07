(ns kotoba-git.object-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba-git.object :as obj]))

(defn- new-store []
  (let [store (atom {})]
    {:put! (fn [cid bytes] (swap! store assoc cid bytes))
     :get-fn (fn [cid] (get @store cid))
     :store store}))

(deftest blob-roundtrip
  (let [{:keys [put! get-fn]} (new-store)
        bytes (.getBytes "hello, kotoba-git" "UTF-8")
        cid (obj/write-blob! put! bytes)]
    (is (string? cid))
    (is (= (seq bytes) (seq (obj/read-blob get-fn cid))))
    (testing "content-addressing is deterministic"
      (is (= cid (obj/write-blob! put! bytes))))))

(deftest tree-roundtrip
  (let [{:keys [put! get-fn]} (new-store)
        blob-cid (obj/write-blob! put! (.getBytes "contents" "UTF-8"))
        tree-cid (obj/write-tree! put! [{:name "b.txt" :cid blob-cid :kind :blob}
                                         {:name "a.txt" :cid blob-cid :kind :blob}])
        tree (obj/read-tree get-fn tree-cid)]
    (is (= :tree (:kind tree)))
    (testing "entries are sorted by name (git tree ordering)"
      (is (= ["a.txt" "b.txt"] (mapv :name (:entries tree)))))
    (is (every? #(= :blob (:kind %)) (:entries tree)))
    (is (every? #(= blob-cid (:cid %)) (:entries tree)))))

(deftest nested-tree-roundtrip
  (let [{:keys [put! get-fn]} (new-store)
        blob-cid (obj/write-blob! put! (.getBytes "nested" "UTF-8"))
        inner-cid (obj/write-tree! put! [{:name "f.txt" :cid blob-cid :kind :blob}])
        outer-cid (obj/write-tree! put! [{:name "dir" :cid inner-cid :kind :tree}])
        outer (obj/read-tree get-fn outer-cid)]
    (is (= [{:name "dir" :cid inner-cid :kind :tree}] (:entries outer)))))

(deftest commit-roundtrip
  (let [{:keys [put! get-fn]} (new-store)
        blob-cid (obj/write-blob! put! (.getBytes "v1" "UTF-8"))
        tree-cid (obj/write-tree! put! [{:name "f.txt" :cid blob-cid :kind :blob}])
        commit-cid (obj/write-commit! put! {:tree tree-cid :parents []
                                             :author "did:key:zAlice" :message "initial"
                                             :ts 1000})
        commit (obj/read-commit get-fn commit-cid)]
    (is (= tree-cid (:tree commit)))
    (is (= [] (:parents commit)))
    (is (= "did:key:zAlice" (:author commit)))
    (is (= "initial" (:message commit)))
    (is (= 1000 (:ts commit)))))

(deftest merge-commit-has-multiple-parents
  (let [{:keys [put! get-fn]} (new-store)
        tree-cid (obj/write-tree! put! [])
        c1 (obj/write-commit! put! {:tree tree-cid :parents [] :author "a" :message "c1" :ts 1})
        c2 (obj/write-commit! put! {:tree tree-cid :parents [] :author "a" :message "c2" :ts 2})
        merge (obj/write-commit! put! {:tree tree-cid :parents [c1 c2] :author "a" :message "merge" :ts 3})]
    (is (= [c1 c2] (:parents (obj/read-commit get-fn merge))))))
