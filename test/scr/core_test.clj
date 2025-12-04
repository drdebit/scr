(ns scr.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [scr.core :as core]))

(deftest extract-text-from-html-test
  (testing "extracts text from simple HTML"
    (is (= "Hello World"
           (core/extract-text-from-html "<html><body><p>Hello</p><p>World</p></body></html>"))))

  (testing "strips tags and normalizes whitespace"
    (is (= "Some text here"
           (core/extract-text-from-html "<div><span>Some</span> <b>text</b> here</div>")))))
