(ns hikyaku.governor
  "依頼を受けてよいかを、手順書とは別の系統で判定する。

  飛脚は**使者**である。文書を作らず、直さず、署名も押印もしない。運ぶ・買う・貼る・
  封をする・出す・受領証を残す —— 事実行為だけ。この線は行政書士法 §19・司法書士法 §73・
  弁護士法 §72 の境界で、ここを越えた依頼は受けない。

  | gate | 何を守るか |
  |---|---|
  | G1 consent          | 本人の DID 署名付き同意（scope `:hikyaku/job`） |
  | G2 messenger-only   | 文書は本人が作成済み。作成・補正・署名・押印・代理申請の依頼は受けない |
  | G3 delegable        | 出頭・押印・公証が残る手続きは繋ぎが成立しない（shomen） |
  | G4 route            | `:electronic-now` は繋ぎ不要（電子の道を案内して断る）。`:unverified` は調べていない |
  | G5 amount-confirmed | 印紙の額は seed entry から自動で決めない。本人が額を確認していること |
  | G6 office-cancels   | 本人が消印すべき印紙（課税文書）は飛脚が貼らない。受理官署が消印するものだけ |
  | G7 pii-sealed       | 届け先住所・氏名は sealed envelope。平文で依頼に書かれていたら受けない |
  | G8 traceable-post   | 追跡できる郵便種別だけ |"
  (:require [hikyaku.plan :as plan]
            [shomen.classify :as c]))

(def forbidden-requests
  "使者の線を越える依頼。"
  #{:draft :amend :sign :seal :file-as-agent :advise})

(def live-confidences #{:maintainer-verified :counsel-verified})

(defn decide
  "`{:entry :request :principal}` → `{:decision :allow|:deny :reasons [..] :plan ..}`。
  `request`: `{:amount-jpy :amount-confirmed-by-principal :documents-prepared-by
  :asks #{..} :recipient {:sealed ..} :postal-class}`。"
  [{:keys [entry request principal]}]
  (let [route (c/route entry)
        p (plan/plan entry request)
        stamp-step (some #(when (= :buy-revenue-stamp (:task %)) %) (:steps p))
        denies
        (cond-> []
          (not= :hikyaku/job (get-in principal [:consent :scope]))
          (conj {:gate :G1 :detail "本人の同意（scope :hikyaku/job）が無い"})

          (not (string? (get-in principal [:consent :signed-by])))
          (conj {:gate :G1 :detail "同意に署名者 DID が無い"})

          (not= :principal (:documents-prepared-by request))
          (conj {:gate :G2 :detail "文書は本人が作成済みでなければならない（飛脚は作成しない）"})

          (seq (filter forbidden-requests (:asks request)))
          (conj {:gate :G2 :detail (str "使者の線を越える依頼: " (pr-str (vec (filter forbidden-requests (:asks request))))
                                        " —— 行政書士法 §19 / 司法書士法 §73 / 弁護士法 §72")})

          (= route :unverified)
          (conj {:gate :G4 :detail "電子の道の状態が :unknown。調べる前に紙で運ばない"})

          (= route :electronic-now)
          (conj {:gate :G4 :detail (str "電子の道がある（" (get-in entry [:req/electronic :e/system]) "）。繋ぎは不要 —— 電子で出す")})

          (and (map? p) (false? (:delegable? p)))
          (conj {:gate :G3 :detail (str "本人でなければできない要件が残る: " (pr-str (:blocked p)))})

          (and stamp-step (nil? (:amount-jpy stamp-step)))
          (conj {:gate :G5 :detail "印紙の額が決まっていない（entry にも依頼にも無い）"})

          (and stamp-step
               (not (contains? live-confidences (:req/confidence entry)))
               (not (true? (:amount-confirmed-by-principal request))))
          (conj {:gate :G5 :detail (str "entry は " (:req/confidence entry) "。seed の額で印紙を買わない —— 本人が額を確認すること（:amount-confirmed-by-principal）")})

          (and stamp-step (= :applicant (:cancel-by (some #(when (= :affix-revenue-stamp (:task %)) %) (:steps p)))))
          (conj {:gate :G6 :detail "本人が消印する印紙（課税文書）は飛脚が貼らない。本人が貼付・消印した文書を運ぶ依頼に変える"})

          (or (string? (:recipient-address request)) (string? (:recipient-name request)))
          (conj {:gate :G7 :detail "届け先が平文で依頼に書かれている。sealed envelope（:recipient {:sealed …}）で渡す"})

          (not (map? (get-in request [:recipient :sealed])))
          (conj {:gate :G7 :detail "届け先の sealed envelope が無い"})

          (and (:postal-class request)
               (not (contains? #{:registered :content-certified :tokutei-kiroku} (:postal-class request))))
          (conj {:gate :G8 :detail (str "追跡できない郵便種別: " (:postal-class request))}))]
    (if (seq denies)
      {:decision :deny :reasons denies}
      {:decision :allow :reasons [] :plan p})))
