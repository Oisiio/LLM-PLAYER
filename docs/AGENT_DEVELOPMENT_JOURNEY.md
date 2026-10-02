# Agent開発で頑張ったこと・進化の記録

**対象期間:** 2026-09-03 ～ 2026-10-03  
**Repository:** `Oisiio/LLM-PLAYER`

この1か月のAgent開発は、単に機能を追加していっただけではない。
実機で動かし、問題を見つけ、原因を調べ、制御方法や計測方法を追加しながらAgentを少しずつ実用的なものへ近づけていった。

---

## 1. まず「Agentそのもの」を作った

### 2026-09-03 — Thinking / Chain-of-Thought

Agent開発の前段階として、LLMの`<think>`を扱うための基盤を整えた。

- Chain-of-Thought promptingを導入
- native layer / chat templateまで含めてthinkingを扱えるようにした

これが後のAgentの「考える → Toolを使う → 結果を見る → 次の行動を決める」という構造の土台になった。

### 2026-09-07 — Agent基盤を実装

ここで初めてAgentらしい仕組みが入った。

- Agent framework
- Tool definition
- Tool registration
- Tool execution
- LLM出力からのTool Call parsing
- Calculator Tool

つまり、

```
User
 ↓
LLM
 ↓
Tool Call
 ↓
Tool
 ↓
Tool Result
 ↓
LLM
```

というAgentの基本ループをLLM-PLAYER内に作った。

---

## 2. 「動く」だけではなく、状態を管理するようにした

### 2026-09-08 — Coroutine-based state management

Agentの実行状態を

- IDLE
- RUNNING
- CANCELLING

として管理。

さらに実機でStop / Cancelが正常に動くことを確認し、動作確認結果も記録した。

Agentは単なる関数呼び出しではなく、ユーザーが途中で止められるアプリケーション機能になった。

---

## 3. Agentを調整できるようにした

### 2026-09-09 — System Prompt / Thinking Toggle

AgentPreferencesを追加。

- System Promptを変更可能
- Thinking ON/OFF
- 設定を保持

### 2026-09-12 — Thinking Budget

さらにThinking Budgetを追加。

これによって、

> 「このモデルにどの程度考えさせるか」

をAgent側から調整できるようになった。

これは後の実機検証で非常に重要になった。

---

## 4. Toolを実用的にした

### 2026-09-13 — Calculator + DateTime

CalculatorだけでなくDateTime Toolを追加。

また、

- Toolの有効/無効
- 最大Step
- Agent設定の永続化
- Agent / Model設定のリセット

なども実装。

Agentが単なるデモではなく、実際に複数のToolを使う環境になった。

---

## 5. 「本当に速いのか？」を測るようにした

### 2026-09-18 — Agent Benchmark

ここから開発の方向が大きく変わった。

Agent Benchmarkを実装し、

- Prompt Token
- Generated Token
- Generation Speed
- Tool実行時間
- Agent全体の処理時間

などを確認できるようにした。

つまり、

> 「動いた」

ではなく、

> 「何秒かかった？何Token使った？どこが遅い？」

を調べられるようになった。

Agent開発で試行錯誤するための「測定器」を自分で作ったことが大きい。

---

## 6. Agent特有の推論コストに挑戦した

### 2026-09-18 — Prefix Caching

Agentでは同じSystem PromptやTool定義などを何度も処理する。

そこでAgent Prefix Cachingを実装。

- KV Cache prefix reuse
- native cache management
- JNI
- Agent Runner側の状態管理

まで触った。

これは単純なUI機能ではなく、llama.cpp / JNI / Agent Runnerをまたぐ低レイヤー寄りの改善だった。

---

## 7. 複数Tool Callを本格的に扱うようにした

### 2026-09-22 — Structured multi-turn KV state

Agentが複数StepでToolを使うことを想定し、

- session-based KV state
- session initialization
- delta appending
- diagnostic logging

を実装。

これによって、

```
LLM
 ↓
Tool 1
 ↓
結果
 ↓
LLM
 ↓
Tool 2
 ↓
結果
 ↓
LLM
```

という複数StepのAgentを効率的に処理するための基盤ができた。

### 2026-09-22 — Thinkingの抽出

`<think>`をAgentStepとして追跡できるようにした。

これによって、

- Thought
- Tool Call
- Tool Result
- Final Answer

を区別してAgentの内部動作を観察できるようになった。

---

## 8. 「Agentが何をしているのか」を見えるようにした

### 2026-09-24 — Benchmark / Diagnostics強化

Benchmarkのログをコピーできるようにしたり、

- reasoning metrics
- full Agent benchmark log
- UI表示
- metrics analysis

などを整備。

途中でUIや型の不具合も発生したが、それらも修正した。

ここで重要なのは、Agentの問題を感覚で判断するのではなく、

> 実際のログを見て原因を探す

という開発方法に変わったこと。

---

## 9. 実機でAgentを使って、問題を見つけた

ここからが特に試行錯誤した部分。

Thinking Budgetを設定して実機でAgentを動かすと、

- 処理時間が非常に長くなる
- Tool Callを繰り返す
- 同じToolを何度も呼ぶ
- Stepが増え続ける
- Prompt Tokenが膨らむ

といった問題が見えてきた。

例えばThinkingを使ったAgentでは、Toolを呼び出すための推論そのものが大きなコストになることも分かった。

そこで、単に「もっと高速なモデルを使う」のではなく、Agent側に制御機構を追加していった。

---

## 10. Agentの暴走を止める仕組みを作った

### 2026-09-25 — First Step Thinking OFF

最初のStepだけThinkingを無効にできるようにした。

目的は、Agent開始時点から不要に長い推論をさせるケースを制御すること。

### 2026-09-25 — MAX_AGENT_STEPS

AgentにハードなStep上限を設定。

最大5Stepまでとして、

- excessive compute usageを防止
- 明確なStop Reasonを追加
- UIでもStep Limitによる終了を表示

するようにした。

これは実機での異常動作を経験したからこそ必要になった安全弁。

---

## 11. Toolが失敗しても立て直せるようにした

### 2026-09-29 — Retry / Error Handling

Tool Callが失敗したときに、

```
Tool Call
 ↓
Error
 ↓
Retry
 ↓
Tool
 ↓
Result
```

という回復ができるようにした。

Agentは「Toolが一回失敗したら終了」という状態から、失敗を処理して次の行動に移れる状態へ進んだ。

---

## 12. 同じToolを無限に呼ぶ問題に対処した

### 2026-09-30 — Duplicate Tool Detection

さらに実機検証を進めると、

> 同じToolを同じ引数で何度も呼ぶ

という別の問題が出てきた。

そこで、

- Tool名
- 正規化した引数

を比較して、成功済みの同一Tool Callを検出。

重複Toolの実行を防止した。

さらに、

- `tool_duplicate_detected`
- AgentResult
- Agent diagnostics
- Agent Screenでの表示

まで追加。

「止める」だけではなく、

> なぜAgentが止まったのか

まで分かるようにした。

---

# この1か月で特に頑張った点

## ① Agentをゼロから実装した

最初はLLM-PLAYERにAgent機能そのものがなかった。

そこから、

**Tool定義 → Tool実行 → Tool Call解析 → Agent Runner → State管理**

まで作った。

---

## ② Androidだけでなくnative / JNI / llama.cppまで触った

Agentの機能追加はKotlinだけでは終わらなかった。

実際には、

```
Compose UI
   ↓
ViewModel / Agent Runner
   ↓
JNI
   ↓
C++
   ↓
llama.cpp
   ↓
GGUF Model
```

という複数レイヤーをまたいで実装・調査した。

特にKV CacheやPrefix Cacheでは、LLM推論エンジン側まで踏み込んだ。

---

## ③ 実機で失敗するまで試した

AgentはPC上で動けば終わりではなかった。

実際のスマートフォンで、

- 長時間推論
- Tool Call Loop
- Duplicate Tool
- Step増加
- KV Cache / Prefix Cacheの挙動
- Thinkingによる処理時間増加

などを確認した。

そして、その問題を次の実装に反映した。

---

## ④ 「測定する仕組み」まで自分で作った

Agent Benchmarkを作ったことで、

```
感覚
 ↓
「なんか遅い」
 ↓
Benchmark
 ↓
Prompt Token
Generated Token
Generation Speed
Tool Time
Total Time
 ↓
原因を調査
```

という開発サイクルを作った。

これはAgent本体を作るのと同じくらい重要な部分。

---

## ⑤ 問題が起きるたびにAgentの設計そのものを改善した

この1か月の流れは、

```
Agentを作る
 ↓
実機で試す
 ↓
問題発見
 ↓
計測
 ↓
原因調査
 ↓
制御機構を追加
 ↓
また実機で試す
 ↓
新しい問題発見
```

の繰り返しだった。

その結果、

**単純なTool実行機能**

から、

**Thinking制御 + Tool実行 + 複数Step + KV State + Retry + Duplicate Detection + Stop Reason + Benchmark**

を持つAgentへ進化した。

---

# 9/3 → 10/3 の到達点

### 9/3

> 「LLMに考えさせる」

↓

### 9/7

> 「LLMにToolを使わせる」

↓

### 9/13

> 「複数のToolを設定して使わせる」

↓

### 9/18

> 「Agentの速度やTokenを測る」

↓

### 9/22

> 「複数Stepの状態を維持する」

↓

### 9/25

> 「暴走しないように制御する」

↓

### 9/29

> 「Tool失敗から復帰する」

↓

### 9/30

> 「Toolの無限重複を検出する」

↓

### 10/3

> **「Agentエンジンは動く。次は会話履歴・Conversation・Memoryを統合して、実用的な会話Agentにする」**

---

## 最後に

この1か月で一番大きかったのは、Agentを「作ったこと」だけではない。

**Agentを実機で壊して、その壊れ方を観測し、必要な仕組みを一つずつ追加してきたこと。**

最初から完成形を設計して一直線に作ったわけではない。

むしろ、

> **作る → 試す → 壊れる → 測る → 原因を探す → 直す → また試す**

を繰り返したことで、LLM-PLAYERのAgentが現在の形まで進化した。

2026年9月3日から10月3日までの1か月は、LLM-PLAYERにとって**「LLMを動かすアプリ」から「LLM自身に判断させてToolを使わせるアプリ」へ進んだ期間**だった。
