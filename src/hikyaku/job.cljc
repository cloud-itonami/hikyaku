(ns hikyaku.job
  "1 件の仕事の状態機械。**証拠の無い step は終わらない。順番は飛ばせない。**

  human computing の核はここにある —— 人が作業するのは変わらないが、作業の
  1 段ごとに証拠（写真の hash・領収・追跡番号）を要求し、揃うまで次へ進めない。
  「やった」という申告は証拠にならない。

  ```text
  :quoted → :accepted → [steps…各 step に証拠] → :completed
                      ↘ :aborted（理由つき。どの step まで証拠があるかは残る）
  ```")

(defn create
  "plan から job を作る。`:cursor` が次にやる step の index。"
  [job-id plan]
  {:job/id job-id
   :status :quoted
   :steps (mapv #(assoc % :done? false :evidence-given nil) (:steps plan))
   :cursor 0
   :labor-jpy (:labor-jpy plan)
   :pass-through (:pass-through plan)
   :log []})

(defn- log [job ev] (update job :log conj (assoc ev :seq (count (:log job)))))

(defn accept [job]
  (if (= :quoted (:status job))
    (-> job (assoc :status :accepted) (log {:event :accepted}))
    (log job {:event :refused :why (str "accept は :quoted からだけ。今は " (:status job))})))

(defn current-step [job] (get-in job [:steps (:cursor job)]))

(defn missing-evidence
  "今の step に足りない証拠の種類。"
  [job evidence]
  (let [need (:evidence (current-step job))]
    (set (remove #(contains? evidence %) need))))

(defn advance
  "`evidence` は `{種類 値}`。今の step の証拠が全部揃っていれば done にして cursor を進める。
  揃っていなければ **job を変えず**に `:refused` を log に積んで返す（理由つき）。
  `step` を渡すと、それが今の step と一致しないときも拒否する（飛ばし防止）。"
  [job {:keys [step evidence]}]
  (let [cur (current-step job)
        miss (missing-evidence job (or evidence {}))]
    (cond
      (not= :accepted (:status job))
      (log job {:event :refused :why (str "進められるのは :accepted の job だけ。今は " (:status job))})

      (nil? cur)
      (log job {:event :refused :why "step が残っていない"})

      (and step (not= step (:task cur)))
      (log job {:event :refused :why (str "次の step は " (:task cur) "。" step " は飛ばせない")})

      (seq miss)
      (log job {:event :refused :step (:task cur) :why (str "証拠が足りない: " (pr-str miss))})

      :else
      (let [done (-> job
                     (assoc-in [:steps (:cursor job) :done?] true)
                     (assoc-in [:steps (:cursor job) :evidence-given] evidence)
                     (update :cursor inc)
                     (log {:event :step-done :step (:task cur) :evidence (keys evidence)}))]
        (if (= (:cursor done) (count (:steps done)))
          (-> done (assoc :status :completed) (log {:event :completed}))
          done)))))

(defn abort [job why]
  (-> job (assoc :status :aborted) (log {:event :aborted :why why :at-step (:task (current-step job))})))

(defn receipt
  "完了した job の受領証 —— 各 step の証拠を順に並べたもの。未完了なら nil。"
  [job]
  (when (= :completed (:status job))
    {:job/id (:job/id job)
     :steps (mapv #(select-keys % [:task :evidence-given :labor-jpy :amount-jpy :denominations :postal-class]) (:steps job))
     :labor-jpy (:labor-jpy job)
     :pass-through (:pass-through job)}))

(defn invoice
  "手間賃と立替の明細。**印紙の額面は立替であって売上ではない。** 別行にして混ぜない。"
  [job]
  (let [stamp (some #(when (= :buy-revenue-stamp (:task %)) (:amount-jpy %)) (:steps job))
        postage (some #(when (= :post (:task %)) (get-in % [:evidence-given :postage-jpy])) (:steps job))]
    {:labor-jpy (:labor-jpy job)
     :pass-through (cond-> {}
                     stamp (assoc :stamp-face-value-jpy stamp)
                     postage (assoc :postage-jpy postage))
     :total-jpy (+ (:labor-jpy job) (or stamp 0) (or postage 0))}))
