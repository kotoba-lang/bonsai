(ns bonsai.private-wire-test
  (:require [bonsai.private-repo :as private]
            [bonsai.private-wire :as wire]
            [clojure.test :refer [deftest is testing]]
            [envelope.seal-jvm :as seal]
            [ipld.core :as ipld]))

(defn- utf8-bytes [text]
  (.getBytes ^String text java.nio.charset.StandardCharsets/UTF_8))

(defn- recipient [id]
  (let [{:keys [priv pub]} (seal/generate-recipient)]
    {:id id :priv priv :wire {:id id :pub pub}}))

(deftest portable-wire-verifies-snapshot-and-block-contract
  (let [alice (recipient "did:key:alice")
        snapshot (private/seal-snapshot
                  {:rid "rad:wire" :bundle-bytes (utf8-bytes "bundle")
                   :recipients [(:wire alice)]})
        verified (wire/verify-snapshot (:snapshot/descriptor-bytes snapshot)
                                       (:snapshot/ciphertext snapshot))]
    (is (= "rad:wire" (:snapshot/rid verified)))
    (is (= (:snapshot/cid snapshot) (:snapshot/cid verified)))
    (is (= #{:descriptor :ciphertext}
           (set (map :role (wire/snapshot-blocks snapshot)))))
    (is (= {"snapshot_cid" (:snapshot/cid snapshot)
            "operation" "push" "expected_heads" []}
           (wire/head-command snapshot "push")))))

(deftest transition-policy-distinguishes-push-share-and-rotation
  (let [alice (recipient "did:key:alice")
        bob (recipient "did:key:bob")
        first-snapshot (private/seal-snapshot
                        {:rid "rad:wire" :bundle-bytes (utf8-bytes "v1")
                         :recipients [(:wire alice)]})
        shared (private/share-snapshot first-snapshot (:id alice) (:priv alice)
                                       (:wire bob))
        rotated (private/rotate-without shared (:id alice) (:priv alice)
                                        (:id bob) [(:wire alice)])
        pushed-from-first (private/seal-snapshot
                           {:rid "rad:wire" :epoch (:snapshot/epoch first-snapshot)
                            :parent (:snapshot/cid first-snapshot)
                            :bundle-bytes (utf8-bytes "v2")
                            :recipients [(:wire alice)]})
        pushed (private/seal-snapshot
                {:rid "rad:wire" :epoch (:snapshot/epoch rotated)
                 :parent (:snapshot/cid rotated) :bundle-bytes (utf8-bytes "v2")
                 :recipients [(:wire alice)]})]
    (is (:ok? (wire/transition nil first-snapshot "push")))
    (is (:ok? (wire/transition first-snapshot shared "share")))
    (is (:ok? (wire/transition shared rotated "rotate")))
    (is (:ok? (wire/transition rotated pushed "push")))
    (is (= [(:snapshot/cid rotated)]
           (get (wire/head-command pushed "push") "expected_heads")))
    (is (= ["bafy-left" "bafy-right"]
           (get (wire/head-command pushed "push"
                                   {:expected-heads ["bafy-right" "bafy-left"]})
                "expected_heads")))
    (is (thrown-with-msg? Exception #"expected heads"
                          (wire/head-command pushed "push"
                                             {:expected-heads [nil]})))
    (is (= :share-must-only-rewrap-key
           (:reason (wire/transition first-snapshot pushed-from-first "share"))))
    (is (= :snapshot-parent-conflict
           (:reason (wire/transition first-snapshot rotated "rotate"))))))

(deftest descriptor-rid-and-canonical-shape-fail-closed
  (let [alice (recipient "did:key:alice")
        snapshot (private/seal-snapshot
                  {:rid "rad:wire" :bundle-bytes (utf8-bytes "bundle")
                   :recipients [(:wire alice)]})
        smuggled (ipld/encode (assoc (:snapshot/descriptor snapshot)
                                     "plaintextRef" "refs/heads/main"))]
    (testing "unknown fields cannot become an accidental plaintext side channel"
      (is (thrown-with-msg? Exception #"invalid.*descriptor"
                            (wire/descriptor-info smuggled))))))
