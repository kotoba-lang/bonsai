(ns bonsai.loose-object
  "Git loose-object zlib envelope over bonsai.git-object framing.

   The zlib codec is `org-ietf-deflate` — portable `.cljc`, not the host's.
   This used to be `java.util.zip.Deflater`/`Inflater` on the JVM and node's
   `zlib` on ClojureScript, which meant a Git object store could only exist on
   a runtime that shipped one, and the two branches did not agree: node's
   `inflateSync` cannot report a trailing-byte or preset-dictionary condition, so
   `:trailing-zlib` and `:zlib-dictionary` were JVM-only rejections. One codec
   means one set of guarantees on every runtime.

   Failure `:reason`s are unchanged (`:invalid-zlib`, `:truncated-zlib`,
   `:trailing-zlib`, `:zlib-dictionary`, `:inflated-limit`) — callers dispatch on
   them."
  (:require [bonsai.git-object :as object]
            [deflate.core :as codec]
            [deflate.zlib :as zlib]))

(def default-max-inflated-bytes (* 64 1024 1024))

(defn- ->ubytes
  "Platform bytes → vector of *unsigned* bytes, which is what the codec takes.
   A JVM byte array holds signed values, so masking is not optional here."
  [xs]
  #?(:clj (mapv #(bit-and (int %) 0xff) (seq xs))
     :cljs (cond
             (instance? js/Uint8Array xs) (vec (array-seq xs))
             (instance? js/ArrayBuffer xs) (vec (array-seq (js/Uint8Array. xs)))
             :else (mapv #(bit-and % 0xff) (vec xs)))))

(defn- ->platform
  "Vector of unsigned bytes → the byte container this runtime's callers expect."
  [v]
  #?(:clj (byte-array (map unchecked-byte v))
     :cljs (let [n (count v) a (js/Uint8Array. n)]
             (dotimes [i n] (aset a i (nth v i)))
             a)))

(defn deflate
  "zlib-compress bytes using the format Git loose objects use."
  [bytes]
  (->platform (codec/deflate (->ubytes bytes))))

(defn- codec-reason
  "Map the codec's `:reason` onto this namespace's long-standing contract."
  [reason]
  (case reason
    :output-limit        :inflated-limit
    :truncated           :truncated-zlib
    :dictionary-required :zlib-dictionary
    :invalid-zlib))

(defn inflate
  "Strictly inflate one zlib stream, bounded against decompression bombs."
  ([bytes] (inflate bytes default-max-inflated-bytes))
  ([bytes max-bytes]
   (let [in (->ubytes bytes)
         {:keys [bytes end]}
         (try
           (zlib/unwrap* in {:max-output max-bytes})
           (catch #?(:clj Exception :cljs :default) e
             (throw (ex-info (or (ex-message e) "invalid Git loose object zlib data")
                             {:reason (codec-reason (:reason (ex-data e)))
                              :limit max-bytes}
                             e))))]
     (when (< end (count in))
       (throw (ex-info "trailing bytes after Git loose object"
                       {:reason :trailing-zlib :remaining (- (count in) end)})))
     (->platform bytes))))

(defn encode
  "Encode type/body into a complete zlib Git loose-object file."
  [type body]
  (deflate (object/frame type body)))

(defn decode
  "Decode and verify a zlib Git loose-object file."
  ([compressed] (decode compressed default-max-inflated-bytes))
  ([compressed max-bytes]
   (let [framed (inflate compressed max-bytes)
         parsed (object/parse-frame framed)]
     (assoc parsed :framed framed :oid (object/oid framed)))))
