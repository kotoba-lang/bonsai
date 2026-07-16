# bonsai

*(旧 kotoba-git — 2026-07-16 rename。盆栽＝人が木を意図的に仕立てる craft ＝ object DAG を操る道具。namespaces も bonsai.* に移行済み (2026-07-16))*

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
  `kotoba-rad`'s *identity* policy (who signed off on it) — and
  `set-ref-guarded!` now composes the two into one call: it takes a
  caller-supplied 0-arg `authorized?` predicate (typically a partial
  application of `kotoba-rad.push-gate/authorize-push?` or
  `authorize-push-cacao?`, but this namespace has no dependency on
  `kotoba-rad` to make that work — any predicate does), checks it
  *before* the fast-forward check, and throws `ex-info` with a `:reason`
  of `:unauthorized` or `:not-fast-forward` so a caller can report the
  two differently (e.g. HTTP 403 vs 409).

## What this deliberately is NOT (yet)

- **No git-CLI wire compatibility.** No smart-HTTP bridge, no byte-exact
  SHA-1 object hashing, no binary packfile format matching real `git`. A
  previous Rust implementation (`kotoba-git` in `kotoba-lang/kotoba`)
  attempted exactly that and was deleted in full on 2026-07-01 — this repo
  does not resurrect that scope.
- **No transport/replication wiring in this repo.** `missing-since` gives
  the object diff a sync protocol needs, and `kotoba-lang/p2p` (gossip
  fanout + bitswap-style delta-sync + `chain/verify-chain`, now with
  pluggable signed head-announce hooks — see `kotoba-rad.announce`) is
  the actual sync layer, but `kotoba-git` itself has no dependency on
  `p2p` and no code wiring the two together; that composition lives in
  whatever application uses both.
- **No push authorization built into `kotoba-git.refs` itself.** Deciding
  *who's* allowed is still `kotoba-rad`'s job (`authorize-push?`/
  `authorize-push-cacao?`) — verified end-to-end in an integration script
  (see the ADR's Verification addendum) — and `kotoba-git.refs/set-ref`
  on its own still enforces nothing. `kotoba-git.ref-policy/
  set-ref-guarded!` now composes identity + shape into one call (see
  above), so a caller no longer has to hand-order the two checks
  themselves — but `set-ref-guarded!` still takes `authorized?` as an
  injected predicate; `kotoba-git` still has zero dependency on
  `kotoba-rad`.
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
    (repo/persist! put! db nil))

  ;; identity + shape composed into one guarded update (kotoba-rad optional):
  ;; (require '[kotoba-git.ref-policy :as policy])
  ;; (policy/set-ref-guarded! db "my-repo" "refs/heads/main" commit
  ;;                           #(kotoba-rad.push-gate/authorize-push?
  ;;                              get-fn journal-head owner-did rid
  ;;                              "refs/heads/main" commit sr))
  )
```

## Testing

```
clojure -M:test          # against the pinned :git/sha deps
clojure -M:local:test    # against sibling checkouts in ../ (same-monorepo dev)
npm install && npm run test:cljs   # real ClojureScript (shadow-cljs node-test), not just .cljc-named
```

Unlike `kotoba-rad` (which pulls in JVM-only `ed25519.core`/`cacao.core`),
`kotoba-git` has no non-portable dependency, so it runs real ClojureScript
CI (`gen-shadow-cljs-edn.bb` resolves `shadow-cljs.edn`'s `:source-paths`
from `clojure -Spath`, so cljs always tests the exact pinned versions
`clojure -M:test` does — never a hand-duplicated, driftable list).
Wiring this up caught two genuine portability bugs the `.cljc` extension
alone didn't guarantee: `kotoba-git.repo`'s `identity-blind`/
`identity-encrypt` had to become real `js/Promise`-returning functions on
cljs (`arrangement.core`'s own cljs code path calls `.then` directly on
`encrypt-fn`'s return value — Web Crypto's AEAD has no sync primitive),
and comparing two typed-array-backed `seq`s directly (`(= (seq a) (seq
b))`) is unreliable in cljs even when the underlying bytes are identical
— `(= (vec a) (vec b))` is the portable comparison (matching
`io-multiformats`'s own test convention).
