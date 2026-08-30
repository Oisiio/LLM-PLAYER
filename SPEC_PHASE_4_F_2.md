# Phase 4-F-2 Formal Specification

## 1. Purpose

Phase 4-F-2 adds **Typical-P Sampling** to LLM-PLAYER's local GGUF inference pipeline.

The goal is to improve sampling quality by filtering tokens according to their information-theoretic typicality while preserving the existing local inference architecture, deterministic Seed behavior, and previously verified sampling controls.

This phase is a sampling implementation phase only. It must not modify `third_party/llama.cpp`.

## 2. Overall Project Progress

Current development status:

- Phase 1: Android foundation — **COMPLETE**
- Phase 2: JNI Native Communication — **COMPLETE**
- Phase 3: llama.cpp integration / GGUF inference — **COMPLETE**
- Phase 4-E-2: EOG / EOG stop — **COMPLETE**
- Phase 4-E-3: Stop Sequence — **COMPLETE**
- Phase 4-E-4: Repetition Penalty — **COMPLETE**
- Phase 4-E-5: Seed + Performance Metrics — **COMPLETE**
- Phase 4-F-1: Min-P Sampling — **COMPLETE**
- Phase 4-F-2: Typical-P Sampling — **SPECIFICATION COMPLETE / IMPLEMENTATION NOT STARTED**

The project is therefore at **Phase 4-F-2 specification stage**. No Phase 4-F-3 work is authorized by this document.

## 3. Candidate Comparison and Decision

The Phase 4-F-2 candidates considered were:

| Candidate | Decision | Reason |
|---|---|---|
| Typical-P | **ADOPT** | Good fit for the current sampling pipeline, deterministic with the existing fixed Seed approach, and compatible with the project's llama.cpp-based architecture |
| Dynamic Temperature | Reject for this phase | Changes sampling behavior through temperature adaptation and expands the scope beyond a single filtering mechanism |
| Top-N-Sigma | Reject for this phase | Less aligned with the current roadmap and provides less direct continuity with the existing probability-based filters |
| XTC | Reject for this phase | More specialized and less appropriate as the next incremental sampling feature |
| Mirostat | Reject for this phase | Adaptive control algorithm with substantially different behavior and tuning requirements; better treated as a separate future experiment |

**Adopted candidate: Typical-P.**

## 4. Parameter Specification

### Typical-P

- Parameter name: `Typical-P`
- Internal representation: floating-point value
- Valid range: `0.0` to `1.0`
- Default / disabled value: **1.0**
- Verification value: **0.95**
- Values outside the valid range must be clamped to `0.0`–`1.0`.

A Typical-P value of `1.0` must disable Typical-P filtering and preserve the behavior of the preceding sampling pipeline.

## 5. Sampling Pipeline

The intended Phase 4-F-2 pipeline is:

1. Repetition Penalty
2. Top-K
3. Typical-P
4. Top-P
5. Min-P
6. Temperature / probability calculation as required by the implementation
7. Seeded random sampling

The implementation must preserve the existing behavior of:

- Temperature = `0.7`
- Top-K = `40`
- Top-P = `0.9`
- Min-P = `0.0` by default unless explicitly enabled
- Repetition Penalty = `1.1`
- Penalty Last-N = `64`
- Seed = `12345`
- Maximum generated tokens = `128`
- Maximum context tokens = `512`
- Stop Sequence = `<END>`

The exact numerical implementation must be verified against the selected llama.cpp sampling semantics before considering the phase complete.

## 6. Required Implementation Constraints

- Primary implementation file: `app/src/main/cpp/native-lib.cpp`
- `third_party/llama.cpp`: **DO NOT MODIFY**
- Android UI: no change is required for the core implementation unless a later verification requirement explicitly needs UI exposure.
- Existing JNI interfaces and verified Phase 4-E functionality must remain compatible.
- Large code changes should be implemented through Gemini 3.7 Flash, following the project's established workflow.

## 7. Verification Requirements

### Test A — Disabled behavior

Typical-P = `1.0`.

Expected:

- Typical-P filtering is skipped.
- Existing sampling behavior remains functional.
- Seed `12345` remains deterministic.

### Test B — Typical-P enabled

Typical-P = `0.95`.

Prompt:

`日本の首都について教えてください`

Expected:

- Typical-P is reported as `0.950000`.
- Generation completes normally.
- No crash or native error occurs.
- Existing EOG / Stop Sequence / MAX_TOKENS handling remains functional.

### Test C — Seed reproducibility

Run the same prompt twice with identical parameters:

- Temperature `0.7`
- Top-K `40`
- Top-P `0.9`
- Typical-P `0.95`
- Min-P `0.0`
- Repetition Penalty `1.1`
- Seed `12345`

Expected:

- Generated token count matches.
- Generated text matches exactly.

### Test D — Existing feature regression

Confirm that Typical-P does not break:

- EOG stopping
- `<END>` Stop Sequence detection, including token-boundary crossing
- 128-token safety limit
- 512-token context limit
- TTFT
- Prompt Processing Time
- Generation Time
- Total Time
- Generation Speed
- Seed reporting

## 8. Reporting Requirements

After implementation, report:

1. Changed files
2. Implementation details
3. Impact on existing functionality
4. Build result
5. Device test results
6. Seed reproducibility result
7. Typical-P disabled/enabled verification

Phase 4-F-2 is not considered complete until the implementation builds successfully and the required device tests pass.

## 9. Checkpoint Rule

After Phase 4-F-2 is verified, create a dedicated checkpoint before proceeding to any later phase.

Do not automatically begin Phase 4-F-3.

## 10. Current State

This document formalizes the Phase 4-F-2 specification only.

**Implementation status: NOT STARTED.**

The next authorized action is implementation of Typical-P after an explicit instruction to proceed.
