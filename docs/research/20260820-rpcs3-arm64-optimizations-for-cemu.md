# RPCS3's ARM64 work — what transfers to Cemu-thor (2026-08-20)

Research pass only. **No code was changed.** Every claim below is either a
citation to an RPCS3 PR or a `file:line` reference into this checkout that was
read while writing this. Nothing here has been measured on the Thor yet.

## Sources

Whatcookie's ~6-month ARM64 optimization run on RPCS3, presented Aug 2026.
Headline claim, **theirs and unverified by us: ~60% faster at ~25% less power.**
Do not restate that as ours.

- [Whatcookie — "RPCS3 optimizations on ARM64: What Didn't Make the Cut"](https://whatcookie.github.io/posts/rpcs3-on-arm64-what-didnt-make-the-cut/)
- [RPCS3 v0.0.42 release notes](https://github.com/RPCS3/rpcs3/releases/tag/v0.0.42)
- Merged PRs, read individually: [#18055](https://github.com/RPCS3/rpcs3/pull/18055),
  [#18056](https://github.com/RPCS3/rpcs3/pull/18056),
  [#18057](https://github.com/RPCS3/rpcs3/pull/18057),
  [#18060](https://github.com/RPCS3/rpcs3/pull/18060),
  [#18133](https://github.com/RPCS3/rpcs3/pull/18133),
  [#18151](https://github.com/RPCS3/rpcs3/pull/18151),
  [#18751](https://github.com/RPCS3/rpcs3/pull/18751),
  [#18763](https://github.com/RPCS3/rpcs3/pull/18763),
  [#18816](https://github.com/RPCS3/rpcs3/pull/18816),
  [#18824](https://github.com/RPCS3/rpcs3/pull/18824),
  [#18830](https://github.com/RPCS3/rpcs3/pull/18830),
  [#19259](https://github.com/RPCS3/rpcs3/pull/19259)
- Prior art in this workspace:
  `../xenia-thor-workspace/xenia-thor/docs/research/20260805-rpcs3-arm64-optimizations-applicable.md`
  (same source material, mapped onto Xenon rather than Espresso)

**Their test device was an AYN Odin 2 — Snapdragon 8 Gen 2, the same SoC as the
Thor.** Their numbers are on our silicon, same 1×X3 / 2×A715 / 2×A710 / 3×A510
layout. Manuals for all four cores are now in `docs/reference/arm/`.

## The one structural caveat

Xenia's guest CPU is Xenon: PowerPC **with VMX/AltiVec**, and RPCS3's guest is
Cell: PowerPC **with AltiVec + SPU SIMD**. Cemu's guest is **Espresso**, a
750CL derivative with **no VMX at all** — its only SIMD is paired-singles (2×f32
in the FPRs). So the large RPCS3 vector items (`VPERM`→`TBL`, `EOR3`/`BCAX`,
SVE2, `UDOT`/`SDOT`, `vmaxfp`→`fmax`, the 16-bit `SMULL`/`UMULL` multiply work)
have **no guest-side counterpart here**. Roughly half of the RPCS3 list dies at
this line, and pretending otherwise would waste the pass.

What survives transfers *because it is host-side*, not because the guest ISAs
match: spin/wait behaviour, timer plumbing, compiler target features, and the
memcmp/checksum shapes in the GPU layer. Those turn out to be where Cemu's
worst ARM64-specific taxes actually are — see items 1–4, which are the real
findings of this pass and are Cemu-specific, not ports.

---

# Tier 1 — verified taxes in this checkout, high confidence

## 1. Guest `mftb` does a 128-bit software divide under a global spinlock ⭐⭐ BIGGEST

**RPCS3 parallel:** #18751 (inline the decrementer read; `readcyclecounter` →
`mrs cntvct_el0`) and #18055 (stop deriving timer behaviour from x86 constants).

**The chain in this tree**, traced end to end:

| step | location |
|---|---|
| guest `mftb` → host call, every execution | `src/Cafe/HW/Espresso/Recompiler/PPCRecompilerImlGen.cpp:501` |
| `PPCRecompiler_GetTBL` → `OSGetSystemTime()` | `PPCRecompilerImlGen.cpp:482` |
| → `coreinit_GetMFTB()` | `src/Cafe/OS/libs/coreinit/coreinit_Time.cpp:13` |
| → `PPCInterpreter_getMainCoreCycleCounter()` | `coreinit_Time.cpp:10` |
| → `PPCTimer_getFromRDTSC()` | `src/Cafe/HW/Espresso/Interpreter/PPCInterpreterMain.cpp:43` |

And `PPCTimer_getFromRDTSC` (`src/Cafe/HW/Espresso/PPCTimer.cpp:127`) does, per
call: take a **process-global `FSpinlock`**, `_mm_mfence()`, read the counter,
one `_umul128`, two `_addcarry_u64`, **one `_udiv128`**, then release.

Two things make this far worse on ARM64 than on x86:

- **`_udiv128` is not an instruction here.** `src/Common/precompiled.h:342`
  implements it as `unsigned __int128` `/` and `%`. AArch64 has no 128-bit
  divide, so this lowers to a libgcc/compiler-rt `__udivti3`/`__udivmodti4`
  **call** — a software division loop, order-of-magnitude worse than x86's
  single `div` instruction. x64 Cemu pays ~30-40 cycles here; ARM64 pays a
  function call into a soft-division routine.
- **The `FSpinlock` is global and shared by all three emulated cores.** Under
  `__CemuIsMulticoreMode()` all guest cores calling `mftb` serialize on one
  lock, and its contended path (`src/util/helpers/fspinlock.h:23`) spins on
  `_mm_pause()` → `isb sy`.

**Why it matters for Star Fox Zero specifically:** `mftb` is how guest code
implements every timing loop and frame pacer, and `OSGetSystemTime` /
`OSGetSystemTick` (`coreinit_Time.cpp:13,23`) route through the *same* function,
so HLE calls contend with recompiled `mftb` on the same lock.

**Three fixes, increasing in ambition:**

1. **Kill the division.** `_rdtscFrequency` is constant after init. Precompute a
   64.64 fixed-point reciprocal once and replace `_udiv128` with a
   multiply-high. Architecture-neutral; helps x64 too.
2. **Read `CNTFRQ_EL0` instead of calibrating.**
   `PPCTimer_estimateRDTSCFrequency()` (`PPCTimer.cpp:34`) **spends a literal 3
   seconds** at startup measuring the counter rate. On ARM64 that rate is
   architecturally exposed — `mrs x0, cntfrq_el0`, typically exactly 19,200,000
   on Qualcomm — so the calibration is both slower to obtain and *less accurate*
   than the free answer. This is RPCS3 #18055's point verbatim. Also cuts 3s off
   every cold start.
3. **Inline the read into recompiled code** (RPCS3 #18751). Once the conversion
   is a mul-shift with no lock, `mftb` can become a handful of instructions
   emitted directly by `BackendAArch64` instead of a host call.

⚠️ **Correctness constraint on (3):** the current design is not merely a counter
read — `_rdtscLastMeasure`/`_rdtscAcc` implement a monotonic accumulator with a
user-facing `GetTimerShiftFactor()` scale (`PPCTimer.cpp:160`). Any inlining
must preserve monotonicity across cores and keep the shift factor live, or
timer-shift settings silently stop working. Do (1) and (2) first; they are
strictly local and testable off-device.

## 2. Espresso `slw`/`srw` emit 3 instructions where 1 will do ⭐ (JIT)

**RPCS3 parallel:** #18816 — LLVM emitting a `movi`+`cmhi` guard on ARM for
shift-overflow semantics that the hardware already provides; RPCS3 dropped from
24 to 20 bytes by using the native form.

**Ours**, `src/Cafe/HW/Espresso/Recompiler/BackendAArch64/BackendAArch64.cpp:729`:

```cpp
else if (imlInstruction->operation == PPCREC_IML_OP_SLW)
{
    tst(regOperand2, 32);
    lsl(regResult, regOperand1, regOperand2);
    csel(regResult, regResult, wzr, Cond::EQ);
}
```
…and the identical shape for `SRW` at :735. Three instructions plus a flag
dependency and a `csel` on the critical path.

PPC `slw`/`srw` take the shift amount from `rB & 0x3F`; the result is 0 when the
amount is ≥32. That is exactly what a **64-bit** ARM shift does when you keep
only the low 32 bits of the result: `lsl x_res, x_op1, x_op2` masks the amount
mod 64 (= `rB & 0x3F`, correct), and any amount ≥32 pushes every original bit
above bit 31, so the W-view of the result is 0 for free. **3 instructions → 1**,
no flag write, no `csel`.

**The x64 backend already does exactly this trick** — `BackendX64.cpp:738` uses
BMI2 `SHLX` on a 64-bit register and then truncates, with the comment "trim
result to 32bit". So this is not a novel idea needing validation; it is the
ARM64 backend not having caught up with its sibling.

Preconditions to confirm before writing it: that `regOperand1`'s X-register
upper half is zero for `SRW` (it should be — the backend writes GPRs through
`WReg`, and W-writes zero-extend on AArch64), and that the IML contract really
is "up to 63 bits" — `src/Cafe/HW/Espresso/Recompiler/IML/IMLInstruction.h:125`
says so explicitly.

## 3. The per-frame texture hash has an AVX2 path and no NEON path ⭐⭐ (GPU-side)

**RPCS3 parallel:** #18824 — replacing a scalar/AND-shaped checksum with
`UABA` (absolute-difference-and-accumulate), measured **38% faster on the
mid-cores and 21% on the big core**, specifically because `UABA` takes three
inputs and so feeds all three 128-bit load ports on the A710/A715 while using
only two ALU slots.

**Ours**, `src/Cafe/HW/Latte/Core/LatteTextureCache.cpp:185`:

```cpp
#if BOOST_OS_WINDOWS
    if (g_CPUFeatures.x86.avx2) { /* __m256i XOR-accumulate loop */ }
#else
    if( false ) {}          // <-- Android/ARM64 lands here
#endif
    else { /* scalar 64-bit rotate-accumulate loop */ }
```

So the "huge texture" hash — run for every large texture, every frame, from
`LatteTC_HasTextureChanged()` (`LatteTextureCache.cpp:236`, called from
`LatteTexture.cpp:1189` and `LatteTextureLegacy.cpp:241`) — is **scalar on the
Thor**, and its scalar form carries a loop-carried rotate dependency
(`h64 = (h64 << 3) | (h64 >> 61)`) that serializes the whole loop at roughly one
iteration per rotate latency.

**Why a NEON path is safe here, specifically:** the AVX2 branch already uses a
different stride (288 bytes) and different arithmetic than the scalar branch, so
the two paths *already* produce different hash values on different machines.
That proves the value is a within-process change-detector only and is never
persisted or compared across builds — so a third, NEON-shaped variant changes no
observable behaviour. (Cross-check `texDataHash2` is never serialized before
relying on this.)

The straightforward version is a direct transliteration of the AVX2 branch to
`uint32x4_t`/`veorq_u32` with a horizontal add at the end. The RPCS3-flavoured
version is the `UABA` accumulate, which is what actually exploits the 3-load /
2-ALU asymmetry. Start with the transliteration, then try `UABA`.

⚠️ Keep it alignment-safe. `LatteTC_ReadUnaligned` exists because guest texture
addresses are not host-aligned (see the AGENTS.md `SIGBUS` history); use
`vld1q_u8` on a `uint8_t*`, never a reinterpret-cast to `uint32x4_t*`.

## 4. The build targets baseline `armv8-a` — no LSE atomics, no dotprod, no fp16 ⭐⭐

**RPCS3 parallel:** #18133 (detect ARM features and hand them to LLVM as target
attributes — RPCS3 was gating FMA on the CPU *name* containing "cortex", so
every Qualcomm core silently fell off the fast path) and #18057 (enable FMA for
ARM unconditionally, since it is baseline on AArch64). Rad0van's #18846 hotfix
in v0.0.42 is the same category: "advertise `+i8mm` to the LLVM target machine".

**Ours:** `src/Cafe/CMakeLists.txt:618` sets `-O3` on `CemuCafe` and that is the
*only* architecture-related flag. `src/android/app/build.gradle.kts:110-135`
passes no `-DCMAKE_C(XX)_FLAGS` with `-mcpu`/`-march`. The NDK default for
`arm64-v8a` is plain `armv8-a`, which means the compiler assumes **none** of:
`lse` (atomics), `dotprod`, `i8mm`, `fp16`, `sha3`, `rcpc`.

`lse` is the one that matters most and it is nothing to do with SIMD. Without
it, **every `std::atomic` read-modify-write in the emulator compiles to an
`ldxr`/`stxr` retry loop** instead of a single `casal`/`ldadd`. Cemu's hot paths
are full of these: `FSpinlock::lock()`'s `exchange`
(`src/util/helpers/fspinlock.h:20`), the coreinit spinlock's
`atomic_compare_exchange` (`src/Cafe/OS/libs/coreinit/coreinit_Spinlock.cpp:134`),
and the striped-lock atomic HLE that AGENTS.md describes. LL/SC loops also
*livelock harder under contention*, which is exactly the multicore-Star-Fox
case.

**Also missing entirely: an ARM branch in feature detection.**
`src/Common/cpu_features.h:21-32` declares an `x86 { ... }` struct and nothing else.
There is no place to record "this device has LSE / dotprod / i8mm", which is why
`LatteTextureCache.cpp:204` has to write `if (false)`. Adding an `arm { ... }`
sibling populated from `getauxval(AT_HWCAP)` is the enabling change for item 3
and for anything later.

⚠️ **Decision needed, and it is a real trade-off:** `-march=armv8-a+lse` makes
the binary *require* LSE hardware and it will `SIGILL` on pre-8.1 devices. The
safe form is clang's `-moutline-atomics`, which emits a runtime check and costs
a predictable branch; the fast form is `-mcpu=cortex-a710` (or `+lse`) with an
explicit minimum-device statement. Given AGENTS.md's "keep the stable default
upstream-friendly" rule, `-moutline-atomics` is the default-safe answer and a
`+lse` build is an opt-in experiment. **Both need an A/B; do not assume.**

---

# Tier 2 — plausible, needs measurement first

## 5. Spin counts are x86-tuned constants

**RPCS3 #18055 is the single biggest measured number in the whole set: 37%
faster in Metal Gear Rising, 27→37 FPS on a Snapdragon 8 Gen 2**, purely from
scaling `busy_wait` by the real ARM timer frequency instead of an x86-derived
iteration count. Their reasoning: "in the time it takes for the 7800X3D to wait
300 ticks, the Snapdragon 8 Gen 2 will have waited for 1.37 ticks."

**Ours — the good news first:** Cemu already made the #18151 change.
`src/Common/precompiled.h:413` maps `_mm_pause()` to `isb sy` on `__aarch64__`,
which is the correct ARM backoff (RPCS3 measured ISB at 845 mA vs `yield` at
939 mA vs bare spin at 921 mA — `yield` is worse than nothing on cores without
SMT, and 99% of AArch64 cores have no SMT).

**The remaining gap is the counts and the `yield`s around them.** The GPU
command-processor idle loop, `src/Cafe/HW/Latte/Core/LatteCommandProcessor.cpp:155`:

```cpp
for (sint32 busy = 0; busy < 80; busy++)
    _mm_pause();
```

80 is a bare x86-tuned constant. On x86 `PAUSE` is ~30-140 cycles depending on
microarchitecture; `isb sy` on a Cortex-X3 is a different cost entirely, so the
*wall-clock* length of this backoff is simply a different number on the Thor
than the person who wrote `80` intended. Same shape at `TCL.cpp:94`,
`LatteAsyncCommands.cpp:89`, `GamePatch.cpp:88`.

Alongside them sit bare `std::this_thread::yield()` calls in the same loops —
`LatteCommandProcessor.cpp:169`, `:587`, `:918`, `LatteThread.cpp:204` — which
are `sched_yield()`, a real syscall, in the GPU thread's idle path.

**Why this is Tier 2 and not Tier 1:** unlike items 1–4 there is no *defect*
here, just a constant whose correct value is unknown. It needs the backoff
duration measured on the Thor and re-derived from `CNTFRQ_EL0`, not guessed.
Given RPCS3's 37%, it is the highest-upside item on the list — it is only lower
priority because the work is measurement, not code.

## 6. `fctiwz` NaN handling diverges from PPC on ARM64 — correctness, not perf

**RPCS3 #19259** is a correctness fix: x86 `cvttsd2si` returns `INT_MIN` for
*all* out-of-range conversions, so RPCS3 carried an XOR fixup to turn positive
overflow into `INT_MAX` to match PPC. ARM `FCVTZS` saturates correctly on its
own, so applying the x86 fixup on ARM64 **inverted** it. That crashed Armored
Core 4/4A on ARM64.

**Ours: we have no such fixup to remove**, which is good — but tracing it
surfaced a different divergence in the same area. `BackendAArch64.cpp:1341`
emits a bare `fcvtzs` for `PPCREC_IML_OP_FPR_FCTIWZ`, and
`BackendX64FPU.cpp:234` emits a bare `cvttsd2si`. Neither has a fixup. But the
two instructions do not agree:

| input | PPC `fctiwz` | x86 `cvttsd2si` | ARM `FCVTZS` |
|---|---|---|---|
| positive overflow | `0x7FFFFFFF` | `0x80000000` | `0x7FFFFFFF` ✅ |
| negative overflow | `0x80000000` | `0x80000000` ✅ | `0x80000000` ✅ |
| **NaN** | **`0x80000000`** | `0x80000000` ✅ | **`0`** ❌ |

So on positive overflow the ARM64 backend is *more* correct than the x64 one,
and on **NaN it is less correct** — ARM returns 0 where Espresso returns
`0x80000000`. Any game that converts a NaN float to int gets a different value
on Android than on desktop Cemu.

**Not yet established:** whether any title actually hits it. Espresso's real
behaviour should be pinned by a test before anyone "fixes" this — a fixup costs
instructions on the hot path, and RPCS3's whole #19259 lesson is that adding a
saturation fixup on the wrong architecture is how you *create* the bug. Write
the differential test first; that part is device-free.

## 7. Hardware-assisted waiting (`WFE`) for the GPU ring wait

**RPCS3 #18830** added a `spin_wait` helper using `ldxar` + `WFE` on ARM (and
`umonitor`/`umwait` on x86), applied to RSX semaphore acquisition. Measured
**42W → 38W** on desktop under low RSX load; ~1W on the ARM handheld. RPCS3
measured `WFE` at **569 mA vs ISB's 845 mA** — by far the biggest power win in
their spin-loop table.

**Ours:** `LatteCP_itHLEWaitForFlip` (`LatteCommandProcessor.cpp:906`) and the
CP ring-read idle loop are the same shape — spin on a memory location until
another thread updates it.

⚠️ Whatcookie's own follow-up post is a warning here, and it is why this is
Tier 2 rather than Tier 1. `WFET` was **not supported on his device** (an 8 Gen 2
— the same SoC as ours), so an ISB fallback was needed regardless. And plain
`WFE` depends on the OS periodic event stream: **100µs on Linux/Android** versus
~1µs on Apple, and he reports a "stampede effect" where all cores wake at once
and real-world performance got *worse* despite good microbenchmarks. On Android
specifically, a 100µs wake granularity in a 16.6ms frame is coarse. Treat this
as a power experiment behind an off-by-default toggle, not a speed item.

---

# Tier 3 — does not transfer, recorded so nobody re-derives it

| RPCS3 item | why it doesn't apply to Cemu |
|---|---|
| #18060 `vmaxfp`/`vminfp` → `fmax`/`fmin` | Espresso has no VMX. Paired-singles have no max/min instruction at all. |
| #18056 `VPERM`/`SHUFB` → `TBL1`/`TBL2` | No VMX permute in the guest ISA. |
| #18818 `TBL` for `ROTQBY` | SPU-specific. |
| #18763/#18789 16-bit `SMULL`/`UMULL`/`SMLAL` | Relies on "all SPU multiplies are 16×16". Espresso multiplies are 32×32 → `mul`/`smull`, already optimal at `BackendAArch64.cpp:726,769,774`. |
| #18833 `I8MM` for `GBH`/`GBB`, `UDOT`/`SDOT` | Gather-bits ops are SPU-only. |
| #18806 SVE2 FMS, SVE multiplies | **The 8 Gen 2 has no SVE at all** — Qualcomm shipped it as ARMv9 without SVE/SVE2. Dead on this device regardless. |
| `EOR3`/`BCAX` (sha3) | Available on the device, but they optimise 3-input *vector bitwise* ops. Espresso's bitwise work is scalar GPR. |
| FEAT_LUT | Whatcookie cut it himself — no detection interface on Windows or Apple. |

---

# Recommendation on where to aim

You said you didn't know which area to target. Based on what the audit actually
turned up rather than on a prior: **host-side first, JIT second, and the GPU-side
hash is the sleeper.**

The reasoning is that the JIT is in better shape than the host layer here. Item 2
is the *only* codegen defect the pass found in 1,756 lines of `BackendAArch64`,
and it saves 2 instructions on one opcode pair. Meanwhile the host layer has a
software 128-bit division under a global lock on the guest's timer path (item 1),
a per-frame texture hash with a deliberately empty non-x86 branch (item 3), and a
binary built without the atomics extension the device has had since 2022 (item 4).
Those are structural, they affect every title rather than shift-heavy ones, and
three of the four are testable without the Thor in hand.

Suggested order:

1. **Item 4** (feature detection + `-moutline-atomics`) — enabling change for
   everything else, and the LSE effect is whole-binary.
2. **Item 1**, steps 1 and 2 (kill `_udiv128`, read `CNTFRQ_EL0`) — local, and
   step 2 also deletes 3 seconds from every cold start, which is independently
   worth having.
3. **Item 3** (NEON texture hash) — self-contained, safe by the argument above,
   and lands on the per-frame path.
4. **Item 5** (spin-count re-derivation) — highest potential upside of anything
   here given RPCS3's 37%, but it is a measurement task and wants the profiler
   and a repeatable Star Fox scene.
5. **Item 2** (`slw`/`srw`) — small, safe, and the x64 backend already proves
   the technique.
6. **Item 6** (`fctiwz` NaN differential test) — correctness, device-free, no
   deadline.

Per AGENTS.md, none of these gets called a win without a single-run in-place A/B
on the Thor with a repeatable scene; cross-run FPS is confounded. Items 1, 2, 3
and 6 can all be developed and unit-tested off-device first.

## Open questions this pass could not settle

- Is `texDataHash2` ever persisted or compared across processes? Item 3's safety
  argument depends on "no". It looked runtime-only, but the shader/texture cache
  serialization paths were not read.
- Which of `cpu0-2` is the A510 with its **own** vector unit? Two of the three
  A510s share one 128-bit vector pipe. The xenia-thor pass probed this on device
  and found the kernel exposes no cluster grouping — all three report identical
  `cpu_capacity 280` and `core_siblings 0-2`. Settling it needs a pinned NEON
  microbenchmark run on each core and then on each pair. Relevant to Cemu's ADPF
  thread placement, but **do not guess an index**.
- What is the real wall-clock cost of `isb sy` on X3 vs A715 vs A510? Every
  spin-count decision in item 5 depends on it and it is not in the SWOGs;
  `docs/reference/arm/` has the pipeline tables but ISB is a barrier, not a
  pipelined op.
