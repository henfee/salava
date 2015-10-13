(ns salava.page.ui.routes)

(defn ^:export routes [context]
  {"/pages" [[""         (constantly [:p "My pages"])]
             ["/mypages" (constantly [:p "My pages"])]]})


(defn ^:export navi [context]
  {"/pages/"         {:weight 30 :title "Pages"}
   "/pages/mypages" {:weight 31 :title "My pages"}})
