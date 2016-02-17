(ns salava.badge.main
  (:require [yesql.core :refer [defqueries]]
            [clojure.string :refer [blank? split upper-case lower-case capitalize]]
            [slingshot.slingshot :refer :all]
            [salava.core.time :refer [unix-time]]
            [salava.core.i18n :refer [t]]
            [salava.core.helper :refer [dump]]
            [salava.core.util :refer [get-db map-sha256 file-from-url hex-digest]]
            [salava.badge.assertion :refer [fetch-json-data]]
            [clojure.string :as string]))

(defqueries "sql/badge/main.sql")

(defn assoc-badge-tags [badge tags]
  (assoc badge :tags (map :tag (filter #(= (:badge_id %) (:id badge))
                                       tags))))
(defn map-badges-tags [badges tags]
  (map (fn [b] (assoc-badge-tags b tags))
       badges))

(defn badge-owner? [ctx badge-id user-id]
  (let [owner (select-badge-owner {:id badge-id} (into {:result-set-fn first :row-fn :user_id} (get-db ctx)))]
    (= owner user-id)))

(defn check-issuer-verified!
  "Fetch issuer data and check if issuer is verified by OBF"
  [ctx badge-id issuer-json-url mtime issuer-verified-initial]
  (try+
    (if (and issuer-json-url mtime (< mtime (- (unix-time) (* 60 60 24 2))))
      (let [issuer-url-base (split issuer-json-url #"&event=")
            issuer-json (if (second issuer-url-base) (fetch-json-data (first issuer-url-base)))
            issuer-verified (if issuer-json (:verified issuer-json) issuer-verified-initial)]
        (if (and issuer-json badge-id)
          (update-badge-set-verified! {:issuer_verified issuer-verified :id badge-id} (get-db ctx)))
        issuer-verified)
      issuer-verified-initial)
    (catch Object _
      issuer-verified-initial)))

(defn badge-issued-and-verified-by-obf
  "Check if badge is issued by Open Badge Factory and if the issuer is verified"
  [ctx badge]
  (try+
    (let [obf-url (get-in ctx [:config :core :open-badge-factory-url] "")
          obf-base (-> obf-url (split #"://") second)
          {:keys [id badge_url issuer_url mtime issuer_verified]} badge
          issued-by-obf (if (and obf-base badge_url) (-> obf-base re-pattern (re-find badge_url) boolean))
          verified-by-obf (and issued-by-obf (check-issuer-verified! ctx id issuer_url mtime issuer_verified))]
      (assoc badge :issued_by_obf (boolean issued-by-obf)
                   :verified_by_obf (boolean verified-by-obf)
                   :obf_url obf-url))
    (catch Object _
      badge)))

(defn user-badges-all
  "Returns all the badges of a given user"
  [ctx userid]
  (let [badges (select-user-badges-all {:user_id userid} (get-db ctx))
        tags (if-not (empty? badges) (select-taglist {:badge_ids (map :id badges)} (get-db ctx)))
        badges-with-tags (map-badges-tags badges tags)]
    (map #(badge-issued-and-verified-by-obf ctx %) badges-with-tags)))

(defn user-badges-to-export
  "Returns valid badges of a given user"
  [ctx userid]
  (let [badges (select-user-badges-to-export {:user_id userid} (get-db ctx))
        tags (if-not (empty? badges) (select-taglist {:badge_ids (map :id badges)} (get-db ctx)))]
    (map-badges-tags badges tags)))

(defn user-badges-pending
  "Returns pending badges of a given user"
  [ctx userid]
  (let [badges (select-user-badges-pending {:user_id userid} (get-db ctx))
        tags (if-not (empty? badges) (select-taglist {:badge_ids (map :badge_content_id badges)} (get-db ctx)))]
    (map-badges-tags badges tags)))

(defn user-owns-badge?
  "Check if user owns badge"
  [ctx assertion user-id]
  (pos? (:count (if (= (get-in assertion [:verify :type]) "hosted")
                  (select-user-owns-hosted-badge
                    {:assertion_url (get-in assertion [:verify :url])
                     :user_id user-id}
                    (into {:result-set-fn first}
                          (get-db ctx)))
                  (select-user-owns-signed-badge
                    {:assertion_json (get-in assertion [:assertion_json])
                     :user_id user-id}
                    (into {:result-set-fn first}
                          (get-db ctx)))))))

(defn get-badge
  "Get badge by id"
  [ctx badge-id user-id]
  (let [badge (select-badge {:id badge-id} (into {:result-set-fn first} (get-db ctx)))
        owner? (= user-id (:owner badge))
        all-congratulations (if user-id (select-all-badge-congratulations {:badge_id badge-id} (get-db ctx)))
        user-congratulation? (and user-id
                                  (not owner?)
                                  (some #(= user-id (:id %)) all-congratulations))
        view-count (if owner? (select-badge-view-count {:badge_id badge-id} (into {:result-set-fn first :row-fn :count} (get-db ctx))))
        badge (badge-issued-and-verified-by-obf ctx badge)]
    (assoc badge :congratulated? user-congratulation?
                 :congratulations all-congratulations
                 :view_count view-count)))

(defn save-badge-content!
  "Save badge content"
  [ctx assertion image-file]
  (let [badge-data {:name (get-in assertion [:badge :name])
                    :description (get-in assertion [:badge :description])
                    :image_file image-file}
        badge-content-sha256 (map-sha256 badge-data)
        data (assoc badge-data :id badge-content-sha256)]
    (replace-badge-content! data (get-db ctx))
    badge-content-sha256))


(defn save-criteria-content!
  "Save badge criteria content"
  [ctx assertion]
  (let [criteria-data {:html_content (get-in assertion [:badge :criteria_html])
                       :markdown_content (get-in assertion [:badge :criteria_markdown])}
        criteria-content-sha256 (map-sha256 criteria-data)
        data (assoc criteria-data :id criteria-content-sha256)]
    (replace-criteria-content! data (get-db ctx))
    criteria-content-sha256))

(defn save-badge!
  "Save user's badge"
  [ctx user-id recipient-email badge badge-content-id issuer-content-id criteria-content-id]
  (let [expires (get-in badge [:assertion :expires])
        issuer-url (get-in badge [:assertion :badge :issuer_url])
        issuer-verified (check-issuer-verified! ctx nil issuer-url 0 false)
        data {:user_id             user-id
              :email               recipient-email
              :assertion_url       (get-in badge [:assertion :verify :url])
              :assertion_jws       (get-in badge [:assertion :assertion_jws])
              :assertion_json      (get-in badge [:assertion :assertion_json])
              :badge_url           (get-in badge [:assertion :badge :badge_url])
              :issuer_url          issuer-url
              :criteria_url        (get-in badge [:assertion :badge :criteria_url])
              :criteria_content_id criteria-content-id
              :badge_content_id    badge-content-id
              :issuer_content_id   issuer-content-id
              :issued_on           (get-in badge [:assertion :issuedOn])
              :expires_on          (if (= expires "0") nil expires)
              :evidence_url        (get-in badge [:assertion :evidence])
              :status              (get-in badge [:_status] "pending")
              :visibility          "private"
              :show_recipient_name 0
              :rating              0
              :ctime               (unix-time)
              :mtime               (unix-time)
              :deleted             0
              :revoked             0
              :issuer_verified     issuer-verified}]
    (insert-badge<! data (get-db ctx))))

(defn save-issuer-content!
  "Save issuer-data"
  [ctx assertion image-file]
  (let [issuer-data {:name (get-in assertion [:badge :issuer :name])
                     :description (get-in assertion [:badge :issuer :description])
                     :url (get-in assertion [:badge :issuer :url])
                     :image_file image-file
                     :email (get-in assertion [:badge :issuer :email])
                     :revocation_list_url (get-in assertion [:badge :issuer :revocationList])}
        issuer-content-sha256 (map-sha256 issuer-data)
        data (assoc issuer-data :id issuer-content-sha256)]
    (replace-issuer-content! data (get-db ctx))
    issuer-content-sha256))

(defn check-email [recipient email algorithms]
  (let [{hashed :hashed identity :identity salt :salt} recipient
        [algo hash] (split (or identity "") #"\$")
        algorithm (get algorithms algo)]
    (if hashed
      (do
        (if-not algorithm
          (throw+ "Invalid algorithm"))
        (first (filter #(= hash (hex-digest algorithm (str % salt))) [email (upper-case email) (lower-case email) (capitalize email)])))
      (if (= email identity)
        email))))

(defn check-recipient [ctx user-emails assertion]
  (let [algorithms (get-in ctx [:config :badge :algorithms])
        recipient (:recipient assertion)]
    (if (empty? user-emails)
      (throw+ "Badge is not issued to user"))
    (loop [emails user-emails
           checked-email (check-email recipient (first emails) algorithms)]
      (cond
        (empty? (rest emails)) nil
        (boolean checked-email) checked-email
        :else (recur (rest emails) (check-email recipient (first emails) algorithms))))))

(defn save-badge-from-assertion!
  [ctx badge user-id emails]
  (try+
    (let [assertion (:assertion badge)
          recipient-email (check-recipient ctx emails assertion)
          badge-image-path (file-from-url (get-in assertion [:badge :image]))
          badge-content-id (save-badge-content! ctx assertion badge-image-path)
          issuer-image (get-in assertion [:badge :issuer :image])
          issuer-image-path (if-not (empty? issuer-image)
                              (file-from-url issuer-image))
          issuer-content-id (save-issuer-content! ctx assertion issuer-image-path)
          criteria-content-id (save-criteria-content! ctx assertion)]
      (if (user-owns-badge? ctx (:assertion badge) user-id)
        (throw+ (t :badge/Alreadyowned)))
      (if-not recipient-email
        (throw+ (t :badge/Userdoesnotownthisbadge)))
      (:generated_key (save-badge! ctx user-id recipient-email badge badge-content-id issuer-content-id criteria-content-id)))))

(defn save-badge-tags!
  "Save tags associated to badge. Delete existing tags."
  [ctx tags badge-id]
  (let [valid-tags (filter #(not (blank? %)) (distinct tags))]
    (delete-badge-tags! {:badge_id badge-id} (get-db ctx))
    (doall (for [tag valid-tags]
             (replace-badge-tag! {:badge_id badge-id :tag tag}
                              (get-db ctx))))))
(defn set-visibility!
  "Set badge visibility"
  [ctx badge-id visibility user-id]
  (if (badge-owner? ctx badge-id user-id)
    (update-visibility! {:id badge-id
                         :visibility visibility}
                        (get-db ctx))))

(defn set-status!
  "Set badge status"
  [ctx badge-id status user-id]
  (if (badge-owner? ctx badge-id user-id)
    (update-status! {:id badge-id
                     :status status}
                    (get-db ctx))))

(defn toggle-show-recipient-name!
  "Toggle recipient name visibility"
  [ctx badge-id show-recipient-name user-id]
  (if (badge-owner? ctx badge-id user-id)
    (update-show-recipient-name! {:id badge-id
                                  :show_recipient_name show-recipient-name}
                                 (get-db ctx))))

(defn congratulate!
  "User congratulates badge receiver"
  [ctx badge-id user-id]
  (let [congratulation-exists (select-badge-congratulation {:badge_id badge-id :user_id user-id} (into {:result-set-fn first} (get-db ctx)))]
    (if (empty? congratulation-exists)
      (insert-badge-congratulation! {:badge_id badge-id :user_id user-id} (get-db ctx)))))

(defn badge-settings
  "Get badge settings"
  [ctx badge-id user-id]
  (if (badge-owner? ctx badge-id user-id)
    (let [badge (select-badge-settings {:id badge-id}
                                       (into {:result-set-fn first}
                                             (get-db ctx)))
          tags (select-taglist {:badge_ids [badge-id]} (get-db ctx))]
      (assoc-badge-tags badge tags))))

(defn save-badge-settings!
  "Update badge setings"
  [ctx badge-id user-id visibility evidence-url rating tags]
  (if (badge-owner? ctx badge-id user-id)
    (let [data {:id          badge-id
               :visibility   visibility
               :evidence_url evidence-url
               :rating       rating}]
     (update-badge-settings! data (get-db ctx))
     (save-badge-tags! ctx tags badge-id))))

(defn delete-badge!
  "Set badge deleted and delete tags"
  [ctx badge-id user-id]
  (when (badge-owner? ctx badge-id user-id)
    (delete-badge-tags! {:badge_id badge-id} (get-db ctx))
    (update-badge-set-deleted! {:id badge-id} (get-db ctx))))

(defn badges-images-names
  "Get badge images and names. Return a map."
  [ctx badge-ids]
  (if-not (empty? badge-ids)
    (let [badges (select-badges-images-names {:ids badge-ids} (get-db ctx))]
      (reduce #(assoc %1 (str (:id %2)) (dissoc %2 :id)) {} badges))))

(defn badges-by-tag-and-owner
  "Get badges by list of tag names and owner's user-id"
  [ctx tag user-id]
  (select-badges-by-tag-and-owner {:badge_tag tag
                                    :user_id user-id} (get-db ctx)))

(defn badge-viewed
  "Save information about viewing a badge. If user is not logged in user-id is nil."
  [ctx badge-id user-id]
  (insert-badge-viewed! {:badge_id badge-id :user_id user-id} (get-db ctx)))

(defn badges-by-issuer [badges-issuers]
  (reduce (fn [result issuer-badge]
            (let [issuer (select-keys issuer-badge [:issuer_content_id :issuer_content_name :issuer_content_url])
                  badge (select-keys issuer-badge [:id :name :image_file])
                  index (.indexOf (map :issuer_content_id result) (:issuer_content_id issuer))]
              (if (= index -1)
                (conj result (assoc issuer :badges [badge]))
                (update-in result [index :badges] conj badge)))) [] badges-issuers))

(defn badge-stats
  "Get badge statistics by user-id."
  [ctx user-id]
  (let [badge-count (select-user-badge-count {:user_id user-id} (into {:result-set-fn first :row-fn :count} (get-db ctx)))
        expired-badge-count (select-user-expired-badge-count {:user_id user-id} (into {:result-set-fn first :row-fn :count} (get-db ctx)))
        badge-views (select-badge-views-stats {:user_id user-id} (get-db ctx))
        badge-congratulations (select-badge-congratulations-stats {:user_id user-id} (get-db ctx))
        issuer-stats (badges-by-issuer (select-badge-issuer-stats {:user_id user-id} (get-db ctx)))]
    {:badge_count badge-count
     :expired_badge_count expired-badge-count
     :badge_views badge-views
     :badge_congratulations badge-congratulations
     :badge_issuers issuer-stats}))