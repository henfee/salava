(ns salava.registerlink.ui.register-link
  (:require [reagent.core :refer [atom cursor]]
            [reagent.session :as session]
            [reagent-modals.modals :as m]
            [clojure.string :refer [trim]]
            [salava.core.ui.ajax-utils :as ajax]
            [salava.core.ui.layout :as layout]
            [salava.core.ui.helper :refer [path-for]]
            [cljs-uuid-utils.core :refer [make-random-uuid uuid-string]]
            [salava.core.i18n :refer [t]]
            [salava.core.helper :refer [dump]]))

(defn random-key []
  (-> (make-random-uuid)
      (uuid-string)))

(defn make-register-url [token]
  (str (session/get :site-url) "/user/register/token/" token))

(defn post-register-token [token state]
  (ajax/POST
   (path-for (str "/obpv1/registerlink/register-token/" token))
   {:response-format :json
    :keywords? true
    :handler (fn [data]
               (do
                 (swap! state assoc :url (make-register-url token )
                        :token token)))
    :error-handler (fn [{:keys [status status-text]}]
                     )}))


(defn post-register-active [active state]
  (dump active)
  (ajax/POST
   (path-for (str "/obpv1/registerlink/register-active"))
   {:response-format :json
    :keywords? true
    :params {:active active}
    :handler (fn [data]
               (do
                 (swap! state assoc :active active)))
    :error-handler (fn [{:keys [status status-text]}]
                     )})
  )


(defn content [state]
  (let [url-atom (cursor state [:url])
        active-atom (cursor state [:active])]
    [:div {:class "col-xs-12"}
     [:h2 (t :admin/Register-link)]
     (if @active-atom
       [:div.form-group
        [:label 
         (str (t :admin/url) ":")]
        [:input {:class    "form-control"
                 :value    @url-atom
                 :onChange #(reset! url-atom (.-target.value %))
                 :disabled true}]])

     [:div.checkbox
      [:label
       [:input {:name      "visibility"
                :type      "checkbox"
                :on-change #(do
                              (post-register-active (if @active-atom false true) state)
                              (.preventDefault %))
                :checked   @active-atom}]
       (t :user/Publishandshare)]]

     
     [:button {:class "btn btn-primary"
               :on-click #(do
                            (post-register-token (random-key) state)
                            (.preventDefault %))}
     (t :admin/Reset)]
     
     
     ]))

(defn init-data [state]
  (ajax/GET 
   (path-for "/obpv1/registerlink/register-token")
   {:handler (fn [data]
               (dump data)
               (swap! state assoc :url (make-register-url (:token data) )
                      :token (:token data)
                      :active (:active data))
               ;(reset! state data)
               )}))

(defn handler [site-navi]
  (let [state (atom {:url ""
                     :token nil
                     :active false})]
    (init-data state)
    (fn []
      (layout/default site-navi (content state)))))