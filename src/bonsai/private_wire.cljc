(ns bonsai.private-wire
  "Portable wire contract for confidential repository snapshots.

  This namespace contains no key material and performs no decryption. It is the
  shared trust-boundary validator used by JVM clients and ClojureScript storage
  services: canonical DAG-CBOR, descriptor identity, ciphertext identity, and
  the linear snapshot-head transition are decided here once."
  (:require [clojure.string :as str]
            [ipld.core :as ipld]
            [multiformats.core :as mf]))

(def version 1)
(def operations #{"fetch" "push" "share" "rotate" "advertise"})
(def descriptor-content-type "application/vnd.ipld.dag-cbor")
(def ciphertext-content-type "application/vnd.ipld.raw")

(def ^:private recipient-required
  #{"id" "kind" "pub" "ephemeralPub" "iv" "wrapped"})
(def ^:private recipient-optional #{"kem" "pqPub" "pqCt"})
(def ^:private envelope-required
  #{"id" "version" "alg" "kdf" "kem" "chunkBytes" "chunks"
    "nonceEpoch" "chunkEpochs" "recipients"})
(def ^:private descriptor-required
  #{"kind" "version" "rid" "epoch" "ciphertext" "envelope"})
(def ^:private descriptor-optional #{"parent"})

(defn valid-rid? [rid]
  (and (string? rid)
       (<= 1 (count rid) 200)
       (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._:@+-]*" rid))
       (not (str/includes? rid ".."))))

(defn- exact-keys? [m required optional]
  (and (map? m)
       (every? #(contains? m %) required)
       (every? (into (set required) optional) (keys m))))

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-recipient? [recipient]
  (and (exact-keys? recipient recipient-required recipient-optional)
       (every? #(nonblank? (get recipient %)) recipient-required)
       (if (= "x25519+ml-kem-768" (get recipient "kem"))
         (every? #(nonblank? (get recipient %)) ["pqPub" "pqCt"])
         (not (or (contains? recipient "pqPub")
                  (contains? recipient "pqCt"))))))

(defn- valid-envelope? [env rid epoch]
  (let [recipients (get env "recipients")]
    (and (exact-keys? env envelope-required #{})
         (= (str "bonsai:" rid) (get env "id"))
         (nat-int? (get env "version"))
         (every? #(nonblank? (get env %)) ["alg" "kdf" "kem"])
         (pos-int? (get env "chunkBytes"))
         (= 1 (get env "chunks"))
         (= epoch (get env "nonceEpoch"))
         (= {"0" epoch} (get env "chunkEpochs"))
         (vector? recipients)
         (seq recipients)
         (every? valid-recipient? recipients)
         (= (count recipients) (count (set (map #(get % "id") recipients)))))))

(defn descriptor-value
  [{:keys [rid epoch parent ciphertext-cid envelope]}]
  (cond-> {"kind" "bonsai.private-snapshot"
           "version" version
           "rid" rid
           "epoch" epoch
           "ciphertext" (ipld/link ciphertext-cid)
           "envelope" envelope}
    parent (assoc "parent" (ipld/link parent))))

(defn descriptor-info
  "Decode and validate canonical descriptor bytes. Returns public routing
  metadata only; recipient wraps remain inside `:snapshot/descriptor`."
  [descriptor-bytes]
  (let [descriptor (try
                     (ipld/decode descriptor-bytes)
                     (catch #?(:clj Exception :cljs :default) cause
                       (throw (ex-info "invalid private snapshot descriptor"
                                       {:reason :descriptor-decode-failed}
                                       cause))))
        rid (get descriptor "rid")
        epoch (get descriptor "epoch")
        ciphertext-link (get descriptor "ciphertext")
        parent-link (get descriptor "parent")]
    (when-not (and (exact-keys? descriptor descriptor-required descriptor-optional)
                   (= "bonsai.private-snapshot" (get descriptor "kind"))
                   (= version (get descriptor "version"))
                   (valid-rid? rid)
                   (nat-int? epoch)
                   (ipld/link? ciphertext-link)
                   (or (nil? parent-link) (ipld/link? parent-link))
                   (valid-envelope? (get descriptor "envelope") rid epoch))
      (throw (ex-info "invalid private snapshot descriptor"
                      {:reason :invalid-descriptor})))
    {:snapshot/version version
     :snapshot/rid rid
     :snapshot/epoch epoch
     :snapshot/parent (some-> parent-link ipld/link-cid)
     :snapshot/cid (ipld/cid descriptor-bytes)
     :snapshot/ciphertext-cid (ipld/link-cid ciphertext-link)
     :snapshot/descriptor descriptor
     :snapshot/descriptor-bytes descriptor-bytes}))

(defn verify-snapshot
  "Verify descriptor and ciphertext identities without decrypting."
  [descriptor-bytes ciphertext]
  (let [info (descriptor-info descriptor-bytes)
        actual (str (mf/cidv1-raw ciphertext))]
    (when-not (= (:snapshot/ciphertext-cid info) actual)
      (throw (ex-info "private snapshot ciphertext CID mismatch"
                      {:reason :ciphertext-cid-mismatch
                       :expected (:snapshot/ciphertext-cid info)
                       :actual actual})))
    (assoc info :snapshot/ciphertext ciphertext)))

(defn transition
  "Validate one mutable-head transition. Git history may branch and merge inside
  the encrypted bundle; this outer storage log is linear so concurrent writers
  receive a conflict instead of silently replacing one another."
  [current candidate operation]
  (cond
    (not (contains? #{"push" "share" "rotate"} operation))
    {:ok? false :reason :invalid-operation}

    (nil? current)
    (if (and (= "push" operation) (nil? (:snapshot/parent candidate)))
      {:ok? true}
      {:ok? false :reason :initial-snapshot-must-be-parentless-push})

    (not= (:snapshot/cid current) (:snapshot/parent candidate))
    {:ok? false :reason :snapshot-parent-conflict}

    (= (:snapshot/cid current) (:snapshot/cid candidate))
    {:ok? false :reason :snapshot-did-not-advance}

    (= "share" operation)
    (if (and (= (:snapshot/ciphertext-cid current)
                (:snapshot/ciphertext-cid candidate))
             (= (:snapshot/epoch current) (:snapshot/epoch candidate)))
      {:ok? true}
      {:ok? false :reason :share-must-only-rewrap-key})

    (= "rotate" operation)
    (if (and (not= (:snapshot/ciphertext-cid current)
                   (:snapshot/ciphertext-cid candidate))
             (= (inc (:snapshot/epoch current)) (:snapshot/epoch candidate)))
      {:ok? true}
      {:ok? false :reason :rotation-must-rekey-and-increment-epoch})

    :else
    (if (and (not= (:snapshot/ciphertext-cid current)
                   (:snapshot/ciphertext-cid candidate))
             (<= (:snapshot/epoch current) (:snapshot/epoch candidate)))
      {:ok? true}
      {:ok? false :reason :push-must-reencrypt-without-epoch-regression})))

(defn snapshot-blocks
  "The two immutable IPLD blocks a transport uploads before committing a head."
  [snapshot]
  [{:role :descriptor
    :cid (:snapshot/cid snapshot)
    :content-type descriptor-content-type
    :bytes (:snapshot/descriptor-bytes snapshot)}
   {:role :ciphertext
    :cid (:snapshot/ciphertext-cid snapshot)
    :content-type ciphertext-content-type
    :bytes (:snapshot/ciphertext snapshot)}])

(defn head-command
  [snapshot operation & [{:keys [advertise context-id]}]]
  (cond-> {"snapshot_cid" (:snapshot/cid snapshot) "operation" operation}
    advertise (assoc "advertise" true)
    context-id (assoc "context_id" context-id)))
