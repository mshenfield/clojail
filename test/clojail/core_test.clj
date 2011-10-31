(ns clojail.core-test
  (:use [clojail core testers]
        clojure.test)
  (:import java.io.StringWriter
           java.util.concurrent.ExecutionException))

(def sb (sandbox secure-tester))
(def easy (sandbox #{}))

(def wbsb (sandbox {:whitelist #{java.io.File java.lang.Math 'new 'clojure.core '+ '-}
                    :blacklist #{'+ java.lang.Math}}))

(deftest dot-test
  (is (= 4 (easy '(. "dots" (length))))))

(deftest dot-shorthand-test
  (is (= true (easy '(= (.length "string") 6)))))

(deftest security-test
  (is (= 7 (sb '(-> "bar.txt" java.io.File. .getName .length))))
  (is (thrown? Exception (sb '(-> java.io.File .getMethods (aget 0) .getName))))
  (is (thrown? Exception (sb '(-> java.io.File .getMethods (aget 0) ((memfn getName))))))
  (is (thrown? Exception (sb '(inc (clojure.core/eval 1))))))

(deftest sandbox-config-test
  (is (string? (easy '(-> java.io.File .getMethods (aget 0) .getName)))))

(deftest whitelist-test
  (is (= 6 (wbsb '(- 12 6))))
  (is (thrown? Exception (wbsb '(+ 3 3))))
  (is (= (java.io.File. "") (wbsb '(java.io.File. ""))))
  (is (thrown? Exception (wbsb '(java.lang.Math/abs 10)))))

(deftest lazy-dot-test
  (is (= [0 0] (sb '(map #(.length %) ["" ""])))))

(deftest binding-test
  (is (= 2 (sb '(#'*out* 2) {#'*out* identity}))))

(deftest macroexpand-test
  (is (= 'let (sb '(first '(let [x 1] x)))))
  (is (= '(dec (clojure.core/-> x inc))
         (sb '(macroexpand '(-> x inc dec)))))
  (is (= 1 (sb '(-> 0 inc dec inc))))
  (is (= '(. "" length) (sb ''(. "" length)))))

;; make sure macros are expanded outside-in, not inside-out
(deftest macroexpand-most-test
  (is (= (range 1 11) (sb '(->> (inc x)
                                (for [x (range 0 10)]))))))

;; sandbox* lets you change tester on the fly
(deftest dynamic-tester-test
  (let [dyn-sb (sandbox*)
        code '(+ 5 5)]
    (is (= 10 (dyn-sb #{} code)))
    (is (thrown? SecurityException (dyn-sb '#{+} code)))
    (is (thrown? SecurityException (dyn-sb #{'eval} 'clojure.core/eval)))))

(deftest namespace-forbid-test
  (let [sb (sandbox #{'clojure.core})]
    (is (thrown? SecurityException (sb '(+ 1 2))))))

(deftest init-test
  (let [sb (sandbox secure-tester :init '(def foo 1))]
    (is (= 1 (sb 'foo)))))

(deftest ns-init-test
  (let [ns-sb (sandbox secure-tester :ns-init `((refer-clojure) (use 'clojure.set)))]
    (is (thrown-with-msg? ExecutionException #"Unable to resolve symbol" (sb 'rename-keys)))
    (is (= clojure.set/rename-keys (ns-sb 'rename-keys)))))

(deftest def-test
  (let [sb-one (sandbox secure-tester-without-def)
        sb-two (sandbox secure-tester-without-def)]
    (testing "Leaves new defs if they're less than max def."
      (doseq [form (map #(list 'def % 0) '[q w e r t y u i])]
        (sb-one form))
      (is (thrown-with-msg? ExecutionException #"Unable to resolve symbol" (sb 't)))
      (is (= 0 (sb-one 'i))))
    (testing "Destroys old *and* new defs if new defs is also over max-def."
      (doseq [form (map #(list 'def % 0) '[q w e r t y u i o p])]
        (sb-two form))
      (is (thrown-with-msg? ExecutionException #"Unable to resolve symbol" (sb 'p))))))

(deftest require-test
  (let [sb (sandbox secure-tester)]
    (is (nil? (sb '(require 'clojure.string))))))

(deftest security-off-test
  (let [sb (sandbox secure-tester :jvm false)]
    (is (= "foo\n" (sb '(slurp "test/test.txt"))))))

(deftest block-fields-test
  (let [sb (sandbox secure-tester)]
    (doseq [field '[System/out System/in System/in]]
      (is (thrown-with-msg? SecurityException #"is bad!" (sb `(. ~field println "foo")))))))