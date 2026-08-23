(ns hikyaku.plan
  "shomen の `interim`（繋ぎ）を、人が実行できる**手順書**に落とす。

  各 step は「何をするか」「終えた証拠として何を残すか」「手間賃」を持つ。
  証拠の種類は `hikyaku.job` が遷移の条件にする —— 証拠の無い step は終わらない。

  ## 郵便の種別

  追跡できない普通郵便は使わない（shomen の interim も `:traceable? true` を要求する）。

  | 届け先 | 種別 | 理由 |
  |---|---|---|
  | 裁判所・法務局・官署 | 一般書留 | 印紙（現金同等）と原本を運ぶ。法務局の郵送申請は書留が案内されている |
  | 内容証明が要る文書 | 内容証明 + 配達証明 | 文書の内容と到達を証明する |
  | それ以外 | 特定記録 | 引受と配達を記録する。補償は無い |"
  (:require [hikyaku.fee :as fee]
            [shomen.classify :as c]))

(def labor-jpy
  "手間賃（円、seed）。印紙の額面と郵便料金は**含まない**（pass-through、別明細）。"
  {:receive-principal-documents 0
   :buy-revenue-stamp 800
   :affix-revenue-stamp 300
   :enclose-originals 300
   :enclose 300
   :post 500
   :archive-receipt 200})

(def evidence-required
  "step ごとに、完了の証拠として要るもの。"
  {:receive-principal-documents #{:document-hash}
   :buy-revenue-stamp #{:receipt-photo-hash :denominations}
   :affix-revenue-stamp #{:affixed-page-photo-hash}
   :enclose-originals #{:before-seal-photo-hash}
   :enclose #{:before-seal-photo-hash}
   :post #{:tracking-number :postal-class}
   :archive-receipt #{:bundle-cid}})

(defn postal-class
  "届け先の種類と文書の性質から郵便種別を決める。"
  [{:keys [authority content-certified?]}]
  (cond
    content-certified? :content-certified
    (contains? #{:court :legal-affairs-bureau :immigration :notary :ministry} authority) :registered
    :else :tokutei-kiroku))

(defn- enrich-step [step entry amount]
  (let [k (:task step)
        base (assoc step
                    :labor-jpy (get labor-jpy k 0)
                    :evidence (get evidence-required k #{}))]
    (case k
      :buy-revenue-stamp
      (let [amt (or amount (:amount-jpy step))]
        (cond-> (assoc base :pass-through :stamp-face-value)
          amt (assoc :amount-jpy amt :denominations (fee/split amt) :sheets (fee/sheets (fee/split amt)))))
      :post
      (assoc base :postal-class (postal-class {:authority (:req/authority entry)})
             :pass-through :postage)
      base)))

(defn plan
  "entry + 依頼（`{:amount-jpy .. :documents ..}`）→ `{:steps [..] :labor-jpy n
  :pass-through {..} :delegable? ..}`。繋ぎが不要（電子の道がある）なら nil、
  `:unverified` なら `{:refused …}`、本人でなければできないなら `{:delegable? false …}`。"
  [entry request]
  (let [i (c/interim entry)]
    (cond
      (nil? i) nil
      (:refused i) i
      (false? (:delegable? i)) i
      :else
      (let [steps (mapv #(enrich-step % entry (:amount-jpy request)) (:tasks i))
            stamp (some #(when (= :buy-revenue-stamp (:task %)) %) steps)]
        {:delegable? true
         :route (:route i)
         :entry-id (:req/id entry)
         :steps steps
         :labor-jpy (reduce + (map :labor-jpy steps))
         :pass-through (cond-> {:postage :actual}
                         stamp (assoc :stamp-face-value (:amount-jpy stamp)))
         :cancel-by (some :cancel-by steps)}))))
