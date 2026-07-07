(ns kotoba-git.log
  "History walking over the commit DAG: reachability, first-parent log, and
   the missing-object negotiation a push/pull/pack exchange needs."
  (:refer-clojure :exclude [ancestors])
  (:require [kotoba-git.object :as obj]))

(defn ancestors
  "Set of every commit CID reachable from head-cid (inclusive), following
   all parent edges — i.e. the real DAG, not just first-parent."
  [get-fn head-cid]
  (loop [frontier [head-cid] seen #{}]
    (if (empty? frontier)
      seen
      (let [cid (peek frontier)
            frontier (pop frontier)]
        (if (or (nil? cid) (seen cid))
          (recur frontier seen)
          (recur (into frontier (:parents (obj/read-commit get-fn cid)))
                 (conj seen cid)))))))

(defn log
  "Commits from head-cid walking first-parent history, newest-first."
  [get-fn head-cid]
  (->> head-cid
       (iterate (fn [cid] (when cid (first (:parents (obj/read-commit get-fn cid))))))
       (take-while some?)
       (mapv (fn [cid] (assoc (obj/read-commit get-fn cid) :cid cid)))))

(defn- tree-object-cids
  "All CIDs reachable from a tree (its entries, recursively through subtrees)."
  [get-fn tree-cid]
  (mapcat (fn [{:keys [cid kind]}]
            (cons cid (when (= kind :tree) (tree-object-cids get-fn cid))))
          (:entries (obj/read-tree get-fn tree-cid))))

(defn missing-since
  "Every CID (commit, tree, or blob) reachable from head-cid's history that
   is not already in `have` (a set of CIDs the requester reports holding).
   This is the object-level negotiation a pack/pull exchange needs to decide
   what to actually transfer."
  [get-fn head-cid have]
  (reduce
   (fn [acc commit-cid]
     (let [commit (obj/read-commit get-fn commit-cid)
           reachable (cons (:tree commit) (tree-object-cids get-fn (:tree commit)))]
       (into acc (remove have (cons commit-cid reachable)))))
   #{}
   (ancestors get-fn head-cid)))
