# Agent Stop / Cancel 実機検証記録

## 概要

LLM-PLAYER の Agent 機能で Calculator Tool を実装した後、実機テストで「Tool の使用はできるが、生成途中で Stop するとアプリが応答しなくなる」問題を発見した。

この問題について Agent の Coroutine / Job と JNI・native 側の生成キャンセル処理を追跡し、Stop / Cancel のライフサイクルを修正した。

## 原因調査

Agent の Stop は Kotlin 側の Coroutine キャンセルだけでは完結せず、同期的に呼ばれている JNI の `nativeGenerateStream()` が native 側で生成処理を継続していた。

確認した構造は以下。

```text
AgentScreen
  ↓
AgentRunner
  ↓
LlmStreamRunner
  ↓
withContext(Dispatchers.Default)
  ↓
JNI nativeGenerateStream()
  ↓
C++ generate_sampling_locked()
```

native 側では `g_cancel_talk_generation` を生成ループから確認しており、`nativeCancelGeneration()` がこのフラグを立てることで生成を抜けられる構造だった。

一方、AgentRunner 側では native の停止と Agent Coroutine のライフサイクルが十分に結び付いていなかったため、Stop 後の状態管理や再実行時に問題が発生する可能性があった。

## 修正内容

Commit:

`3950af80fb07e9b94511974fcdcc68aa6b305090`

`feat(agent): implement coroutine-based state management`

主な変更:

- Agent に `IDLE / RUNNING / CANCELLING` の状態を追加
- Agent 実行中の `Job` を保持
- Stop 時に native の `cancelGeneration()` を呼び出す
- Stop 時に Agent Coroutine の `Job.cancel()` も実行
- `CancellationException` を処理して安全に Agent を終了
- `NonCancellable` で終了処理を行い、最終的に `IDLE` に戻す
- `RUNNING` 以外からの重複 Stop を無視
- `CANCELLING` 中の再実行を禁止
- Agent の複数同時実行を防止
- Token callback 側でもキャンセル状態を確認
- Stop ログを重複記録しないように整理

## 実機検証

修正後、実機で Agent の Stop / Cancel 動作を確認。

### 結果

**PASS**

Agent の生成途中で Stop してもアプリが応答不能になる問題は解消された。

Stop 後の Agent の状態復帰・再実行についても確認し、今回の Stop / Cancel 修正は実機テストを通過した。

## この出来事をnoteに書くなら

単純な「Stop ボタンのバグ修正」ではなく、次の開発記録として残す価値がある。

> Tool は動いた。ところが Stop を押したらアプリが固まった。
> そこで Agent の Coroutine から JNI、さらに C++ の生成ループまで追跡した。

特に、Android の UI 上では一つの Stop 操作に見えても、実際には

`Compose → AgentRunner → Coroutine/Job → LlmStreamRunner → JNI → C++ → llama.cpp系の生成処理`

という複数レイヤーをまたいでいることが分かった点が重要。

「機能を作る」だけでなく、実機で問題を発見し、処理の流れを逆追跡して原因を切り分け、修正して再検証した事例としてnoteの開発記録に利用する。

## 関連

- Agent Calculator Tool 実装
- Agent Stop / Cancel 修正
- Coroutine と native generation のキャンセル連携
- 実機での Stop → 再実行テスト
- Talk 機能との回帰確認
