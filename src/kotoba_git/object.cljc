(ns kotoba-git.object
  "Content-addressed blob/tree/commit object model — the git-equivalent
   layer. Blobs use the raw (0x55) codec (multiformats.core); trees and
   commits are DAG-CBOR nodes (io-ipld) whose child references are real
   CBOR tag-42 links, not plain strings."
  (:require [multiformats.core :as mf]
            [ipld.core :as ipld]))

(defn write-blob!
  "Content-address raw bytes and store them via put!. Returns the blob's CID."
  [put! bytes]
  (let [cid (mf/cidv1-raw bytes)]
    (put! cid bytes)
    cid))

(defn read-blob
  "Fetch a blob's raw bytes by CID, or nil if absent."
  [get-fn cid]
  (get-fn cid))

(defn write-tree!
  "entries: seq of {:name str :cid str :kind (:blob|:tree)}.
   Persists a DAG-CBOR tree node with entries sorted by name and returns
   the tree's CID."
  [put! entries]
  (ipld/put-node!
   put!
   {"kind" "tree"
    "entries" (vec (for [{:keys [name cid kind]} (sort-by :name entries)]
                      [name (ipld/link cid) (clojure.core/name kind)]))}))

(defn read-tree
  "Decode a tree node back into {:kind :tree :entries [{:name :cid :kind} ...]}."
  [get-fn cid]
  (when-let [node (ipld/get-node get-fn cid)]
    {:kind :tree
     :entries (vec (for [[name link kind] (get node "entries")]
                      {:name name :cid (ipld/link-cid link) :kind (keyword kind)}))}))

(defn write-commit!
  "opts: {:tree cid :parents [cid ...] :author did-or-string :message str :ts long}.
   Persists a DAG-CBOR commit node (parents is a vector so merge commits —
   more than one parent — are representable) and returns the commit's CID."
  [put! {:keys [tree parents author message ts]}]
  (ipld/put-node!
   put!
   {"kind" "commit"
    "tree" (ipld/link tree)
    "parents" (mapv ipld/link parents)
    "author" author
    "message" message
    "ts" ts}))

(defn read-commit
  "Decode a commit node back into {:kind :commit :tree :parents :author :message :ts}."
  [get-fn cid]
  (when-let [node (ipld/get-node get-fn cid)]
    {:kind :commit
     :tree (ipld/link-cid (get node "tree"))
     :parents (mapv ipld/link-cid (get node "parents"))
     :author (get node "author")
     :message (get node "message")
     :ts (get node "ts")}))
