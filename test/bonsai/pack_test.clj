(ns bonsai.pack-test
  (:require [bonsai.pack :as pack]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(defn utf8 [s] (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8))

(deftest pack-and-index-are-accepted-by-git
  (let [bytes (pack/encode [{:type "blob" :body (utf8 "hello\n")}
                            {:type "blob" :body (utf8 "world\n")}])
        objects (pack/decode bytes)
        idx (pack/encode-index-v2 objects bytes)
        dir (.toFile (java.nio.file.Files/createTempDirectory
                      "bonsai-pack-" (make-array java.nio.file.attribute.FileAttribute 0)))
        pack-file (java.io.File. dir "objects.pack") idx-file (java.io.File. dir "objects.idx")]
    (try
      (with-open [o (java.io.FileOutputStream. pack-file)] (.write o ^bytes bytes))
      (with-open [o (java.io.FileOutputStream. idx-file)] (.write o ^bytes idx))
      (let [{:keys [exit err]} (shell/sh "git" "verify-pack" "-v" (.getPath idx-file))]
        (is (zero? exit) err))
      (is (= ["blob" "blob"] (mapv :type objects)))
      (finally (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))

