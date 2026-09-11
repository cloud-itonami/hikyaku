# hikyaku

**飛脚（hikyaku）— 法律がまだ紙に留めている手続きの「使者」層。収入印紙を買い、消印せずに貼り、
本人の文書を封入し、追跡できる郵便で出し、1 step ごとに証拠を残す human-computing actor。
R0（決定核と状態機械。workforce への配信 host は未実装）。**

この repo の名前は機能を示さないので先に名乗る: **`kotoba-lang/shomen` の `interim`（繋ぎ）を
手順書に落とし、独立した governor（G1〜G8）で「使者の線」を守り、証拠の無い step は終わらない
状態機械で人の作業を回す actor** である。電子の道が開くまでの繋ぎであって、紙を温存する
ためのものではない —— `:electronic-now` の手続きは電子の道を案内して断る（G4）。

```bash
kbb --backend sci --classpath src:test:../../kotoba-lang/shomen/src run_tests.cljk   # 9 tests / 65 assertions
```

## 使者であって代理ではない（ここが一番大事）

飛脚は文書を作らない・直さない・署名も押印もしない・代理で申請しない。買う・貼る・封をする・
出す・受領証を残す —— 事実行為だけ。この線は行政書士法 §19・司法書士法 §73・弁護士法 §72 の
境界で、G2 は `:draft` `:amend` `:sign` `:seal` `:file-as-agent` `:advise` を含む依頼を断る。
`cloud-itonami/kadode`（退職の使者）と同じ立ち位置。

| 残る要件 | 飛脚ができるか |
|---|---|
| 収入印紙（受理官署が消印） | できる。買って貼る。**消印しない** |
| 収入印紙（本人が消印する課税文書） | **貼らない**（G6）。本人が貼って消印した文書を運ぶ |
| 書面の提出・原本添付 | 運ぶ。封入前後の写真 hash を残す |
| 出頭・押印・公証 | **できない**（G3）。shomen が `:delegable? false` を返し、本人が行く |

## 動き方

```clojure
(require '[hikyaku.actor :as a] '[shomen.catalog :as cat])

(def principal {:did "did:key:…" :consent {:signed-by "did:key:…" :scope :hikyaku/job}})

(a/step (a/init principal)
        [:job/requested {:job-id "j"
                         :entry (cat/entry :jp-civil-execution-attachment-petition)
                         :request {:documents-prepared-by :principal
                                   :asks #{:carry}
                                   :amount-jpy 4000 :amount-confirmed-by-principal true
                                   :recipient {:sealed {:cipher "age1…"}}}}])
;; => [state [[:ledger/append …]
;;            [:job/quote {:labor-jpy 2100 :pass-through {:postage :actual :stamp-face-value 4000}
;;                         :steps [{:task :receive-principal-documents …}
;;                                 {:task :buy-revenue-stamp :amount-jpy 4000 :evidence #{:receipt-photo-hash :denominations}}
;;                                 {:task :affix-revenue-stamp :cancel-by :receiving-office …}
;;                                 {:task :enclose …} {:task :post :postal-class :registered …}
;;                                 {:task :archive-receipt …}]}]]]
```

本人が見積に同意すると `[:workforce/assign …]` が 1 step ずつ出る。作業者が証拠
（領収の写真 hash・券種・貼付ページの写真 hash・封入前の写真 hash・追跡番号・受領証の CID）
を返すと次の step が出る。**証拠が足りなければ `[:refuse …]` で止まり、job は変わらない。
順番は飛ばせない**（`hikyaku.job`）。

## 印紙の額

`hikyaku.fee` は民事訴訟費用等に関する法律 別表第一の算式（訴額 100 万円 → 10,000 円、
1 億円 → 320,000 円、10 億円 → 3,020,000 円）、定額表（差押命令 4,000 円 …）、派生
（支払督促 1/2、控訴 1.5 倍、上告 2 倍）、券種分割（320,000 円 = 10 万円 × 3 + 2 万円 × 1）を持つ。
**表は seed。** governor G5 は seed の額で印紙を買わず、entry が `:maintainer-verified` 以上で
なければ本人の確認（`:amount-confirmed-by-principal`）を要求する。

## 明細

```clojure
(hikyaku.job/invoice completed-job)
;; => {:labor-jpy 2100
;;     :pass-through {:stamp-face-value-jpy 4000 :postage-jpy 560}
;;     :total-jpy 6660}
```

印紙の額面は立替であって売上ではない。手間賃と別行にして混ぜない（G10）。

## 隣接 repo との境界

- `cloud-itonami/insatsu` — 印刷・郵送の routing と見積（job データ）。物理行為は持たない
  と自分で書いている。hikyaku は物理行為の**証拠**を持つ。印刷が要る job は insatsu の前段。
- `cloud-itonami/yuubin` — Web ゆうびん投函の自動化。2026-04-20 に断念（bot 検出）。
  hikyaku は**人が**郵便局へ行く前提で、自動化しない。
- `cloud-itonami/teigen` — 同じ shomen entry の提言側。繋ぎと提言は同じ分類から出る。

## R0 で無いもの

- workforce への配信 host（`[:workforce/assign …]` を受けて人に渡し、証拠を集める側）。
  cloud-itonami の `workforce_provision` に繋ぐのが次の一手
- 台帳の永続化（`:ledger` は state の vector）
- sealed envelope の実装（`:recipient {:sealed …}` の中身は age 等の host の仕事。ここは
  「平文で来たら断る」だけ）
- 郵便料金表（`:postage :actual` —— 作業者が証拠として `:postage-jpy` を返す）

## License

AGPL-3.0-or-later
