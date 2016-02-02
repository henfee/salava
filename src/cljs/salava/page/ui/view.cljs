(ns salava.page.ui.view
  (:require [reagent.core :refer [atom cursor]]
            [ajax.core :as ajax]
            [salava.core.ui.layout :as layout]
            [salava.core.i18n :refer [t]]
            [salava.core.ui.helper :refer [navigate-to]]
            [salava.page.ui.helper :as ph]))

(defn check-password [password-atom page-id state]
  (ajax/POST
    (str "/obpv1/page/password/" page-id)
    {:response-format :json
     :keywords? true
     :params {:password @password-atom}
     :handler (fn [data]
                (swap! state assoc :page data)
                (swap! state assoc :ask-password false))
     :error-handler (fn [{:keys [status status-text]}]
                      (if (= status 401)
                        (swap! state assoc :password-error true)))}))

(defn page-password-field [state]
  (let [password-atom (cursor state [:password])]
    [:div {:id "page-password"}
     (if (:password-error @state)
       [:div {:class "alert alert-warning"}
        (t :page/Passwordincorrect)])
     [:div.row
      [:div.col-md-12
       [:h2 (t :page/Passwordrequired)]]]
     [:div.form-group
      [:input {:class "form-control"
               :type "text"
               :on-change #(reset! password-atom (.-target.value %))
               :value @password-atom
               :placeholder (t :page/Enterpassword)}]]
     [:div.form-group
      [:button {:class "btn btn-primary"
                :on-click #(check-password password-atom (:page-id @state) state)}
       (t :core/Submit)]]]))

(defn page-content [page]
  [:div {:id "page-view"}
   (if (:owner? page)
     [:div {:id "buttons"
            :class "text-right"}
      [:a {:class "btn btn-primary"
           :href  (str "/page/edit/" (:id page))}
       (t :page/Edit)]
      [:button {:class "btn btn-primary"
                :on-click #(.print js/window)}
       (t :core/Print)]])
   [ph/view-page page]])

(defn content [state]
  (let [page (:page @state)]

    (if (:ask-password @state)
      [page-password-field state]
      [:div {:id "page-container"}
       [page-content page]])))

(defn init-data [state id]
  (ajax/GET
    (str "/obpv1/page/view/" id)
    {:response-format :json
     :keywords? true
     :handler (fn [data]
                (swap! state assoc :page (:page data))
                (swap! state assoc :ask-password (:ask-password data)))
     :error-handler (fn [{:keys [status status-text]}]
                      (if (= status 401)
                        (navigate-to "/user/login")))}))

(defn handler [site-navi params]
  (let [id (:page-id params)
        state (atom {:page {}
                     :page-id id
                     :ask-password false
                     :password ""
                     :password-error false})]
    (init-data state id)
    (fn []
      (layout/default site-navi (content state)))))
