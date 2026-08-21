#include "Cafe/HW/Espresso/Const.h"
#include "config/ActiveSettings.h"
#include "util/helpers/fspinlock.h"
#include "util/highresolutiontimer/HighResolutionTimer.h"
#include "Common/cpu_features.h"

#if defined(ARCH_X86_64)
#include <immintrin.h>
#pragma intrinsic(__rdtsc)
#endif

uint64 _rdtscLastMeasure = 0;
uint64 _rdtscFrequency = 0;

struct uint128_t
{
	uint64 low;
	uint64 high;
};

static_assert(sizeof(uint128_t) == 16);

uint128_t _rdtscAcc{};

#if defined(__aarch64__)
// On AArch64 the guest timer does not need the accumulate-under-a-global-lock design that
// the x86 path uses. CNTVCT_EL0 is a single monotonic counter shared by every core, so the
// guest tick count is a pure function of it and needs no shared mutable state, no lock, no
// barrier and no division.
//
// This matters because guest code polls the time constantly: profiling Star Fox Zero put
// coreinit::OSGetTime() at 7.8% of all emulator CPU with another 3.6% in the spinlock's
// atomic swap, and every guest mftb routes through here as well. All three emulated cores
// were serialising on one lock to read a counter that is already coherent between them.
//
// Only the timer shift factor (used by fast forward) makes this stateful, and it changes
// rarely, so the base point is republished by the writer and read through a seqlock.
static std::atomic<uint64> sTimerSeq{0};
static uint64 sTimerBaseCnt = 0;   // counter value when the current shift took effect
static uint64 sTimerBaseTicks = 0; // guest ticks accumulated before that point
static uint8 sTimerBaseShift = 3;
static uint64 sTimerCntToCoreMul = 0; // CORE_CLOCK / CNTFRQ in 32.32 fixed point

static uint64 PPCTimer_rawTicksSince(uint64 baseCnt, uint64 nowCnt)
{
	const uint64 delta = nowCnt - baseCnt;
	// 64x64->128 multiply is mul+umulh here, so this is cheaper than any division
	return (uint64)(((unsigned __int128)delta * sTimerCntToCoreMul) >> 32);
}
#endif

uint64 muldiv64(uint64 a, uint64 b, uint64 d)
{
	uint64 diva = a / d;
	uint64 moda = a % d;
	uint64 divb = b / d;
	uint64 modb = b % d;
	return diva * b + moda * divb + moda * modb / d;
}

// Returns the counter frequency if the hardware states it exactly, 0 if it has to be measured.
static uint64 PPCTimer_getExactFrequency()
{
#if defined(__aarch64__)
	// On AArch64 __rdtsc() is CNTVCT_EL0, whose rate is architecturally exposed in
	// CNTFRQ_EL0 (typically exactly 19200000 on Qualcomm). Reading it is both instant
	// and exact, where PPCTimer_estimateRDTSCFrequency() spends 3 seconds of startup
	// producing an approximation of a number the hardware will simply tell us.
	uint64 cntfrq = 0;
	asm volatile("mrs %0, cntfrq_el0" : "=r"(cntfrq));
	return cntfrq; // 0 if firmware left it unprogrammed, in which case we measure
#else
	return 0;
#endif
}

uint64 PPCTimer_estimateRDTSCFrequency()
{
    #if defined(ARCH_X86_64)
	if (!g_CPUFeatures.x86.invariant_tsc)
		cemuLog_log(LogType::Force, "Invariant TSC not supported");
    #endif

	_mm_mfence();
	uint64 tscStart = __rdtsc();
	unsigned int startTime = GetTickCount();
	HRTick startTick = HighResolutionTimer::now().getTick();
	// wait roughly 3 seconds
	while (true)
	{
		if ((GetTickCount() - startTime) >= 3000)
			break;
		std::this_thread::sleep_for(std::chrono::milliseconds(10));
	}
	_mm_mfence();
	HRTick stopTick = HighResolutionTimer::now().getTick();
	uint64 tscEnd = __rdtsc();
	// derive frequency approximation from measured time difference
	uint64 tsc_diff = tscEnd - tscStart;
	uint64 hrtFreq = 0;
	uint64 hrtDiff = HighResolutionTimer::getTimeDiffEx(startTick, stopTick, hrtFreq);
	uint64 tsc_freq = muldiv64(tsc_diff, hrtFreq, hrtDiff);

	// uint64 freqMultiplier = tsc_freq / hrtFreq;
	//cemuLog_log(LogType::Force, "RDTSC measurement test:");
	//cemuLog_log(LogType::Force, "TSC-diff:   0x{:016x}", tsc_diff);
	//cemuLog_log(LogType::Force, "TSC-freq:   0x{:016x}", tsc_freq);
	//cemuLog_log(LogType::Force, "HPC-diff:   0x{:016x}", qpc_diff);
	//cemuLog_log(LogType::Force, "HPC-freq:   0x{:016x}", (uint64)qpc_freq.QuadPart);
	//cemuLog_log(LogType::Force, "Multiplier: 0x{:016x}", freqMultiplier);

	return tsc_freq;
}

int PPCTimer_initThread()
{
	_rdtscFrequency = PPCTimer_estimateRDTSCFrequency();
	return 0;
}

void PPCTimer_init()
{
	uint64 exactFrequency = PPCTimer_getExactFrequency();
	if (exactFrequency != 0)
	{
		// no measurement needed, so skip the 3 second calibration thread entirely
		_rdtscFrequency = exactFrequency;
	}
	else
	{
		std::thread t(PPCTimer_initThread);
		t.detach();
	}
	_rdtscLastMeasure = __rdtsc();
}

uint64 _tickSummary = 0;

void PPCTimer_start()
{
	_rdtscLastMeasure = __rdtsc();
	_tickSummary = 0;
#if defined(__aarch64__)
	if (_rdtscFrequency != 0)
	{
		// CORE_CLOCK / CNTFRQ in 32.32 fixed point. The relative error is below 2^-32, i.e.
		// under one guest tick per four billion, so there is no meaningful drift.
		sTimerCntToCoreMul = (uint64)(((unsigned __int128)Espresso::CORE_CLOCK << 32) / _rdtscFrequency);
		sTimerBaseCnt = _rdtscLastMeasure;
		sTimerBaseTicks = 0;
		sTimerBaseShift = ActiveSettings::GetTimerShiftFactor();
	}
#endif
}

uint64 PPCTimer_getRawTsc()
{
	return __rdtsc();
}

uint64 PPCTimer_microsecondsToTsc(uint64 us)
{
	return (us * _rdtscFrequency) / 1000000ULL;
}

uint64 PPCTimer_tscToMicroseconds(uint64 us)
{
	uint128_t r{};
	r.low = _umul128(us, 1000000ULL, &r.high);

	uint64 remainder;
	const uint64 microseconds = _udiv128(r.high, r.low, _rdtscFrequency, &remainder);

	return microseconds;
}

bool PPCTimer_isReady()
{
	return _rdtscFrequency != 0;
}

void PPCTimer_waitForInit()
{
	while (!PPCTimer_isReady()) std::this_thread::sleep_for(std::chrono::milliseconds(10));
}

FSpinlock sTimerSpinlock;

#if defined(__aarch64__)
void PPCTimer_rebaseForShiftChange(uint8 newShift)
{
	sTimerSpinlock.lock();
	const uint64 nowCnt = __rdtsc();
	const uint64 raw = PPCTimer_rawTicksSince(sTimerBaseCnt, nowCnt);
	sTimerSeq.fetch_add(1, std::memory_order_release); // mark writer active (odd)
	sTimerBaseTicks = sTimerBaseTicks + ((raw << 3) >> sTimerBaseShift);
	sTimerBaseCnt = nowCnt;
	sTimerBaseShift = newShift;
	sTimerSeq.fetch_add(1, std::memory_order_release); // publish (even)
	sTimerSpinlock.unlock();
}

static uint64 PPCTimer_getFromCNTVCT()
{
	const uint64 nowCnt = __rdtsc();
	uint64 baseCnt, baseTicks;
	uint8 baseShift;
	for (;;)
	{
		const uint64 seq = sTimerSeq.load(std::memory_order_acquire);
		if (seq & 1)
			continue; // a rebase is in progress
		baseCnt = sTimerBaseCnt;
		baseTicks = sTimerBaseTicks;
		baseShift = sTimerBaseShift;
		std::atomic_thread_fence(std::memory_order_acquire);
		if (sTimerSeq.load(std::memory_order_relaxed) == seq)
			break;
	}
	const uint64 raw = PPCTimer_rawTicksSince(baseCnt, nowCnt);
	return baseTicks + ((raw << 3) >> baseShift);
}
#endif

// thread safe
uint64 PPCTimer_getFromRDTSC()
{
#if defined(__aarch64__)
	if (sTimerCntToCoreMul != 0)
		return PPCTimer_getFromCNTVCT();
#endif
	sTimerSpinlock.lock();
	_mm_mfence();
	uint64 rdtscCurrentMeasure = __rdtsc();
	uint64 rdtscDif = rdtscCurrentMeasure - _rdtscLastMeasure;
	// optimized max(rdtscDif, 0) without conditionals
	rdtscDif = rdtscDif & ~(uint64)((sint64)rdtscDif >> 63);

	uint128_t diff{};
	diff.low = _umul128(rdtscDif, Espresso::CORE_CLOCK, &diff.high);

	if(rdtscCurrentMeasure > _rdtscLastMeasure)
		_rdtscLastMeasure = rdtscCurrentMeasure; // only travel forward in time

	uint8 c = 0;
	#if BOOST_OS_WINDOWS
	c = _addcarry_u64(c, _rdtscAcc.low, diff.low, &_rdtscAcc.low);
	_addcarry_u64(c, _rdtscAcc.high, diff.high, &_rdtscAcc.high);
	#else
	// requires casting because of long / long long nonesense
	c = _addcarry_u64(c, _rdtscAcc.low, diff.low, (unsigned long long*)&_rdtscAcc.low);
	_addcarry_u64(c, _rdtscAcc.high, diff.high, (unsigned long long*)&_rdtscAcc.high);
	#endif

	// The accumulator carries the remainder forward, so this stays exact either way.
	// The high word is only non-zero when rdtscDif * CORE_CLOCK overflows 64 bits,
	// which needs several hundred seconds between two calls at the ARM counter rate -
	// so in practice the 64-bit path is always the one taken. That matters because
	// AArch64 has no 128-bit divide: _udiv128 lowers to a __udivti3 software-division
	// libcall there, while the 64-bit form below is a single udiv instruction.
	uint64 elapsedTick;
	if (_rdtscAcc.high == 0)
	{
		elapsedTick = _rdtscAcc.low / _rdtscFrequency;
		_rdtscAcc.low = _rdtscAcc.low - elapsedTick * _rdtscFrequency;
	}
	else
	{
		uint64 remainder;
		elapsedTick = _udiv128(_rdtscAcc.high, _rdtscAcc.low, _rdtscFrequency, &remainder);
		_rdtscAcc.low = remainder;
	}
	_rdtscAcc.high = 0;

	// timer scaling
	elapsedTick <<= 3ull; // *8
	uint8 timerShiftFactor = ActiveSettings::GetTimerShiftFactor();
	elapsedTick >>= timerShiftFactor;

	_tickSummary += elapsedTick;

	sTimerSpinlock.unlock();
	return _tickSummary;
}
