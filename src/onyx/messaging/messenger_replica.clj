(ns onyx.messaging.messenger-replica
  ;; FIXME: rename to messenger-state!
  (:require [clojure.set :refer [intersection union difference map-invert]]
            [clojure.data :refer [diff]]
            [onyx.scheduling.common-task-scheduler :as cts]
            [onyx.log.commands.common :as common]
            [onyx.messaging.messenger :as m]
            [onyx.extensions :as extensions]
            [taoensso.timbre :refer [info warn]]
            [onyx.static.planning :as planning]
            [onyx.static.default-vals :refer [defaults arg-or-default]]))

; (defn build-pick-peer-fn
;   [replica job-id my-peer-id task-id task-map egress-peers slot-id->peer-id peer-config]
;   (let [out-peers (egress-peers task-id)
;         choose-f (cts/choose-downstream-peers replica job-id peer-config my-peer-id out-peers)]
;     (cond (empty? out-peers)
;           (fn [_] nil)

;           (and (planning/grouping-task? task-map) (#{:continue :kill} (:onyx/flux-policy task-map)))
;           (fn [hash-group] 
;             (nth out-peers
;                  (mod hash-group
;                       (count out-peers))))

;           (and (planning/grouping-task? task-map) (= :recover (:onyx/flux-policy task-map)))
;           (let [n-peers (or (:onyx/n-peers task-map)
;                             (:onyx/max-peers task-map))] 
;             (fn [hash-group] 
;               (slot-id->peer-id (mod hash-group n-peers))))

;           (planning/grouping-task? task-map) 
;           (throw (ex-info "Unhandled grouping-task flux-policy." task-map))

;           :else
;           (fn [hash-group]
;             (choose-f hash-group)))))

; (defn task-egress-publications [egress-tasks receivable-peers peer-sites site->publication]
;   (->> egress-tasks
;        (map (fn [[task-name task-id]]
;               (let [;slot-id->peer-id (map-invert (get slot-ids task-id))
;                     egress-peers (receivable-peers task-id)
;                     publications (->> egress-peers 
;                                       (map (comp :pub site->publication peer-sites))
;                                       vec)] 
;                 [task-id publications])))
;        (into {})))

(defn root-task-ids [replica {:keys [onyx.core/task onyx.core/workflow onyx.core/job-id]}]
  (mapv
   (fn [root-task]
     (get-in replica [:task-name->id job-id root-task]))
   (common/root-tasks workflow task)))

(defn leaf-task-ids [replica {:keys [onyx.core/task onyx.core/workflow onyx.core/job-id]}]
  (mapv
   (fn [leaf-task]
     (get-in replica [:task-name->id job-id leaf-task]))
   (common/leaf-tasks workflow task)))

(defn messenger-details 
  [{:keys [peer-state allocations peer-sites] :as replica} 
   {:keys [onyx.core/workflow onyx.core/catalog onyx.core/task onyx.core/serialized-task onyx.core/job-id onyx.core/id] :as event}]
  (let [task-map (planning/find-task catalog task)
        {:keys [egress-ids ingress-ids]} serialized-task
        receivable-peers (common/job-receivable-peers peer-state allocations job-id)
        egress-pubs (->> (vals egress-ids) 
                         (mapcat (fn [task-id] 
                                   (let [peers (receivable-peers task-id)]
                                     (map (fn [peer-id]
                                            {:src-peer-id id
                                             :dst-task-id task-id
                                             :site (peer-sites peer-id)})
                                          peers))))
                         set)
        ack-pubs (if (= (:onyx/type task-map) :output) 
                   (->> (root-task-ids replica event)
                       (mapcat (fn [task-id] 
                                 (let [peers (receivable-peers task-id)]
                                   (map (fn [peer-id]
                                          {:src-peer-id id
                                           :dst-task-id task-id
                                           :site (peer-sites peer-id)})
                                        peers))))
                       set)
                   #{})
        ingress-subs (->> (vals ingress-ids) 
                          (mapcat (fn [task-id] 
                                    (let [peers (receivable-peers task-id)]
                                      (map (fn [peer-id]
                                             {:src-peer-id peer-id
                                              :dst-task-id (:onyx.core/task-id event)
                                              :site (peer-sites id)})
                                           peers))))
                          set)
        ack-subs (if (= (:onyx/type task-map) :input) 
                   (->> (leaf-task-ids replica event)
                        (mapcat (fn [task-id] 
                                  (let [peers (receivable-peers task-id)]
                                    (map (fn [peer-id]
                                           {:src-peer-id peer-id
                                            :dst-task-id (:onyx.core/task-id event)
                                            :site (peer-sites id)})
                                         peers))))
                        set)
                   #{})]
    {:pubs (into ack-pubs egress-pubs)
     :subs (into ack-subs ingress-subs)}))

(defn update-messenger [messenger old-pub-subs new-pub-subs]
  (let [remove-pubs (difference (:pubs old-pub-subs) (:pubs new-pub-subs))
        add-pubs (difference (:pubs new-pub-subs) (:pubs old-pub-subs))
        remove-subs (difference (:subs old-pub-subs) (:subs new-pub-subs))
        add-subs (difference (:subs new-pub-subs) (:subs old-pub-subs))]
    (as-> messenger m
      (reduce (fn [m pub] 
                (m/remove-publication m pub)) 
              m
              remove-pubs)
      (reduce (fn [m pub] 
                (m/add-publication m pub)) 
              m
              add-pubs)
      (reduce (fn [m sub] 
                (m/remove-subscription m sub)) 
              m
              remove-subs)
      (reduce (fn [m sub] 
                (m/add-subscription m sub)) 
              m
              add-subs))))

(defn new-messenger-state! [messenger event old-replica new-replica]
  (if (= old-replica new-replica)
    messenger
    (let [old-pub-subs (messenger-details old-replica event)
          new-pub-subs (messenger-details new-replica event)
          new-messenger (-> messenger
                            (m/set-replica-version (:version new-replica))
                            (update-messenger old-pub-subs new-pub-subs))]
      (if (= :input (:onyx/type (:onyx.core/task-map event)))
        ;; Emit initial barrier from input tasks upon new replica, 
        ;; essentially flushing everything out downstream
        (-> new-messenger m/emit-barrier)
        new-messenger))))
