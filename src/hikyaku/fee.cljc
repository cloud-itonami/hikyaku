(ns hikyaku.fee
  "収入印紙で納める手数料の**額**と、その額を作る**券種**。

  ## 民事訴訟費用等に関する法律 別表第一（訴えの提起）

  訴額に応じた逓減の段階表。段ごとに「まで」で切り上げる。

  | 訴額の部分 | 単位 | 額 |
  |---|---|---|
  | 100 万円まで | 10 万円ごと | 1,000 円 |
  | 100 万円超 〜 500 万円 | 20 万円ごと | 1,000 円 |
  | 500 万円超 〜 1,000 万円 | 50 万円ごと | 2,000 円 |
  | 1,000 万円超 〜 10 億円 | 100 万円ごと | 3,000 円 |
  | 10 億円超 〜 50 億円 | 500 万円ごと | 10,000 円 |
  | 50 億円超 | 1,000 万円ごと | 10,000 円 |

  実例（よく引かれる値）: 100 万円 → 10,000 / 500 万円 → 30,000 / 1,000 万円 → 50,000 /
  1 億円 → 320,000 / 10 億円 → 3,020,000。

  **この表は seed で、`:unverified-seed` である。** 改正で動く（最後の改正は平成 15 年）。
  飛脚の governor は seed の額をそのまま買わず、本人の確認（`:amount-confirmed-by-principal`）
  を要求する。

  ## 券種

  収入印紙の券種は 31 種（1 円〜10 万円）。購入リストは貪欲法で最大券種から作る。
  郵便局で常備されているのは高額券種とは限らないので、`:denominations` は**希望**で
  あって在庫ではない。")

(def suit-fee-bands
  "[上限（この額まで）, 単位, 単位あたりの額]。上限 nil は無限。"
  [[1000000      100000   1000]
   [5000000      200000   1000]
   [10000000     500000   2000]
   [1000000000   1000000  3000]
   [5000000000   5000000  10000]
   [nil          10000000 10000]])

(defn- ceil-div [a b] (quot (+ a b -1) b))

(defn suit-fee
  "訴えの提起の手数料（円）。`claim-jpy` は訴額（正の整数）。算定不能な訴額
  （非財産権上の請求）は 160 万円とみなす（民訴費用法 §4 ②）—— 呼ぶ側が
  `:non-pecuniary` を渡す。"
  [claim]
  (let [claim-jpy (if (= claim :non-pecuniary) 1600000 claim)]
    (when-not (and (integer? claim-jpy) (pos? claim-jpy))
      (throw (ex-info "訴額は正の整数" {:claim claim})))
    (loop [lo 0 bands suit-fee-bands total 0]
      (let [[hi unit amt] (first bands)
            part (- (if hi (min hi claim-jpy) claim-jpy) lo)]
        (if (or (<= part 0) (empty? bands))
          total
          (let [t (+ total (* amt (ceil-div part unit)))]
            (if (or (nil? hi) (<= claim-jpy hi))
              t
              (recur hi (rest bands) t))))))))

(def fixed-fees
  "定額の手数料（円）。出典は同法 別表第一。**seed**。"
  {:attachment-order          {:jpy 4000 :label "債権差押命令の申立て" :basis "別表第一 十一項"}
   :provisional-attachment    {:jpy 2000 :label "仮差押命令の申立て" :basis "別表第一 十一項"}
   :self-bankruptcy           {:jpy 1500 :label "自己破産（申立 1,000 + 免責 500）" :basis "別表第一"}
   :individual-rehabilitation {:jpy 10000 :label "個人再生の申立て" :basis "別表第一"}})

(defn derived-fee
  "訴え提起の手数料から導かれるもの。
  - `:payment-order` 支払督促 = 1/2
  - `:appeal` 控訴 = 1.5 倍
  - `:final-appeal` 上告 = 2 倍"
  [kind claim]
  (let [base (suit-fee claim)]
    (case kind
      :suit base
      :payment-order (quot base 2)
      :appeal (quot (* base 3) 2)
      :final-appeal (* base 2))))

(def denominations
  "収入印紙の券種（円、降順）。"
  [100000 60000 50000 40000 30000 20000 10000 8000 6000 5000 4000 3000 2000 1000
   600 500 400 300 200 100 50 30 20 10 5 2 1])

(defn split
  "`amount-jpy` を券種に分ける（貪欲法）。`{券種 枚数}` を降順の vector で返す。"
  [amount-jpy]
  (loop [remaining amount-jpy ds denominations out []]
    (cond
      (zero? remaining) out
      (empty? ds) (throw (ex-info "券種で表せない額" {:amount amount-jpy}))
      :else (let [d (first ds) n (quot remaining d)]
              (recur (- remaining (* n d)) (rest ds) (if (pos? n) (conj out [d n]) out))))))

(defn sheets [splits] (reduce + (map second splits)))
