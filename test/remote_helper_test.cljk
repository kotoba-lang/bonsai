(ns remote-helper-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest remote-helper-clone-fetch-push
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "bonsai-remote-" (make-array java.nio.file.attribute.FileAttribute 0)))
        origin (java.io.File. root "origin.git")
        seed (java.io.File. root "seed")
        clone (java.io.File. root "clone")
        helper-dir (.getCanonicalPath (java.io.File. "bin"))
        env (assoc (into {} (System/getenv)) "PATH" (str helper-dir ":" (System/getenv "PATH")))
        run (fn [& args] (apply shell/sh (concat args [:env env])))]
    (try
      (is (zero? (:exit (run "git" "init" "--bare" (.getPath origin)))))
      (is (zero? (:exit (run "git" "init" (.getPath seed)))))
      (spit (java.io.File. seed "README.md") "bonsai\n")
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "add" "README.md"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed)
                             "-c" "user.name=Bonsai" "-c" "user.email=bonsai@example.test"
                             "commit" "-m" "initial"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "branch" "-M" "main"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "push"
                             (str "kotoba::" (.getPath origin)) "main"))))
      (is (zero? (:exit (run "git" "clone" "--branch" "main"
                             (str "kotoba::" (.getPath origin)) (.getPath clone)))))
      (is (= "bonsai\n" (slurp (java.io.File. clone "README.md"))))
      (finally (doseq [f (reverse (file-seq root))] (.delete ^java.io.File f))))))

(deftest network-adapter-materializes-and-publishes
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "bonsai-network-" (make-array java.nio.file.attribute.FileAttribute 0)))
        remote-root (java.io.File. root "remote")
        cache-root (java.io.File. root "cache")
        seed (java.io.File. root "seed") clone (java.io.File. root "clone")
        helper-dir (.getCanonicalPath (java.io.File. "bin"))
        adapter (.getCanonicalPath (java.io.File. "bin/kotoba-git-reference-adapter"))
        env (assoc (into {} (System/getenv))
                   "PATH" (str helper-dir ":" (System/getenv "PATH"))
                   "KOTOBA_GIT_CACHE" (.getPath cache-root)
                   "KOTOBA_GIT_ADAPTER" adapter
                   "KOTOBA_GIT_REFERENCE_ROOT" (.getPath remote-root))
        run (fn [& args] (apply shell/sh (concat args [:env env])))
        remote "kotoba://example.test/team/repo"]
    (try
      (is (zero? (:exit (run "git" "init" (.getPath seed)))))
      (spit (java.io.File. seed "README.md") "network adapter\n")
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "add" "README.md"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed)
                             "-c" "user.name=Bonsai" "-c" "user.email=bonsai@example.test"
                             "commit" "-m" "network"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "branch" "-M" "main"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "push" remote "main"))))
      (is (zero? (:exit (run "git" "clone" "--branch" "main" remote (.getPath clone)))))
      (is (= "network adapter\n" (slurp (java.io.File. clone "README.md"))))
      (is (= 1 (count (filter #(.isDirectory ^java.io.File %)
                              (or (seq (.listFiles cache-root)) [])))))
      (finally (doseq [f (reverse (file-seq root))] (.delete ^java.io.File f))))))

(deftest http-adapter-publishes-bundle-and-ref-projection
  (let [stored (atom nil) request-meta (atom nil)
        server (com.sun.net.httpserver.HttpServer/create
                (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        handler (reify com.sun.net.httpserver.HttpHandler
                  (handle [_ exchange]
                    (let [method (.getRequestMethod exchange)]
                      (cond
                        (= method "GET")
                        (if-let [body @stored]
                          (do (.getResponseHeaders exchange)
                              (.sendResponseHeaders exchange 200 (alength ^bytes body))
                              (with-open [out (.getResponseBody exchange)] (.write out ^bytes body)))
                          (.sendResponseHeaders exchange 404 -1))

                        (= method "PUT")
                        (let [body (with-open [in (.getRequestBody exchange)] (.readAllBytes in))
                              headers (.getRequestHeaders exchange)]
                          (reset! stored body)
                          (reset! request-meta
                                  {:authorization (.getFirst headers "Authorization")
                                   :digest (.getFirst headers "X-Kotoba-Bundle-SHA256")
                                   :refs (.getFirst headers "X-Kotoba-Refs-EDN-B64")})
                          (.sendResponseHeaders exchange 200 -1))
                        :else (.sendResponseHeaders exchange 405 -1)))))
        _ (.createContext server "/" handler)
        _ (.start server)
        port (.getPort (.getAddress server))
        root (.toFile (java.nio.file.Files/createTempDirectory
                       "bonsai-http-" (make-array java.nio.file.attribute.FileAttribute 0)))
        cache-root (java.io.File. root "cache") seed (java.io.File. root "seed")
        clone (java.io.File. root "clone") helper-dir (.getCanonicalPath (java.io.File. "bin"))
        adapter (.getCanonicalPath (java.io.File. "bin/kotobase-http-git-adapter"))
        env (assoc (into {} (System/getenv))
                   "PATH" (str helper-dir ":" (System/getenv "PATH"))
                   "KOTOBA_GIT_CACHE" (.getPath cache-root)
                   "KOTOBA_GIT_ADAPTER" adapter
                   "KOTOBA_GIT_AUTHORIZATION" "Bearer test-only-token"
                   "KOTOBA_GIT_ALLOW_HTTP_LOOPBACK" "1")
        run (fn [& args] (apply shell/sh (concat args [:env env])))
        remote (str "kotoba://127.0.0.1:" port "/rid-test")]
    (try
      (is (zero? (:exit (run "git" "init" (.getPath seed)))))
      (spit (java.io.File. seed "data.txt") "http adapter\n")
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "add" "data.txt"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed)
                             "-c" "user.name=Bonsai" "-c" "user.email=bonsai@example.test"
                             "commit" "-m" "http"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "branch" "-M" "main"))))
      (is (zero? (:exit (run "git" "-C" (.getPath seed) "push" remote "main"))))
      (is (bytes? @stored))
      (is (= "Bearer test-only-token" (:authorization @request-meta)))
      (is (re-matches #"[0-9a-f]{64}" (:digest @request-meta)))
      (is (seq (:refs @request-meta)))
      (is (zero? (:exit (run "git" "clone" "--branch" "main" remote (.getPath clone)))))
      (is (= "http adapter\n" (slurp (java.io.File. clone "data.txt"))))
      (finally (.stop server 0)
               (doseq [f (reverse (file-seq root))] (.delete ^java.io.File f))))))
