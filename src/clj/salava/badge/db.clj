(ns salava.badge.db
  (:import (java.io StringReader))
  (:require [clojure.tools.logging :as log]
            [yesql.core :refer [defqueries]]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [net.cgrand.enlive-html :as html]
            [schema.core :as s]
            [salava.badge.schemas :as schemas] ;cljc
            [salava.core.util :as u]))

(defqueries "sql/badge/main.sql")

(defn- content-id [data]
  (u/map-sha256 (assoc data :id "")))

(defn alt-markdown [^String input]
  (let [link-tags (-> (StringReader. input) (html/html-resource) (html/select [:head :link]))
        md-url (some #(when (and (= (:rel %) "alternate") (= (:type %) "text/x-markdown")) (:href %))
                     (map :attrs link-tags))]
    (try (u/http-get md-url) (catch Exception _ ""))))

(defn- content-type [_ input & _]
  (cond
    (map? input)          :default
    (string/blank? input) :blank
    (and (string? input) (re-find #"^https?://" input)) :url
    (and (string? input) (re-find #"^\s*\{" input))     :json
    (and (string? input) (re-find #"<.+>" input))       :html))


;;

(defmulti save-criteria-content! content-type)

(defmethod save-criteria-content! :blank [_ _ & _]
  (throw (Exception. "badge/MissingCriteriaContent")))

(defmethod save-criteria-content! :url [ctx input & _]
  (save-criteria-content! ctx (u/http-get input) {:url input}))

(defmethod save-criteria-content! :html [ctx input & more]
  (let [input-meta (or (meta input) (first more) {})]
    (save-criteria-content!
      ctx (with-meta {:id ""
                      :html_content input
                      :markdown_content (alt-markdown input)}
                     input-meta))))

(defmethod save-criteria-content! :default [ctx input & _]
  (s/validate schemas/CriteriaContent input)
  (let [id (content-id input)]
    (insert-criteria-content! (assoc input :id id) (u/get-db ctx))
    {:criteria_content_id id :criteria_url (:url (meta input))}))

;;

(defmulti save-issuer-content! content-type)

(defmethod save-issuer-content! :blank [_ _ & _]
  (throw (Exception. "badge/MissingIssuerContent")))

(defmethod save-issuer-content! :url [ctx input & _]
  (save-issuer-content! ctx (u/http-get input) {:url input}))

(defmethod save-issuer-content! :json [ctx input & more]
  (let [input-meta (or (meta input) (first more) {})
        data (json/read-str input :key-fn keyword)]
    (save-issuer-content!
      ctx (with-meta {:id ""
                      :name        (:name data)
                      :image_file  (if-not (string/blank? (:image data)) (u/file-from-url ctx (:image data)))
                      :description (:description data)
                      :url   (:url data)
                      :email (:email data)
                      :revocation_list_url (:revocationList data)}
                     input-meta))))

(defmethod save-issuer-content! :default [ctx input & _]
  (s/validate schemas/IssuerContent input)
  (let [id (content-id input)]
    (insert-issuer-content! (assoc input :id id) (u/get-db ctx))
    {:issuer_content_id id :issuer_url (:url (meta input))}))

;;

(defmulti save-creator-content! content-type)

(defmethod save-creator-content! :blank [_ _ & _]
  (throw (Exception. "badge/MissingCreatorContent")))

(defmethod save-creator-content! :url [ctx input & _]
  (save-creator-content! ctx (u/http-get input) {:json-url input}))

(defmethod save-creator-content! :json [ctx input & more]
  (let [input-meta (or (meta input) (first more) {})
        data (json/read-str input :key-fn keyword)]
    (save-creator-content!
      ctx (with-meta {:id ""
                      :name        (:name data)
                      :image_file  (if-not (string/blank? (:image data)) (u/file-from-url ctx (:image data)))
                      :description (:description data)
                      :url   (:url data)
                      :email (:email data)
                      :json_url ((meta input) :json-url "")}
                     input-meta))))

(defmethod save-creator-content! :default [ctx input & _]
  (s/validate schemas/CreatorContent input)
  (let [id (content-id input)]
    (insert-creator-content! (assoc input :id id) (u/get-db ctx))
    {:creator_content_id id :creator_url (:json-url (meta input))}))

;;

(defmulti save-badge-content! content-type)

(defmethod save-badge-content! :blank [_ _ & _]
  (throw (Exception. "badge/MissingBadgeContent")))

(defmethod save-badge-content! :url [ctx input & _]
  (save-badge-content! ctx (u/http-get input) {:url input}))

(defmethod save-badge-content! :json [ctx input & more]
  (let [input-meta (or (meta input) (first more) {})
        data (json/read-str input :key-fn keyword)]
    (merge
      (save-badge-content! ctx (with-meta {:id ""
                                           :name        (:name data)
                                           :image_file  (u/file-from-url ctx (:image data))
                                           :description (:description data)
                                           :alignment   []
                                           :tags        (:tags data)}
                                          input-meta))
      (if (:issuer data)
        (save-issuer-content! ctx (:issuer data))
        {:issuer_content_id nil})
      (if (:criteria data)
        (save-criteria-content! ctx (:criteria data))
        {:criteria_content_id nil})
      (if (:extensions:OriginalCreator data)
        (save-creator-content! ctx (get-in data [:extensions:OriginalCreator :url]))
        {:creator_content_id nil}))))

(defmethod save-badge-content! :default [ctx input & _]
  (s/validate schemas/BadgeContent input)
  (let [id (content-id input)]
    (jdbc/with-db-transaction  [t-con (:connection (u/get-db ctx))]
      (insert-badge-content! (assoc input :id id) {:connection t-con})
      (doseq [tag (:tags input)]
        (insert-badge-content-tag! {:badge_content_id id :tag tag} {:connection t-con}))
      (doseq [a (:alignment input)]
        (insert-badge-content-alignment! (assoc a :badge_content_id id) {:connection t-con})))
    {:badge_content_id id :badge_url (:url (meta input))}))

;;

(defn save-badge! [ctx data]
  (let [content (save-badge-content! ctx (:badge_url data))]
    (assoc content :id (insert-badge<! (merge data content) (u/get-db ctx)))))

;;
