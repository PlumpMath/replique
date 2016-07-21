(ns ewen.replique.ui.notifications
  (:require [hiccup.core :refer-macros [html]]
            [hiccup.def :refer-macros [defhtml]]
            [goog.dom :as dom]
            [ewen.replique.ui.core :as core]
            [ewen.replique.ui.utils :as utils]
            [ewen.ddom.core :as ddom]))

(defhtml notifications-tmpl [{notifications :notifications}]
  [:div#notifications
   (for [[_ {:keys [type] :as notif}] notifications]
     (case type
       :err [:p.notif.err (:msg notif)]
       :download [:div.notif.download
                  [:span (str (:file notif) " download: ")]
                  [:progress {:value (:progress notif)
                              :max 100}]]
       :success [:p.notif.success (:msg notif)]
       :else nil))])

(defn single-notif [notif-msg]
  (let [notif-key {:id (utils/next-id)
                   :timestamp (js/Date.now)}]
    (swap! core/state update-in [:notifications] assoc notif-key notif-msg)
    (js/setTimeout
     #(swap! core/state update-in [:notifications] dissoc notif-key) 3000)))

(defn notif-with-id [notif-msg id]
  (let [{:keys [timeout]} (get (:notifications @core/state) {:id id})
        notif-key {:id id
                   :timestamp (js/Date.now)}]
    (swap! core/state update-in [:notifications]
           assoc notif-key notif-msg)))

(defn clear-notif [id]
  (let [{:keys [timeout]} (get (:notifications @core/state) {:id id})]
    (when timeout (.clearTimeout js/window timeout))
    (swap! core/state update-in [:notifications] dissoc {:id id})))

(defn refresh-notifications [root {:keys [notifications] :as state}]
  (if notifications
    (let [node (utils/replace-or-append
                root "#notifications"
                (ddom/string->fragment (notifications-tmpl state)))]
      node)
    (when-let [node (.querySelector root "#notifications")]
      (dom/removeNode node))))

(comment
  (reset-notifications)
  )

(defn notif-comparator [{id1 :id t1 :timestamp} {id2 :id t2 :timestamp}]
  (cond (= id1 id2) 0
        (= t1 t2) (compare id1 id2)
        :else (compare t1 t2)))

(defn reset-notifications []
  (swap! core/state assoc :notifications (sorted-map-by notif-comparator)))

(add-watch core/state :notifications-watcher
           (fn [r k o n]
             (when (not= (:notifications o) (:notifications n))
               (when-let [root (.getElementById js/document "root")]
                 (refresh-notifications root n)))))

(swap! core/refresh-view-fns assoc :notifications refresh-notifications)
