(ns bonsai.loose-object-test
  (:require #?(:clj [clojure.java.shell :as shell])
            [bonsai.loose-object :as loose]
            [clojure.test :refer [deftest is]]))

(defn- utf8 [s]
  #?(:clj (.getBytes ^String s java.nio.charset.StandardCharsets/UTF_8)
     :cljs (.encode (js/TextEncoder.) s)))

(deftest loose-roundtrip
  (let [body (utf8 "hello\n")
        decoded (loose/decode (loose/encode "blob" body))]
    (is (= "blob" (:type decoded)))
    (is (= "ce013625030ba8dba906f756967f9e9ca394464a" (:oid decoded)))
    (is (= (vec body) (vec (:body decoded))))))

(deftest inflated-size-is-bounded
  (is (= :inflated-limit
         (try (loose/decode (loose/encode "blob" (utf8 "hello\n")) 5)
              nil
              (catch #?(:clj Exception :cljs js/Error) e (:reason (ex-data e)))))))

#?(:clj
   (deftest loose-file-is-readable-by-git
     (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                         "bonsai-loose-" (make-array java.nio.file.attribute.FileAttribute 0)))
           git-dir (java.io.File. dir ".git")
           oid "ce013625030ba8dba906f756967f9e9ca394464a"
           object-dir (java.io.File. git-dir (str "objects/" (subs oid 0 2)))
           object-file (java.io.File. object-dir (subs oid 2))]
       (try
         (is (zero? (:exit (shell/sh "git" "init" (.getPath dir)))))
         (.mkdirs object-dir)
         (with-open [out (java.io.FileOutputStream. object-file)]
           (.write out ^bytes (loose/encode "blob" (utf8 "hello\n"))))
         (let [{:keys [exit out err]} (shell/sh "git" "-C" (.getPath dir)
                                                "cat-file" "-p" oid)]
           (is (zero? exit) err)
           (is (= "hello\n" out)))
         (finally (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f)))))))
