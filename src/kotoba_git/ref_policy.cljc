(ns kotoba-git.ref-policy
  "Ref-update policy: deciding whether a proposed ref move is allowed given
   its *shape* (fast-forward vs not), as distinct from kotoba-rad's job of
   deciding *who* is allowed to move a ref. A repo can compose both: check
   authorize-push? (identity) and fast-forward? (shape) before calling
   kotoba-git.refs/set-ref, which itself enforces neither on its own."
  (:require [kotoba-git.log :as log]
            [kotoba-git.refs :as refs]))

(defn fast-forward?
  "Is moving a ref from old-commit-cid to new-commit-cid a fast-forward?
   True if old-commit-cid is nil (the ref doesn't exist yet -- creating a
   ref is always a fast-forward), if it equals new-commit-cid (a no-op
   update), or if it's one of new-commit-cid's ancestors in the commit DAG."
  [db old-commit-cid new-commit-cid]
  (or (nil? old-commit-cid)
      (= old-commit-cid new-commit-cid)
      (contains? (log/ancestors db new-commit-cid) old-commit-cid)))

(defn set-ref-ff-only!
  "Like kotoba-git.refs/set-ref, but throws ex-info (rather than silently
   moving the ref) if the update is not a fast-forward. Use this instead
   of set-ref directly when a repo/branch policy requires ff-only pushes
   (e.g. any branch other than a force-pushable feature branch)."
  [db repo-id ref-name commit-cid]
  (let [current (refs/get-ref db repo-id ref-name)]
    (when-not (fast-forward? db current commit-cid)
      (throw (ex-info "ref update is not a fast-forward"
                       {:repo-id repo-id :ref ref-name
                        :current current :proposed commit-cid})))
    (refs/set-ref db repo-id ref-name commit-cid)))
