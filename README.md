# kotoba-git

A content-addressed git object model (blob/tree/commit) and mutable ref
store, built entirely on the `kotoba-lang` content-addressing and Datomic-
style database stack (`io-multiformats` + `org-ietf-cbor` + `io-ipld` +
`prolly-tree` + `arrangement`) instead of git's own SHA-1/packfile format.

This is the "git-equivalent" half of ADR-2607072200 (`kotoba-git-kotoba-rad-
on-kotobase-peer`, superproject `90-docs/adr/`). `kotoba-rad` is the sibling
"Radicle-equivalent" half (sovereign identity, delegates, signed refs).

## What this is

- **`kotoba-git.object`** — `write-blob!`/`read-blob` (raw, 0x55 codec),
  `write-tree!`/`read-tree` and `write-commit!`/`read-commit` (DAG-CBOR,
  0x71 codec, real CBOR tag-42 links via `io-ipld`). Commits carry a
  `parents` vector (0, 1, or N parents), so merge commits are representable
  — this is a real commit *DAG*, not a linear chain.
- **`kotoba-git.log`** — `ancestors` (full DAG reachability), `log`
  (first-parent history, newest-first), and `missing-since` (every commit/
  tree/blob CID reachable from a head that isn't already in a `have` set —
  the object-negotiation primitive a push/pull/pack exchange needs).
- **`kotoba-git.refs`** — `refs/heads/main`-style mutable pointers stored as
  quads in an `arrangement` db (`set-ref`/`get-ref`/`list-refs`), with
  `persist!` to snapshot the ref set to content-addressed storage via
  `arrangement.core/commit!`.

All three namespaces are storage-agnostic: every function takes an injected
`put!`/`get-fn` pair (`(fn [cid bytes] ...)` / `(fn [cid] -> bytes)`), so the
same code runs against an in-memory atom (see the tests), a `kotobase`
`IStore`-backed persistence layer, or a future p2p-synced block store,
without any change to `kotoba-git` itself.

## What this deliberately is NOT (yet)

- **No git-CLI wire compatibility.** There is no smart-HTTP bridge, no byte-
  exact SHA-1 object hashing, and no binary packfile format matching real
  `git`. A previous Rust implementation (`kotoba-git` in `kotoba-lang/kotoba`)
  did attempt exactly that and was deleted in full on 2026-07-01 — this repo
  does not resurrect that scope. If git-CLI interop is ever needed, it's a
  distinct translation layer on top of these primitives, not a rewrite of
  them.
- **No transport/replication wiring.** `missing-since` gives you the object
  diff a sync protocol needs, but nothing here speaks to `kotoba-lang/p2p`
  directly (that repo's own `deps.edn` currently points at a renamed-away
  `commit-dag` coordinate and needs a patch before it's usable as-is).
- **No push authorization.** Deciding whether a given ref update is allowed
  is `kotoba-rad`'s job (`kotoba-rad.push-gate/authorize-push?`), not this
  repo's — `kotoba-git` only knows how to read/write objects and refs, not
  who's allowed to move them.
- **No restore-from-persisted-snapshot for refs.** `refs/persist!` writes a
  snapshot CID via `arrangement.core/commit!`; `arrangement` does not yet
  expose a public "rehydrate a db from a snapshot CID" function to pair with
  it (that logic currently lives inside `kotobase-peer`'s own `fold!`/
  `cold-datoms`, not as a standalone reusable API). Until it does, keep the
  live `db` value around yourself between restarts.

## Usage

```clojure
(require '[kotoba-git.object :as obj]
         '[kotoba-git.log :as log]
         '[kotoba-git.refs :as refs])

(def store (atom {}))
(def put! (fn [cid bytes] (swap! store assoc cid bytes)))
(def get-fn (fn [cid] (get @store cid)))

(def blob (obj/write-blob! put! (.getBytes "hello\n" "UTF-8")))
(def tree (obj/write-tree! put! [{:name "hello.txt" :cid blob :kind :blob}]))
(def commit (obj/write-commit! put! {:tree tree :parents []
                                      :author "did:key:z..." :message "initial"
                                      :ts (System/currentTimeMillis)}))

(def refs-db (-> (refs/empty-refs) (refs/set-ref "my-repo" "refs/heads/main" commit)))
(refs/get-ref refs-db "my-repo" "refs/heads/main") ;=> commit

(log/log get-fn commit)             ;=> [{:cid commit :tree tree :parents [] ...}]
(log/missing-since get-fn commit #{}) ;=> #{commit tree blob}
```

## Testing

```
clojure -M:test          # against the pinned :git/sha deps
clojure -M:local:test    # against sibling checkouts in ../ (same-monorepo dev)
```
