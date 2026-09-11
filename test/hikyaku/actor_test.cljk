(ns hikyaku.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [hikyaku.actor :as a]
            [hikyaku.fee :as fee]
            [hikyaku.governor :as gov]
            [hikyaku.job :as job]
            [hikyaku.plan :as plan]
            [shomen.catalog :as cat]))

;; ── 手数料 ────────────────────────────────────────────────────────

(deftest suit-fee-known-values
  (is (= 10000 (fee/suit-fee 1000000)))
  (is (= 30000 (fee/suit-fee 5000000)))
  (is (= 50000 (fee/suit-fee 10000000)))
  (is (= 320000 (fee/suit-fee 100000000)))
  (is (= 3020000 (fee/suit-fee 1000000000)))
  (testing "段の途中は切り上げ"
    (is (= 1000 (fee/suit-fee 1)))
    (is (= 2000 (fee/suit-fee 100001)))
    (is (= 11000 (fee/suit-fee 1000001))))
  (testing "算定不能は 160 万円とみなす（民訴費用法 §4 ②）"
    (is (= 13000 (fee/suit-fee :non-pecuniary))))
  (testing "派生"
    (is (= 5000 (fee/derived-fee :payment-order 1000000)))
    (is (= 15000 (fee/derived-fee :appeal 1000000)))
    (is (= 20000 (fee/derived-fee :final-appeal 1000000)))))

(deftest denominations
  (is (= [[4000 1]] (fee/split 4000)))
  (is (= [[10000 1] [3000 1]] (fee/split 13000)))
  (is (= [[100000 3] [20000 1]] (fee/split 320000)))
  (is (= 4 (fee/sheets (fee/split 320000)))))

;; ── 手順書 ────────────────────────────────────────────────────────

(def immigration (cat/entry :jp-immigration-residence-permission-fee))
(def execution (cat/entry :jp-civil-execution-attachment-petition))

(deftest plan-from-shomen
  (let [p (plan/plan execution {})]
    (is (true? (:delegable? p)))
    (is (= [:receive-principal-documents :buy-revenue-stamp :affix-revenue-stamp :enclose :post :archive-receipt]
           (mapv :task (:steps p))))
    (let [buy (second (:steps p))]
      (is (= 4000 (:amount-jpy buy)) "差押命令申立ての額は entry が持っている")
      (is (= [[4000 1]] (:denominations buy)))
      (is (= #{:receipt-photo-hash :denominations} (:evidence buy))))
    (is (= :receiving-office (:cancel-by p)) "裁判所の印紙は消印しない")
    (is (= :registered (:postal-class (nth (:steps p) 4))) "裁判所には書留")
    (is (= 2100 (:labor-jpy p)))
    (is (= {:postage :actual :stamp-face-value 4000} (:pass-through p)) "印紙は立替、別明細"))
  (testing "電子の道があれば手順書は無い / 調べていなければ拒否 / 出頭が残れば本人"
    (is (nil? (plan/plan (cat/entry :jp-commercial-registration-incorporation) {})))
    (is (= :unverified (:refused (plan/plan (cat/entry :jp-civil-suit-filing) {}))))
    (is (false? (:delegable? (plan/plan (cat/entry :jp-naturalisation-application) {}))))))

;; ── governor ──────────────────────────────────────────────────────

(def principal {:did "did:key:z6MkPrincipal"
                :consent {:signed-by "did:key:z6MkPrincipal" :scope :hikyaku/job}})

(def good-request
  {:documents-prepared-by :principal
   :asks #{:carry}
   :amount-jpy 6000
   :amount-confirmed-by-principal true
   :recipient {:sealed {:cipher "age1…" :to "did:key:z6MkWorker"}}})

(defn- decide [& {:keys [entry request principal*]
                  :or {entry immigration request good-request}}]
  (gov/decide {:entry entry :request request :principal (or principal* principal)}))

(deftest governor-allows-a-clean-stamp-job
  (let [v (decide)]
    (is (= :allow (:decision v)))
    (is (= 6000 (:amount-jpy (second (:steps (:plan v))))))))

(deftest governor-denies-for-its-own-reasons
  (is (some #(= :G1 (:gate %)) (:reasons (decide :principal* {:did "x"}))))
  (is (some #(= :G2 (:gate %)) (:reasons (decide :request (assoc good-request :documents-prepared-by :hikyaku)))))
  (is (some #(and (= :G2 (:gate %)) (re-find #"弁護士法" (:detail %)))
            (:reasons (decide :request (assoc good-request :asks #{:carry :draft})))))
  (is (some #(= :G3 (:gate %)) (:reasons (decide :entry (cat/entry :jp-naturalisation-application)))))
  (is (some #(= :G4 (:gate %)) (:reasons (decide :entry (cat/entry :jp-commercial-registration-incorporation)))))
  (is (some #(= :G4 (:gate %)) (:reasons (decide :entry (cat/entry :jp-civil-suit-filing)))))
  (is (some #(= :G5 (:gate %)) (:reasons (decide :request (dissoc good-request :amount-confirmed-by-principal))))
      "seed の額で印紙を買わない")
  (is (= :allow (:decision (decide :entry (assoc immigration :req/confidence :maintainer-verified)
                                   :request (dissoc good-request :amount-confirmed-by-principal))))
      "verified entry なら本人確認は要らない")
  (is (some #(= :G5 (:gate %)) (:reasons (decide :request (dissoc good-request :amount-jpy)))) "額が無い")
  (is (some #(= :G6 (:gate %)) (:reasons (decide :entry (-> (cat/entry :jp-contract-stamp-duty)
                                                            (assoc-in [:req/electronic :e/status] :not-permitted)
                                                            (assoc-in [:req/electronic :e/removes] #{})))))
      "本人が消印する印紙は飛脚が貼らない")
  (is (some #(= :G7 (:gate %)) (:reasons (decide :request (assoc good-request :recipient-address "東京都…")))))
  (is (some #(= :G7 (:gate %)) (:reasons (decide :request (dissoc good-request :recipient)))))
  (is (some #(= :G8 (:gate %)) (:reasons (decide :request (assoc good-request :postal-class :ordinary))))))

;; ── 状態機械 ──────────────────────────────────────────────────────

(deftest job-needs-evidence-and-order
  (let [p (plan/plan execution {})
        j (job/accept (job/create "j1" p))]
    (is (= :accepted (:status j)))
    (testing "証拠が足りないと進まず、job は変わらない"
      (let [j' (job/advance j {:step :receive-principal-documents :evidence {}})]
        (is (= 0 (:cursor j')))
        (is (= :refused (:event (last (:log j')))))
        (is (re-find #"document-hash" (:why (last (:log j')))))))
    (testing "飛ばせない"
      (let [j' (job/advance j {:step :buy-revenue-stamp :evidence {:receipt-photo-hash "h" :denominations [[4000 1]]}})]
        (is (= 0 (:cursor j')))
        (is (re-find #"飛ばせない" (:why (last (:log j')))))))
    (testing "証拠が揃えば進む"
      (let [j' (job/advance j {:step :receive-principal-documents :evidence {:document-hash "sha256:abc"}})]
        (is (= 1 (:cursor j')))
        (is (true? (get-in j' [:steps 0 :done?])))))))

(deftest job-completes-into-a-receipt-and-invoice
  (let [p (plan/plan execution {})
        evidence {:receive-principal-documents {:document-hash "sha256:doc"}
                  :buy-revenue-stamp {:receipt-photo-hash "sha256:rcpt" :denominations [[4000 1]]}
                  :affix-revenue-stamp {:affixed-page-photo-hash "sha256:page"}
                  :enclose {:before-seal-photo-hash "sha256:env"}
                  :post {:tracking-number "RR123456789JP" :postal-class :registered :postage-jpy 560}
                  :archive-receipt {:bundle-cid "bafy…"}}
        j (reduce (fn [j s] (job/advance j {:step s :evidence (get evidence s)}))
                  (job/accept (job/create "j2" p))
                  (map :task (:steps p)))]
    (is (= :completed (:status j)))
    (is (= 6 (count (:steps (job/receipt j)))))
    (is (= {:labor-jpy 2100 :pass-through {:stamp-face-value-jpy 4000 :postage-jpy 560} :total-jpy 6660}
           (job/invoice j))
        "印紙の額面は立替。手間賃と混ぜない")))

;; ── actor ─────────────────────────────────────────────────────────

(deftest actor-flow
  (let [s0 (a/init principal)
        [s1 fx1] (a/step s0 [:job/requested {:job-id "j" :entry execution
                                              :request (assoc good-request :amount-jpy 4000)}])]
    (is (= [:ledger/append :job/quote] (mapv first fx1)))
    (is (= 2100 (:labor-jpy (second (second fx1)))))
    (let [[s2 fx2] (a/step s1 [:job/accepted {:job-id "j"}])]
      (is (= :workforce/assign (first (second fx2))))
      (is (= :receive-principal-documents (:step (second (second fx2)))))
      (let [[s3 fx3] (a/step s2 [:step/evidence {:job-id "j" :step :receive-principal-documents :evidence {}}])]
        (is (= :refuse (first (second fx3))) "証拠が無ければ拒否")
        (let [[s4 fx4] (a/step s3 [:step/evidence {:job-id "j" :step :receive-principal-documents :evidence {:document-hash "h"}}])]
          (is (= :workforce/assign (first (second fx4))))
          (is (= :buy-revenue-stamp (:step (second (second fx4)))))
          (is (= [[4000 1]] (:denominations (second (second fx4)))) "作業者には券種まで渡す")
          (is (= 4 (count (:ledger s4)))))))))

(deftest actor-refuses-denied-request
  (let [[_ fx] (a/step (a/init principal) [:job/requested {:job-id "x" :entry (cat/entry :jp-commercial-registration-incorporation)
                                                           :request good-request}])]
    (is (= :refuse (first (second fx))))
    (is (some #(= :G4 (:gate %)) (:reasons (second (second fx)))))))
