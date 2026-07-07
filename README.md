# kotoba-git

A content-addressed git object model (blob/tree/commit) and mutable ref
store, represented as **native `arrangement` (Datomic-shaped) datoms** —
not a separate content-addressed block store bolted onto the side of the
`kotobase-peer` stack. A blob/tree/commit's *subject* IS its own content
hash; `io-multiformats`/`org-ietf-cbor`/`io-ipld` contribute only their
pure canonical-encoding/hashing functions to derive that identity —
nothing is persisted through them. Git is a schema *within* the Datomic-
shaped foundation, not a store beside it.

This is the "git-equivalent" half of ADR-2607072200 (`kotoba-git-kotoba-rad-
on-kotobase-peer`, superproject `90-docs/adr/`, see its addendum for this
redesign). `kotoba-rad` is the sibling "Radicle-equivalent" half (sovereign
identity, delegates, signed refs).

## What this is

- **`kotoba-git.repo`** — `empty-repo` (a fresh `arrangement` db) and
  `persist!` (snapshot the *whole* repo — objects and refs together — to
  durable storage via `arrangement.core/commit!`). A repo is one db;
  object subjects are content hashes, ref subjects are repo ids —
  disjoint namespaces, so both coexist without collision.
- **`kotoba-git.object`** — `write-blob`/`read-blob` (raw, 0x55 codec),
  `write-tree`/`read-tree` and `write-commit`/`read-commit`. Every write
  function is a pure `(fn [db ...] -> [db' cid])`, threaded the same way
  `kotoba-git.refs` already was. Commits carry a `parents` vector (0, 1,
  or N), so merge commits are representable — a real commit *DAG*, not a
  linear chain. `commit/tree` is asserted as a genuine `ipld/link`, so
  `arrangement.core/refs-to` answers "which commits reference this tree"
  for free — a reverse graph query with zero extra code, the concrete
  payoff of living inside the Datomic-shaped store instead of beside it.
- **`kotoba-git.log`** — `ancestors` (full DAG reachability), `log`
  (first-parent history, newest-first), and `missing-since` (every commit/
  tree/blob CID reachable from a head that isn't already in a `have` set —
  the object-negotiation primitive a push/pull/pack exchange needs). Reads
  straight out of the same `db` `kotoba-git.object` writes into.
- **`kotoba-git.refs`** — `refs/heads/main`-style mutable pointers as
  quads in the same `db` (`set-ref`/`get-ref`/`list-refs`) — the mutable-
  pointer-over-immutable-DAG pattern Datomic itself uses for its own
  indexes.
- **`kotoba-git.ref-policy`** — `fast-forward?` and `set-ref-ff-only!`:
  whether moving a ref from one commit to another is a fast-forward (the
  old target is nil, equal to the new one, or one of its ancestors in the
  commit DAG — computed via `kotoba-git.log/ancestors`), and a variant of
  `set-ref` that throws (leaving the ref untouched) rather than silently
  allowing a non-fast-forward move. This is *shape* policy (is this
  update a rewind/diverge?), independent of and composable with
  `kotoba-rad`'s *identity* policy (who signed off on it).

## What this deliberately is NOT (yet)

- **No git-CLI wire compatibility.** No smart-HTTP bridge, no byte-exact
  SHA-1 object hashing, no binary packfile format matching real `git`. A
  previous Rust implementation (`kotoba-git` in `kotoba-lang/kotoba`)
  attempted exactly that and was deleted in full on 2026-07-01 — this repo
  does not resurrect that scope.
- **No transport/replication wiring.** `missing-since` gives the object
  diff a sync protocol needs, but nothing here speaks to `kotoba-lang/p2p`
  directly (that repo's own `deps.edn` currently points at a renamed-away
  `commit-dag` coordinate and needs a patch first).
- **No push authorization.** Deciding whether a ref update is allowed *by
  identity* is `kotoba-rad`'s job (`kotoba-rad.push-gate/authorize-push?`)
  — verified end-to-end in an integration script (see the ADR's
  Verification addendum), but `kotoba-git.refs/set-ref` itself enforces
  nothing; the caller must invoke the gate first. (`kotoba-git.ref-policy`
  now covers the *shape* half — fast-forward-only — but composing both
  checks before a real ref update is still the caller's responsibility;
  no single function does both yet.)
- **No recursive/fixpoint Datalog for history walks.** `arrangement.datalog`
  is a conjunctive-join query layer, not (yet) a transitive-closure one
  (ADR-2607022600 flags "Datalog fixpoint" as a follow-up) — `ancestors`/
  `log`/`missing-since` still walk the DAG by hand rather than expressing
  reachability as one Datalog query.
- **No restore-from-persisted-snapshot.** `repo/persist!` writes a snapshot
  CID via `arrangement.core/commit!`; `arrangement` does not yet expose a
  public "rehydrate a db from a snapshot CID" counterpart (that logic
  lives inside `kotobase-peer`'s own `fold!`/`cold-datoms`, not as a
  standalone reusable API). Until it does, keep the live `db` value around
  yourself between restarts.

## Usage

```clojure
(require '[kotoba-git.repo :as repo]
         '[kotoba-git.object :as obj]
         '[kotoba-git.log :as log]
         '[kotoba-git.refs :as refs])

(def db0 (repo/empty-repo))
(let [[db1 blob] (obj/write-blob db0 (.getBytes "hello\n" "UTF-8"))
      [db2 tree] (obj/write-tree db1 [{:name "hello.txt" :cid blob :kind :blob}])
      [db3 commit] (obj/write-commit db2 {:tree tree :parents []
                                           :author "did:key:z..." :message "initial"
                                           :ts (System/currentTimeMillis)})
      db (refs/set-ref db3 "my-repo" "refs/heads/main" commit)]
  (refs/get-ref db "my-repo" "refs/heads/main")          ;=> commit
  (log/log db commit)                                    ;=> [{:cid commit :tree tree ...}]
  (log/missing-since db commit #{})                       ;=> #{commit tree blob}

  ;; persist the whole repo (objects + refs together)
  (let [store (atom {})
        put! (fn [cid bytes] (swap! store assoc cid bytes))]
    (repo/persist! put! db nil)))
```

## Testing

```
clojure -M:test          # against the pinned :git/sha deps
clojure -M:local:test    # against sibling checkouts in ../ (same-monorepo dev)
```
