#pragma once

#include <chrono>

#if BOOST_PLAT_ANDROID
#include <sys/types.h>
#include <vector>
#endif

namespace AndroidPerformanceHints
{
#if BOOST_PLAT_ANDROID
void UpdateSchedulerThreads(const std::vector<pid_t>& threadIds, size_t expectedThreadCount);
#endif

void ReportFrameBoundary(std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now());
void CloseSchedulerSession();
}
