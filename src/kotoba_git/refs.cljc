(ns kotoba-git.refs
  "Mutable ref/branch pointers (e.g. \"refs/heads/main\" -> commit CID) as
   quads in an arrangement db — the mutable-pointer-over-immutable-content-
   addressed-DAG pattern, same shape Datomic uses for its own indexes."
  (:require [arrangement.core :as arr]
            [arrangement.query :as q]
            [ipld.core :as ipld]))

;; Refs are public repo metadata — there is nothing to keep secret about
;; "which ref points at which commit" — so these are identity pass-throughs
;; satisfying arrangement.core/commit!'s mandatory blind/encrypt contract
;; (ADR-2607051000) without adding privacy semantics this domain doesn't need.
(defn- identity-blind [v] (str v))
(defn- identity-encrypt [bytes] bytes)

(defn empty-refs [] (arr/empty-db))

(defn- ref-pred [ref-name] (str "ref:" ref-name))

(defn set-ref
  "Point ref-name at commit-cid, replacing whatever it previously pointed at."
  [db repo-id ref-name commit-cid]
  (as-> db db
    (if-let [prev-link (some->> (q/query db [repo-id (ref-pred ref-name) nil] (constantly true))
                                 first :o)]
      (arr/retract-quad db {:s repo-id :p (ref-pred ref-name) :o prev-link})
      db)
    (arr/assert-quad db {:s repo-id :p (ref-pred ref-name) :o (ipld/link commit-cid)})))

(defn get-ref
  "The commit CID ref-name currently points at, or nil."
  [db repo-id ref-name]
  (some-> (q/query db [repo-id (ref-pred ref-name) nil] (constantly true))
          first :o ipld/link-cid))

(defn list-refs
  "{ref-name commit-cid} for every ref registered under repo-id."
  [db repo-id]
  (into {}
        (keep (fn [[p os]]
                (when (clojure.string/starts-with? p "ref:")
                  [(subs p 4) (ipld/link-cid (first os))])))
        (arr/entity-attrs db repo-id)))

(defn persist!
  "Commit the current refs db to content-addressed storage, returning the
   new snapshot CID (arrangement.core/commit!, ADR-2607051000)."
  [put! db prev-cid]
  (arr/commit! put! db prev-cid arr/current-schema-version identity-blind identity-encrypt))
