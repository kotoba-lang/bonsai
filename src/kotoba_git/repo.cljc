(ns kotoba-git.repo
  "A repo is a single arrangement db holding both objects
   (kotoba-git.object) and refs (kotoba-git.refs) as quads — git is not a
   separate content-addressed store bolted onto the Datomic-shaped
   kotobase-peer stack, it is a schema within it. Object subjects are
   content hashes (blob/tree/commit CIDs); ref subjects are repo ids —
   disjoint subject namespaces, so both coexist in one db without
   collision."
  (:refer-clojure :exclude [load])
  (:require [arrangement.core :as arr]
            [ipld.core :as ipld]
            [prolly-tree.core :as pt]))

(defn empty-repo [] (arr/empty-db))

;; A repo's objects and refs are not secret at this layer — identity
;; pass-throughs satisfying arrangement.core/commit!'s mandatory blind/
;; encrypt contract (ADR-2607051000) without adding privacy semantics this
;; domain doesn't need. Platform split is REQUIRED, not cosmetic: on cljs,
;; arrangement.core's own index-root calls `.then` directly on encrypt-fn's
;; return value (Web Crypto's AEAD has no sync primitive), so encrypt-fn
;; (and blind-fn, threaded the same way) must genuinely return a
;; js/Promise there -- a plain synchronous return throws "(...).then is
;; not a function" (caught by this repo's own real ClojureScript CI, not
;; assumed from the .cljc extension alone).
(defn- identity-blind [v]
  #?(:clj (str v) :cljs (js/Promise.resolve (str v))))
(defn- identity-encrypt [bytes]
  #?(:clj bytes :cljs (js/Promise.resolve bytes)))

(defn persist!
  "Snapshot the whole repo db (objects + refs together) to content-
   addressed storage via arrangement.core/commit!. Returns the new
   snapshot CID directly on JVM, a js/Promise of it on cljs (arrangement.
   core's own platform split, inherited unchanged)."
  [put! db prev-cid]
  (arr/commit! put! db prev-cid arr/current-schema-version identity-blind identity-encrypt))

(defn load
  "Rehydrate a full repo db from a `persist!` snapshot CID. Reads the SPO
   index once and reconstructs the other covering indexes by assertion.
   `get-fn` has the same synchronous `(cid -> bytes)` contract as `persist!`'s
   `put!`. Identity encryption is deliberately inverted as identity here."
  [get-fn snapshot-cid]
  (if (nil? snapshot-cid)
    (empty-repo)
    (let [snapshot (ipld/decode (get-fn snapshot-cid))
          schema-version (get snapshot "schema-version")
          _ (when-not (= arr/current-schema-version schema-version)
              (throw (ex-info "kotoba-git: unsupported snapshot schema"
                              {:reason :unsupported-schema
                               :expected arr/current-schema-version
                               :actual schema-version})))
          root-cid (some-> (get-in snapshot ["index-roots" "spo"]) ipld/link-cid)
          entries (if root-cid (pt/scan-prefix get-fn root-cid "") [])]
      (reduce (fn [db [_ bytes]]
                (let [[s p o] (mapv arr/edn->link (ipld/decode bytes))]
                  (arr/assert-quad db {:s s :p p :o o})))
              (empty-repo)
              entries))))
