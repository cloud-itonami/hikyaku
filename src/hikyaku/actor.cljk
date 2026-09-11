(ns hikyaku.actor
  "actor 本体。`state + event -> next-state + effects`。1 run = 1 操作。

  | event | 何が起きたか |
  |---|---|
  | `[:job/requested {:job-id :entry :request}]` | 本人が繋ぎを依頼した |
  | `[:job/accepted {:job-id}]`                  | 本人が見積に同意した |
  | `[:step/evidence {:job-id :step :evidence}]` | 作業者が 1 step の証拠を出した |
  | `[:job/aborted {:job-id :why}]`              | 中止 |

  | effect | host がすること |
  |---|---|
  | `[:ledger/append ev]`     | 監査台帳に積む |
  | `[:job/quote {...}]`      | 本人に見積（手間賃 + 立替の内訳 + 手順）を見せる |
  | `[:workforce/assign ...]` | 作業者に次の step と要る証拠を渡す（cloud-itonami の workforce） |
  | `[:job/receipt ...]`      | 完了受領証（証拠の列）を本人へ |
  | `[:refuse ...]`           | 断る。理由つき |"
  (:require [hikyaku.governor :as gov]
            [hikyaku.job :as job]))

(defn init [principal] {:principal principal :jobs {} :ledger []})

(defn- append [ledger ev] (conj ledger (assoc ev :seq (count ledger))))

(defn- next-assignment [j]
  (when-let [s (job/current-step j)]
    [:workforce/assign {:job-id (:job/id j) :step (:task s) :evidence (:evidence s)
                        :note (:note s) :amount-jpy (:amount-jpy s) :denominations (:denominations s)
                        :postal-class (:postal-class s) :cancel-by (:cancel-by s)}]))

(defn step [state [kind payload]]
  (case kind
    :job/requested
    (let [{:keys [job-id entry request]} payload
          v (gov/decide {:entry entry :request request :principal (:principal state)})]
      (case (:decision v)
        :deny
        (let [ev {:event :denied :job-id job-id :entry-id (:req/id entry) :reasons (:reasons v)}]
          [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])
        :allow
        (let [j (job/create job-id (:plan v))
              ev {:event :quoted :job-id job-id :entry-id (:req/id entry)
                  :labor-jpy (:labor-jpy j) :pass-through (:pass-through j)}]
          [(-> state (assoc-in [:jobs job-id] j) (update :ledger append ev))
           [[:ledger/append ev]
            [:job/quote {:job-id job-id :labor-jpy (:labor-jpy j) :pass-through (:pass-through j)
                         :steps (mapv #(select-keys % [:task :note :evidence :labor-jpy :amount-jpy :postal-class]) (:steps j))}]]])))

    :job/accepted
    (let [{:keys [job-id]} payload
          j (get-in state [:jobs job-id])]
      (if-not j
        (let [ev {:event :unknown-job :job-id job-id}]
          [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])
        (let [j' (job/accept j)
              ev {:event :accepted :job-id job-id}]
          [(-> state (assoc-in [:jobs job-id] j') (update :ledger append ev))
           (cond-> [[:ledger/append ev]]
             (= :accepted (:status j')) (conj (next-assignment j')))])))

    :step/evidence
    (let [{:keys [job-id step evidence]} payload
          j (get-in state [:jobs job-id])]
      (if-not j
        (let [ev {:event :unknown-job :job-id job-id}]
          [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])
        (let [j' (job/advance j {:step step :evidence evidence})
              last-log (last (:log j'))
              ev (assoc last-log :job-id job-id)
              state' (-> state (assoc-in [:jobs job-id] j') (update :ledger append ev))]
          (case (:event last-log)
            :refused [state' [[:ledger/append ev] [:refuse ev]]]
            :completed [state' [[:ledger/append ev] [:job/receipt (job/receipt j')]]]
            [state' (cond-> [[:ledger/append ev]]
                      (next-assignment j') (conj (next-assignment j')))]))))

    :job/aborted
    (let [{:keys [job-id why]} payload
          j (get-in state [:jobs job-id])]
      (if-not j
        (let [ev {:event :unknown-job :job-id job-id}]
          [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])
        (let [j' (job/abort j why)
              ev {:event :aborted :job-id job-id :why why}]
          [(-> state (assoc-in [:jobs job-id] j') (update :ledger append ev)) [[:ledger/append ev]]])))

    (let [ev {:event :unknown-event :kind kind}]
      [(update state :ledger append ev) [[:ledger/append ev] [:refuse ev]]])))
