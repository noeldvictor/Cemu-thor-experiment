#include "util/highresolutiontimer/HighResolutionTimer.h"
#include "Common/precompiled.h"

HighResolutionTimer HighResolutionTimer::now()
{
#if defined(__aarch64__) && (BOOST_OS_LINUX || BOOST_PLAT_ANDROID)
	// AArch64 exposes the architectural counter to userspace, so this needs no syscall
	// and no vdso call at all - just a register read, at the rate in CNTFRQ_EL0.
	//
	// This matters far more than it looks: LatteTiming_HandleTimedVsync() is called on
	// every iteration of the command processor's idle spin loop, so whenever the GPU
	// queue is empty this function runs continuously. Profiling Star Fox Zero on the
	// Thor with simpleperf put 30% of all emulator CPU time in __kernel_clock_gettime,
	// and CLOCK_MONOTONIC_RAW is the slow clock id even where the vdso handles it.
	uint64 t;
	asm volatile("mrs %0, cntvct_el0" : "=r"(t));
	return HighResolutionTimer(t);
#elif BOOST_OS_WINDOWS
	LARGE_INTEGER pc;
	QueryPerformanceCounter(&pc);
	return HighResolutionTimer(pc.QuadPart);
#elif BOOST_OS_LINUX
    timespec pc;
    clock_gettime(CLOCK_MONOTONIC_RAW, &pc);
    uint64 nsec = (uint64)pc.tv_sec * (uint64)1000000000 + (uint64)pc.tv_nsec;
    return HighResolutionTimer(nsec);
#elif BOOST_OS_MACOS
	return HighResolutionTimer(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW));
#elif BOOST_OS_BSD
    timespec pc;
    clock_gettime(CLOCK_MONOTONIC, &pc);
    uint64 nsec = (uint64)pc.tv_sec * (uint64)1000000000 + (uint64)pc.tv_nsec;
    return HighResolutionTimer(nsec);
#endif
}

HRTick HighResolutionTimer::getFrequency()
{
	return m_freq;
}

uint64 HighResolutionTimer::m_freq = []() -> uint64 {
#if defined(__aarch64__) && (BOOST_OS_LINUX || BOOST_PLAT_ANDROID)
	// must match the counter read in now(). Typically 19200000 on Qualcomm, which is a
	// ~52ns tick - far finer than anything this timer is used for (the shortest consumer
	// is the vsync interval at 16.6ms, i.e. ~320000 ticks).
	uint64 freq;
	asm volatile("mrs %0, cntfrq_el0" : "=r"(freq));
	if (freq != 0)
		return freq;
	// firmware left CNTFRQ_EL0 unprogrammed, fall back to the clock_gettime resolution
	{
		timespec pc;
		clock_getres(CLOCK_MONOTONIC_RAW, &pc);
		return (uint64)1000000000 / (uint64)pc.tv_nsec;
	}
#elif BOOST_OS_WINDOWS
	LARGE_INTEGER freq;
	QueryPerformanceFrequency(&freq);
	return (uint64)(freq.QuadPart);
#elif BOOST_OS_MACOS
	return 1000000000;
#elif BOOST_OS_BSD
	timespec pc;
	clock_getres(CLOCK_MONOTONIC, &pc);
	return (uint64)1000000000 / (uint64)pc.tv_nsec;
#else
    timespec pc;
    clock_getres(CLOCK_MONOTONIC_RAW, &pc);
    return (uint64)1000000000 / (uint64)pc.tv_nsec;
#endif
}();
