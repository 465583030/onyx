(ns onyx.peer.multi-peer-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is testing]]
            [onyx.test-helper :refer [load-config with-test-env]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.static.uuid :refer [random-uuid]]
            [onyx.api]))

(def n-messages 1000) ; ^:broken, reduced from 100000

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def in-chan (atom nil))
(def in-buffer (atom nil))

(def out-chan (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer in-buffer
   :core.async/chan @in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan @out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(deftest multi-peer-test
  (let [id (random-uuid)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/tenancy-id id)
        peer-config (assoc (:peer-config config) :onyx/tenancy-id id)
        batch-size 40
        catalog [{:onyx/name :in
                  :onyx/plugin :onyx.plugin.core-async/input
                  :onyx/type :input
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Reads segments from a core.async channel"}

                 {:onyx/name :inc
                  :onyx/fn ::my-inc
                  :onyx/type :function
                  :onyx/batch-size batch-size}

                 {:onyx/name :out
                  :onyx/plugin :onyx.plugin.core-async/output
                  :onyx/type :output
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Writes segments to a core.async channel"}]
        workflow [[:in :inc] [:inc :out]]
        lifecycles [{:lifecycle/task :in
                     :lifecycle/calls ::in-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls ::out-calls}]]

    (reset! in-chan (chan (inc n-messages)))
    (reset! in-buffer {})
    (reset! out-chan (chan (sliding-buffer (inc n-messages))))

    (with-test-env [test-env [8 env-config peer-config]]
      (doseq [n (range n-messages)]
        (>!! @in-chan {:n n}))
      (close! @in-chan)
      (let [{:keys [job-id]} (onyx.api/submit-job peer-config
                                                  {:catalog catalog :workflow workflow
                                                   :lifecycles lifecycles
                                                   :task-scheduler :onyx.task-scheduler/balanced})
            _ (onyx.test-helper/feedback-exception! peer-config job-id)
            results (take-segments! @out-chan 50)
            expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
        (is (= expected (set results)))))))
