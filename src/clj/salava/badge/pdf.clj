(ns salava.badge.pdf
  (:require [yesql.core :refer [defqueries]]
            [salava.core.time :refer [unix-time date-from-unix-time]]
            [salava.core.i18n :refer [t]]
            [salava.core.helper :refer [dump private?]]
            [salava.badge.main :refer [user-badges-to-export fetch-badge badge-url]]
            [salava.core.util :as u :refer [get-db plugin-fun get-plugins md->html str->qr-base64]]
            [clj-pdf.core :as pdf]
            [clj-pdf-markdown.core :refer [markdown->clj-pdf]]
            [clojure.string :refer [ends-with?]]
            [clojure.java.io :as io]
;;             [tikkba.dom :refer [svg-doc]]
;;           	 [analemma.svg :refer [svg rect]]
;;           	 [analemma.xml :as xml]
            [tikkba.transcoder :as transcoder]))

(defqueries "sql/badge/main.sql")

(defn pdf-generator-helper [ctx user-id input]
  (let [badges-for-export (user-badges-to-export ctx user-id)
        filtered-badges (filter (fn [b] (some #(= % (:id b)) input)) badges-for-export)
        badge-with-content (map #(-> (fetch-badge ctx (:id %))
                                     (assoc :tags (:tags %))) filtered-badges)
        badge-ids (map #(:badge_id (first (get-in % [:content]))) badge-with-content)
        temp (map #(select-multi-language-badge-content {:id %} (u/get-db ctx)) badge-ids)
        badges (map #(-> %1
                           (dissoc :content)
                           (assoc :qr_code (str->qr-base64 (badge-url ctx (:id %1)))
                                  :endorsements (vec (select-badge-endorsements {:id (:badge_id %1)} (u/get-db ctx)))
                                  :content %2)) badge-with-content temp)]
    #_(dump badges)
    badges))

(defn generatePDF [ctx user-id input lang]
    (let [data-dir (get-in ctx [:config :core :data-dir])
          site-url (get-in ctx [:config :core :site-url])
          badges (pdf-generator-helper ctx user-id input)
          font-path  (first (mapcat #(get-in ctx [:config % :font] []) (get-plugins ctx)))
          font  {:ttf-name (str site-url font-path)}
          stylesheet {:heading-name {:color [127 113 121]
                                     :family :times-roman
                                     :align :center}

                      :generic {:family :times-roman
                                :color [127 113 121]
                                :indent 20}
                      :link {:family :times-roman
                             :color [66 100 162]}
                      :chunk {:size 11
                              :style :bold}}

          pdf-settings  (if (empty? font-path) {:stylesheet stylesheet  :bottom-margin 0 :footer {:page-numbers false :align :right}} {:font font :stylesheet stylesheet  :bottom-margin 0 :footer {:page-numbers false :align :right}})
          badge-template (pdf/template
                           (let [template #(cons [:paragraph]  [ (if (ends-with? (:image_file %) "png")
                                                                   [:image {:width 100 :height 100 :align :center} (str data-dir "/" (:image_file %))]
                                                                   [:image {:width 100 :height 100 :align :center :base64 true} $qr_code ]

                                                                   #_[:cell {:align :center :border true}[:svg #_{:scale 0.5 :translate [200 0]}
                                                                                                        (io/file (str data-dir "/" (:image_file %)))]])

                                                   [:heading.heading-name (:name %)]
                                                   [:paragraph {:indent 20 :align :center}#_[:line] [:spacer]]
                                                   [:paragraph.generic {:align :left} (:description %)][:spacer]
                                                   [:paragraph.generic
                                                    [:chunk.chunk (str (t :badge/Recipient)": ")] (str $first_name  " " $last_name ) "\n"
                                                    [:chunk.chunk (str (t :badge/Issuedby)": ")] (:issuer_content_name %)"\n"
                                                    (when-not (empty? (:creator_name %))
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/Createdby)": ")] (:creator_name %)])
                                                    [:chunk.chunk (str (t :badge/Issuedon)": ")] (date-from-unix-time (long (* 1000 $issued_on)) "date") "\n"
                                                    (when $expires_on
                                                     [:paragraph
                                                      [:chunk.chunk (str (t :badge/Expireson)": ")] (date-from-unix-time (long (* 1000 $expires_on)) "date")])

                                                    (when-not (empty? $tags)
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/Tags)": ")] (into [:phrase ] (for [t $tags] (str t " ")))])
                                                    (when-not (empty? $alignments)
                                                      [:paragraph.generic
                                                       [:spacer 0]
                                                       [:phrase {:size 12 :style :bold} (t :badge/Alignments)]
                                                       [into [:paragraph ] (for [a $alignments]
                                                                             [:paragraph
                                                                              [:chunk.chunk "Name: "] (:name a) "\n"
                                                                              [:chunk.chunk "Description: "] (:description a) "\n"
                                                                              [:chunk.chunk "Url: "] (:url a) [:spacer 0]]) ]])
                                                    [:paragraph
                                                      [:chunk.chunk (str (t :badge/CriteriaUrl)": ")] [:anchor {:target (:criteria_url %) :style{:family :times-roman :color [66 100 162]}} (:criteria_url %)]]
                                                    [:spacer 0]
                                                    [:phrase.generic
                                                     [:chunk.chunk  (t :badge/Criteria)] ]"\n"
                                                     (markdown->clj-pdf {:spacer {:extra-starting-value 1 :allow-extra-line-breaks? true :single-value 2} :wrap {:global-wrapper :paragraph}} (:criteria_content %))]

                                                  (when-not (empty? $endorsements)
                                                    [:paragraph.generic
                                                     [:spacer 0]
                                                     [:phrase {:size 12 :style :bold} (t :badge/BadgeEndorsedBy)]
                                                       [:spacer 0]
                                                       (into [:paragraph {:indent 0} ] (for [e $endorsements]
                                                           [:paragraph
                                                            #_[:chunk.chunk "Endorser: " ] (:issuer_name e) "\n"
                                                            #_[:chunk.chunk "Issuer url: "] [:anchor {:target (:issuer_url e) :style{:family :times-roman :color [66 100 162]}} (:issuer_url e)] "\n"
                                                            #_[:chunk.chunk "Issued on: "][:chunk (date-from-unix-time (long (* 1000 (:issued_on e))))] "\n"
                                                            (markdown->clj-pdf (:content e))]))])

                                                    [:line {:dotted true}]
                                                    [:spacer 0]
                                                    [:heading.heading-name (t :badge/IssuerInfo)]
                                                    [:spacer 0]
                                                    (when-not (empty? (:issuer_description %))
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/IssuerDescription)": ")] (:issuer_description %)])
                                                   [:spacer 1]
                                                     [:paragraph.generic
                                                      [:chunk.chunk (str (t :badge/IssuerWebsite)": ")]
                                                      [:anchor {:target (:issuer_content_url %) :style{:family :times-roman :color [66 100 162]}} (:issuer_content_url %)]]
                                                    (when-not (empty? (:issuer_contact %))
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/IssuerContact)": ")] (:issuer_contact %)])
                                                    (when-not (empty? (:creator_description %))
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/CreatorDescription)": ")] (:creator_description %)])
                                                    (when-not (empty? (:creator_url %))
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/CreatorWebsite)": ")] [:anchor {:target (:creator_url %) :style{:family :times-roman :color [66 100 162]}} (:creator_url %)] "\n"])
                                                    (when-not (empty? (:creator_contact %))
                                                      [:paragraph.generic
                                                        [:chunk.chunk (str (t :badge/CreatorContact)": ")] (:creator_contact %)])
                                                    (let [issuer-endorsement (select-issuer-endorsements {:id (:issuer_content_id %)} (u/get-db ctx))]
                                                      (when-not (empty? issuer-endorsement)
                                                          [:paragraph.generic
                                                           [:spacer]
                                                           [:phrase {:size 12 :style :bold} (t :badge/IssuerEndorsedBy)]
                                                            [:spacer 0]
                                                            (reduce into []
                                                              (for [e issuer-endorsement]
                                                               [:paragraph {:indent 0}
                                                                #_[:chunk.chunk "Endorser: " ] (:issuer_name e) "\n"
                                                                #_[:chunk.chunk "Issuer url: "] [:anchor {:target (:issuer_url e) :style{:family :times-roman :color [66 100 162]}} (:issuer_url e)] "\n"
                                                                #_[:chunk.chunk "Issued on: "][:chunk.link (date-from-unix-time (long (* 1000 (:issued_on e))))] "\n"
                                                                (markdown->clj-pdf {:wrap {:global-wrapper :paragraph}} (:content e))]))]))

                                                    [:pdf-table {:align :right :width-percent 100 :cell-border false}
                                                     nil
                                                     [[:pdf-cell [:paragraph {:align :right} [:chunk [:image  {:width 85 :height 85 :base64 true} $qr_code]]"\n"
                                                      [:phrase [:chunk.link {:style :italic} (str site-url "/badge/info/" $id)]]]]
                                                     ]]

                                                    [:pagebreak]])

                                 content (if (= lang "all") (map template $content) (map template (filter #(= (:default_language_code %) (:language_code %)) $content)))]

                             (reduce into [[:chapter ]] content)))]
;;TODO Test alignments
 (fn [output-stream]
   (pdf/pdf (into [pdf-settings] (badge-template badges)) output-stream)))
)