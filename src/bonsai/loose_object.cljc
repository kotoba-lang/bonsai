(ns bonsai.loose-object
  "Git loose-object zlib envelope over bonsai.git-object framing."
  (:require [bonsai.git-object :as object]
            #?(:cljs ["zlib" :as zlib])))

(def default-max-inflated-bytes (* 64 1024 1024))

(defn- ->bytes [xs]
  #?(:clj (if (bytes? xs) xs (byte-array (map unchecked-byte xs)))
     :cljs (if (instance? js/Uint8Array xs) xs (js/Uint8Array. (clj->js xs)))))

(defn deflate
  "zlib-compress bytes using the format Git loose objects use."
  [bytes]
  #?(:clj
     (let [out (java.io.ByteArrayOutputStream.)
           z (java.util.zip.Deflater.)
           buf (byte-array 8192)]
       (try
         (.setInput z ^bytes (->bytes bytes))
         (.finish z)
         (while (not (.finished z))
           (let [n (.deflate z buf)] (.write out buf 0 n)))
         (.toByteArray out)
         (finally (.end z))))
     :cljs (js/Uint8Array. (zlib/deflateSync (js/Buffer.from (->bytes bytes))))))

(defn inflate
  "Strictly inflate one zlib stream, bounded against decompression bombs."
  ([bytes] (inflate bytes default-max-inflated-bytes))
  ([bytes max-bytes]
   #?(:clj
      (let [out (java.io.ByteArrayOutputStream.)
            z (java.util.zip.Inflater.)
            buf (byte-array 8192)]
        (try
          (.setInput z ^bytes (->bytes bytes))
          (loop []
            (cond
              (.finished z) nil
              (.needsDictionary z)
              (throw (ex-info "Git loose object requires a zlib dictionary"
                              {:reason :zlib-dictionary}))
              (.needsInput z)
              (throw (ex-info "truncated Git loose object"
                              {:reason :truncated-zlib}))
              :else
              (let [n (.inflate z buf)]
                (when (and (zero? n) (not (.finished z)))
                  (throw (ex-info "invalid Git loose object zlib stream"
                                  {:reason :invalid-zlib})))
                (when (> (+ (.size out) n) max-bytes)
                  (throw (ex-info "inflated Git object exceeds limit"
                                  {:reason :inflated-limit :limit max-bytes})))
                (.write out buf 0 n)
                (recur))))
          (when-not (zero? (.getRemaining z))
            (throw (ex-info "trailing bytes after Git loose object"
                            {:reason :trailing-zlib :remaining (.getRemaining z)})))
          (.toByteArray out)
          (catch java.util.zip.DataFormatException e
            (throw (ex-info "invalid Git loose object zlib data"
                            {:reason :invalid-zlib} e)))
          (finally (.end z))))
      :cljs
      (let [out (try
                  (zlib/inflateSync (js/Buffer.from (->bytes bytes))
                                    #js {:maxOutputLength max-bytes})
                  (catch :default e
                    (throw (ex-info "invalid or oversized Git loose object"
                                    {:reason (if (= "ERR_BUFFER_TOO_LARGE" (.-code e))
                                               :inflated-limit :invalid-zlib)
                                     :limit max-bytes} e))))]
        (js/Uint8Array. out)))))

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
